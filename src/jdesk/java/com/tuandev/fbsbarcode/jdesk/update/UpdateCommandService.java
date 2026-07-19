package com.tuandev.fbsbarcode.jdesk.update;

import com.tuandev.fbsbarcode.shared.ConfigService;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates an explicit, fail-closed check/download/install update state machine. */
public final class UpdateCommandService {
    private static final String VERSION_PATTERN = "[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}";
    private static final String JOB_PATTERN = "[0-9a-f-]{36}";

    private final String currentVersion;
    private final ManifestSource manifests;
    private final DownloadRunner downloader;
    private final InstallRunner installer;
    private final StopRequester stopRequester;
    private final BooleanSupplier installSupported;
    private final Supplier<String> skippedVersion;
    private final Consumer<String> skipVersion;

    private SignedUpdateManifestVerifier.VerifiedManifest available;
    private DownloadJob job;

    public static UpdateCommandService createProduction(
            String currentVersion,
            Path temporaryBase,
            String encodedPublicKey,
            String expectedPublisher,
            WindowsUpdateInstaller.SnapshotCreator snapshots,
            StopRequester stopRequester) {
        WindowsUpdateInstaller windows =
                new WindowsUpdateInstaller(temporaryBase, expectedPublisher, snapshots);
        return new UpdateCommandService(
                currentVersion,
                () -> new SignedUpdateManifestSource(encodedPublicKey).load(),
                new VerifiedUpdateDownloader(temporaryBase),
                windows,
                stopRequester,
                windows::isSupported,
                ConfigService::getSkippedVersion,
                ConfigService::setSkippedVersion);
    }

    UpdateCommandService(
            String currentVersion,
            ManifestSource manifests,
            DownloadRunner downloader,
            InstallRunner installer,
            StopRequester stopRequester,
            BooleanSupplier installSupported) {
        this(
                currentVersion,
                manifests,
                downloader,
                installer,
                stopRequester,
                installSupported,
                () -> "",
                ignored -> {});
    }

    UpdateCommandService(
            String currentVersion,
            ManifestSource manifests,
            DownloadRunner downloader,
            InstallRunner installer,
            StopRequester stopRequester,
            BooleanSupplier installSupported,
            Supplier<String> skippedVersion,
            Consumer<String> skipVersion) {
        if (currentVersion == null || !currentVersion.matches(VERSION_PATTERN)) {
            throw new IllegalArgumentException("Current version is invalid");
        }
        this.currentVersion = currentVersion;
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.stopRequester = Objects.requireNonNull(stopRequester, "stopRequester");
        this.installSupported = Objects.requireNonNull(installSupported, "installSupported");
        this.skippedVersion = Objects.requireNonNull(skippedVersion, "skippedVersion");
        this.skipVersion = Objects.requireNonNull(skipVersion, "skipVersion");
    }

    @DesktopCommand("updates.check")
    @RequiresCapability("updates:read")
    public CompletionStage<CheckResponse> check(CheckRequest request, InvocationContext context) {
        requireRequest(request);
        try {
            SignedUpdateManifestVerifier.VerifiedManifest candidate =
                    Objects.requireNonNull(manifests.load(), "manifest");
            validateSafeMetadata(candidate);
            synchronized (this) {
                if (compareVersions(candidate.version(), currentVersion) <= 0) {
                    available = null;
                    return CompletableFuture.completedFuture(new CheckResponse(
                            "current", currentVersion, "", "", List.of(), false, false));
                }
                if (!candidate.mandatory() && candidate.version().equals(readSkippedVersion())) {
                    available = null;
                    return CompletableFuture.completedFuture(new CheckResponse(
                            "skipped",
                            currentVersion,
                            candidate.version(),
                            candidate.publishedAt(),
                            candidate.notes(),
                            false,
                            false));
                }
                available = candidate;
                return CompletableFuture.completedFuture(new CheckResponse(
                        "available",
                        currentVersion,
                        available.version(),
                        available.publishedAt(),
                        available.notes(),
                        available.mandatory(),
                        installSupported.getAsBoolean()));
            }
        } catch (Exception exception) {
            synchronized (this) {
                available = null;
            }
            return CompletableFuture.completedFuture(new CheckResponse(
                    "unavailable", currentVersion, "", "", List.of(), false, false));
        }
    }

    @DesktopCommand("updates.skip")
    @RequiresCapability("updates:write")
    public synchronized CompletionStage<SkipResponse> skip(SkipRequest request, InvocationContext context) {
        String version = requireVersion(request == null ? null : request.version());
        if (available == null || !available.version().equals(version)) throw invalid("not_available");
        if (available.mandatory()) throw invalid("mandatory");
        try {
            skipVersion.accept(version);
            available = null;
            return CompletableFuture.completedFuture(new SkipResponse(true, version));
        } catch (RuntimeException exception) {
            throw failure("skip_failed");
        }
    }

