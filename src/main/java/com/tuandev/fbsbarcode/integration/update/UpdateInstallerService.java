package com.tuandev.fbsbarcode.integration.update;

import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.AppDataRecoveryService;
import com.tuandev.fbsbarcode.shared.I18nService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class UpdateInstallerService {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public boolean supportsInAppInstall(UpdateInfo info) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String url = info == null ? null : info.getBestDownloadUrl();
        return os.contains("win")
                && url != null
                && (url.toLowerCase(Locale.ROOT).endsWith(".exe") || url.toLowerCase(Locale.ROOT).endsWith(".msi"));
    }

    public Path downloadInstaller(UpdateInfo info, ProgressListener progressListener) throws IOException {
        String url = info.getBestDownloadUrl();
        if (url == null || url.isBlank()) {
            throw new IOException(I18nService.getInstance().tr("update.error.missing_url"));
        }

        String extension = guessExtension(url);
        Path tempBase = AppPaths.nativeTempDir();
        Files.createDirectories(tempBase);
        Path tempDir = Files.createTempDirectory(tempBase, "wcode-update-");
        Path installerFile = tempDir.resolve("WCode-update" + extension);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "WCode-Updater")
                .get()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException(I18nService.getInstance().tr("update.error.download_failed") + " HTTP " + response.code());
            }
            long totalBytes = response.body().contentLength();
            if (progressListener != null) {
                progressListener.onProgress(0L, totalBytes);
            }
            try (InputStream input = response.body().byteStream();
                 OutputStream output = Files.newOutputStream(installerFile)) {
                byte[] buffer = new byte[8192];
                long downloadedBytes = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloadedBytes += read;
                    if (progressListener != null) {
                        progressListener.onProgress(downloadedBytes, totalBytes);
                    }
                }
            }
        }

        return installerFile;
    }

    public void launchInstallerAfterExit(Path installerFile) throws IOException {
        AppDataRecoveryService.prepareBackupForUpdate();
        String escapedInstaller = installerFile.toAbsolutePath().toString().replace("'", "''");
        String command = "Start-Sleep -Seconds 2; Start-Process -FilePath '" + escapedInstaller + "'";
        new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-Command", command
        ).start();
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".msi")) return ".msi";
        if (lower.endsWith(".zip")) return ".zip";
        return ".exe";
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }
}
