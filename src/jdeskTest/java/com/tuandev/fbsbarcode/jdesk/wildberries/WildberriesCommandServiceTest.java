package com.tuandev.fbsbarcode.jdesk.wildberries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.wb.WbSyncReport;
import com.tuandev.fbsbarcode.jdesk.shop.ShopActivityGate;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.ConfigService;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WildberriesCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-stay-in-java";

    @Test
    void runsSyncAsABackgroundJobAndReturnsOnlySafeCounts() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WildberriesCommandService service = service((shop, progress) -> {
            assertEquals(SECRET, shop.getApiKey());
            progress.accept("wildberries", 1, 2);
            return new WbSyncReport(12, 3, 4, 5);
        });

        WildberriesCommandService.StartSyncResponse start = service
                .startOverview(new WildberriesCommandService.StartSyncRequest(7), context(events))
                .toCompletableFuture()
                .join();
        WildberriesCommandService.SyncStatusResponse status = awaitTerminal(service, start);

        assertTrue(start.accepted());
        assertEquals("completed", status.state());
        assertEquals(12, status.products());
        assertEquals(3, status.supplies());
        assertEquals(4, status.orders());
        assertEquals(5, status.statuses());
        assertFalse(status.toString().contains(SECRET));
        awaitEventCount(events, 3);
        assertEquals(3, events.stream().filter("wildberries.syncProgress"::equals).count());
    }

    @Test
    void rejectsAnUnknownShopBeforeCallingWildberries() {
        AtomicInteger calls = new AtomicInteger();
        WildberriesCommandService service = service((shop, progress) -> {
            calls.incrementAndGet();
            return new WbSyncReport(0, 0, 0, 0);
        });

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.startOverview(new WildberriesCommandService.StartSyncRequest(9), null));

        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertEquals(0, calls.get());
    }

    @Test
    void coalescesDuplicateSyncsForTheSameShop() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        WildberriesCommandService service = service((shop, progress) -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test interrupted", exception);
            }
            return new WbSyncReport(1, 2, 3, 4);
        });
        WildberriesCommandService.StartSyncResponse first = service
                .startOverview(new WildberriesCommandService.StartSyncRequest(7), null)
                .toCompletableFuture()
                .join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        WildberriesCommandService.StartSyncResponse duplicate = service
                .startOverview(new WildberriesCommandService.StartSyncRequest(7), null)
                .toCompletableFuture()
                .join();
        release.countDown();
        awaitTerminal(service, first);

        assertFalse(duplicate.accepted());
        assertEquals(first.jobId(), duplicate.jobId());
        assertEquals(1, calls.get());
    }

    @Test
    void sharedActivityGateMakesBackgroundSyncAndDeleteMutuallyExclusive() throws Exception {
        ShopActivityGate gate = new ShopActivityGate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WildberriesCommandService service = new WildberriesCommandService(
                () -> List.of(new Shop(7, "Main shop", SECRET)),
                (shop, progress) -> {
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("test interrupted", exception);
                    }
                    return new WbSyncReport(1, 1, 1, 1);
                },
                gate);
        WildberriesCommandService.StartSyncResponse start = service
                .startOverview(new WildberriesCommandService.StartSyncRequest(7), null)
                .toCompletableFuture().join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertThrows(ShopActivityGate.ShopBusyException.class,
                () -> gate.deleteWhenIdle(7, () -> "deleted"));
        release.countDown();
        awaitTerminal(service, start);

        assertEquals("deleted", gate.deleteWhenIdle(7, () -> "deleted"));
    }

    @Test
    void deleteLeaseRejectsSyncBeforeShopSnapshotIsResolved() throws Exception {
        ShopActivityGate gate = new ShopActivityGate();
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger shopReads = new AtomicInteger();
        WildberriesCommandService service = new WildberriesCommandService(
                () -> {
                    shopReads.incrementAndGet();
                    return List.of(new Shop(7, "Main shop", SECRET));
                },
                (shop, progress) -> new WbSyncReport(0, 0, 0, 0),
                gate);
        Thread deletion = Thread.ofVirtual().start(() -> gate.deleteWhenIdle(7, () -> {
            deleting.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return null;
        }));
        assertTrue(deleting.await(5, TimeUnit.SECONDS));

        JDeskException busy = assertThrows(JDeskException.class,
                () -> service.startOverview(new WildberriesCommandService.StartSyncRequest(7), null));

        assertEquals("shop_busy", ((WildberriesCommandService.SyncError) busy.details()).kind());
        assertEquals(0, shopReads.get());
        release.countDown();
        deletion.join();
    }

    @Test
    void exposesSafeRetryableStatusForUpstreamFailure() throws Exception {
        WildberriesCommandService service = service((shop, progress) -> {
            throw new WbApiException("upstream " + SECRET, 401, "{\"token\":\"" + SECRET + "\"}");
        });

        WildberriesCommandService.StartSyncResponse start = service
                .startOverview(new WildberriesCommandService.StartSyncRequest(7), null)
                .toCompletableFuture()
                .join();
        WildberriesCommandService.SyncStatusResponse status = awaitTerminal(service, start);

        assertEquals("failed", status.state());
        assertEquals("token_invalid", status.errorKind());
        assertFalse(status.retryable());
        assertFalse(status.toString().contains(SECRET));
    }

    @Test
    void cancelsAJobWithoutDiscardingAlreadyCommittedSyncPages() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        WildberriesCommandService service = service((shop, progress) -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
                return new WbSyncReport(0, 0, 0, 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("cancelled after committed pages", exception);
            }
        });
        WildberriesCommandService.StartSyncResponse start = service
                .startOverview(new WildberriesCommandService.StartSyncRequest(7), null)
                .toCompletableFuture()
                .join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        WildberriesCommandService.CancelSyncResponse cancelled = service
                .cancelOverview(new WildberriesCommandService.CancelSyncRequest(7, start.jobId()), null)
                .toCompletableFuture()
                .join();
        WildberriesCommandService.SyncStatusResponse status = awaitTerminal(service, start);

        assertTrue(cancelled.cancelRequested());
        assertEquals("cancelled", status.state());
        assertEquals("cancelled", status.errorKind());
    }

    @Test
    void mapsShopRepositoryFailuresWithoutExposingDatabaseDetails() {
        WildberriesCommandService service = new WildberriesCommandService(
                () -> {
                    throw new IllegalStateException("sqlite path contains " + SECRET);
                },
                (shop, progress) -> new WbSyncReport(0, 0, 0, 0));

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.startOverview(new WildberriesCommandService.StartSyncRequest(7), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertEquals("internal", ((WildberriesCommandService.SyncError) error.details()).kind());
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.getCause());
    }

    @Test
    void syncsAnExistingShopOnlyWhenLiveReadSmokeIsExplicitlyEnabled() throws Exception {
        assumeTrue("1".equals(System.getenv("WCODE_LIVE_WB_SMOKE")));
        try (AppDataLock ignored = AppDataLock.acquire(AppPaths.appDataDir(), "jdesk-live-wb-test")) {
            Database.initDatabase();
            List<Shop> shops = new ShopRepository().findAll().stream()
                    .filter(shop -> shop.getApiKey() != null && !shop.getApiKey().isBlank())
                    .toList();
            assumeTrue(!shops.isEmpty());
            Integer configuredShopId = ConfigService.getLastSelectedShopId();
            String requestedShopId = System.getenv("WCODE_LIVE_WB_SHOP_ID");
            Shop target = shops.stream()
                    .filter(shop -> requestedShopId == null
                            ? configuredShopId != null && shop.getId() == configuredShopId
                            : Integer.toString(shop.getId()).equals(requestedShopId))
                    .findFirst()
                    .orElse(shops.getFirst());

            WildberriesCommandService service = new WildberriesCommandService();
            WildberriesCommandService.StartSyncResponse start = service
                    .startOverview(new WildberriesCommandService.StartSyncRequest(target.getId()), null)
                    .toCompletableFuture()
                    .join();
            WildberriesCommandService.SyncStatusResponse status =
                    awaitTerminal(service, start, Duration.ofMinutes(2));

            assertEquals("completed", status.state());
            assertEquals(target.getId(), status.shopId());
            assertTrue(status.products() >= 0);
            assertTrue(status.supplies() >= 0);
            assertFalse(status.toString().contains(target.getApiKey()));
        }
    }

    private static WildberriesCommandService service(WildberriesCommandService.SyncRunner runner) {
        return new WildberriesCommandService(
                () -> List.of(new Shop(7, "Main shop", SECRET)),
                runner);
    }

    private static WildberriesCommandService.SyncStatusResponse awaitTerminal(
            WildberriesCommandService service,
            WildberriesCommandService.StartSyncResponse start) throws Exception {
        return awaitTerminal(service, start, Duration.ofSeconds(5));
    }

    private static WildberriesCommandService.SyncStatusResponse awaitTerminal(
            WildberriesCommandService service,
            WildberriesCommandService.StartSyncResponse start,
            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            WildberriesCommandService.SyncStatusResponse status = service
                    .syncStatus(new WildberriesCommandService.SyncStatusRequest(start.shopId(), start.jobId()), null)
                    .toCompletableFuture()
                    .join();
            if (!"running".equals(status.state())) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Wildberries sync job did not finish within " + timeout);
    }

    private static void awaitEventCount(List<String> events, int expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(1);
        while (events.size() < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(5);
        }
    }

    private static InvocationContext context(List<String> events) {
        EventEmitter emitter = (name, payload) -> events.add(name);
        return new InvocationContext() {
            @Override
            public dev.jdesk.api.WindowId windowId() {
                return null;
            }

            @Override
            public String commandName() {
                return "wildberries.syncOverview";
            }

            @Override
            public String requestId() {
                return "test-request";
            }

            @Override
            public dev.jdesk.api.PlatformInfo platform() {
                return null;
            }

            @Override
            public dev.jdesk.api.ApplicationHandle application() {
                return null;
            }

            @Override
            public EventEmitter events() {
                return emitter;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }
}
