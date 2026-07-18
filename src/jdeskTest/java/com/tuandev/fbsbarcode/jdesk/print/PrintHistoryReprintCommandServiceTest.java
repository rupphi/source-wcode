package com.tuandev.fbsbarcode.jdesk.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintHistoryReprintCommandServiceTest {
    private static final String SECRET = "history-reprint-secret-that-must-not-cross-the-bridge";

    @TempDir
    Path tempDir;

    @Test
    void cancellingTheNativeSaveDialogDoesNotReprint() {
        AtomicInteger reprints = new AtomicInteger();
        PrintHistoryReprintCommandService service = service(
                job("success"),
                (context, name) -> CompletableFuture.completedFuture(Optional.empty()),
                (job, labels, details) -> {
                    reprints.incrementAndGet();
                    return 5;
                },
                file -> {});

        PrintHistoryReprintCommandService.ReprintHistoryResponse response = service
                .reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                        7, "9007199254741001"), null)
                .toCompletableFuture()
                .join();

        assertTrue(response.cancelled());
        assertEquals("", response.exportId());
        assertEquals(0, reprints.get());
    }

    @Test
    void reprintsToSafeCompanionFilesAndOpensOnlyThroughAnOpaqueSession() throws Exception {
        Path selected = tempDir.resolve("labels.pdf");
        AtomicReference<Path> opened = new AtomicReference<>();
        PrintHistoryReprintCommandService service = service(
                job("success"),
                (context, name) -> CompletableFuture.completedFuture(Optional.of(selected)),
                (job, labels, details) -> {
                    Files.writeString(labels, "labels");
                    Files.writeString(details, "details");
                    return 5;
                },
                opened::set);

        PrintHistoryReprintCommandService.ReprintHistoryResponse response = service
                .reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                        7, "9007199254741001"), null)
                .toCompletableFuture()
                .join();

        assertFalse(response.cancelled());
        assertTrue(response.exportId().matches("[0-9a-f-]{36}"));
        assertEquals("labels.pdf", response.labelsFileName());
        assertEquals("NHAT_HANG-labels.pdf", response.detailsFileName());
        assertEquals("9007199254741001", response.jobId());
        assertEquals(5, response.itemCount());
        assertFalse(response.toString().contains(tempDir.toString()));
        assertFalse(response.toString().contains(SECRET));

        PrintHistoryReprintCommandService.OpenHistoryReprintResponse openedResponse = service
                .open(new PrintHistoryReprintCommandService.OpenHistoryReprintRequest(
                        7, response.exportId(), "details"), null)
                .toCompletableFuture()
                .join();

        assertTrue(openedResponse.opened());
        assertEquals("NHAT_HANG-labels.pdf", openedResponse.fileName());
        assertEquals(tempDir.resolve("NHAT_HANG-labels.pdf"), opened.get());
    }

    @Test
    void boundsSuggestedAndCompanionNamesWithoutHidingTheRealSavedName() throws Exception {
        Path selected = tempDir.resolve("a".repeat(176) + ".pdf");
        AtomicReference<String> suggestedName = new AtomicReference<>();
        PrintHistoryJobSummary longSupply = new PrintHistoryJobSummary(
                9_007_199_254_741_001L,
                7,
                "Main",
                "S".repeat(400),
                "Supply",
                "2026-07-18T10:00:00Z",
                5,
                1,
                "Default",
                "{}",
                "success",
                SECRET);
        PrintHistoryReprintCommandService service = service(
                longSupply,
                (context, name) -> {
                    suggestedName.set(name);
                    return CompletableFuture.completedFuture(Optional.of(selected));
                },
                (job, labels, details) -> {
                    Files.writeString(labels, "labels");
                    Files.writeString(details, "details");
                    return 5;
                },
                file -> {});

        PrintHistoryReprintCommandService.ReprintHistoryResponse response = service
                .reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                        7, "9007199254741001"), null)
                .toCompletableFuture()
                .join();

        assertTrue(suggestedName.get().length() <= 180);
        assertTrue(response.detailsFileName().length() <= 180);
        assertTrue(Files.exists(tempDir.resolve(response.detailsFileName())));
    }

    @Test
    void rejectsInvalidUnownedMissingOrFailedJobsBeforeOpeningTheDialog() {
        AtomicInteger pickerCalls = new AtomicInteger();
        PrintHistoryReprintCommandService failed = service(
                job("failed"),
                (context, name) -> {
                    pickerCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                },
                (job, labels, details) -> 5,
                file -> {});
        PrintHistoryReprintCommandService missing = new PrintHistoryReprintCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                (shopId, jobId) -> null,
                (context, name) -> {
                    pickerCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                },
                (job, labels, details) -> 5,
                file -> {},
                fixedClock(),
                Duration.ofMinutes(30),
                4);

        assertInvalid(() -> failed.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                7, "9007199254741001"), null));
        assertInvalid(() -> missing.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                7, "9007199254741001"), null));
        assertInvalid(() -> missing.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                9, "9007199254741001"), null));
        assertInvalid(() -> missing.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                7, "not-a-number"), null));
        assertEquals(0, pickerCalls.get());
    }

    @Test
    void sanitizesShopRepositoryFailuresBeforeOpeningTheDialog() {
        PrintHistoryReprintCommandService service = new PrintHistoryReprintCommandService(
                () -> {
                    throw new IllegalStateException("sqlite " + SECRET);
                },
                (shopId, jobId) -> job("success"),
                (context, name) -> CompletableFuture.completedFuture(Optional.empty()),
                (job, labels, details) -> 5,
                file -> {},
                fixedClock(),
                Duration.ofMinutes(30),
                4);

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                        7, "9007199254741001"), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
        assertFalse(error.toString().contains(SECRET));
    }

    @Test
    void rejectsForgedExpiredAndCrossShopOpenSessions() throws Exception {
        Path selected = tempDir.resolve("labels.pdf");
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-18T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        PrintHistoryReprintCommandService service = new PrintHistoryReprintCommandService(
                () -> List.of(new Shop(7, "Main", SECRET), new Shop(8, "Other", "other")),
                (shopId, jobId) -> job("success"),
                (context, name) -> CompletableFuture.completedFuture(Optional.of(selected)),
                (job, labels, details) -> {
                    Files.writeString(labels, "labels");
                    Files.writeString(details, "details");
                    return 5;
                },
                file -> {},
                clock,
                Duration.ofMinutes(30),
                4);
        String exportId = service.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                        7, "9007199254741001"), null)
                .toCompletableFuture()
                .join()
                .exportId();

        assertInvalid(() -> service.open(new PrintHistoryReprintCommandService.OpenHistoryReprintRequest(
                8, exportId, "labels"), null));
        assertInvalid(() -> service.open(new PrintHistoryReprintCommandService.OpenHistoryReprintRequest(
                7, "00000000-0000-4000-8000-000000000000", "labels"), null));
        assertInvalid(() -> service.open(new PrintHistoryReprintCommandService.OpenHistoryReprintRequest(
                7, exportId, "unknown"), null));
        now.set(Instant.parse("2026-07-18T10:31:00Z"));
        assertInvalid(() -> service.open(new PrintHistoryReprintCommandService.OpenHistoryReprintRequest(
                7, exportId, "labels"), null));
    }

    @Test
    void interruptionOwnsTheWholeNativeReprintTransaction() throws Exception {
        CompletableFuture<Optional<Path>> pickerResult = new CompletableFuture<>();
        CountDownLatch pickerStarted = new CountDownLatch(1);
        AtomicInteger reprints = new AtomicInteger();
        PrintHistoryReprintCommandService service = service(
                job("success"),
                (context, name) -> {
                    pickerStarted.countDown();
                    return pickerResult;
                },
                (job, labels, details) -> {
                    reprints.incrementAndGet();
                    return 5;
                },
                file -> {});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread invocation = Thread.ofVirtual().start(() -> {
            try {
                service.reprint(new PrintHistoryReprintCommandService.ReprintHistoryRequest(
                        7, "9007199254741001"), null);
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        assertTrue(pickerStarted.await(2, TimeUnit.SECONDS));
        invocation.interrupt();
        invocation.join(2_000);

        assertFalse(invocation.isAlive());
        assertTrue(failure.get() instanceof java.util.concurrent.CancellationException);
        assertEquals(0, reprints.get());
    }

    private PrintHistoryReprintCommandService service(
            PrintHistoryJobSummary job,
            PrintHistoryReprintCommandService.FilePicker picker,
            PrintHistoryReprintCommandService.HistoryReprinter reprinter,
            PrintHistoryReprintCommandService.FileOpener opener) {
        return new PrintHistoryReprintCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                (shopId, jobId) -> job,
                picker,
                reprinter,
                opener,
                fixedClock(),
                Duration.ofMinutes(30),
                4);
    }

    private PrintHistoryJobSummary job(String status) {
        return new PrintHistoryJobSummary(
                9_007_199_254_741_001L,
                7,
                "Main",
                "WB-GI-1",
                "Supply",
                "2026-07-18T10:00:00Z",
                5,
                1,
                "Default",
                "{}",
                status,
                SECRET);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        JDeskException error = assertThrows(JDeskException.class, executable);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }
}
