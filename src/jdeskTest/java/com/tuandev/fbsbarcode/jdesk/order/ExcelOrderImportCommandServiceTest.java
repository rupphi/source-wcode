package com.tuandev.fbsbarcode.jdesk.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.order.ExcelOrderImportService;
import com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.models.Sticker;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExcelOrderImportCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-never-cross-the-import-bridge";
    private static final Path SELECTED_FILE = Path.of("/private/operator/orders.xlsx");
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void cancellationReturnsAnEmptySafeResultWithoutReadingAFile() {
        AtomicInteger reads = new AtomicInteger();
        ExcelOrderImportCommandService service = service(
                context -> CompletableFuture.completedFuture(Optional.empty()),
                path -> {
                    reads.incrementAndGet();
                    return List.of(order(1, "ART-1", "One"));
                },
                (token, orderIds) -> List.of());

        ExcelOrderImportCommandService.ImportedOrderPage response = service
                .importExcel(new ExcelOrderImportCommandService.ImportExcelRequest(7, 25), null)
                .toCompletableFuture()
                .join();

        assertTrue(response.cancelled());
        assertEquals("", response.sessionId());
        assertEquals("", response.fileName());
        assertEquals(0, response.totalItems());
        assertEquals(0, reads.get());
    }

    @Test
    void importsSortsEnrichesAndPagesOrdersWithoutReturningPathOrSecret() {
        List<Order> imported = List.of(
                order(103, "ART-10", " Third\u0000 item "),
                order(101, "ART-2", "First"),
                order(102, "ART-1", "Second"));
        ExcelOrderImportCommandService service = service(
                context -> CompletableFuture.completedFuture(Optional.of(SELECTED_FILE)),
                path -> imported,
                (token, orderIds) -> {
                    assertEquals(SECRET, token);
                    assertEquals(List.of(103L, 101L, 102L), orderIds);
                    return List.of(new Sticker(101L, 12L, 34L, "STICKER-101", "remote-file"));
                });

        ExcelOrderImportCommandService.ImportedOrderPage first = service
                .importExcel(new ExcelOrderImportCommandService.ImportExcelRequest(7, 2), null)
                .toCompletableFuture()
                .join();

        assertFalse(first.cancelled());
        assertTrue(first.sessionId().matches("[0-9a-f-]{36}"));
        assertEquals("orders.xlsx", first.fileName());
        assertEquals(3, first.totalItems());
        assertEquals(2, first.totalPages());
        assertEquals(List.of("102", "101"), first.items().stream().map(item -> item.orderId()).toList());
        assertEquals("First", first.items().get(1).name());
        assertTrue(first.items().get(1).stickerAvailable());
        assertTrue(first.items().getFirst().imagePath().startsWith("jdesk://app/order-images/"));
        assertFalse(first.toString().contains(SELECTED_FILE.getParent().toString()));
        assertFalse(first.toString().contains(SECRET));

        ExcelOrderImportCommandService.ImportedOrderPage second = service
                .loadImported(new ExcelOrderImportCommandService.LoadImportedOrdersRequest(
                        7, first.sessionId(), "third", 1, 10), null)
                .toCompletableFuture()
                .join();

        assertEquals("third", second.query());
        assertEquals(1, second.totalItems());
        assertEquals("103", second.items().getFirst().orderId());
        assertEquals("Third item", second.items().getFirst().name());
    }

    @Test
    void rejectsInvalidOrCrossShopSessionRequestsBeforeReturningData() {
        AtomicInteger dialogCalls = new AtomicInteger();
        ExcelOrderImportCommandService service = service(
                context -> {
                    dialogCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.of(SELECTED_FILE));
                },
                path -> List.of(order(101, "ART-1", "One")),
                (token, orderIds) -> List.of());

        assertInvalid(() -> service.importExcel(
                new ExcelOrderImportCommandService.ImportExcelRequest(9, 25), null));
        assertEquals(0, dialogCalls.get());

        String sessionId = service.importExcel(
                        new ExcelOrderImportCommandService.ImportExcelRequest(7, 25), null)
                .toCompletableFuture()
                .join()
                .sessionId();
        assertInvalid(() -> service.loadImported(
                new ExcelOrderImportCommandService.LoadImportedOrdersRequest(8, sessionId, "", 1, 25), null));
        assertInvalid(() -> service.loadImported(
                new ExcelOrderImportCommandService.LoadImportedOrdersRequest(7, "../../orders", "", 1, 25), null));
        assertInvalid(() -> service.loadImported(
                new ExcelOrderImportCommandService.LoadImportedOrdersRequest(7, sessionId, "bad\nquery", 1, 25), null));
    }

    @Test
    void mapsInvalidWorkbookAndStickerFailuresWithoutLeakingTheirDetails() {
        ExcelOrderImportCommandService invalidWorkbook = service(
                context -> CompletableFuture.completedFuture(Optional.of(SELECTED_FILE)),
                path -> {
                    throw new ExcelOrderImportService.InvalidExcelFileException("malformed " + SECRET);
                },
                (token, orderIds) -> List.of());

        JDeskException invalid = asyncError(() -> invalidWorkbook.importExcel(
                new ExcelOrderImportCommandService.ImportExcelRequest(7, 25), null));
        assertEquals(ErrorCode.INVALID_REQUEST, invalid.code());
        assertFalse(invalid.publicMessage().contains(SECRET));

        ExcelOrderImportCommandService stickerFailure = service(
                context -> CompletableFuture.completedFuture(Optional.of(SELECTED_FILE)),
                path -> List.of(order(101, "ART-1", "One")),
                (token, orderIds) -> {
                    throw new IOException("upstream body " + SECRET);
                });

        JDeskException unavailable = asyncError(() -> stickerFailure.importExcel(
                new ExcelOrderImportCommandService.ImportExcelRequest(7, 25), null));
        assertEquals(ErrorCode.INTERNAL_ERROR, unavailable.code());
        assertFalse(unavailable.publicMessage().contains(SECRET));
    }

    private ExcelOrderImportCommandService service(
            ExcelOrderImportCommandService.FilePicker picker,
            ExcelOrderImportCommandService.ExcelReader reader,
            ExcelOrderImportCommandService.StickerReader stickers) {
        return new ExcelOrderImportCommandService(
                () -> List.of(
                        new Shop(7, "Main", SECRET),
                        new Shop(8, "Secondary", "secondary-secret")),
                picker,
                reader,
                stickers,
                new OrderImageAssetService(),
                NOW,
                Duration.ofMinutes(30),
                8,
                Runnable::run);
    }

    private Order order(long id, String article, String name) {
        Order order = new Order();
        order.setId(id);
        order.setArticle(article);
        order.setName(name);
        order.setBrand("Brand");
        order.setSize("M");
        order.setColor("Black");
        order.setBarcode("BAR-" + id);
        order.setImageUrl(null);
        order.setImage(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        return order;
    }

    private void assertInvalid(StageCall call) {
        JDeskException exception = assertThrows(JDeskException.class, call::run);
        assertEquals(ErrorCode.INVALID_REQUEST, exception.code());
    }

    private JDeskException asyncError(StageCall call) {
        CompletionException wrapper = assertThrows(CompletionException.class, () -> call.run()
                .toCompletableFuture()
                .join());
        return (JDeskException) wrapper.getCause();
    }

    @FunctionalInterface
    private interface StageCall {
        java.util.concurrent.CompletionStage<?> run();
    }
}
