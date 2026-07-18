package com.tuandev.fbsbarcode.jdesk.supply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SupplyCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-not-cross-the-supply-bridge";

    @Test
    void returnsAValidatedSanitizedSupplyPage() {
        AtomicReference<SupplyCommandService.SupplyQuery> captured = new AtomicReference<>();
        SupplyCommandService service = new SupplyCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                query -> {
                    captured.set(query);
                    return new WbSupplyRepository.SupplyPage(
                            List.of(new WbSupplySummary(
                                    "WB-GI-1", " Alpha\u0000 supply ", false, true, "2026-07-18T10:00:00Z", 12)),
                            26,
                            20,
                            6);
                });

        SupplyCommandService.ListSuppliesResponse response = service
                .list(new SupplyCommandService.ListSuppliesRequest(7, " alpha ", "open", 2, 25), null)
                .toCompletableFuture()
                .join();

        assertEquals(new SupplyCommandService.SupplyQuery(7, "alpha", false, 25, 25), captured.get());
        assertEquals(2, response.page());
        assertEquals(2, response.totalPages());
        assertEquals(20, response.openItems());
        assertEquals(6, response.closedItems());
        assertEquals(
                new SupplyCommandService.SupplyItem(
                        "WB-GI-1", "Alpha supply", "open", "b2b", "2026-07-18T10:00:00Z", 12),
                response.items().getFirst());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void rejectsInvalidOrUnownedRequestsBeforeRepositoryAccess() {
        AtomicInteger calls = new AtomicInteger();
        SupplyCommandService service = new SupplyCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                query -> {
                    calls.incrementAndGet();
                    return new WbSupplyRepository.SupplyPage(List.of(), 0, 0, 0);
                });

        List<SupplyCommandService.ListSuppliesRequest> invalid = List.of(
                new SupplyCommandService.ListSuppliesRequest(0, "", "all", 1, 25),
                new SupplyCommandService.ListSuppliesRequest(9, "", "all", 1, 25),
                new SupplyCommandService.ListSuppliesRequest(7, "", null, 1, 25),
                new SupplyCommandService.ListSuppliesRequest(7, "", "unknown", 1, 25),
                new SupplyCommandService.ListSuppliesRequest(7, "", "all", 0, 25),
                new SupplyCommandService.ListSuppliesRequest(7, "", "all", 1, 101),
                new SupplyCommandService.ListSuppliesRequest(7, "line\nbreak", "all", 1, 25));

        for (SupplyCommandService.ListSuppliesRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.list(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void mapsRepositoryFailureWithoutLeakingDetails() {
        SupplyCommandService service = new SupplyCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                query -> {
                    throw new IllegalStateException("sqlite failure " + SECRET);
                });

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.list(new SupplyCommandService.ListSuppliesRequest(7, "", "all", 1, 25), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.details());
        assertNull(error.getCause());
    }

    @Test
    void bridgeCodecRoundTripsTheRequestAndResponseWithoutSecrets() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        SupplyCommandService.ListSuppliesRequest request = codec.decode(
                "{\"shopId\":7,\"query\":\"\",\"status\":\"all\",\"page\":1,\"pageSize\":25}",
                SupplyCommandService.ListSuppliesRequest.class);
        SupplyCommandService service = new SupplyCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                query -> new WbSupplyRepository.SupplyPage(List.of(), 0, 0, 0));

        String json = codec.encode(service.list(request, null).toCompletableFuture().join());

        assertTrue(json.contains("\"totalItems\":0"));
        assertTrue(json.contains("\"items\":[]"));
        assertFalse(json.contains(SECRET));
    }

    @Test
    void readsConfiguredLiveSuppliesOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue("1".equals(System.getenv("WCODE_LIVE_READ_SMOKE")));
        try (AppDataLock ignored = AppDataLock.acquire(AppPaths.appDataDir(), "jdesk-live-supply-test")) {
            Database.initDatabase();
            List<Shop> shops = new ShopRepository().findAll();
            assumeTrue(!shops.isEmpty());
            Shop shop = shops.getFirst();

            SupplyCommandService.ListSuppliesResponse response = new SupplyCommandService()
                    .list(new SupplyCommandService.ListSuppliesRequest(shop.getId(), "", "all", 1, 25), null)
                    .toCompletableFuture()
                    .join();

            assertEquals(shop.getId(), response.shopId());
            assertTrue(response.totalItems() >= response.items().size());
            assertFalse(response.toString().contains(shop.getApiKey()));
        }
    }
}
