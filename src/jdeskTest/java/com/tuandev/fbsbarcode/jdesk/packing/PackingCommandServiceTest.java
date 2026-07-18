package com.tuandev.fbsbarcode.jdesk.packing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PackingCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-not-cross-the-packing-bridge";

    @Test
    void filtersAndPagesNewOrdersBeforeRegisteringCachedImages() {
        List<Order> newOrders = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            newOrders.add(order(
                    9_007_199_254_740_990L + index,
                    index <= 11 ? "Обувь" : "Сумки",
                    index == 11 ? " Alpha\u0000 product " : "Product " + index,
                    "ART-" + index));
        }
        AtomicReference<List<Long>> imageOrderIds = new AtomicReference<>();
        PackingCommandService service = service(
                new PackingWorkflow.PackingBoard(
                        newOrders,
                        List.of(new WbSupplySummary("OPEN-1", "Open", false, false, "", 3)),
                        List.of(new WbSupplySummary("DONE-1", "Done", true, true, "", 4))),
                orders -> {
                    imageOrderIds.set(orders.stream().map(Order::getId).toList());
                    orders.forEach(value -> value.setImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
                });

        PackingCommandService.PackingBoardResponse response = service
                .load(new PackingCommandService.PackingBoardRequest(
                        7, "new", " alpha ", List.of(" Обувь "), 1, 10), null)
                .toCompletableFuture()
                .join();

        assertEquals("alpha", response.query());
        assertEquals(List.of("Обувь"), response.categories());
        assertEquals(List.of("Обувь", "Сумки"), response.availableCategories());
        assertEquals(12, response.newOrderCount());
        assertEquals(1, response.preparationCount());
        assertEquals(1, response.dispatchCount());
        assertEquals(1, response.totalItems());
        assertEquals(1, response.totalPages());
        assertEquals(List.of(9_007_199_254_741_001L), imageOrderIds.get());
        assertEquals("9007199254741001", response.orders().getFirst().orderId());
        assertEquals("Alpha product", response.orders().getFirst().name());
        assertTrue(response.orders().getFirst().imagePath().startsWith("jdesk://app/order-images/"));
        assertTrue(response.supplies().isEmpty());
        assertFalse(response.toString().contains(SECRET));
        assertFalse(response.toString().contains("https://"));
    }

    @Test
    void returnsPreparationAndDispatchPagesWithoutLoadingOrderImages() {
        AtomicInteger imageCalls = new AtomicInteger();
        PackingCommandService service = service(
                new PackingWorkflow.PackingBoard(
                        List.of(order(101, "Обувь", "Product", "ART-1")),
                        supplies("OPEN", 11, false),
                        supplies("DONE", 2, true)),
                orders -> imageCalls.incrementAndGet());

        PackingCommandService.PackingBoardResponse preparation = service
                .load(new PackingCommandService.PackingBoardRequest(
                        7, "preparation", "", List.of(), 2, 10), null)
                .toCompletableFuture()
                .join();
        PackingCommandService.PackingBoardResponse dispatch = service
                .load(new PackingCommandService.PackingBoardRequest(
                        7, "dispatch", "", List.of(), 1, 10), null)
                .toCompletableFuture()
                .join();

        assertEquals(11, preparation.totalItems());
        assertEquals(2, preparation.totalPages());
        assertEquals(List.of("OPEN-11"),
                preparation.supplies().stream().map(PackingCommandService.PackingSupplyItem::id).toList());
        assertTrue(preparation.orders().isEmpty());
        assertEquals(2, dispatch.totalItems());
        assertEquals("closed", dispatch.supplies().getFirst().status());
        assertEquals("consumer", dispatch.supplies().getFirst().mode());
        assertEquals(0, imageCalls.get());
    }

    @Test
    void rejectsInvalidOrUnownedRequestsBeforeLoadingTheBoard() {
        AtomicInteger boardCalls = new AtomicInteger();
        PackingCommandService service = new PackingCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shop -> {
                    boardCalls.incrementAndGet();
                    return new PackingWorkflow.PackingBoard(List.of(), List.of(), List.of());
                },
                orders -> {},
                new com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService());

        List<PackingCommandService.PackingBoardRequest> invalid = List.of(
                new PackingCommandService.PackingBoardRequest(0, "new", "", List.of(), 1, 10),
                new PackingCommandService.PackingBoardRequest(9, "new", "", List.of(), 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "unknown", "", List.of(), 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "new", "bad\nquery", List.of(), 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "new", "", null, 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "new", "", List.of(""), 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "new", "", List.of("bad\ncategory"), 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "preparation", "", List.of("Обувь"), 1, 10),
                new PackingCommandService.PackingBoardRequest(7, "new", "", List.of(), 0, 10),
                new PackingCommandService.PackingBoardRequest(7, "new", "", List.of(), 1, 101));

        for (PackingCommandService.PackingBoardRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.load(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, boardCalls.get());
    }

    @Test
    void mapsReaderFailuresWithoutLeakingSecrets() {
        PackingCommandService service = new PackingCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shop -> {
                    throw new IllegalStateException("sqlite failure " + SECRET);
                },
                orders -> {},
                new com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService());

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.load(new PackingCommandService.PackingBoardRequest(
                        7, "new", "", List.of(), 1, 10), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.details());
        assertNull(error.getCause());
    }

    @Test
    void bridgeCodecRoundTripsTheBoundedPackingContract() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        PackingCommandService.PackingBoardRequest request = codec.decode(
                """
                {"shopId":7,"tab":"new","query":"","categories":[],"page":1,"pageSize":10}
                """,
                PackingCommandService.PackingBoardRequest.class);

        String json = codec.encode(service(
                        new PackingWorkflow.PackingBoard(List.of(), List.of(), List.of()),
                        orders -> {})
                .load(request, null)
                .toCompletableFuture()
                .join());

        assertTrue(json.contains("\"newOrderCount\":0"));
        assertTrue(json.contains("\"orders\":[]"));
        assertTrue(json.contains("\"supplies\":[]"));
        assertFalse(json.contains(SECRET));
    }

    @Test
    void readsConfiguredLivePackingBoardOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue("1".equals(System.getenv("WCODE_LIVE_READ_SMOKE")));
        try (AppDataLock ignored = AppDataLock.acquire(AppPaths.appDataDir(), "jdesk-live-packing-test")) {
            Database.initDatabase();
            List<Shop> shops = new ShopRepository().findAll();
            assumeTrue(!shops.isEmpty());
            Shop shop = shops.getFirst();

            PackingCommandService.PackingBoardResponse response = new PackingCommandService(
                            new com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService())
                    .load(new PackingCommandService.PackingBoardRequest(
                            shop.getId(), "new", "", List.of(), 1, 20), null)
                    .toCompletableFuture()
                    .join();

            assertEquals(shop.getId(), response.shopId());
            assertTrue(response.totalItems() >= response.orders().size());
            assertTrue(response.orders().size() <= 20);
            assertTrue(response.supplies().isEmpty());
            assertFalse(response.toString().contains(shop.getApiKey()));
        }
    }

    private PackingCommandService service(
            PackingWorkflow.PackingBoard board, PackingCommandService.PageImageLoader imageLoader) {
        return new PackingCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shop -> board,
                imageLoader,
                new com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService());
    }

    private static List<WbSupplySummary> supplies(String prefix, int count, boolean done) {
        List<WbSupplySummary> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            values.add(new WbSupplySummary(
                    prefix + "-" + index,
                    "Supply " + index,
                    done,
                    index % 2 == 0,
                    "2026-07-18T10:00:00Z",
                    index));
        }
        return values;
    }

    private static Order order(long id, String subject, String name, String article) {
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
        order.setBarcode("SKU-1");
        order.setCreatedAt("2026-07-18T10:00:00Z");
        order.setPrice(12_345);
        order.setSupplierStatus("new");
        order.setWbStatus("waiting");
        order.setRequiresKiz(true);
        order.setImageUrl("https://untrusted.example/secret-image");
        return order;
    }
}
