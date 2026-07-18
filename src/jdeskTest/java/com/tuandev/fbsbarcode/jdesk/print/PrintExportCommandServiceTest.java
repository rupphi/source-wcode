package com.tuandev.fbsbarcode.jdesk.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.print.OrderExportWorkflow;
import com.tuandev.fbsbarcode.features.print.PrintJobOptions;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyDetailCommandService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintExportCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-never-cross-the-bridge";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC);
    private static final SupplyDetailCommandService.OrderSortRequest SORT =
            new SupplyDetailCommandService.OrderSortRequest(true, true, true, true);

    @TempDir Path tempDir;

    @Test
    void cancellingTheNativeSaveDialogDoesNotExportOrCreateAnOpenSession() {
        AtomicInteger exports = new AtomicInteger();
        PrintExportCommandService service = service(
                suggested -> {
                    assertEquals("WCODE-SUP-1.pdf", suggested);
                    return Optional.empty();
                },
                (shop, source, options, labels, details) -> {
                    exports.incrementAndGet();
                    throw new AssertionError("export should not run");
                },
                ignored -> {});

        PrintExportCommandService.PrintExportResponse response = service
                .exportSupply(request(7), null)
                .toCompletableFuture()
                .join();

        assertTrue(response.cancelled());
        assertEquals("", response.exportId());
        assertEquals("", response.labelsFileName());
        assertEquals(0, exports.get());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void exportsTwoPdfFilesWithoutReturningPathsAndOpensThemThroughAnOpaqueSession() throws Exception {
        Path selected = tempDir.resolve("labels.pdf");
        Path existingDetails = tempDir.resolve("NHAT_HANG-labels.pdf");
        Files.writeString(existingDetails, "preserve-existing-details");
        List<Path> exportedPaths = new ArrayList<>();
        List<Path> openedPaths = new ArrayList<>();
        PrintExportCommandService service = service(
                ignored -> Optional.of(selected),
                (shop, source, options, labels, details) -> {
                    assertEquals(SECRET, shop.getApiKey());
                    assertEquals("SUP-1", source.supplyId());
                    assertEquals(2, source.orders().size());
                    assertEquals(new PrintJobOptions(
                            com.tuandev.fbsbarcode.features.print.PrintPageOrder.STICKER_THEN_BARCODE,
                            2), options);
                    exportedPaths.add(labels);
                    exportedPaths.add(details);
                    Files.writeString(labels, "labels");
                    Files.writeString(details, "details");
                    return new PrintExportCommandService.PdfExportReceipt(9_007_199_254_740_993L, 1);
                },
                openedPaths::add);

        PrintExportCommandService.PrintExportResponse response = service
                .exportSupply(request(7), null)
                .toCompletableFuture()
                .join();

        assertFalse(response.cancelled());
        assertTrue(response.exportId().matches("[0-9a-f-]{36}"));
        assertEquals("labels.pdf", response.labelsFileName());
        assertEquals("NHAT_HANG-labels-2.pdf", response.detailsFileName());
        assertEquals("9007199254740993", response.printJobId());
        assertEquals(2, response.itemCount());
        assertEquals(6, response.pageCount());
        assertEquals(1, response.kizAttachmentCount());
        assertEquals(List.of(selected, tempDir.resolve("NHAT_HANG-labels-2.pdf")), exportedPaths);
        assertEquals("preserve-existing-details", Files.readString(existingDetails));
        assertFalse(response.toString().contains(tempDir.toString()));
        assertFalse(response.toString().contains(SECRET));

        PrintExportCommandService.OpenExportResponse opened = service
                .openExport(new PrintExportCommandService.OpenExportRequest(
                        7, response.exportId(), "details"), null)
                .toCompletableFuture()
                .join();

        assertTrue(opened.opened());
        assertEquals("NHAT_HANG-labels-2.pdf", opened.fileName());
        assertEquals(List.of(tempDir.resolve("NHAT_HANG-labels-2.pdf")), openedPaths);
        assertFalse(opened.toString().contains(tempDir.toString()));
    }

    @Test
    void rejectsInvalidRequestsUnknownShopsAndCrossShopOpenAttemptsBeforeIo() {
        AtomicInteger pickerCalls = new AtomicInteger();
        PrintExportCommandService service = service(
                ignored -> {
                    pickerCalls.incrementAndGet();
                    return Optional.of(tempDir.resolve("labels.pdf"));
                },
                (shop, source, options, labels, details) -> new PrintExportCommandService.PdfExportReceipt(1, 0),
                ignored -> {});

        assertInvalid(() -> service.exportSupply(request(9), null));
        assertInvalid(() -> service.exportSupply(new PrintExportCommandService.ExportSupplyRequest(
                7, "bad\nsupply", "", SORT, "barcode_then_sticker", 1), null));
        assertInvalid(() -> service.exportSupply(new PrintExportCommandService.ExportSupplyRequest(
                7, "SUP-1", "", SORT, "barcode_then_sticker", 101), null));
        assertEquals(0, pickerCalls.get());

        PrintExportCommandService.PrintExportResponse response = service
                .exportSupply(request(7), null)
                .toCompletableFuture()
                .join();
        assertInvalid(() -> service.openExport(new PrintExportCommandService.OpenExportRequest(
                8, response.exportId(), "labels"), null));
        assertInvalid(() -> service.openExport(new PrintExportCommandService.OpenExportRequest(
                7, "../../labels", "labels"), null));
        assertInvalid(() -> service.openExport(new PrintExportCommandService.OpenExportRequest(
                7, response.exportId(), "unknown"), null));
    }

    @Test
    void redactsExporterFailuresAndDoesNotCreateAnOpenSession() {
        PrintExportCommandService service = service(
                ignored -> Optional.of(tempDir.resolve("labels.pdf")),
                (shop, source, options, labels, details) -> {
                    throw new java.io.IOException("failed at " + tempDir + " with " + SECRET);
                },
                ignored -> {});

        JDeskException error = asyncError(() -> service.exportSupply(request(7), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertEquals(new PrintExportCommandService.PrintExportError("export_failed", true), error.details());
        assertFalse(error.publicMessage().contains(SECRET));
        assertFalse(error.publicMessage().contains(tempDir.toString()));
        assertEquals(null, error.getCause());
    }

    private PrintExportCommandService service(
            Picker picker,
            PrintExportCommandService.PdfExporter exporter,
            PrintExportCommandService.FileOpener opener) {
        return new PrintExportCommandService(
                () -> List.of(new Shop(7, "Main", SECRET), new Shop(8, "Second", "second-secret")),
                (shop, supplyId, query, sort) -> new PrintExportCommandService.PrintSource(
                        supplyId,
                        "Supply / One",
                        List.of(order(101), order(102))),
                (shop, orders) -> {},
                (context, suggestedName) -> CompletableFuture.completedFuture(picker.pick(suggestedName)),
                exporter,
                opener,
                NOW,
                Duration.ofMinutes(30),
                8,
                Runnable::run);
    }

    private PrintExportCommandService.ExportSupplyRequest request(int shopId) {
        return new PrintExportCommandService.ExportSupplyRequest(
                shopId, "SUP-1", "", SORT, "sticker_then_barcode", 2);
    }

    private static Order order(long id) {
        Order order = new Order();
        order.setId(id);
        order.setArticle("ART-" + id);
        order.setName("Order " + id);
        order.setBarcode("BAR-" + id);
        return order;
    }

    private static void assertInvalid(StageCall call) {
        JDeskException error = assertThrows(JDeskException.class, call::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }

    private static JDeskException asyncError(StageCall call) {
        CompletionException wrapper = assertThrows(CompletionException.class, () -> call.run()
                .toCompletableFuture()
                .join());
        return (JDeskException) wrapper.getCause();
    }

    @FunctionalInterface
    private interface Picker {
        Optional<Path> pick(String suggestedName);
    }

    @FunctionalInterface
    private interface StageCall {
        java.util.concurrent.CompletionStage<?> run();
    }
}
