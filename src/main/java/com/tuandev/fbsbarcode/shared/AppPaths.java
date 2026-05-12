package com.tuandev.fbsbarcode.shared;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AppPaths {
    private static final String APP_DIR_NAME = "WCode";

    private AppPaths() {
    }

    public static Path appDataDir() {
        Path base = windowsLocalAppData()
                .orElseGet(() -> Paths.get(System.getProperty("user.home", ".")));
        return base.resolve(APP_DIR_NAME);
    }

    public static Path logsDir() {
        return appDataDir().resolve("logs");
    }

    public static Path nativeTempDir() {
        List<Path> candidates = List.of(
                windowsProgramData().map(path -> path.resolve(APP_DIR_NAME).resolve("tmp")).orElse(null),
                windowsTemp().map(path -> path.resolve(APP_DIR_NAME)).orElse(null),
                appDataDir().resolve("tmp")
        );

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            } catch (IOException ignored) {
            }
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }

    public static File preferredFileChooserDirectory() {
        List<Path> candidates = List.of(
                pathsFromBase(Paths.get(System.getProperty("user.home", ".")), "Downloads"),
                pathsFromBase(appDataDir(), "exports"),
                appDataDir(),
                Paths.get(System.getProperty("user.dir", "."))
        );

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (Files.isDirectory(candidate)) {
                    return candidate.toFile();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Path pathsFromBase(Path base, String child) {
        return base == null ? null : base.resolve(child);
    }

    private static Optional<Path> windowsLocalAppData() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return Optional.empty();
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Optional.of(Paths.get(localAppData));
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Optional.of(Paths.get(appData));
        }
        return Optional.empty();
    }

    private static Optional<Path> windowsProgramData() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return Optional.empty();
        }
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Optional.of(Paths.get(programData));
        }
        return Optional.of(Paths.get("C:\\ProgramData"));
    }

    private static Optional<Path> windowsTemp() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return Optional.empty();
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            return Optional.of(Paths.get(systemRoot, "Temp"));
        }
        return Optional.of(Paths.get("C:\\Windows\\Temp"));
    }
}