    @DesktopCommand("updates.startDownload")
    @RequiresCapability("updates:download")
    public synchronized CompletionStage<StartDownloadResponse> startDownload(
            StartDownloadRequest request, InvocationContext context) {
        String version = requireVersion(request == null ? null : request.version());
        if (available == null || !available.version().equals(version)) {
            throw invalid("not_available");
        }
        if (job != null && job.version.equals(version) && !job.allowsReplacement()) {
            return CompletableFuture.completedFuture(
                    new StartDownloadResponse(false, job.jobId, job.version));
        }
        if (job != null && job.isRunning()) {
            throw invalid("download_busy");
        }

        DownloadJob created = new DownloadJob(UUID.randomUUID().toString(), available);
        job = created;
        created.worker = Thread.ofVirtual()
                .name("wcode-update-download")
                .start(() -> runDownload(created));
        return CompletableFuture.completedFuture(
                new StartDownloadResponse(true, created.jobId, created.version));
    }

    @DesktopCommand("updates.downloadStatus")
    @RequiresCapability("updates:read")
    public CompletionStage<DownloadStatusResponse> downloadStatus(
            DownloadStatusRequest request, InvocationContext context) {
        return CompletableFuture.completedFuture(requireJob(request == null ? null : request.jobId()).snapshot());
    }

    @DesktopCommand("updates.cancelDownload")
    @RequiresCapability("updates:download")
    public CompletionStage<CancelDownloadResponse> cancelDownload(
            CancelDownloadRequest request, InvocationContext context) {
        DownloadJob selected = requireJob(request == null ? null : request.jobId());
        return CompletableFuture.completedFuture(
                new CancelDownloadResponse(selected.requestCancel(), selected.jobId));
    }

    @DesktopCommand("updates.install")
    @RequiresCapability("updates:install")
    public CompletionStage<InstallResponse> install(InstallRequest request, InvocationContext context) {
        DownloadJob selected = requireJob(request == null ? null : request.jobId());
        if (!installSupported.getAsBoolean()) {
            throw invalid("unsupported");
        }
        Path downloaded = selected.beginInstall();
        try {
            installer.install(downloaded, selected.manifest.asset());
            stopRequester.requestStop();
            selected.installerStarted();
            return CompletableFuture.completedFuture(new InstallResponse(true, selected.version));
        } catch (Exception exception) {
            selected.installFailed();
            throw failure("install_failed");
        }
    }

