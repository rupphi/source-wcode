package com.tuandev.fbsbarcode.jdesk.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCommandServiceTest {
    private static final String SECRET_URL =
            "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi";
    private static final String SECRET_HASH = "a".repeat(64);

    @TempDir Path temp;

    @Test
    void checksExplicitlyAndReturnsOnlySafeReleaseMetadata() {
        UpdateCommandService service = service("1.2.2", (asset, progress, cancelled) -> temp.resolve("unused"));

        UpdateCommandService.CheckResponse response = service.check(
                        new UpdateCommandService.CheckRequest(), null)
                .toCompletableFuture().join();

        assertEquals("available", response.state());
        assertEquals("1.2.2", response.currentVersion());
        assertEquals("1.2.3", response.version());
        assertEquals(List.of("Signed installer"), response.notes());
        assertTrue(response.mandatory());
        assertTrue(response.installSupported());
        String json = new JacksonJsonCodec().encode(response);
        assertFalse(json.contains(SECRET_URL));
        assertFalse(json.contains(SECRET_HASH));
        assertFalse(json.toLowerCase().contains("path"));
    }

    @Test
    void reportsCurrentAndRefusesDownloadWhenNoNewerVerifiedReleaseExists() {
        AtomicInteger downloads = new AtomicInteger();
        UpdateCommandService service = service("1.2.3", (asset, progress, cancelled) -> {
            downloads.incrementAndGet();
            return temp.resolve("unused");
        });

        UpdateCommandService.CheckResponse response = service.check(
                        new UpdateCommandService.CheckRequest(), null)
                .toCompletableFuture().join();
        JDeskException error = assertThrows(JDeskException.class, () -> service.startDownload(
                new UpdateCommandService.StartDownloadRequest("1.2.3"), null));

        assertEquals("current", response.state());
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertEquals("not_available", ((UpdateCommandService.UpdateError) error.details()).kind());
        assertEquals(0, downloads.get());
    }

    @Test
    void downloadsOnlyAfterExplicitStartAndExposesBoundedProgressWithoutPrivateFields() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Path privatePath = temp.resolve("private-installer-name.msi");
        UpdateCommandService service = service("1.2.2", (asset, progress, cancelled) -> {
            assertEquals(SECRET_URL, asset.url().toString());
            assertEquals(SECRET_HASH, asset.sha256());
            progress.update(4_000_000);
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            progress.update(asset.size());
            return privatePath;
        });

        service.check(new UpdateCommandService.CheckRequest(), null).toCompletableFuture().join();
        UpdateCommandService.StartDownloadResponse start = service.startDownload(
                        new UpdateCommandService.StartDownloadRequest("1.2.3"), null)
                .toCompletableFuture().join();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        UpdateCommandService.DownloadStatusResponse running = service.downloadStatus(
                        new UpdateCommandService.DownloadStatusRequest(start.jobId()), null)
                .toCompletableFuture().join();
        release.countDown();
        UpdateCommandService.DownloadStatusResponse completed = awaitTerminal(service, start.jobId());

        assertEquals("running", running.state());
        assertEquals(4_000_000L, running.downloadedBytes());
        assertEquals(12_345_678L, running.totalBytes());
        assertEquals("completed", completed.state());
        assertEquals(12_345_678L, completed.downloadedBytes());
        String json = new JacksonJsonCodec().encode(completed);
        assertFalse(json.contains(privatePath.toString()));
        assertFalse(json.contains(SECRET_URL));
        assertFalse(json.contains(SECRET_HASH));
    }

    @Test
    void repeatedStartReusesTheCompletedVerifiedJobInsteadOfAccumulatingInstallers() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        UpdateCommandService service = service("1.2.2", (asset, progress, cancelled) -> {
            downloads.incrementAndGet();
            progress.update(asset.size());
            return temp.resolve("WCode.msi");
        });
        service.check(new UpdateCommandService.CheckRequest(), null).toCompletableFuture().join();
        UpdateCommandService.StartDownloadResponse first = service.startDownload(
                        new UpdateCommandService.StartDownloadRequest("1.2.3"), null)
                .toCompletableFuture().join();
        awaitTerminal(service, first.jobId());

        UpdateCommandService.StartDownloadResponse repeated = service.startDownload(
                        new UpdateCommandService.StartDownloadRequest("1.2.3"), null)
                .toCompletableFuture().join();

        assertFalse(repeated.accepted());
        assertEquals(first.jobId(), repeated.jobId());
        assertEquals(1, downloads.get());
    }

    @Test
    void cancelInterruptsTheWorkerAndEndsInAnAllowlistedState() throws Exception {
        AtomicBoolean sawCancellation = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        UpdateCommandService service = service("1.2.2", (asset, progress, cancelled) -> {
            started.countDown();
            try {
                while (!cancelled.getAsBoolean()) {
                    Thread.sleep(5);
                }
                sawCancellation.set(true);
                throw new InterruptedException("private cancellation detail");
            } catch (InterruptedException exception) {
                sawCancellation.set(cancelled.getAsBoolean());
                throw exception;
            }
        });
        service.check(new UpdateCommandService.CheckRequest(), null).toCompletableFuture().join();
        UpdateCommandService.StartDownloadResponse start = service.startDownload(
                        new UpdateCommandService.StartDownloadRequest("1.2.3"), null)
                .toCompletableFuture().join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        UpdateCommandService.CancelDownloadResponse cancel = service.cancelDownload(
                        new UpdateCommandService.CancelDownloadRequest(start.jobId()), null)
                .toCompletableFuture().join();
        UpdateCommandService.DownloadStatusResponse status = awaitTerminal(service, start.jobId());

        assertTrue(cancel.cancelRequested());
        assertTrue(sawCancellation.get());
        assertEquals("cancelled", status.state());
        assertEquals("cancelled", status.errorKind());
    }

    @Test
    void installRequiresACompletedJobAndASecondExplicitAction() throws Exception {
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        Path installer = temp.resolve("WCode.msi");
        UpdateCommandService service = new UpdateCommandService(
                "1.2.2",
                UpdateCommandServiceTest::manifest,
                (asset, progress, cancelled) -> {
                    progress.update(asset.size());
                    return installer;
                },
                (path, asset) -> {
                    assertEquals(installer, path);
                    assertEquals(SECRET_HASH, asset.sha256());
                    installs.incrementAndGet();
                },
                stops::incrementAndGet,
                () -> true);
        service.check(new UpdateCommandService.CheckRequest(), null).toCompletableFuture().join();
        UpdateCommandService.StartDownloadResponse start = service.startDownload(
                        new UpdateCommandService.StartDownloadRequest("1.2.3"), null)
                .toCompletableFuture().join();
        awaitTerminal(service, start.jobId());

        UpdateCommandService.InstallResponse response = service.install(
                        new UpdateCommandService.InstallRequest(start.jobId()), null)
                .toCompletableFuture().join();

        assertTrue(response.accepted());
        assertEquals("1.2.3", response.version());
        assertEquals(1, installs.get());
        assertEquals(1, stops.get());
        assertFalse(response.toString().contains(installer.toString()));
    }

    @Test
    void skipsOnlyTheCurrentNonMandatoryReleaseThroughTheSharedStore() {
        java.util.concurrent.atomic.AtomicReference<String> skipped = new java.util.concurrent.atomic.AtomicReference<>("");
        UpdateCommandService service = new UpdateCommandService(
                "1.2.2",
                () -> new SignedUpdateManifestVerifier.VerifiedManifest(
                        "1.2.3", "2026-07-19T00:00:00Z", List.of("Optional update"), false,
                        manifest().asset()),
                (asset, progress, cancelled) -> temp.resolve("unused"),
                (path, asset) -> {},
                () -> {},
                () -> true,
                skipped::get,
                skipped::set);
        service.check(new UpdateCommandService.CheckRequest(), null).toCompletableFuture().join();

        UpdateCommandService.SkipResponse response = service.skip(
                        new UpdateCommandService.SkipRequest("1.2.3"), null)
                .toCompletableFuture().join();
        UpdateCommandService.CheckResponse checkedAgain = service.check(
                        new UpdateCommandService.CheckRequest(), null)
                .toCompletableFuture().join();

        assertTrue(response.skipped());
        assertEquals("1.2.3", skipped.get());
        assertEquals("skipped", checkedAgain.state());
        assertEquals("1.2.3", checkedAgain.version());
    }

    @Test
    void mandatoryReleaseCannotBeSkipped() {
        UpdateCommandService service = service("1.2.2", (asset, progress, cancelled) -> temp.resolve("unused"));
        service.check(new UpdateCommandService.CheckRequest(), null).toCompletableFuture().join();

        JDeskException error = assertThrows(JDeskException.class, () -> service.skip(
                new UpdateCommandService.SkipRequest("1.2.3"), null));

        assertEquals("mandatory", ((UpdateCommandService.UpdateError) error.details()).kind());
    }

    private UpdateCommandService service(String currentVersion, UpdateCommandService.DownloadRunner downloader) {
        return new UpdateCommandService(
                currentVersion,
                UpdateCommandServiceTest::manifest,
                downloader,
                (path, asset) -> {},
                () -> {},
                () -> true);
    }

    private static SignedUpdateManifestVerifier.VerifiedManifest manifest() {
        return new SignedUpdateManifestVerifier.VerifiedManifest(
                "1.2.3",
                "2026-07-19T00:00:00Z",
                List.of("Signed installer"),
                true,
                new SignedUpdateManifestVerifier.VerifiedAsset(
                        "WCode.msi", 12_345_678L, SECRET_HASH, URI.create(SECRET_URL)));
    }

    private static UpdateCommandService.DownloadStatusResponse awaitTerminal(
            UpdateCommandService service, String jobId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            UpdateCommandService.DownloadStatusResponse status = service.downloadStatus(
                            new UpdateCommandService.DownloadStatusRequest(jobId), null)
                    .toCompletableFuture().join();
            if (!"running".equals(status.state())) return status;
            Thread.sleep(5);
        }
        throw new AssertionError("Update download did not finish within five seconds");
    }
}
