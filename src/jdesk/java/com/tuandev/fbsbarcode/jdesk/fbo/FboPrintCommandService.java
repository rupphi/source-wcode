package com.tuandev.fbsbarcode.jdesk.fbo;

import com.tuandev.fbsbarcode.features.fbo.FboBarcodePdfExporter;
import com.tuandev.fbsbarcode.features.fbo.FboBarcodePrintItem;
import com.tuandev.fbsbarcode.features.fbo.FboKizPrintPlanner;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPlan;
import com.tuandev.fbsbarcode.features.fbo.FboProductRepository;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileDialog;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public final class FboPrintCommandService {
    private static final int MAX_ITEMS = 500;
    private static final int MAX_QUANTITY = 10_000;
    private static final int MAX_TOTAL_PAIRS = 10_000;
    private static final int MAX_SKU_LENGTH = 128;
    private static final int MAX_FILE_NAME_LENGTH = 180;
    private static final String SESSION_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private final Supplier<List<Shop>> shops;
    private final ProductResolver products;
    private final PrintPlanner planner;
    private final FilePicker picker;
    private final PdfExporter exporter;
    private final InventoryFinalizer inventory;
    private final FileOpener opener;
    private final Clock clock;
    private final Duration sessionTtl;
    private final int maxSessions;
    private final Map<String, ExportSession> sessions = new LinkedHashMap<>();

    public FboPrintCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        FboProductRepository productRepository = new FboProductRepository();
        FboKizPrintPlanner printPlanner = new FboKizPrintPlanner();
        FboBarcodePdfExporter pdfExporter = new FboBarcodePdfExporter();
        this.shops = shopRepository::findAll;
        this.products = productRepository::findBySkus;
        this.planner = printPlanner::plan;
        this.picker = FboPrintCommandService::showSaveDialog;
        this.exporter = (plan, output, beforePublish) ->
                pdfExporter.exportPlan(plan, output.toFile(), beforePublish);
        this.inventory = new InventoryFinalizer() {
            @Override
            public void consume(int shopId, List<Kiz> kizs) {
                KizService.deleteKizs(shopId, kizs);
            }

            @Override
            public void release(int shopId, List<Kiz> kizs) {
                KizService.releaseKizs(shopId, kizs);
            }
        };
        this.opener = FboPrintCommandService::openWithDesktop;
        this.clock = Clock.systemUTC();
        this.sessionTtl = Duration.ofMinutes(30);
        this.maxSessions = 8;
    }

    FboPrintCommandService(
            Supplier<List<Shop>> shops,
            ProductResolver products,
            PrintPlanner planner,
            FilePicker picker,
            PdfExporter exporter,
            InventoryFinalizer inventory,
            FileOpener opener,
            Clock clock,
            Duration sessionTtl,
            int maxSessions) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.products = Objects.requireNonNull(products, "products");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.isZero() || sessionTtl.isNegative() || maxSessions <= 0) {
            throw new IllegalArgumentException("FBO print session limits are invalid.");
        }
        this.maxSessions = maxSessions;
    }

    @DesktopCommand("fbo.export")
    @RequiresCapability("fbo:print")
    public CompletionStage<FboExportResponse> export(
            FboExportRequest request, InvocationContext context) {
        ValidatedExport validated = validateExport(request);
        try {
            requireShop(validated.shopId());
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    safeFailure("FBO products could not be prepared.", "preflight_failed", false));
        }
        PreparedItems prepared;
        try {
            prepared = resolve(validated);
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    safeFailure("FBO products could not be prepared.", "preflight_failed", false));
        }

        Optional<Path> selected;
        try {
            CompletionStage<Optional<Path>> dialog = picker.pick(context, "WCODE-FBO.pdf");
            if (dialog == null) {
                throw new IllegalStateException("Native save dialog returned no result");
            }
            selected = await(dialog);
            if (selected == null) {
                throw new IllegalStateException("Native save dialog returned no result");
            }
        } catch (CancellationException error) {
            return CompletableFuture.failedFuture(error);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    safeFailure("Native save dialog could not be opened.", "dialog_unavailable", true));
        }
        if (selected.isEmpty()) {
            return CompletableFuture.completedFuture(cancelledResponse());
        }

        try {
            Path output = outputTarget(selected.get());
            FboPrintPlan plan;
            FboPrintPlan planned;
            try {
                planned = planner.plan(validated.shopId(), prepared.items());
            } catch (RuntimeException error) {
                throw safeFailure("FBO print preflight failed.", "preflight_failed", false);
            }
            try {
                plan = requirePlan(planned, prepared.pairCount());
            } catch (RuntimeException error) {
                releaseQuietly(validated.shopId(), recoverableKizs(planned));
                throw safeFailure("FBO print preflight failed.", "preflight_failed", false);
            }
            boolean[] consumed = {false};
            try {
                exporter.export(plan, output, () -> {
                    inventory.consume(validated.shopId(), plan.usedKizs());
                    consumed[0] = true;
                });
            } catch (Exception error) {
                if (!consumed[0]) {
                    releaseQuietly(validated.shopId(), plan.usedKizs());
                }
                throw safeFailure("FBO PDF could not be exported.", "export_failed", true);
            }
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                throw safeFailure("FBO PDF was not published.", "export_failed", true);
            }
            String exportId = UUID.randomUUID().toString();
            putSession(exportId, new ExportSession(
                    validated.shopId(), output, clock.instant().plus(sessionTtl)));
            return CompletableFuture.completedFuture(new FboExportResponse(
                    false,
                    exportId,
                    sanitizeFileName(output),
                    prepared.pairCount(),
                    plan.pages().size(),
                    plan.usedKizs().size()));
        } catch (JDeskException error) {
            return CompletableFuture.failedFuture(error);
        } catch (IOException error) {
            return CompletableFuture.failedFuture(
                    safeFailure("FBO output target is unavailable.", "output_unavailable", true));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    safeFailure("FBO print failed safely.", "export_failed", true));
        }
    }

    @DesktopCommand("fbo.openExport")
    @RequiresCapability("fbo:print")
    public CompletionStage<OpenFboExportResponse> open(
            OpenFboExportRequest request, InvocationContext context) {
        ValidatedOpen validated = validateOpen(request);
        ExportSession session;
        try {
            requireShop(validated.shopId());
            session = requireSession(validated.shopId(), validated.exportId());
            if (!Files.isRegularFile(session.file(), LinkOption.NOFOLLOW_LINKS)) {
                throw SafeCommandExecutor.invalidRequest("The FBO export is no longer available.");
            }
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    safeFailure("The FBO PDF could not be opened.", "open_failed", true));
        }
        try {
            opener.open(session.file());
            return CompletableFuture.completedFuture(
                    new OpenFboExportResponse(true, sanitizeFileName(session.file())));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(
                    safeFailure("The FBO PDF could not be opened.", "open_failed", true));
        }
    }

    private PreparedItems resolve(ValidatedExport validated) {
        List<String> skus = validated.items().stream().map(FboQuantityItem::sku).toList();
        List<FboProductSku> resolved = List.copyOf(Objects.requireNonNull(
                products.resolve(validated.shopId(), skus), "resolved FBO products"));
        Map<String, FboProductSku> bySku = new LinkedHashMap<>();
        for (FboProductSku product : resolved) {
            if (product == null || product.sku() == null) {
                throw SafeCommandExecutor.invalidRequest("The selected FBO products are no longer available.");
            }
            String key = product.sku().toLowerCase(Locale.ROOT);
            if (bySku.putIfAbsent(key, product) != null) {
                throw new IllegalStateException("FBO product resolver returned duplicates");
            }
        }
        List<FboBarcodePrintItem> items = new ArrayList<>();
        int pairs = 0;
        for (FboQuantityItem item : validated.items()) {
            FboProductSku product = bySku.get(item.sku().toLowerCase(Locale.ROOT));
            if (product == null || !product.sku().equals(item.sku())) {
                throw SafeCommandExecutor.invalidRequest("The selected FBO products are no longer available.");
            }
            items.add(new FboBarcodePrintItem(product, item.quantity()));
            pairs = Math.addExact(pairs, item.quantity());
        }
        if (bySku.size() != items.size()) {
            throw SafeCommandExecutor.invalidRequest("The selected FBO products are no longer available.");
        }
        return new PreparedItems(List.copyOf(items), pairs);
    }

    private static FboPrintPlan requirePlan(FboPrintPlan plan, int pairCount) {
        Objects.requireNonNull(plan, "FBO print plan");
        List<com.tuandev.fbsbarcode.features.fbo.FboPrintPage> pages =
                List.copyOf(Objects.requireNonNull(plan.pages(), "FBO print pages"));
        List<Kiz> kizs = List.copyOf(Objects.requireNonNull(plan.usedKizs(), "FBO reserved KIZ"));
        if (pages.size() != Math.multiplyExact(pairCount, 2)
                || pages.stream().anyMatch(page -> page == null || page.product() == null)
                || kizs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("FBO print plan is invalid");
        }
        return new FboPrintPlan(pages, kizs);
    }

    private static List<Kiz> recoverableKizs(FboPrintPlan plan) {
        if (plan == null || plan.usedKizs() == null) {
            return List.of();
        }
        return plan.usedKizs().stream().filter(Objects::nonNull).toList();
    }

    private Shop requireShop(int shopId) {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops")).stream()
                .filter(shop -> shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> SafeCommandExecutor.invalidRequest(
                        "The selected shop is not available."));
    }

    private static ValidatedExport validateExport(FboExportRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.items() == null || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw SafeCommandExecutor.invalidRequest("The FBO print selection is invalid.");
        }
        Map<String, FboQuantityItem> items = new LinkedHashMap<>();
        int total = 0;
        for (FboQuantityItem item : request.items()) {
            if (item == null || !validSku(item.sku()) || item.quantity() <= 0 || item.quantity() > MAX_QUANTITY) {
                throw SafeCommandExecutor.invalidRequest("The FBO print selection is invalid.");
            }
            String sku = item.sku().strip();
            if (items.putIfAbsent(sku.toLowerCase(Locale.ROOT), new FboQuantityItem(sku, item.quantity())) != null) {
                throw SafeCommandExecutor.invalidRequest("The FBO print selection contains duplicate SKUs.");
            }
            total = Math.addExact(total, item.quantity());
            if (total > MAX_TOTAL_PAIRS) {
                throw SafeCommandExecutor.invalidRequest("The FBO print selection is too large.");
            }
        }
        return new ValidatedExport(request.shopId(), List.copyOf(items.values()), total);
    }

    private static ValidatedOpen validateOpen(OpenFboExportRequest request) {
        if (request == null
                || request.shopId() <= 0
                || request.exportId() == null
                || !request.exportId().matches(SESSION_PATTERN)) {
            throw SafeCommandExecutor.invalidRequest("The FBO export session is invalid.");
        }
        return new ValidatedOpen(request.shopId(), request.exportId());
    }

    private static boolean validSku(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_SKU_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static CompletionStage<Optional<Path>> showSaveDialog(
            InvocationContext context, String suggestedName) {
        if (context == null) {
            throw safeFailure("Native save dialog is unavailable.", "dialog_unavailable", true);
        }
        return context.application().showSaveDialog(FileDialog.SaveDialog.withName(
                        "Save WCode FBO labels",
                        suggestedName,
                        new FileDialog.Filter("PDF", List.of("pdf"))))
                .thenApply(result -> result.path().map(Path::of));
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Native FBO print was cancelled.");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof CancellationException cancelled) {
                throw cancelled;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new java.util.concurrent.CompletionException(cause);
        }
    }

    private static Path outputTarget(Path selected) throws IOException {
        if (selected == null) {
            throw new IOException("No FBO output file selected");
        }
        Path output = selected.toAbsolutePath().normalize();
        String fileName = output.getFileName() == null ? "" : output.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            output = output.resolveSibling(fileName + ".pdf");
            fileName = output.getFileName().toString();
        }
        if (fileName.isBlank()
                || fileName.length() > MAX_FILE_NAME_LENGTH
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("FBO output file name is invalid");
        }
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IOException("FBO output directory is unavailable");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output))) {
            throw new IOException("FBO output target is invalid");
        }
        return output;
    }

    private static void openWithDesktop(Path file) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Desktop open is not supported");
        }
        Desktop.getDesktop().open(file.toFile());
    }

    private void releaseQuietly(int shopId, List<Kiz> kizs) {
        try {
            inventory.release(shopId, kizs);
        } catch (RuntimeException ignored) {
            // The original safe export error remains authoritative; reservations are recoverable.
        }
    }

    private synchronized void putSession(String exportId, ExportSession session) {
        pruneSessions();
        while (sessions.size() >= maxSessions) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        sessions.put(exportId, session);
    }

    private synchronized ExportSession requireSession(int shopId, String exportId) {
        pruneSessions();
        ExportSession session = sessions.get(exportId);
        if (session == null || session.shopId() != shopId) {
            throw SafeCommandExecutor.invalidRequest("The FBO export session is not available.");
        }
        return session;
    }

    private void pruneSessions() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static FboExportResponse cancelledResponse() {
        return new FboExportResponse(true, "", "", 0, 0, 0);
    }

    private static String sanitizeFileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "";
        }
        String value = fileName.toString().replaceAll("\\p{Cntrl}+", " ").strip();
        return value.length() > MAX_FILE_NAME_LENGTH ? value.substring(0, MAX_FILE_NAME_LENGTH) : value;
    }

    private static JDeskException safeFailure(String message, String kind, boolean retryable) {
        return new JDeskException(
                ErrorCode.INTERNAL_ERROR,
                message,
                new FboPrintError(kind, retryable),
                null);
    }

    @FunctionalInterface
    interface ProductResolver {
        List<FboProductSku> resolve(int shopId, List<String> skus);
    }

    @FunctionalInterface
    interface PrintPlanner {
        FboPrintPlan plan(int shopId, List<FboBarcodePrintItem> items);
    }

    @FunctionalInterface
    interface FilePicker {
        CompletionStage<Optional<Path>> pick(InvocationContext context, String suggestedName);
    }

    @FunctionalInterface
    interface PdfExporter {
        void export(FboPrintPlan plan, Path output, Runnable beforePublish) throws Exception;
    }

    interface InventoryFinalizer {
        void consume(int shopId, List<Kiz> kizs);

        void release(int shopId, List<Kiz> kizs);
    }

    @FunctionalInterface
    interface FileOpener {
        void open(Path file) throws Exception;
    }

    private record ValidatedExport(int shopId, List<FboQuantityItem> items, int pairCount) {
    }

    private record ValidatedOpen(int shopId, String exportId) {
    }

    private record PreparedItems(List<FboBarcodePrintItem> items, int pairCount) {
    }

    private record ExportSession(int shopId, Path file, Instant expiresAt) {
    }

    public record FboQuantityItem(String sku, int quantity) {
    }

    public record FboExportRequest(int shopId, List<FboQuantityItem> items) {
        public FboExportRequest {
            items = items == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    public record FboExportResponse(
            boolean cancelled,
            String exportId,
            String fileName,
            int pairCount,
            int pageCount,
            int kizCount) {
    }

    public record OpenFboExportRequest(int shopId, String exportId) {
    }

    public record OpenFboExportResponse(boolean opened, String fileName) {
    }

    public record FboPrintError(String kind, boolean retryable) {
    }
}
