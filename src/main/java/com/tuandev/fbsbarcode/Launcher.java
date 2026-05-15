package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.AppDataRecoveryService;
import javafx.application.Application;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class Launcher {
    public static void main(String[] args) {
        configureStartupEnvironment();
        AppDataRecoveryService.recoverIfNeededOnStartup();
        Application.launch(MainApplication.class, args);
    }

    private static void configureStartupEnvironment() {
        try {
            Path tempDir = AppPaths.nativeTempDir();
            Path javafxCacheDir = AppPaths.javaFxCacheDir();
            Path safeUserHomeDir = AppPaths.safeUserHomeDir();
            Files.createDirectories(tempDir);
            Files.createDirectories(javafxCacheDir);
            Files.createDirectories(safeUserHomeDir);

            String currentUserHome = System.getProperty("user.home", "");
            if (!isAsciiOnly(currentUserHome)) {
                System.setProperty("user.home", safeUserHomeDir.toString());
            }
            System.setProperty("org.sqlite.tmpdir", tempDir.toString());
            System.setProperty("java.io.tmpdir", tempDir.toString());
            System.setProperty("javafx.cachedir", javafxCacheDir.toString());

            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> writeStartupLog(thread.getName(), throwable));
        } catch (Exception ignored) {
        }
    }

    private static boolean isAsciiOnly(String value) {
        return value != null && value.chars().allMatch(ch -> ch >= 32 && ch <= 126);
    }

    private static void writeStartupLog(String threadName, Throwable throwable) {
        try {
            Path logsDir = AppPaths.logsDir();
            Files.createDirectories(logsDir);
            Path logFile = logsDir.resolve("startup.log");

            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));

            String content = """
                    [%s] Thread: %s
                    user.home=%s
                    LOCALAPPDATA=%s
                    APPDATA=%s
                    java.io.tmpdir=%s
                    org.sqlite.tmpdir=%s
                    javafx.cachedir=%s
                    %s

                    """.formatted(
                    LocalDateTime.now(),
                    threadName,
                    System.getProperty("user.home"),
                    System.getenv("LOCALAPPDATA"),
                    System.getenv("APPDATA"),
                    System.getProperty("java.io.tmpdir"),
                    System.getProperty("org.sqlite.tmpdir"),
                    System.getProperty("javafx.cachedir"),
                    stack
            );

            Files.writeString(logFile, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
