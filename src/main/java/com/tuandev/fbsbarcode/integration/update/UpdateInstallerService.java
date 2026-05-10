package com.tuandev.fbsbarcode.integration.update;

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
        return os.contains("win") && url != null && url.toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    public Path downloadInstaller(UpdateInfo info) throws IOException {
        String url = info.getBestDownloadUrl();
        if (url == null || url.isBlank()) {
            throw new IOException("Không tìm thấy link tải bản cập nhật");
        }

        String extension = guessExtension(url);
        Path tempDir = Files.createTempDirectory("fbsbarcode-update-");
        Path installerFile = tempDir.resolve("FBSBarcode-update" + extension);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "FBSBarcode-Updater")
                .get()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Tải bản cập nhật thất bại: HTTP " + response.code());
            }
            try (InputStream input = response.body().byteStream();
                 OutputStream output = Files.newOutputStream(installerFile)) {
                input.transferTo(output);
            }
        }

        return installerFile;
    }

    public void launchInstallerAfterExit(Path installerFile) throws IOException {
        Path script = installerFile.getParent().resolve("run-update.cmd");
        String scriptContent = """
                @echo off
                timeout /t 2 /nobreak > nul
                start "" "%s"
                del "%%~f0"
                """.formatted(installerFile.toAbsolutePath());
        Files.writeString(script, scriptContent);
        new ProcessBuilder("cmd", "/c", "start", "", script.toAbsolutePath().toString()).start();
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".msi")) return ".msi";
        if (lower.endsWith(".zip")) return ".zip";
        return ".exe";
    }
}
