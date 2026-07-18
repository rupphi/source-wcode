package com.tuandev.fbsbarcode.jdesk;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppDataRecoveryService;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.LocalDataSnapshotService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

/** Performs fail-closed data ownership, recovery, snapshot and database initialization. */
public final class JDeskStartup {
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private JDeskStartup() {
    }

    public static Session prepare(Path appDataDir, String appVersion) throws Exception {
        if (appVersion == null || !VERSION_PATTERN.matcher(appVersion).matches()) {
            throw new IllegalArgumentException("appVersion has an invalid format");
        }
        Path normalizedDir = appDataDir.toAbsolutePath().normalize();
        if (!normalizedDir.equals(AppPaths.appDataDir().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("appDataDir must match the configured WCode app-data directory");
        }

        AppDataLock ownership = AppDataLock.acquire(normalizedDir, "jdesk");
        try {
            LocalDataSnapshotService snapshots = new LocalDataSnapshotService();
            snapshots.recoverInterrupted(ownership);
            AppDataRecoveryService.recoverIfNeededOnStartup();
            Path marker = normalizedDir.resolve("writer-state").resolve("jdesk-" + appVersion + ".ready");
            if (isReady(marker, appVersion)) {
                Database.initDatabase();
                return new Session(ownership);
            }

            Path database = normalizedDir.resolve("database.db");
            String snapshotChecksum = "none";
            if (Files.isRegularFile(database)) {
                int schemaVersion = readSchemaVersion(database);
                LocalDataSnapshotService.Snapshot snapshot = snapshots.create(
                        ownership, appVersion, schemaVersion, "jdesk-writer-" + appVersion.toLowerCase());
                snapshotChecksum = snapshot.sha256();
            }

            Database.initDatabase();
            writeReadyMarker(marker, appVersion, snapshotChecksum);
            return new Session(ownership);
        } catch (Exception exception) {
            ownership.close();
            throw exception;
        }
    }

    private static int readSchemaVersion(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static boolean isReady(Path marker, String appVersion) {
        try {
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            String content = Files.readString(marker, StandardCharsets.UTF_8);
            return content.contains("writerVersion=" + appVersion + System.lineSeparator())
                    && content.matches("(?s).*snapshotSha256=(none|[0-9a-f]{64})\\R.*");
        } catch (IOException exception) {
            return false;
        }
    }

    private static void writeReadyMarker(Path marker, String appVersion, String snapshotChecksum)
            throws IOException {
        Files.createDirectories(marker.getParent());
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        String content = "writerVersion=" + appVersion + System.lineSeparator()
                + "snapshotSha256=" + snapshotChecksum + System.lineSeparator();
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class Session implements AutoCloseable {
        private final AppDataLock ownership;

        private Session(AppDataLock ownership) {
            this.ownership = ownership;
        }

        @Override
        public void close() throws IOException {
            ownership.close();
        }
    }
}
