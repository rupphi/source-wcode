package com.tuandev.fbsbarcode.jdesk.print;

import com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator;
import com.tuandev.fbsbarcode.features.print.OrderExportWorkflow;
import com.tuandev.fbsbarcode.features.print.PrintJobOptions;
import com.tuandev.fbsbarcode.features.print.PrintPageOrder;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.features.supply.OrderSortingService;
import com.tuandev.fbsbarcode.features.supply.SupplyLoadWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.jdesk.supply.SupplyDetailCommandService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.ui.supply.OrderSortOptions;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class PrintExportCommandService {
    private static final int MAX_SUPPLY_ID_LENGTH = 128;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_ORDERS = 5_000;
    private static final int MAX_BARCODE_COPIES = 100;
    private static final int MAX_FILE_NAME_LENGTH = 180;
    private static final String SESSION_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual()
            .name("wcode-print-export")
            .start(command);

    private final Supplier<List<Shop>> shops;
    private final PrintSourceReader sources;
    private final KizVerifier kizVerifier;
    private final FilePicker picker;
    private final PdfExporter exporter;
    private final FileOpener opener;
    private final Clock clock;
    private final Duration sessionTtl;
    private final int maxSessions;
    private final Executor executor;
    private final Map<String, ExportSession> sessions = new LinkedHashMap<>();

    public PrintExportCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        WbSupplyRepository supplyRepository = new WbSupplyRepository();
        SupplyLoadWorkflow supplyWorkflow = new SupplyLoadWorkflow();
        OrderSortingService sorting = new OrderSortingService();
        OrderExportWorkflow exportWorkflow = new OrderExportWorkflow();
        KizAttachmentCoordinator attachments = KizAttachmentCoordinator.getInstance();
        this.shops = shopRepository::findAll;
        this.sources = (shop, supplyId, query, sort) -> readSource(
                supplyRepository, supplyWorkflow, sorting, shop, supplyId, query, sort);
        this.kizVerifier = (shop, orders) -> exportWorkflow.verifyKizAvailability(orders, shop);
        this.picker = PrintExportCommandService::showSaveDialog;
        this.exporter = (shop, source, options, labels, details) -> {
            List<Order> orders = new ArrayList<>(source.orders());
            supplyWorkflow.enrichStickers(shop, orders);
            OrderExportWorkflow.ExportResult result = exportWorkflow.export(
                    new OrderExportWorkflow.ExportRequest(
                            shop,
                            source.supplyId(),
                            source.supplyName(),
                            orders,
                            options,
                            labels.toFile(),
                            details.toFile()));
            attachments.enqueue(shop, source.supplyId(), source.supplyName(), result.kizAttachments());
            return new PdfExportReceipt(result.printJobId(), result.kizAttachments().size());
        };
        this.opener = PrintExportCommandService::openWithDesktop;
        this.clock = Clock.systemUTC();
        this.sessionTtl = Duration.ofMinutes(30);
        this.maxSessions = 8;
        this.executor = VIRTUAL_EXECUTOR;
    }

    PrintExportCommandService(
            Supplier<List<Shop>> shops,
            PrintSourceReader sources,
            KizVerifier kizVerifier,
            FilePicker picker,
            PdfExporter exporter,
            FileOpener opener,
            Clock clock,
            Duration sessionTtl,
            int maxSessions,
            Executor executor) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.kizVerifier = Objects.requireNonNull(kizVerifier, "kizVerifier");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (sessionTtl.isZero() || sessionTtl.isNegative() || maxSessions <= 0) {
            throw new IllegalArgumentException("Print export session limits are invalid.");
        }
        this.maxSessions = maxSessions;
    }

    @DesktopCommand("printing.exportSupply")
    @RequiresCapability("printing:export")
    public CompletionStage<PrintExportResponse> exportSupply(
            ExportSupplyRequest request, InvocationContext context) {
        ValidatedExport validated = validateExport(request);
        Shop shop = requireShop(validated.shopId());
        return CompletableFuture.supplyAsync(
                        () -> prepare(shop, validated), executor)
                .thenCompose(prepared -> picker.pick(context, suggestedName(prepared.source().supplyId()))
                        .handle((selected, error) -> {
                            if (error != null) {
                                throw safeFailure("Native save dialog could not be opened.", "dialog_unavailable", true);
                            }
                            if (selected == null) {
                                throw safeFailure("Native save dialog returned an invalid result.", "dialog_unavailable", true);
                            }
                            return new SelectedExport(prepared, selected);
                        }))
                .thenCompose(selected -> {
                    if (selected.path().isEmpty()) {
                        return CompletableFuture.completedFuture(cancelledResponse());
                    }
                    return CompletableFuture.supplyAsync(
                            () -> exportSelected(selected.prepared(), selected.path().get()), executor);
                });
    }

    @DesktopCommand("printing.openExport")
    @RequiresCapability("printing:export")
    public CompletionStage<OpenExportResponse> openExport(
            OpenExportRequest request, InvocationContext context) {
        ValidatedOpen validated = validateOpen(request);
        requireShop(validated.shopId());
        ExportSession session = requireSession(validated.shopId(), validated.exportId());
        Path file = validated.fileKind().equals("details") ? session.details() : session.labels();
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw SafeCommandExecutor.invalidRequest("The exported PDF is no longer available.");
            }
            try {
                opener.open(file);
            } catch (Exception error) {
                throw safeFailure("The exported PDF could not be opened.", "open_failed", true);
            }
            return new OpenExportResponse(true, sanitizeFileName(file));
        }, executor);
    }

    private PreparedExport prepare(Shop shop, ValidatedExport validated) {
        try {
            PrintSource source = Objects.requireNonNull(
                    sources.read(shop, validated.supplyId(), validated.query(), validated.sort()),
                    "print source");
            List<Order> orders = List.copyOf(Objects.requireNonNull(source.orders(), "print orders"));
            if (!Objects.equals(source.supplyId(), validated.supplyId()) || orders.isEmpty()) {
                throw SafeCommandExecutor.invalidRequest("The selected supply has no printable orders.");
            }
            if (orders.size() > MAX_ORDERS) {
                throw SafeCommandExecutor.invalidRequest("Narrow the order search to 5000 items or fewer.");
            }
            if (orders.stream().anyMatch(order -> order == null || order.getId() == null || order.getId() <= 0)) {
                throw new IllegalStateException("Printable orders are invalid.");
            }
            kizVerifier.verify(shop, orders);
            return new PreparedExport(
                    shop,
                    new PrintSource(source.supplyId(), sanitize(source.supplyName(), 180), orders),
                    new PrintJobOptions(validated.pageOrder(), validated.barcodeCopies()));
        } catch (JDeskException error) {
            throw error;
        } catch (WbApiException error) {
            String kind = error.isContentPermissionError()
                    ? "token_invalid"
                    : error.isRateLimited() ? "rate_limited" : "upstream";
            throw safeFailure("Print preflight could not be completed.", kind,
                    error.isRateLimited() || error.getStatusCode() >= 500);
        } catch (IOException error) {
            throw safeFailure("Print preflight could not be completed.", "upstream", true);
        } catch (RuntimeException error) {
            throw safeFailure("Print preflight failed safely.", "preflight_failed", false);
        } catch (Exception error) {
            throw safeFailure("Print preflight could not be completed.", "preflight_failed", true);
        }
    }

    private PrintExportResponse exportSelected(PreparedExport prepared, Path selected) {
        try {
            OutputTargets targets = outputTargets(selected);
            PdfExportReceipt receipt = Objects.requireNonNull(exporter.export(
                    prepared.shop(),
                    prepared.source(),
                    prepared.options(),
                    targets.labels(),
                    targets.details()), "PDF export receipt");
            if (receipt.printJobId() <= 0 || receipt.kizAttachmentCount() < 0) {
                throw new IllegalStateException("PDF export receipt is invalid.");
            }
            String exportId = UUID.randomUUID().toString();
            putSession(exportId, new ExportSession(
                    prepared.shop().getId(), targets.labels(), targets.details(), clock.instant().plus(sessionTtl)));
            int itemCount = prepared.source().orders().size();
            int pageCount = Math.multiplyExact(itemCount, prepared.options().barcodeCopies() + 1);
            return new PrintExportResponse(
                    false,
                    exportId,
                    sanitizeFileName(targets.labels()),
                    sanitizeFileName(targets.details()),
                    Long.toString(receipt.printJobId()),
                    itemCount,
                    pageCount,
                    receipt.kizAttachmentCount());
        } catch (JDeskException error) {
            throw error;
        } catch (WbApiException error) {
            String kind = error.isContentPermissionError()
                    ? "token_invalid"
                    : error.isRateLimited() ? "rate_limited" : "upstream";
            throw safeFailure("PDF files could not be created.", kind,
                    error.isRateLimited() || error.getStatusCode() >= 500);
        } catch (Exception error) {
            throw safeFailure("PDF files could not be created.", "export_failed", true);
        }
    }

    private static PrintSource readSource(
            WbSupplyRepository supplyRepository,
            SupplyLoadWorkflow supplyWorkflow,
            OrderSortingService sorting,
            Shop shop,
            String supplyId,
            String query,
            SupplyDetailCommandService.OrderSortRequest sort) {
        WbSupplySummary summary = supplyRepository.findSupplySummary(shop.getId(), supplyId);
        if (summary == null) {
            throw SafeCommandExecutor.invalidRequest("The selected supply is not available.");
        }
        List<Order> matching = supplyWorkflow.loadLocal(shop, supplyId).stream()
                .filter(order -> matches(order, query))
                .toList();
        List<Order> ordered = sorting.sort(matching, new OrderSortOptions(
                sort.bySubject(), sort.byArticle(), sort.byColor(), sort.bySize()));
        return new PrintSource(supplyId, summary.getName(), List.copyOf(ordered));
    }

    private static boolean matches(Order order, String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(order.getId(), needle)
                || contains(order.getNmId(), needle)
                || contains(order.getName(), needle)
                || contains(order.getBrand(), needle)
                || contains(order.getSubjectName(), needle)
                || contains(order.getArticle(), needle)
                || contains(order.getColor(), needle)
                || contains(order.getSize(), needle)
                || contains(order.getRuSize(), needle)
                || contains(order.getBarcode(), needle);
    }

    private static boolean contains(Object value, String needle) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private Shop requireShop(int shopId) {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops")).stream()
                .filter(Objects::nonNull)
                .filter(shop -> shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> SafeCommandExecutor.invalidRequest("The selected shop is not available."));
    }

    private static ValidatedExport validateExport(ExportSupplyRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (!validText(request.supplyId(), MAX_SUPPLY_ID_LENGTH, false)) {
            throw SafeCommandExecutor.invalidRequest("The supply id is invalid.");
        }
        if (!validText(request.query(), MAX_QUERY_LENGTH, true)) {
            throw SafeCommandExecutor.invalidRequest("The order search query is invalid.");
        }
        if (request.sort() == null) {
            throw SafeCommandExecutor.invalidRequest("Order sorting options are required.");
        }
        PrintPageOrder pageOrder = parsePageOrder(request.pageOrder());
        if (request.barcodeCopies() < 1 || request.barcodeCopies() > MAX_BARCODE_COPIES) {
            throw SafeCommandExecutor.invalidRequest("Barcode copies must be between 1 and 100.");
        }
        return new ValidatedExport(
                request.shopId(),
                request.supplyId().strip(),
                request.query().strip(),
                request.sort(),
                pageOrder,
                request.barcodeCopies());
    }

    private static ValidatedOpen validateOpen(OpenExportRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.exportId() == null || !request.exportId().matches(SESSION_PATTERN)) {
            throw SafeCommandExecutor.invalidRequest("The print export session is invalid.");
        }
        if (!"labels".equals(request.fileKind()) && !"details".equals(request.fileKind())) {
            throw SafeCommandExecutor.invalidRequest("The exported PDF kind is invalid.");
        }
        return new ValidatedOpen(request.shopId(), request.exportId(), request.fileKind());
    }

    private static PrintPageOrder parsePageOrder(String value) {
        if ("barcode_then_sticker".equals(value)) {
            return PrintPageOrder.BARCODE_THEN_STICKER;
        }
        if ("sticker_then_barcode".equals(value)) {
            return PrintPageOrder.STICKER_THEN_BARCODE;
        }
        throw SafeCommandExecutor.invalidRequest("The print page order is invalid.");
    }

    private static boolean validText(String value, int maxLength, boolean allowBlank) {
        return value != null
                && value.length() <= maxLength
                && (allowBlank || !value.isBlank())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static CompletionStage<Optional<Path>> showSaveDialog(
            InvocationContext context, String suggestedName) {
        if (context == null) {
            throw safeFailure("Native save dialog is unavailable.", "dialog_unavailable", true);
        }
        return context.application().showSaveDialog(FileDialog.SaveDialog.withName(
                        "Save WCode labels",
                        suggestedName,
                        new FileDialog.Filter("PDF", List.of("pdf"))))
                .thenApply(result -> result.path().map(Path::of));
    }

    private static String suggestedName(String supplyId) {
        String safe = supplyId == null ? "supply" : supplyId.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isBlank()) {
            safe = "supply";
        }
        return "WCODE-" + safe + ".pdf";
    }

    private static OutputTargets outputTargets(Path selected) throws IOException {
        if (selected == null) {
            throw new IOException("No output file selected.");
        }
        Path labels = selected.toAbsolutePath().normalize();
        String fileName = labels.getFileName() == null ? "" : labels.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            labels = labels.resolveSibling(fileName + ".pdf");
            fileName = labels.getFileName().toString();
        }
        if (!validText(fileName, MAX_FILE_NAME_LENGTH, false)) {
            throw new IOException("Output file name is invalid.");
        }
        Path parent = labels.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IOException("Output directory is unavailable.");
        }
        if (Files.exists(labels, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(labels, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(labels))) {
            throw new IOException("Output target is not a regular file.");
        }
        Path details = uniqueCompanion(parent, fileName);
        return new OutputTargets(labels, details);
    }

    private static Path uniqueCompanion(Path parent, String labelsName) throws IOException {
        String baseName = labelsName.substring(0, labelsName.length() - 4);
        for (int suffix = 1; suffix <= 1_000; suffix++) {
            String candidateName = "NHAT_HANG-" + baseName + (suffix == 1 ? "" : "-" + suffix) + ".pdf";
            Path candidate = parent.resolve(candidateName);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("No safe companion PDF name is available.");
    }

    private static void openWithDesktop(Path file) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Desktop open is not supported.");
        }
        Desktop.getDesktop().open(file.toFile());
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
            throw SafeCommandExecutor.invalidRequest("The print export session is not available.");
        }
        return session;
    }

    private void pruneSessions() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static PrintExportResponse cancelledResponse() {
        return new PrintExportResponse(true, "", "", "", "", 0, 0, 0);
    }

    private static String sanitizeFileName(Path path) {
        Path name = path.getFileName();
        return sanitize(name == null ? "" : name.toString(), MAX_FILE_NAME_LENGTH);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxLength));
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length() && safe.length() < maxLength; index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                if (!previousWhitespace && safe.length() > 0) {
                    safe.append(' ');
                    previousWhitespace = true;
                }
            } else {
                safe.append(character);
                previousWhitespace = false;
            }
        }
        return safe.toString().strip();
    }

    private static JDeskException safeFailure(String message, String kind, boolean retryable) {
        return new JDeskException(
                ErrorCode.INTERNAL_ERROR,
                message,
                new PrintExportError(kind, retryable),
                null);
    }

    @FunctionalInterface
    interface PrintSourceReader {
        PrintSource read(
                Shop shop,
                String supplyId,
                String query,
                SupplyDetailCommandService.OrderSortRequest sort) throws Exception;
    }

    @FunctionalInterface
    interface KizVerifier {
        void verify(Shop shop, List<Order> orders) throws Exception;
    }

    @FunctionalInterface
    interface FilePicker {
        CompletionStage<Optional<Path>> pick(InvocationContext context, String suggestedName);
    }

    @FunctionalInterface
    interface PdfExporter {
        PdfExportReceipt export(
                Shop shop,
                PrintSource source,
                PrintJobOptions options,
                Path labels,
                Path details) throws Exception;
    }

    @FunctionalInterface
    interface FileOpener {
        void open(Path file) throws Exception;
    }

    private record ValidatedExport(
            int shopId,
            String supplyId,
            String query,
            SupplyDetailCommandService.OrderSortRequest sort,
            PrintPageOrder pageOrder,
            int barcodeCopies) {
    }

    private record ValidatedOpen(int shopId, String exportId, String fileKind) {
    }

    private record PreparedExport(Shop shop, PrintSource source, PrintJobOptions options) {
    }

    private record SelectedExport(PreparedExport prepared, Optional<Path> path) {
    }

    private record OutputTargets(Path labels, Path details) {
    }

    private record ExportSession(int shopId, Path labels, Path details, Instant expiresAt) {
    }

    public record PrintSource(String supplyId, String supplyName, List<Order> orders) {
    }

    public record PdfExportReceipt(long printJobId, int kizAttachmentCount) {
    }

    public record ExportSupplyRequest(
            int shopId,
            String supplyId,
            String query,
            SupplyDetailCommandService.OrderSortRequest sort,
            String pageOrder,
            int barcodeCopies) {
    }

    public record OpenExportRequest(int shopId, String exportId, String fileKind) {
    }

    public record PrintExportResponse(
            boolean cancelled,
            String exportId,
            String labelsFileName,
            String detailsFileName,
            String printJobId,
            int itemCount,
            int pageCount,
            int kizAttachmentCount) {
    }

    public record OpenExportResponse(boolean opened, String fileName) {
    }

    public record PrintExportError(String kind, boolean retryable) {
    }
}
