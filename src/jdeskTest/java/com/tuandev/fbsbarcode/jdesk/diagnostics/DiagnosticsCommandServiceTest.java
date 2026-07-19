package com.tuandev.fbsbarcode.jdesk.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsCommandServiceTest {
    private static final String SECRET = "diagnostic-secret-must-not-cross-bridge";

    @TempDir Path temp;

    @Test
    void summaryReturnsOnlyBoundedAllowlistedFields() {
        DiagnosticsCommandService service = service(summary(), Optional.empty(), new AtomicInteger());

        DiagnosticsCommandService.DiagnosticsSummary response = service.summary(
                        new DiagnosticsCommandService.SummaryRequest(), null)
                .toCompletableFuture().join();

        assertEquals(summary(), response);
        String json = new JacksonJsonCodec().encode(response);
        assertFalse(json.contains(SECRET));
        assertFalse(json.contains("/Users/"));
        assertFalse(json.toLowerCase().contains("apikey"));
        assertFalse(json.toLowerCase().contains("license"));
        assertFalse(json.toLowerCase().contains("fingerprint"));
    }

    @Test
    void cancelledExportWritesNothingAndReturnsSecretFreeReceipt() {
        AtomicInteger writes = new AtomicInteger();
        DiagnosticsCommandService service = service(summary(), Optional.empty(), writes);

        DiagnosticsCommandService.ExportResponse response = service.export(
                        new DiagnosticsCommandService.ExportRequest(), null)
                .toCompletableFuture().join();

        assertFalse(response.exported());
        assertEquals(true, response.cancelled());
        assertEquals(0, writes.get());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void exportCollectsFreshSnapshotAfterSelectionAndReturnsNoPathOrFileName() {
        AtomicInteger writes = new AtomicInteger();
        Path selected = temp.resolve("private-name-" + SECRET + ".zip");
        DiagnosticsCommandService service = service(summary(), Optional.of(selected), writes);

        DiagnosticsCommandService.ExportResponse response = service.export(
                        new DiagnosticsCommandService.ExportRequest(), null)
                .toCompletableFuture().join();

        assertEquals(new DiagnosticsCommandService.ExportResponse(true, false), response);
        assertEquals(1, writes.get());
        assertFalse(response.toString().contains(selected.toString()));
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void malformedCollectorAndWriterFailureBecomeSafeAllowlistedErrors() {
        DiagnosticsCommandService malformed = service(
                new DiagnosticsCommandService.DiagnosticsSummary(
                        SECRET + "?", "0.1.3", "25", "macos", "26", "arm64",
                        "healthy", 0, 0, 0, 0, 0),
                Optional.empty(),
                new AtomicInteger());
        JDeskException internal = assertThrows(JDeskException.class, () -> malformed.summary(
                new DiagnosticsCommandService.SummaryRequest(), null));
        assertEquals(ErrorCode.INTERNAL_ERROR, internal.code());
        assertFalse(internal.publicMessage().contains(SECRET));
        assertEquals(new DiagnosticsCommandService.DiagnosticsError("summary_unavailable"), internal.details());

        DiagnosticsCommandService failing = new DiagnosticsCommandService(
                ignored -> summary(),
                ignored -> CompletableFuture.completedFuture(Optional.of(temp.resolve("support.zip"))),
                (path, snapshot) -> { throw new IllegalStateException("write failed " + SECRET); });
        JDeskException export = assertThrows(JDeskException.class, () -> failing.export(
                new DiagnosticsCommandService.ExportRequest(), null).toCompletableFuture().join());
        assertEquals(ErrorCode.INTERNAL_ERROR, export.code());
        assertFalse(export.publicMessage().contains(SECRET));
        assertEquals(new DiagnosticsCommandService.DiagnosticsError("export_failed"), export.details());
        assertNull(export.getCause());
    }

    private static DiagnosticsCommandService service(
            DiagnosticsCommandService.DiagnosticsSummary summary,
            Optional<Path> selected,
            AtomicInteger writes) {
        return new DiagnosticsCommandService(
                ignored -> summary,
                ignored -> CompletableFuture.completedFuture(selected),
                (path, snapshot) -> writes.incrementAndGet());
    }

    private static DiagnosticsCommandService.DiagnosticsSummary summary() {
        return new DiagnosticsCommandService.DiagnosticsSummary(
                "1.1.7", "0.1.3", "25", "macos", "26.5.1", "arm64",
                "healthy", 8, 863, 34, 0, 0);
    }
}
