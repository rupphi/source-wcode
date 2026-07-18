package com.tuandev.fbsbarcode.jdesk.supply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SupplyRefreshCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-stay-in-java";
    private static final String SUPPLY_ID = "WB-GI-123";

    @Test
    void refreshesAnOwnedSupplyInTheBackgroundAndReturnsOnlySafeCounts() throws Exception {
        SupplyRefreshCommandService service = service((shop, supplyId) -> {
            assertEquals(SECRET, shop.getApiKey());
            assertEquals(SUPPLY_ID, supplyId);
            return 17;
        });

        SupplyRefreshCommandService.StartSupplyRefreshResponse start = service
                .start(new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, SUPPLY_ID), null)
                .toCompletableFuture()
                .join();
        SupplyRefreshCommandService.SupplyRefreshStatusResponse status = awaitTerminal(service, start);

        assertTrue(start.accepted());
        assertEquals("completed", status.state());
        assertEquals(17, status.localOrders());
        assertFalse(status.toString().contains(SECRET));
    }

    @Test
    void rejectsUnknownSupplyBeforeCallingWildberries() {
        AtomicInteger calls = new AtomicInteger();
        SupplyRefreshCommandService service = new SupplyRefreshCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                (shopId, supplyId) -> null,
                (shop, supplyId) -> {
                    calls.incrementAndGet();
                    return 0;
                });

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.start(
                        new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, SUPPLY_ID), null));

        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertEquals(0, calls.get());
    }

    @Test
    void coalescesDuplicateRefreshesForTheSameSupply() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SupplyRefreshCommandService service = service((shop, supplyId) -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test interrupted", exception);
            }
            return 3;
        });
        SupplyRefreshCommandService.StartSupplyRefreshResponse first = service
                .start(new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, SUPPLY_ID), null)
                .toCompletableFuture()
                .join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        SupplyRefreshCommandService.StartSupplyRefreshResponse duplicate = service
                .start(new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, SUPPLY_ID), null)
                .toCompletableFuture()
                .join();
        JDeskException busy = assertThrows(
                JDeskException.class,
                () -> service.start(
                        new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, "WB-GI-OTHER"), null));
        release.countDown();
        awaitTerminal(service, first);

        assertFalse(duplicate.accepted());
        assertEquals(first.jobId(), duplicate.jobId());
        assertEquals("shop_busy", ((SupplyRefreshCommandService.SupplyRefreshError) busy.details()).kind());
        assertEquals(1, calls.get());
    }

    @Test
    void exposesSafeRetryableStatusForRateLimit() throws Exception {
        SupplyRefreshCommandService service = service((shop, supplyId) -> {
            throw new WbApiException("upstream " + SECRET, 429, "{\"token\":\"" + SECRET + "\"}");
        });

        SupplyRefreshCommandService.StartSupplyRefreshResponse start = service
                .start(new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, SUPPLY_ID), null)
                .toCompletableFuture()
                .join();
        SupplyRefreshCommandService.SupplyRefreshStatusResponse status = awaitTerminal(service, start);

        assertEquals("failed", status.state());
        assertEquals("rate_limited", status.errorKind());
        assertEquals(429, status.httpStatus());
        assertTrue(status.retryable());
        assertFalse(status.toString().contains(SECRET));
    }

    @Test
    void cancelsWithoutClaimingThatCommittedPagesWereRolledBack() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        SupplyRefreshCommandService service = service((shop, supplyId) -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
                return 0;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("cancelled after committed pages", exception);
            }
        });
        SupplyRefreshCommandService.StartSupplyRefreshResponse start = service
                .start(new SupplyRefreshCommandService.StartSupplyRefreshRequest(7, SUPPLY_ID), null)
                .toCompletableFuture()
                .join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        SupplyRefreshCommandService.CancelSupplyRefreshResponse cancelled = service
                .cancel(new SupplyRefreshCommandService.CancelSupplyRefreshRequest(
                        start.shopId(), start.supplyId(), start.jobId()), null)
                .toCompletableFuture()
                .join();
        SupplyRefreshCommandService.SupplyRefreshStatusResponse status = awaitTerminal(service, start);

        assertTrue(cancelled.cancelRequested());
        assertEquals("cancelled", status.state());
        assertEquals("cancelled", status.errorKind());
    }

    private static SupplyRefreshCommandService service(SupplyRefreshCommandService.RefreshRunner runner) {
        return new SupplyRefreshCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                (shopId, supplyId) -> new WbSupplySummary(supplyId, "Supply", false, false, "", 2),
                runner);
    }

    private static SupplyRefreshCommandService.SupplyRefreshStatusResponse awaitTerminal(
            SupplyRefreshCommandService service,
            SupplyRefreshCommandService.StartSupplyRefreshResponse start) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            SupplyRefreshCommandService.SupplyRefreshStatusResponse status = service
                    .status(new SupplyRefreshCommandService.SupplyRefreshStatusRequest(
                            start.shopId(), start.supplyId(), start.jobId()), null)
                    .toCompletableFuture()
                    .join();
            if (!"running".equals(status.state())) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Supply refresh job did not finish within 5 seconds");
    }
}
