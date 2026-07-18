package com.tuandev.fbsbarcode.jdesk.fbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.fbo.FboBarcodePdfExporter;
import com.tuandev.fbsbarcode.features.fbo.FboBarcodePrintItem;
import com.tuandev.fbsbarcode.features.fbo.FboKizPrintPlanner;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPage;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPlan;
import com.tuandev.fbsbarcode.features.fbo.FboProductRepository;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FboPrintCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-not-cross-the-fbo-print-bridge";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path tempDir;

    @Test
    void cancellingTheNativeDialogDoesNotPlanReserveOrExport() {
        AtomicInteger plans = new AtomicInteger();
        AtomicInteger exports = new AtomicInteger();
        FboPrintCommandService service = service(
                ignored -> Optional.empty(),
                (shopId, items) -> {
                    plans.incrementAndGet();
                    return plan(items.getFirst().product(), items.getFirst().quantity());
                },
                (plan, output, beforePublish) -> exports.incrementAndGet(),
                new FinalizerSpy(),
                ignored -> {});

        FboPrintCommandService.FboExportResponse response = service
                .export(request(7), null)
                .toCompletableFuture()
                .join();

        assertTrue(response.cancelled());
        assertEquals("", response.exportId());
        assertEquals(0, plans.get());
        assertEquals(0, exports.get());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void exportsResolvedProductsConsumesKizAtPublishAndOpensThroughAnOpaqueSession() throws Exception {
        Path selected = tempDir.resolve("fbo-labels.pdf");
        AtomicReference<List<String>> resolvedSkus = new AtomicReference<>();
        List<Path> opened = new ArrayList<>();
        FinalizerSpy finalizer = new FinalizerSpy();
        FboPrintCommandService service = service(
                ignored -> Optional.of(selected),
                (shopId, items) -> {
                    assertEquals(7, shopId);
                    assertEquals(List.of(2), items.stream().map(FboBarcodePrintItem::quantity).toList());
                    return plan(items.getFirst().product(), 2);
                },
                (printPlan, output, beforePublish) -> {
                    assertEquals(selected, output);
                    assertEquals(4, printPlan.pages().size());
                    Files.writeString(output, "pdf");
                    beforePublish.run();
                },
                finalizer,
                opened::add,
                skus -> {
                    resolvedSkus.set(skus);
                    return List.of(product("SKU-1"));
                });

        FboPrintCommandService.FboExportResponse response = service
                .export(request(7), null)
                .toCompletableFuture()
                .join();

        assertEquals(List.of("SKU-1"), resolvedSkus.get());
        assertFalse(response.cancelled());
        assertTrue(response.exportId().matches("[0-9a-f-]{36}"));
        assertEquals("fbo-labels.pdf", response.fileName());
        assertEquals(2, response.pairCount());
        assertEquals(4, response.pageCount());
        assertEquals(1, response.kizCount());
        assertEquals(1, finalizer.consumeCalls.get());
        assertEquals(0, finalizer.releaseCalls.get());
        assertFalse(response.toString().contains(tempDir.toString()));
        assertFalse(response.toString().contains(SECRET));

        FboPrintCommandService.OpenFboExportResponse openedResponse = service
                .open(new FboPrintCommandService.OpenFboExportRequest(7, response.exportId()), null)
                .toCompletableFuture()
                .join();
        assertTrue(openedResponse.opened());
        assertEquals("fbo-labels.pdf", openedResponse.fileName());
        assertEquals(List.of(selected), opened);
        assertInvalid(() -> service.open(
                new FboPrintCommandService.OpenFboExportRequest(8, response.exportId()), null));
    }

    @Test
    void releasesReservationsWhenExportFailsBeforePublishAndRedactsTheCause() {
        FinalizerSpy finalizer = new FinalizerSpy();
        FboPrintCommandService service = service(
                ignored -> Optional.of(tempDir.resolve("labels.pdf")),
                (shopId, items) -> plan(items.getFirst().product(), 2),
                (plan, output, beforePublish) -> {
                    throw new IOException("failed at " + tempDir + " with " + SECRET);
                },
                finalizer,
                ignored -> {});

        JDeskException error = asyncError(() -> service.export(request(7), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertEquals(new FboPrintCommandService.FboPrintError("export_failed", true), error.details());
        assertFalse(error.publicMessage().contains(SECRET));
        assertFalse(error.publicMessage().contains(tempDir.toString()));
        assertEquals(0, finalizer.consumeCalls.get());
        assertEquals(1, finalizer.releaseCalls.get());
    }

    @Test
    void doesNotReleaseAlreadyConsumedKizWhenPublisherReportsALateFailure() {
        FinalizerSpy finalizer = new FinalizerSpy();
        FboPrintCommandService service = service(
                ignored -> Optional.of(tempDir.resolve("labels.pdf")),
                (shopId, items) -> plan(items.getFirst().product(), 2),
                (plan, output, beforePublish) -> {
                    beforePublish.run();
                    throw new IOException("late publish failure");
                },
                finalizer,
                ignored -> {});

        asyncError(() -> service.export(request(7), null));

        assertEquals(1, finalizer.consumeCalls.get());
        assertEquals(0, finalizer.releaseCalls.get());
    }

    @Test
    void rejectsInvalidQuantitiesDuplicatesAndUnknownShopsBeforeResolutionOrDialogs() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger dialogs = new AtomicInteger();
        FboPrintCommandService service = service(
                ignored -> {
                    dialogs.incrementAndGet();
                    return Optional.of(tempDir.resolve("labels.pdf"));
                },
                (shopId, items) -> plan(items.getFirst().product(), 1),
                (plan, output, beforePublish) -> {},
                new FinalizerSpy(),
                ignored -> {},
                skus -> {
                    resolutions.incrementAndGet();
                    return List.of(product("SKU-1"));
                });

        List<FboPrintCommandService.FboExportRequest> invalid = List.of(
                request(9),
                new FboPrintCommandService.FboExportRequest(7, null),
                new FboPrintCommandService.FboExportRequest(7, List.of()),
                new FboPrintCommandService.FboExportRequest(7, List.of(
                        new FboPrintCommandService.FboQuantityItem("", 1))),
                new FboPrintCommandService.FboExportRequest(7, List.of(
                        new FboPrintCommandService.FboQuantityItem("SKU-1", 0))),
                new FboPrintCommandService.FboExportRequest(7, List.of(
                        new FboPrintCommandService.FboQuantityItem("SKU-1", 10_001))),
                new FboPrintCommandService.FboExportRequest(7, List.of(
                        new FboPrintCommandService.FboQuantityItem("SKU-1", 1),
                        new FboPrintCommandService.FboQuantityItem("sku-1", 1))));

        for (FboPrintCommandService.FboExportRequest request : invalid) {
            assertInvalid(() -> service.export(request, null));
        }
        assertEquals(0, resolutions.get());
        assertEquals(0, dialogs.get());
    }

    @Test
    void rejectsResolverMismatchAndRedactsPlannerFailures() {
        FboPrintCommandService mismatch = service(
                ignored -> Optional.of(tempDir.resolve("labels.pdf")),
                (shopId, items) -> plan(items.getFirst().product(), 1),
                (plan, output, beforePublish) -> {},
                new FinalizerSpy(),
                ignored -> {},
                skus -> List.of(product("OTHER")));
        assertInvalid(() -> mismatch.export(request(7), null));

        FboPrintCommandService plannerFailure = service(
                ignored -> Optional.of(tempDir.resolve("labels.pdf")),
                (shopId, items) -> {
                    throw new IllegalStateException("missing mapping " + SECRET);
                },
                (plan, output, beforePublish) -> {},
                new FinalizerSpy(),
                ignored -> {});

        JDeskException error = asyncError(() -> plannerFailure.export(request(7), null));
        assertEquals(new FboPrintCommandService.FboPrintError("preflight_failed", false), error.details());
        assertFalse(error.publicMessage().contains(SECRET));
    }

    @Test
    void releasesKizFromAnInvalidPlanBeforeReturningASafePreflightError() {
        FinalizerSpy finalizer = new FinalizerSpy();
        FboPrintCommandService service = service(
                ignored -> Optional.of(tempDir.resolve("labels.pdf")),
                (shopId, items) -> new FboPrintPlan(
                        List.of(FboPrintPage.barcode(items.getFirst().product(), 1)),
                        List.of(new Kiz(1, "KIZ-1", "reservation"))),
                (plan, output, beforePublish) -> {},
                finalizer,
                ignored -> {});

        JDeskException error = asyncError(() -> service.export(request(7), null));

        assertEquals(new FboPrintCommandService.FboPrintError("preflight_failed", false), error.details());
        assertEquals(0, finalizer.consumeCalls.get());
        assertEquals(1, finalizer.releaseCalls.get());
    }

    @Test
    void interruptionOwnsTheWholeNativeSaveTransaction() throws Exception {
        CountDownLatch dialogStarted = new CountDownLatch(1);
        CompletableFuture<Optional<Path>> dialogResult = new CompletableFuture<>();
        AtomicInteger exports = new AtomicInteger();
        FboPrintCommandService service = new FboPrintCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                (shopId, skus) -> List.of(product("SKU-1")),
                (shopId, items) -> plan(items.getFirst().product(), 2),
                (context, suggested) -> {
                    dialogStarted.countDown();
                    return dialogResult;
                },
                (plan, output, beforePublish) -> exports.incrementAndGet(),
                new FinalizerSpy(),
                ignored -> {},
                NOW,
                Duration.ofMinutes(30),
                8);
        AtomicReference<java.util.concurrent.CompletionStage<FboPrintCommandService.FboExportResponse>> result =
                new AtomicReference<>();

        Thread invocation = Thread.ofVirtual().start(() -> result.set(service.export(request(7), null)));
        assertTrue(dialogStarted.await(5, TimeUnit.SECONDS));
        invocation.interrupt();
        invocation.join(Duration.ofSeconds(5));

        assertFalse(invocation.isAlive());
        assertTrue(result.get().toCompletableFuture().isCompletedExceptionally());
        assertThrows(CancellationException.class, () -> result.get().toCompletableFuture().join());
        assertEquals(0, exports.get());
    }

    @Test
    void redactsUnexpectedShopSourceFailuresForExportAndOpen() {
        FboPrintCommandService service = new FboPrintCommandService(
                () -> {
                    throw new IllegalStateException("sqlite " + SECRET);
                },
                (shopId, skus) -> List.of(product("SKU-1")),
                (shopId, items) -> plan(items.getFirst().product(), 2),
                (context, suggested) -> CompletableFuture.completedFuture(
                        Optional.of(tempDir.resolve("labels.pdf"))),
                (plan, output, beforePublish) -> {},
                new FinalizerSpy(),
                ignored -> {},
                NOW,
                Duration.ofMinutes(30),
                8);

        JDeskException exportError = asyncError(() -> service.export(request(7), null));
        JDeskException openError = asyncError(() -> service.open(
                new FboPrintCommandService.OpenFboExportRequest(
                        7, "9a59c3c2-55dc-4bb1-90e7-3b5dba0eaa43"),
                null));

        assertEquals(new FboPrintCommandService.FboPrintError("preflight_failed", false), exportError.details());
        assertEquals(new FboPrintCommandService.FboPrintError("open_failed", true), openError.details());
        assertFalse(exportError.publicMessage().contains(SECRET));
        assertFalse(openError.publicMessage().contains(SECRET));
    }

    @Test
    void exportsARealTwoPage58By40PdfFromResolvedLocalProducts() throws Exception {
        Path appData = tempDir.resolve("actual-export-app-data");
        Path output = tempDir.resolve("actual-fbo.pdf");
        String previousAppData = System.getProperty("wcode.appdata.dir");
        try {
            System.setProperty("wcode.appdata.dir", appData.toString());
            Database.initDatabase();
            try (Connection connection = Database.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO shops(id, name, api_key) VALUES (7, 'Main', 'token')");
                statement.execute("""
                        INSERT INTO wb_product_cards(
                            shop_id, nm_id, vendor_code, subject_name, brand, title, need_kiz, synced_at)
                        VALUES (7, 1001, 'ART-1', 'Shoes', 'WCode', 'Local product', 0, 'now')
                        """);
                statement.execute("""
                        INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                        VALUES (7, 2001, 1001, 'M', '42')
                        """);
                statement.execute("""
                        INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                        VALUES (7, 2001, 'SKU-LOCAL')
                        """);
            }
            FboProductRepository repository = new FboProductRepository();
            FboKizPrintPlanner realPlanner = new FboKizPrintPlanner();
            FboBarcodePdfExporter realExporter = new FboBarcodePdfExporter();
            FboPrintCommandService service = new FboPrintCommandService(
                    () -> List.of(new Shop(7, "Main", SECRET)),
                    repository::findBySkus,
                    realPlanner::plan,
                    (context, suggested) -> CompletableFuture.completedFuture(Optional.of(output)),
                    (plan, target, beforePublish) ->
                            realExporter.exportPlan(plan, target.toFile(), beforePublish),
                    new InventoryFinalizerForUnmarkedProduct(),
                    ignored -> {},
                    NOW,
                    Duration.ofMinutes(30),
                    8);

            FboPrintCommandService.FboExportResponse response = service
                    .export(new FboPrintCommandService.FboExportRequest(
                            7,
                            List.of(new FboPrintCommandService.FboQuantityItem("SKU-LOCAL", 1))), null)
                    .toCompletableFuture()
                    .join();

            assertFalse(response.cancelled());
            assertEquals(1, response.pairCount());
            assertEquals(2, response.pageCount());
            assertEquals(0, response.kizCount());
            assertTrue(Files.size(output) > 0);
            try (PdfDocument pdf = new PdfDocument(new PdfReader(output.toFile()))) {
                assertEquals(2, pdf.getNumberOfPages());
                assertEquals(PrintTemplateService.PAGE_WIDTH, pdf.getPage(1).getPageSize().getWidth(), 0.2d);
                assertEquals(PrintTemplateService.PAGE_HEIGHT, pdf.getPage(1).getPageSize().getHeight(), 0.2d);
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void bridgeCodecLeavesNullItemsForAllowlistedRequestValidation() {
        FboPrintCommandService.FboExportRequest request = new JacksonJsonCodec().decode(
                """
                {"shopId":7,"items":[null]}
                """,
                FboPrintCommandService.FboExportRequest.class);
        FboPrintCommandService service = service(
                ignored -> Optional.empty(),
                (shopId, items) -> plan(items.getFirst().product(), 1),
                (plan, output, beforePublish) -> {},
                new FinalizerSpy(),
                ignored -> {});

        assertInvalid(() -> service.export(request, null));
    }

    private FboPrintCommandService service(
            Picker picker,
            FboPrintCommandService.PrintPlanner planner,
            FboPrintCommandService.PdfExporter exporter,
            FboPrintCommandService.InventoryFinalizer finalizer,
            FboPrintCommandService.FileOpener opener) {
        return service(picker, planner, exporter, finalizer, opener, skus -> List.of(product("SKU-1")));
    }

    private FboPrintCommandService service(
            Picker picker,
            FboPrintCommandService.PrintPlanner planner,
            FboPrintCommandService.PdfExporter exporter,
            FboPrintCommandService.InventoryFinalizer finalizer,
            FboPrintCommandService.FileOpener opener,
            Resolver resolver) {
        return new FboPrintCommandService(
                () -> List.of(new Shop(7, "Main", SECRET), new Shop(8, "Second", "second-secret")),
                (shopId, skus) -> resolver.resolve(skus),
                planner,
                (context, suggested) -> CompletableFuture.completedFuture(picker.pick(suggested)),
                exporter,
                finalizer,
                opener,
                NOW,
                Duration.ofMinutes(30),
                8);
    }

    private FboPrintCommandService.FboExportRequest request(int shopId) {
        return new FboPrintCommandService.FboExportRequest(
                shopId, List.of(new FboPrintCommandService.FboQuantityItem("SKU-1", 2)));
    }

    private static FboPrintPlan plan(FboProductSku product, int quantity) {
        List<FboPrintPage> pages = new ArrayList<>();
        for (int index = 1; index <= quantity; index++) {
            pages.add(FboPrintPage.barcodeWithKiz(product, "KIZ-" + index, index));
            pages.add(FboPrintPage.barcodeWithKiz(product, "KIZ-" + index, index));
        }
        return new FboPrintPlan(pages, List.of(new Kiz(1, "KIZ-1", "reservation")));
    }

    private static FboProductSku product(String sku) {
        return new FboProductSku(
                101,
                "ART-1",
                "Subject",
                "Brand",
                "Product",
                "Blue",
                "M",
                "44",
                sku,
                "https://untrusted.example/image.png",
                true);
    }

    private static void assertInvalid(StageCall call) {
        JDeskException error = assertThrows(JDeskException.class, call::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }

    private static JDeskException asyncError(StageCall call) {
        CompletionException wrapper = assertThrows(
                CompletionException.class, () -> call.run().toCompletableFuture().join());
        return (JDeskException) wrapper.getCause();
    }

    private static final class FinalizerSpy implements FboPrintCommandService.InventoryFinalizer {
        private final AtomicInteger consumeCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();

        @Override
        public void consume(int shopId, List<Kiz> kizs) {
            assertEquals(7, shopId);
            assertEquals(1, kizs.size());
            consumeCalls.incrementAndGet();
        }

        @Override
        public void release(int shopId, List<Kiz> kizs) {
            assertEquals(7, shopId);
            assertEquals(1, kizs.size());
            releaseCalls.incrementAndGet();
        }
    }

    private static final class InventoryFinalizerForUnmarkedProduct
            implements FboPrintCommandService.InventoryFinalizer {
        @Override
        public void consume(int shopId, List<Kiz> kizs) {
            assertEquals(7, shopId);
            assertTrue(kizs.isEmpty());
        }

        @Override
        public void release(int shopId, List<Kiz> kizs) {
            throw new AssertionError("An unmarked product must not reserve KIZ.");
        }
    }

    @FunctionalInterface
    private interface Picker {
        Optional<Path> pick(String suggestedName);
    }

    @FunctionalInterface
    private interface Resolver {
        List<FboProductSku> resolve(List<String> skus);
    }

    @FunctionalInterface
    private interface StageCall {
        java.util.concurrent.CompletionStage<?> run();
    }
}
