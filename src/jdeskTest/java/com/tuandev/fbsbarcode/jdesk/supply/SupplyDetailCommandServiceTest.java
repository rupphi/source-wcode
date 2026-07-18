package com.tuandev.fbsbarcode.jdesk.supply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.features.supply.OrderSortingService;
import com.tuandev.fbsbarcode.integration.wb.WbOrderRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.models.Order;
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

class SupplyDetailCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-not-cross-the-detail-bridge";

    @Test
    void returnsAStableSanitizedOrderPageWithStringIdentifiers() {
        List<Order> orders = new ArrayList<>();
        for (int index = 1; index <= 11; index++) {
            orders.add(order(
                    9_007_199_254_740_993L + index,
                    index == 11 ? "Alpha" : "Zeta " + index,
                    index == 11 ? "Alpha\u0000 bag" : "Product " + index,
                    "ART-" + index,
                    "SKU-" + index));
        }
        SupplyDetailCommandService service = service(
                (shopId, supplyId) -> new WbSupplySummary(
                        supplyId, " Drop\u0000 one ", false, true, "2026-07-18T10:00:00Z", 11),
                (shopId, supplyId) -> orders);

        SupplyDetailCommandService.SupplyDetailResponse response = service
                .load(new SupplyDetailCommandService.LoadSupplyDetailRequest(
                        7,
                        " WB-GI-1 ",
                        "",
                        1,
                        10,
                        new SupplyDetailCommandService.OrderSortRequest(true, true, true, true)), null)
                .toCompletableFuture()
                .join();

        assertEquals("WB-GI-1", response.supply().id());
        assertEquals("Drop one", response.supply().name());
        assertEquals(11, response.totalItems());
        assertEquals(2, response.totalPages());
        assertEquals(10, response.items().size());
        assertEquals("9007199254741004", response.items().getFirst().orderId());
        assertEquals("Alpha bag", response.items().getFirst().name());
        assertEquals("1001", response.items().getFirst().nmId());
        assertEquals(12_345, response.items().getFirst().priceKopecks());
        assertTrue(response.items().getFirst().requiresKiz());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void filtersOrdersAcrossSafeLocalFields() {
        SupplyDetailCommandService service = service(
                (shopId, supplyId) -> new WbSupplySummary(supplyId, "Supply", false, false, "", 2),
                (shopId, supplyId) -> List.of(
                        order(101, "Shoes", "First", "ART-1", "SKU-1"),
                        order(102, "Bags", "Second", "ART-2", "SKU-SPECIAL")));

        SupplyDetailCommandService.SupplyDetailResponse response = service
                .load(new SupplyDetailCommandService.LoadSupplyDetailRequest(
                        7,
                        "WB-GI-1",
                        " special ",
                        1,
                        10,
                        new SupplyDetailCommandService.OrderSortRequest(true, false, false, false)), null)
                .toCompletableFuture()
                .join();

        assertEquals("special", response.query());
        assertEquals(1, response.totalItems());
        assertEquals("102", response.items().getFirst().orderId());
    }

    @Test
    void rejectsInvalidUnownedOrMissingSuppliesBeforeLoadingOrders() {
        AtomicInteger supplyCalls = new AtomicInteger();
        AtomicInteger orderCalls = new AtomicInteger();
        SupplyDetailCommandService service = new SupplyDetailCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                (shopId, supplyId) -> {
                    supplyCalls.incrementAndGet();
                    return null;
                },
                (shopId, supplyId) -> {
                    orderCalls.incrementAndGet();
                    return List.of();
                },
                new OrderSortingService());
        SupplyDetailCommandService.OrderSortRequest sort =
                new SupplyDetailCommandService.OrderSortRequest(true, true, true, true);

        List<SupplyDetailCommandService.LoadSupplyDetailRequest> invalid = List.of(
                new SupplyDetailCommandService.LoadSupplyDetailRequest(0, "WB", "", 1, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(9, "WB", "", 1, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "", "", 1, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "WB\nBAD", "", 1, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "WB-MISSING", "", 1, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "WB", "bad\nquery", 1, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "WB", "", 0, 10, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "WB", "", 1, 101, sort),
                new SupplyDetailCommandService.LoadSupplyDetailRequest(7, "WB", "", 1, 10, null));

        for (SupplyDetailCommandService.LoadSupplyDetailRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.load(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(1, supplyCalls.get());
        assertEquals(0, orderCalls.get());
    }

    @Test
    void mapsOrderFailureWithoutLeakingDetails() {
        SupplyDetailCommandService service = service(
                (shopId, supplyId) -> new WbSupplySummary(supplyId, "Supply", false, false, "", 1),
                (shopId, supplyId) -> {
                    throw new IllegalStateException("sqlite failure " + SECRET);
                });

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.load(validRequest(), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.details());
        assertNull(error.getCause());
    }

    @Test
    void bridgeCodecRoundTripsNestedSortOptionsWithoutSecrets() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        SupplyDetailCommandService.LoadSupplyDetailRequest request = codec.decode(
                """
                {"shopId":7,"supplyId":"WB-GI-1","query":"","page":1,"pageSize":10,
                 "sort":{"bySubject":true,"byArticle":true,"byColor":true,"bySize":true}}
                """,
                SupplyDetailCommandService.LoadSupplyDetailRequest.class);
        SupplyDetailCommandService service = service(
                (shopId, supplyId) -> new WbSupplySummary(supplyId, "Supply", false, false, "", 0),
                (shopId, supplyId) -> List.of());

        String json = codec.encode(service.load(request, null).toCompletableFuture().join());

        assertTrue(json.contains("\"totalItems\":0"));
        assertTrue(json.contains("\"items\":[]"));
        assertFalse(json.contains(SECRET));
    }

    @Test
    void readsConfiguredLiveSupplyDetailOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue("1".equals(System.getenv("WCODE_LIVE_READ_SMOKE")));
        try (AppDataLock ignored = AppDataLock.acquire(AppPaths.appDataDir(), "jdesk-live-supply-detail-test")) {
            Database.initDatabase();
            List<Shop> shops = new ShopRepository().findAll();
            assumeTrue(!shops.isEmpty());
            Shop shop = shops.getFirst();
            WbSupplyRepository.SupplyPage supplies =
                    new WbSupplyRepository().findSupplyPage(shop.getId(), "", null, 1, 0);
            assumeTrue(!supplies.items().isEmpty());
            String supplyId = supplies.items().getFirst().getSupplyId();

            SupplyDetailCommandService.SupplyDetailResponse response = new SupplyDetailCommandService()
                    .load(new SupplyDetailCommandService.LoadSupplyDetailRequest(
                            shop.getId(),
                            supplyId,
                            "",
                            1,
                            100,
                            new SupplyDetailCommandService.OrderSortRequest(true, true, true, true)), null)
                    .toCompletableFuture()
                    .join();

            assertEquals(supplyId, response.supply().id());
            assertTrue(response.totalItems() >= response.items().size());
            assertFalse(response.toString().contains(shop.getApiKey()));
        }
    }

    private SupplyDetailCommandService service(
            SupplyDetailCommandService.SupplyReader supplyReader,
            SupplyDetailCommandService.OrderReader orderReader) {
        return new SupplyDetailCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                supplyReader,
                orderReader,
                new OrderSortingService());
    }

    private SupplyDetailCommandService.LoadSupplyDetailRequest validRequest() {
        return new SupplyDetailCommandService.LoadSupplyDetailRequest(
                7,
                "WB-GI-1",
                "",
                1,
                10,
                new SupplyDetailCommandService.OrderSortRequest(true, true, true, true));
    }

    private Order order(long id, String subject, String name, String article, String barcode) {
        Order order = new Order();
        order.setId(id);
        order.setNmId(1001L);
        order.setBrand(" Brand ");
        order.setName(name);
        order.setSubjectName(subject);
        order.setSize("M");
        order.setRuSize("44");
        order.setColor("Blue");
        order.setArticle(article);
        order.setBarcode(barcode);
        order.setCreatedAt("2026-07-18T10:00:00Z");
        order.setPrice(12_345);
        order.setSupplierStatus("confirm");
        order.setWbStatus("sorted");
        order.setRequiresKiz(true);
        order.setImageUrl("https://untrusted.example/secret-image");
        return order;
    }
}
