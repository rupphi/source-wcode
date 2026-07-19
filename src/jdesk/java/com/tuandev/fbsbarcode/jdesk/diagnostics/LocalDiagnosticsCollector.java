package com.tuandev.fbsbarcode.jdesk.diagnostics;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.AppPaths;
import dev.jdesk.api.PlatformInfo;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import org.sqlite.SQLiteConfig;

/** Reads only aggregate local health values; identities and raw rows never leave SQLite. */
final class LocalDiagnosticsCollector {
    DiagnosticsCommandService.DiagnosticsSummary collect(PlatformInfo platform) {
        Path database = AppPaths.appDataDir().resolve("database.db");
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            return fallback(platform);
        }
        try (Connection connection = openReadOnly(database)) {
            String health = integrity(connection);
            return summary(platform, health,
                    count(connection, "shops"),
                    count(connection, "wb_supplies"),
                    count(connection, "print_jobs"),
                    pendingCredentials(connection),
                    count(connection, "shop_credential_tombstones"));
        } catch (RuntimeException | SQLException exception) {
            return fallback(platform);
        }
    }

    private static Connection openReadOnly(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5_000);
        return config.createConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
    }

    DiagnosticsCommandService.DiagnosticsSummary fallback(PlatformInfo platform) {
        return summary(platform, "unavailable", 0, 0, 0, 0, 0);
    }

    private static DiagnosticsCommandService.DiagnosticsSummary summary(
            PlatformInfo platform, String database, int shops, int supplies, int jobs,
            int pendingCredentials, int tombstones) {
        PlatformInfo safePlatform = platform == null ? new PlatformInfo("Other", "unknown", "unknown") : platform;
        return new DiagnosticsCommandService.DiagnosticsSummary(
                safe(BuildConfig.getAppVersion()),
                "0.1.3",
                safe(Runtime.version().feature() + ""),
                osFamily(safePlatform.osName()),
                safe(safePlatform.osVersion()),
                architecture(safePlatform.architecture()),
                database,
                shops,
                supplies,
                jobs,
                pendingCredentials,
                tombstones);
    }

    private static String integrity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA integrity_check(1)");
                ResultSet result = statement.executeQuery()) {
            return result.next() && "ok".equalsIgnoreCase(result.getString(1)) ? "healthy" : "corrupt";
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table)) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet result = statement.executeQuery()) {
            return bounded(result.next() ? result.getLong(1) : 0);
        }
    }

    private static int pendingCredentials(Connection connection) throws SQLException {
        if (!tableExists(connection, "shop_credential_mirrors")) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM shop_credential_mirrors WHERE mirrored_version IS NULL "
                        + "OR mirrored_version<>credential_version "
                        + "OR mirrored_fingerprint<>credential_fingerprint");
                ResultSet result = statement.executeQuery()) {
            return bounded(result.next() ? result.getLong(1) : 0);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static int bounded(long value) {
        return (int) Math.max(0, Math.min(1_000_000, value));
    }

    private static String osFamily(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("mac")) return "macos";
        if (lower.contains("win")) return "windows";
        if (lower.contains("linux")) return "linux";
        return "other";
    }

    private static String architecture(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.equals("aarch64") || lower.equals("arm64")) return "arm64";
        if (lower.equals("amd64") || lower.equals("x86_64")) return "x86_64";
        return "other";
    }

    private static String safe(String value) {
        String normalized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._ -]", "").strip();
        return normalized.isEmpty() ? "unknown" : normalized.substring(0, Math.min(64, normalized.length()));
    }
}
