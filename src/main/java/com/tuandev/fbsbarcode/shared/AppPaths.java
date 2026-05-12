package com.tuandev.fbsbarcode.shared;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

public final class AppPaths {
    private static final String APP_DIR_NAME = "WCode";

    private AppPaths() {
    }

    public static Path appDataDir() {
        Path base = windowsLocalAppData()
                .orElseGet(() -> Paths.get(System.getProperty("user.home", ".")));
        return base.resolve(APP_DIR_NAME);
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

    private static java.util.Optional<Path> windowsLocalAppData() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return java.util.Optional.empty();
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return java.util.Optional.of(Paths.get(localAppData));
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return java.util.Optional.of(Paths.get(appData));
        }
        return java.util.Optional.empty();
    }
}
