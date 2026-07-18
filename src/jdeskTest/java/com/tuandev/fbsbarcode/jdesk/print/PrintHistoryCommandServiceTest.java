package com.tuandev.fbsbarcode.jdesk.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PrintHistoryCommandServiceTest {
    private static final String SECRET = "secret-history-error-that-must-not-cross-the-bridge";

    @Test
    void returnsAFilteredSanitizedHistoryPageWithStringJobIds() {
        List<PrintHistoryJobSummary> jobs = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            jobs.add(job(
                    9_007_199_254_740_990L + index,
                    index == 11 ? "WB-GI-ALPHA" : "WB-GI-" + index,
                    index == 11 ? " Alpha\u0000 supply " : "Supply " + index,
                    index % 3 == 0 ? "failed" : "success"));
        }
        PrintHistoryCommandService service = service(jobs);

        PrintHistoryCommandService.PrintHistoryResponse response = service
                .list(new PrintHistoryCommandService.PrintHistoryRequest(
                        7, " alpha ", "success", 1, 10), null)
                .toCompletableFuture()
                .join();

        assertEquals("alpha", response.query());
        assertEquals("success", response.status());
        assertEquals(8, response.successfulItems());
        assertEquals(4, response.failedItems());
        assertEquals(1, response.totalItems());
        assertEquals(1, response.totalPages());
        assertEquals("9007199254741001", response.items().getFirst().jobId());
        assertEquals("Alpha supply", response.items().getFirst().supplyName());
        assertEquals("Default", response.items().getFirst().templateName());
        assertTrue(response.items().getFirst().canReprint());
        assertFalse(response.toString().contains(SECRET));
        assertFalse(response.toString().contains("templateLayoutJson"));
        assertFalse(response.toString().contains("errorMessage"));
    }

    @Test
    void pagesFailedJobsAndNeverMarksThemReprintable() {
        List<PrintHistoryJobSummary> jobs = new ArrayList<>();
        for (int index = 1; index <= 11; index++) {
            jobs.add(job(index, "FAILED-" + index, "Failed " + index, "failed"));
        }

        PrintHistoryCommandService.PrintHistoryResponse response = service(jobs)
                .list(new PrintHistoryCommandService.PrintHistoryRequest(
                        7, "", "failed", 2, 10), null)
                .toCompletableFuture()
                .join();

        assertEquals(11, response.totalItems());
        assertEquals(2, response.totalPages());
        assertEquals(1, response.items().size());
        assertEquals("11", response.items().getFirst().jobId());
        assertFalse(response.items().getFirst().canReprint());
    }

    @Test
    void marksOnlySuccessfulJobsWithinTheReprintQuotaAsReprintable() {
        List<PrintHistoryJobSummary> jobs = List.of(
                job(1, "EMPTY", "Empty", "success", 0),
                job(2, "TOO-LARGE", "Too large", "success", 5_001),
                job(3, "READY", "Ready", "success", 5_000));

        PrintHistoryCommandService.PrintHistoryResponse response = service(jobs)
                .list(new PrintHistoryCommandService.PrintHistoryRequest(
                        7, "", "success", 1, 10), null)
                .toCompletableFuture()
                .join();

        assertFalse(response.items().get(0).canReprint());
        assertFalse(response.items().get(1).canReprint());
        assertTrue(response.items().get(2).canReprint());
    }

    @Test
    void rejectsInvalidOrUnownedRequestsBeforeReadingHistory() {
        AtomicInteger calls = new AtomicInteger();
        PrintHistoryCommandService service = new PrintHistoryCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> {
                    calls.incrementAndGet();
                    return List.of();
                });

        List<PrintHistoryCommandService.PrintHistoryRequest> invalid = List.of(
                new PrintHistoryCommandService.PrintHistoryRequest(0, "", "all", 1, 10),
                new PrintHistoryCommandService.PrintHistoryRequest(9, "", "all", 1, 10),
                new PrintHistoryCommandService.PrintHistoryRequest(7, "bad\nquery", "all", 1, 10),
                new PrintHistoryCommandService.PrintHistoryRequest(7, "", null, 1, 10),
                new PrintHistoryCommandService.PrintHistoryRequest(7, "", "unknown", 1, 10),
                new PrintHistoryCommandService.PrintHistoryRequest(7, "", "all", 0, 10),
                new PrintHistoryCommandService.PrintHistoryRequest(7, "", "all", 1, 101));

        for (PrintHistoryCommandService.PrintHistoryRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.list(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void mapsReaderAndMalformedDataFailuresWithoutLeakingDetails() {
        PrintHistoryCommandService failing = new PrintHistoryCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> {
                    throw new IllegalStateException("sqlite " + SECRET);
                });
        PrintHistoryCommandService malformed = service(List.of(job(0, "SUP", "Supply", "success")));

        for (PrintHistoryCommandService service : List.of(failing, malformed)) {
            JDeskException error = assertThrows(
                    JDeskException.class,
                    () -> service.list(new PrintHistoryCommandService.PrintHistoryRequest(
                            7, "", "all", 1, 10), null));
            assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
            assertFalse(error.publicMessage().contains(SECRET));
            assertNull(error.details());
            assertNull(error.getCause());
        }
    }

    @Test
    void bridgeCodecRoundTripsOnlyTheAllowlistedHistoryFields() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        PrintHistoryCommandService.PrintHistoryRequest request = codec.decode(
                """
                {"shopId":7,"query":"","status":"all","page":1,"pageSize":10}
                """,
                PrintHistoryCommandService.PrintHistoryRequest.class);

        String json = codec.encode(service(List.of(job(1, "SUP-1", "Supply", "success")))
                .list(request, null)
                .toCompletableFuture()
                .join());

        assertTrue(json.contains("\"jobId\":\"1\""));
        assertTrue(json.contains("\"canReprint\":true"));
        assertFalse(json.contains(SECRET));
        assertFalse(json.contains("layout"));
    }

    @Test
    void readsConfiguredLivePrintHistoryOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue("1".equals(System.getenv("WCODE_LIVE_READ_SMOKE")));
        try (AppDataLock ignored = AppDataLock.acquire(AppPaths.appDataDir(), "jdesk-live-history-test")) {
            Database.initDatabase();
            List<Shop> shops = new ShopRepository().findAll();
            assumeTrue(!shops.isEmpty());
            Shop shop = shops.getFirst();

            PrintHistoryCommandService.PrintHistoryResponse response = new PrintHistoryCommandService()
                    .list(new PrintHistoryCommandService.PrintHistoryRequest(
                            shop.getId(), "", "all", 1, 25), null)
                    .toCompletableFuture()
                    .join();

            assertEquals(shop.getId(), response.shopId());
            assertTrue(response.totalItems() >= response.items().size());
            assertTrue(response.items().size() <= 25);
            assertFalse(response.toString().contains(shop.getApiKey()));
        }
    }

    private PrintHistoryCommandService service(List<PrintHistoryJobSummary> jobs) {
        return new PrintHistoryCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> jobs);
    }

    private PrintHistoryJobSummary job(long id, String supplyId, String supplyName, String status) {
        return job(id, supplyId, supplyName, status, 5);
    }

    private PrintHistoryJobSummary job(
            long id, String supplyId, String supplyName, String status, int itemCount) {
        return new PrintHistoryJobSummary(
                id,
                7,
                "Main",
                supplyId,
                supplyName,
                "2026-07-18T10:00:00Z",
                itemCount,
                1,
                " Default ",
                "{\"layout\":\"" + SECRET + "\"}",
                status,
                SECRET);
    }
}
