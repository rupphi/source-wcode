package com.tuandev.fbsbarcode.jdesk.diagnostics;

import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileDialog;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.RequiresCapability;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Exposes a bounded local health summary and explicit redacted support-bundle export. */
public final class DiagnosticsCommandService {
    private static final Set<String> OS_FAMILIES = Set.of("macos", "windows", "linux", "other");
    private static final Set<String> DATABASE_STATES = Set.of("healthy", "unavailable", "corrupt");
    private static final Set<String> ARCHITECTURES = Set.of("arm64", "x86_64", "other");
    private static final String SAFE_TEXT = "[A-Za-z0-9][A-Za-z0-9._ -]{0,63}";
    private static final int MAX_COUNT = 1_000_000;

    private final SnapshotSource source;
    private final SavePicker picker;
    private final BundleWriter writer;

    public DiagnosticsCommandService() {
        LocalDiagnosticsCollector collector = new LocalDiagnosticsCollector();
        this.source = collector::collect;
        this.picker = DiagnosticsCommandService::showSaveDialog;
        this.writer = new SupportBundleWriter()::write;
    }

    DiagnosticsCommandService(SnapshotSource source, SavePicker picker, BundleWriter writer) {
        this.source = Objects.requireNonNull(source, "source");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @DesktopCommand("diagnostics.summary")
    @RequiresCapability("diagnostics:read")
    public CompletionStage<DiagnosticsSummary> summary(SummaryRequest request, InvocationContext context) {
        requireRequest(request);
        return SafeCommandExecutor.execute(() -> {
            try {
                return validate(source.collect(platform(context)));
            } catch (RuntimeException exception) {
                throw failure("summary_unavailable");
            }
        });
    }

    @DesktopCommand("diagnostics.export")
    @RequiresCapability("diagnostics:export")
    public CompletionStage<ExportResponse> export(ExportRequest request, InvocationContext context) {
        requireRequest(request);
        return SafeCommandExecutor.execute(() -> {
            Optional<Path> selected;
            try {
                selected = await(picker.pick(context));
            } catch (CancellationException cancelled) {
                return new ExportResponse(false, true);
            } catch (RuntimeException exception) {
                throw failure("dialog_unavailable");
            }
            if (selected == null) {
                throw failure("dialog_unavailable");
            }
            if (selected.isEmpty()) {
                return new ExportResponse(false, true);
            }
            try {
                DiagnosticsSummary snapshot = validate(source.collect(platform(context)));
                writer.write(selected.get(), snapshot);
            } catch (Exception exception) {
                throw failure("export_failed");
            }
            return new ExportResponse(true, false);
        });
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw SafeCommandExecutor.invalidRequest("A diagnostics request is required.");
        }
    }

    private static DiagnosticsSummary validate(DiagnosticsSummary value) {
        Objects.requireNonNull(value, "diagnostics summary");
        if (!safe(value.appVersion()) || !safe(value.jdeskVersion()) || !safe(value.javaVersion())
                || !OS_FAMILIES.contains(value.osFamily()) || !safe(value.osVersion())
                || !ARCHITECTURES.contains(value.architecture())
                || !DATABASE_STATES.contains(value.databaseStatus())
                || !count(value.shopCount()) || !count(value.supplyCount())
                || !count(value.printJobCount()) || !count(value.pendingCredentialCount())
                || !count(value.pendingTombstoneCount())) {
            throw new IllegalStateException("Diagnostics summary is invalid");
        }
        return value;
    }

    private static boolean safe(String value) {
        return value != null && value.matches(SAFE_TEXT);
    }

    private static boolean count(int value) {
        return value >= 0 && value <= MAX_COUNT;
    }

    private static PlatformInfo platform(InvocationContext context) {
        return context == null || context.platform() == null
                ? new PlatformInfo(
                        System.getProperty("os.name", "Other"),
                        System.getProperty("os.version", "unknown"),
                        System.getProperty("os.arch", "unknown"))
                : context.platform();
    }

    private static CompletionStage<Optional<Path>> showSaveDialog(InvocationContext context) {
        if (context == null || context.application() == null) {
            throw failure("dialog_unavailable");
        }
        return context.application().showSaveDialog(FileDialog.SaveDialog.withName(
                        "Export WCode support bundle",
                        "WCode-support.zip",
                        new FileDialog.Filter("ZIP", List.of("zip"))))
                .thenApply(result -> result.path().map(Path::of));
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Native dialog failed");
        }
    }

    private static JDeskException failure(String kind) {
        return new JDeskException(
                ErrorCode.INTERNAL_ERROR,
                "The diagnostics operation could not be completed safely.",
                new DiagnosticsError(kind),
                null);
    }

    @FunctionalInterface
    interface SnapshotSource {
        DiagnosticsSummary collect(PlatformInfo platform);
    }

    @FunctionalInterface
    interface SavePicker {
        CompletionStage<Optional<Path>> pick(InvocationContext context);
    }

    @FunctionalInterface
    interface BundleWriter {
        void write(Path target, DiagnosticsSummary snapshot) throws Exception;
    }

    public record SummaryRequest() {
    }

    public record ExportRequest() {
    }

    public record ExportResponse(boolean exported, boolean cancelled) {
    }

    public record DiagnosticsError(String kind) {
    }

    public record DiagnosticsSummary(
            String appVersion,
            String jdeskVersion,
            String javaVersion,
            String osFamily,
            String osVersion,
            String architecture,
            String databaseStatus,
            int shopCount,
            int supplyCount,
            int printJobCount,
            int pendingCredentialCount,
            int pendingTombstoneCount) {
    }
}