    private void runDownload(DownloadJob target) {
        try {
            Path downloaded = downloader.download(
                    target.manifest.asset(), target::progress, target::cancelRequested);
            if (target.cancelRequested()) {
                target.cancel();
            } else if (downloaded == null) {
                target.fail("download_failed");
            } else {
                target.complete(downloaded);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            target.cancel();
        } catch (Exception exception) {
            if (target.cancelRequested()) {
                target.cancel();
            } else {
                target.fail("download_failed");
            }
        }
    }

    private synchronized DownloadJob requireJob(String jobId) {
        if (jobId == null || !jobId.matches(JOB_PATTERN) || job == null || !job.jobId.equals(jobId)) {
            throw invalid("job_not_found");
        }
        return job;
    }

    private static void requireRequest(Object request) {
        if (request == null) throw invalid("invalid_request");
    }

    private static String requireVersion(String version) {
        if (version == null || !version.matches(VERSION_PATTERN)) throw invalid("invalid_version");
        return version;
    }

    private static void validateSafeMetadata(SignedUpdateManifestVerifier.VerifiedManifest manifest) {
        requireVersion(manifest.version());
        try {
            if (!Instant.parse(manifest.publishedAt()).toString().equals(manifest.publishedAt())) {
                throw new IllegalArgumentException("Invalid release timestamp");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid release timestamp");
        }
        if (manifest.notes() == null || manifest.notes().size() > 20 || manifest.asset() == null) {
            throw new IllegalArgumentException("Invalid release metadata");
        }
        for (String note : manifest.notes()) {
            if (note == null || note.isBlank() || note.length() > 500
                    || note.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid release metadata");
            }
        }
    }

    private String readSkippedVersion() {
        try {
            String skipped = skippedVersion.get();
            return skipped != null && skipped.matches(VERSION_PATTERN) ? skipped : "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < 3; index++) {
            int comparison = Integer.compare(Integer.parseInt(leftParts[index]), Integer.parseInt(rightParts[index]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static JDeskException invalid(String kind) {
        return new JDeskException(
                ErrorCode.INVALID_REQUEST,
                "The update request is no longer valid.",
                new UpdateError(kind, false),
                null);
    }

    private static JDeskException failure(String kind) {
        return new JDeskException(
                ErrorCode.INTERNAL_ERROR,
                "The update operation could not be completed safely.",
                new UpdateError(kind, true),
                null);
    }

    @FunctionalInterface
    interface ManifestSource {
        SignedUpdateManifestVerifier.VerifiedManifest load() throws Exception;
    }

    @FunctionalInterface
    interface DownloadRunner {
        Path download(
                SignedUpdateManifestVerifier.VerifiedAsset asset,
                ProgressSink progress,
                BooleanSupplier cancelled) throws Exception;
    }

    @FunctionalInterface
    interface ProgressSink {
        void update(long downloadedBytes);
    }

    @FunctionalInterface
    interface InstallRunner {
        void install(Path path, SignedUpdateManifestVerifier.VerifiedAsset asset) throws Exception;
    }

    @FunctionalInterface
    public interface StopRequester {
        void requestStop();
    }

    public record CheckRequest() {}

    public record CheckResponse(
            String state,
            String currentVersion,
            String version,
            String publishedAt,
            List<String> notes,
            boolean mandatory,
            boolean installSupported) {}

    public record StartDownloadRequest(String version) {}

    public record SkipRequest(String version) {}

    public record SkipResponse(boolean skipped, String version) {}

    public record StartDownloadResponse(boolean accepted, String jobId, String version) {}

    public record DownloadStatusRequest(String jobId) {}

    public record DownloadStatusResponse(
            String jobId,
            String version,
            String state,
            long downloadedBytes,
            long totalBytes,
            String completedAt,
            String errorKind,
            boolean retryable) {}

    public record CancelDownloadRequest(String jobId) {}

    public record CancelDownloadResponse(boolean cancelRequested, String jobId) {}

    public record InstallRequest(String jobId) {}

    public record InstallResponse(boolean accepted, String version) {}

    public record UpdateError(String kind, boolean retryable) {}

    private static final class DownloadJob {
        private final String jobId;
        private final String version;
        private final SignedUpdateManifestVerifier.VerifiedManifest manifest;
        private String state = "running";
        private long downloadedBytes;
        private String completedAt = "";
        private String errorKind = "";
        private boolean cancelRequested;
        private Path downloaded;
        private volatile Thread worker;

        private DownloadJob(String jobId, SignedUpdateManifestVerifier.VerifiedManifest manifest) {
            this.jobId = jobId;
            this.version = manifest.version();
            this.manifest = manifest;
        }

        private synchronized boolean isRunning() {
            return "running".equals(state);
        }

        private synchronized boolean allowsReplacement() {
            return "cancelled".equals(state)
                    || ("failed".equals(state) && "download_failed".equals(errorKind));
        }

        private synchronized void progress(long value) {
            if (!"running".equals(state)
                    || value < downloadedBytes
                    || value < 0
                    || value > manifest.asset().size()) {
                if (value < 0 || value > manifest.asset().size()) cancelRequested = true;
                return;
            }
            downloadedBytes = value;
        }

        private synchronized void complete(Path path) {
            if (cancelRequested) {
                cancel();
                return;
            }
            if (downloadedBytes != manifest.asset().size()) {
                fail("download_failed");
                return;
            }
            downloaded = path;
            state = "completed";
            completedAt = Instant.now().toString();
        }

        private synchronized void fail(String kind) {
            errorKind = kind;
            state = "failed";
            completedAt = Instant.now().toString();
        }

        private synchronized boolean requestCancel() {
            if (!"running".equals(state)) return false;
            cancelRequested = true;
            Thread current = worker;
            if (current != null) current.interrupt();
            return true;
        }

        private synchronized boolean cancelRequested() {
            return cancelRequested;
        }

        private synchronized void cancel() {
            state = "cancelled";
            errorKind = "cancelled";
            completedAt = Instant.now().toString();
            downloaded = null;
        }

        private synchronized Path beginInstall() {
            if (!"completed".equals(state) || downloaded == null) throw invalid("not_downloaded");
            state = "installing";
            return downloaded;
        }

        private synchronized void installerStarted() {
            state = "installer_started";
            completedAt = Instant.now().toString();
        }

        private synchronized void installFailed() {
            state = "failed";
            errorKind = "install_failed";
            completedAt = Instant.now().toString();
        }

        private synchronized DownloadStatusResponse snapshot() {
            return new DownloadStatusResponse(
                    jobId,
                    version,
                    state,
                    downloadedBytes,
                    manifest.asset().size(),
                    completedAt,
                    errorKind,
                    "download_failed".equals(errorKind) || "cancelled".equals(errorKind));
        }
    }
}
