package com.tuandev.fbsbarcode.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

/** Creates verified, WAL-consistent rollback snapshots before local-data writer changes. */
public final class LocalDataSnapshotService {
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern REASON_PATTERN = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final String DATABASE_NAME = "database.db";

    public Snapshot create(AppDataLock ownership, String appVersion, int schemaVersion, String reason)
            throws IOException, SQLException {
        Objects.requireNonNull(ownership, "ownership");
        requireMatch(appVersion, VERSION_PATTERN, "appVersion");
        requireMatch(reason, REASON_PATTERN, "reason");
        if (schemaVersion < 0) {
            throw new IllegalArgumentException("schemaVersion must not be negative");
        }

        Path appDataDir = ownership.requireOwnedAppDataDir();
        Path liveDatabase = appDataDir.resolve(DATABASE_NAME);
        if (!Files.isRegularFile(liveDatabase)) {
            throw new IOException("The live WCode database does not exist.");
        }

        Instant createdAt = Instant.now();
        String snapshotId = createdAt.toEpochMilli() + "-" + UUID.randomUUID();
        Path snapshotDir = appDataDir.resolve("snapshots").resolve(snapshotId);
        Path snapshotDatabase = snapshotDir.resolve(DATABASE_NAME);
        Path metadata = snapshotDir.resolve("snapshot.properties");
        Files.createDirectories(snapshotDir);

        try {
            backup(liveDatabase, snapshotDatabase);
            assertIntegrity(snapshotDatabase);
            String checksum = sha256(snapshotDatabase);
            String metadataContent = "appVersion=" + appVersion + System.lineSeparator()
                    + "schemaVersion=" + schemaVersion + System.lineSeparator()
                    + "reason=" + reason + System.lineSeparator()
                    + "createdAt=" + createdAt + System.lineSeparator()
                    + "sha256=" + checksum + System.lineSeparator();
            Files.writeString(
                    metadata,
                    metadataContent,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);

            Snapshot snapshot = new Snapshot(
                    snapshotDatabase, metadata, checksum, appVersion, schemaVersion, reason, createdAt);
            if (!verify(snapshot)) {
                throw new IOException("The new WCode data snapshot failed verification.");
            }
            return snapshot;
        } catch (IOException | SQLException exception) {
            Files.deleteIfExists(metadata);
            Files.deleteIfExists(snapshotDatabase);
            Files.deleteIfExists(snapshotDir);
            throw exception;
        }
    }

    public boolean verify(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Files.isRegularFile(snapshot.database()) || !Files.isRegularFile(snapshot.metadata())) {
            return false;
        }
        try {
            if (!MessageDigest.isEqual(
                    snapshot.sha256().getBytes(StandardCharsets.US_ASCII),
                    sha256(snapshot.database()).getBytes(StandardCharsets.US_ASCII))) {
                return false;
            }
            assertIntegrity(snapshot.database());
            String metadata = Files.readString(snapshot.metadata(), StandardCharsets.UTF_8);
            return metadata.contains("sha256=" + snapshot.sha256() + System.lineSeparator())
                    && metadata.contains("appVersion=" + snapshot.appVersion() + System.lineSeparator())
                    && metadata.contains("schemaVersion=" + snapshot.schemaVersion() + System.lineSeparator());
        } catch (IOException | SQLException exception) {
            return false;
        }
    }

    private static void backup(Path source, Path destination) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath())) {
            SQLiteConnection sqliteConnection = connection.unwrap(SQLiteConnection.class);
            int result = sqliteConnection
                    .getDatabase()
                    .backup("main", destination.toAbsolutePath().toString(), (remaining, total) -> {}, 100, 100, 100);
            if (result != SQLiteErrorCode.SQLITE_OK.code) {
                throw new SQLException("SQLite online backup failed with result code " + result);
            }
        }
    }

    private static void assertIntegrity(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1)) || result.next()) {
                throw new SQLException("SQLite integrity check failed for a WCode snapshot.");
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
    }

    public record Snapshot(
            Path database,
            Path metadata,
            String sha256,
            String appVersion,
            int schemaVersion,
            String reason,
            Instant createdAt) {
        public Snapshot {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(appVersion, "appVersion");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
