package com.tuandev.fbsbarcode.jdesk.print;

import com.tuandev.fbsbarcode.features.print.OrderExportWorkflow;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
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

public final class PrintHistoryReprintCommandService {
    private static final int MAX_ITEMS = 5_000;
    private static final int MAX_FILE_NAME_LENGTH = 180;
    private static final String REPRINT_PREFIX = "WCODE-REPRINT-";
    private static final String DETAILS_PREFIX = "NHAT_HANG-";
    private static final String JOB_ID_PATTERN = "[1-9][0-9]{0,18}";
    private static final String SESSION_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private final Supplier<List<Shop>> shops;
    private final HistoryJobReader jobs;
    private final FilePicker picker;
    private final HistoryReprinter reprinter;
    private final FileOpener opener;
    private final Clock clock;
    private final Duration sessionTtl;
    private final int maxSessions;
    private final Map<String, ReprintSession> sessions = new LinkedHashMap<>();

    public PrintHistoryReprintCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        PrintHistoryService historyService = new PrintHistoryService();
        this.shops = shopRepository::findAll;
        this.jobs = (shopId, jobId) -> historyService.getJobs(shopId).stream()
                .filter(job -> job.id() == jobId)
                .findFirst()
                .orElse(null);
        this.picker = PrintHistoryReprintCommandService::showSaveDialog;
        this.reprinter = (job, labels, details) -> {
            OrderExportWorkflow.ExportResult result = historyService.reprint(
                    job, labels.toFile(), details.toFile());
            return result.exportedOrders().size();
        };
        this.opener = PrintHistoryReprintCommandService::openWithDesktop;
        this.clock = Clock.systemUTC();
        this.sessionTtl = Duration.ofMinutes(30);
        this.maxSessions = 8;
    }

    PrintHistoryReprintCommandService(
            Supplier<List<Shop>> shops,
            HistoryJobReader jobs,
            FilePicker picker,
            HistoryReprinter reprinter,
            FileOpener opener,
            Clock clock,
            Duration sessionTtl,
            int maxSessions) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.reprinter = Objects.requireNonNull(reprinter, "reprinter");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.isZero() || sessionTtl.isNegative() || maxSessions <= 0) {
            throw new IllegalArgumentException("History reprint session limits are invalid.");
        }
        this.maxSessions = maxSessions;
    }

    @DesktopCommand("printing.reprintHistory")
    @RequiresCapability("printing:export")
    public CompletionStage<ReprintHistoryResponse> reprint(
            ReprintHistoryRequest request, InvocationContext context) {
        ValidatedReprint validated = validateReprint(request);
        requireShop(validated.shopId());
        PrintHistoryJobSummary job = requireJob(validated.shopId(), validated.jobId());
        Optional<Path> selected;
        try {
            CompletionStage<Optional<Path>> dialog = picker.pick(context, suggestedName(job));
            if (dialog == null) {
                throw safeFailure("Native save dialog returned an invalid result.", "dialog_unavailable", true);
            }
            selected = await(dialog);
            if (selected == null) {
                throw safeFailure("Native save dialog returned an invalid result.", "dialog_unavailable", true);
            }
        } catch (CancellationException error) {
            throw error;
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            throw safeFailure("Native save dialog could not be opened.", "dialog_unavailable", true);
        }
        if (selected.isEmpty()) {
            return CompletableFuture.completedFuture(cancelledResponse());
        }
        try {
            OutputTargets targets = outputTargets(selected.get());
            int itemCount = reprinter.reprint(job, targets.labels(), targets.details());
            if (itemCount <= 0 || itemCount > MAX_ITEMS || itemCount != job.itemCount()) {
                throw new IllegalStateException("History reprint item count is invalid.");
            }
            String exportId = UUID.randomUUID().toString();
            putSession(exportId, new ReprintSession(
                    validated.shopId(), targets.labels(), targets.details(), clock.instant().plus(sessionTtl)));
            return CompletableFuture.completedFuture(new ReprintHistoryResponse(
                    false,
                    exportId,
                    sanitizeFileName(targets.labels()),
                    sanitizeFileName(targets.details()),
                    Long.toString(job.id()),
                    itemCount));
        } catch (JDeskException error) {
            throw error;
        } catch (Exception error) {
            throw safeFailure("History PDF files could not be created.", "export_failed", true);
        }
    }

    @DesktopCommand("printing.openHistoryReprint")
    @RequiresCapability("printing:export")
    public CompletionStage<OpenHistoryReprintResponse> open(
            OpenHistoryReprintRequest request, InvocationContext context) {
        ValidatedOpen validated = validateOpen(request);
        requireShop(validated.shopId());
        ReprintSession session = requireSession(validated.shopId(), validated.exportId());
        Path file = validated.fileKind().equals("details") ? session.details() : session.labels();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw SafeCommandExecutor.invalidRequest("The reprinted PDF is no longer available.");
        }
        try {
            opener.open(file);
        } catch (Exception error) {
            throw safeFailure("The reprinted PDF could not be opened.", "open_failed", true);
        }
        return CompletableFuture.completedFuture(
                new OpenHistoryReprintResponse(true, sanitizeFileName(file)));
    }

    private Shop requireShop(int shopId) {
        List<Shop> available;
        try {
            available = List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            throw safeFailure("Shops could not be read.", "shops_unavailable", true);
        }
        return available.stream()
                .filter(Objects::nonNull)
                .filter(shop -> shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> SafeCommandExecutor.invalidRequest(
                        "The selected shop is not available."));
    }

    private PrintHistoryJobSummary requireJob(int shopId, long jobId) {
        PrintHistoryJobSummary job;
        try {
            job = jobs.read(shopId, jobId);
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            throw safeFailure("Print history could not be read.", "history_unavailable", true);
        }
        if (job == null
                || job.id() != jobId
                || job.shopId() != shopId
                || !job.canReprint()
                || job.itemCount() <= 0
                || job.itemCount() > MAX_ITEMS) {
            throw SafeCommandExecutor.invalidRequest("The selected print job cannot be reprinted.");
        }
        return job;
    }

    private static ValidatedReprint validateReprint(ReprintHistoryRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.jobId() == null || !request.jobId().matches(JOB_ID_PATTERN)) {
            throw SafeCommandExecutor.invalidRequest("The print job id is invalid.");
        }
        try {
            return new ValidatedReprint(request.shopId(), Long.parseLong(request.jobId()));
        } catch (NumberFormatException error) {
            throw SafeCommandExecutor.invalidRequest("The print job id is invalid.");
        }
    }

    private static ValidatedOpen validateOpen(OpenHistoryReprintRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.exportId() == null || !request.exportId().matches(SESSION_PATTERN)) {
            throw SafeCommandExecutor.invalidRequest("The history reprint session is invalid.");
        }
        if (!("labels".equals(request.fileKind()) || "details".equals(request.fileKind()))) {
            throw SafeCommandExecutor.invalidRequest("The reprinted PDF kind is invalid.");
        }
        return new ValidatedOpen(request.shopId(), request.exportId(), request.fileKind());
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Native history reprint was cancelled.");
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

    private static CompletionStage<Optional<Path>> showSaveDialog(
            InvocationContext context, String suggestedName) {
        if (context == null) {
            throw safeFailure("Native save dialog is unavailable.", "dialog_unavailable", true);
        }
        return context.application().showSaveDialog(FileDialog.SaveDialog.withName(
                        "Save WCode reprint",
                        suggestedName,
                        new FileDialog.Filter("PDF", List.of("pdf"))))
                .thenApply(result -> result.path().map(Path::of));
    }

    private static String suggestedName(PrintHistoryJobSummary job) {
        String source = job.supplyId() == null || job.supplyId().isBlank()
                ? Long.toString(job.id())
                : job.supplyId();
        String safe = source.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isBlank()) {
            safe = Long.toString(job.id());
        }
        int maxSourceLength = MAX_FILE_NAME_LENGTH - REPRINT_PREFIX.length() - ".pdf".length();
        return REPRINT_PREFIX + safe.substring(0, Math.min(safe.length(), maxSourceLength)) + ".pdf";
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
        if (!validFileName(fileName)) {
            throw new IOException("Output file name is invalid.");
        }
        Path parent = labels.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IOException("Output directory is unavailable.");
        }
        if (Files.exists(labels, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(labels, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(labels))) {
            throw new IOException("Output target is not a regular file.");
        }
        return new OutputTargets(labels, uniqueCompanion(parent, fileName));
    }

    private static Path uniqueCompanion(Path parent, String labelsName) throws IOException {
        String baseName = labelsName.substring(0, labelsName.length() - 4);
        for (int suffix = 1; suffix <= 1_000; suffix++) {
            String suffixText = suffix == 1 ? "" : "-" + suffix;
            int maxBaseLength = MAX_FILE_NAME_LENGTH
                    - DETAILS_PREFIX.length()
                    - suffixText.length()
                    - ".pdf".length();
            String boundedBase = baseName.substring(0, Math.min(baseName.length(), maxBaseLength));
            String candidateName = DETAILS_PREFIX + boundedBase + suffixText + ".pdf";
            Path candidate = parent.resolve(candidateName);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("No safe companion PDF name is available.");
    }

    private static boolean validFileName(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_FILE_NAME_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static void openWithDesktop(Path file) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Desktop open is not supported.");
        }
        Desktop.getDesktop().open(file.toFile());
    }

    private synchronized void putSession(String exportId, ReprintSession session) {
        pruneSessions();
        while (sessions.size() >= maxSessions) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        sessions.put(exportId, session);
    }

    private synchronized ReprintSession requireSession(int shopId, String exportId) {
        pruneSessions();
        ReprintSession session = sessions.get(exportId);
        if (session == null || session.shopId() != shopId) {
            throw SafeCommandExecutor.invalidRequest("The history reprint session is not available.");
        }
        return session;
    }

    private void pruneSessions() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static ReprintHistoryResponse cancelledResponse() {
        return new ReprintHistoryResponse(true, "", "", "", "", 0);
    }

    private static String sanitizeFileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return "";
        }
        String value = name.toString().replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return value.length() > MAX_FILE_NAME_LENGTH
                ? value.substring(0, MAX_FILE_NAME_LENGTH)
                : value;
    }

    private static JDeskException safeFailure(String message, String kind, boolean retryable) {
        return new JDeskException(
                ErrorCode.INTERNAL_ERROR,
                message,
                new HistoryReprintError(kind, retryable),
                null);
    }

    @FunctionalInterface
    interface HistoryJobReader {
        PrintHistoryJobSummary read(int shopId, long jobId);
    }

    @FunctionalInterface
    interface FilePicker {
        CompletionStage<Optional<Path>> pick(InvocationContext context, String suggestedName);
    }

    @FunctionalInterface
    interface HistoryReprinter {
        int reprint(PrintHistoryJobSummary job, Path labels, Path details) throws Exception;
    }

    @FunctionalInterface
    interface FileOpener {
        void open(Path file) throws Exception;
    }

    private record ValidatedReprint(int shopId, long jobId) {
    }

    private record ValidatedOpen(int shopId, String exportId, String fileKind) {
    }

    private record OutputTargets(Path labels, Path details) {
    }

    private record ReprintSession(int shopId, Path labels, Path details, Instant expiresAt) {
    }

    public record ReprintHistoryRequest(int shopId, String jobId) {
    }

    public record ReprintHistoryResponse(
            boolean cancelled,
            String exportId,
            String labelsFileName,
            String detailsFileName,
            String jobId,
            int itemCount) {
    }

    public record OpenHistoryReprintRequest(int shopId, String exportId, String fileKind) {
    }

    public record OpenHistoryReprintResponse(boolean opened, String fileName) {
    }

    public record HistoryReprintError(String errorKind, boolean retryable) {
    }
}
