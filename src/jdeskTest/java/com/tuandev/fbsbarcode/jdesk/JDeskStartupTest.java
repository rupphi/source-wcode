package com.tuandev.fbsbarcode.jdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.shared.LocalDataMigrationGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JDeskStartupTest {
    @TempDir Path tempDir;

    @Test
    void snapshotsExistingDatabaseOnceBeforeWriterVersionInitializes() throws Exception {
        Path appData = tempDir.resolve("app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_marker(value TEXT NOT NULL)");
            statement.execute("INSERT INTO legacy_marker(value) VALUES ('preserve-me')");
        }

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertTrue(hasTable(database, "shops"));
                assertTrue(hasTable(database, "shop_credential_mirrors"));
                assertTrue(hasTable(database, "shop_credential_tombstones"));
                assertTrue(Files.exists(appData.resolve("writer-state/jdesk-1.1.7.ready")));
                assertEquals(1, readSchemaVersion(database));
                assertTrue(Files.readString(appData.resolve("writer-state/jdesk-1.1.7.ready"))
                        .contains("schemaVersion=1\n"));
                assertEquals(1, snapshotCount(appData));
            }

            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, snapshotCount(appData));
            }

            try (JDeskStartup.Session session = JDeskStartup.prepare(appData, "1.1.7")) {
                session.createSignedUpdateSnapshot();
                assertEquals(2, snapshotCount(appData));
                assertTrue(hasSnapshotReason(appData, "signed-update-install"));
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void schemaRevisionChangeCannotReuseReadyWriterMarker() throws Exception {
        Path appData = tempDir.resolve("schema-revision-app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, readSchemaVersion(database));
                assertEquals(1, snapshotCount(appData));
                assertTrue(hasSnapshotReason(appData, "wcode-schema-1"));
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 0");
            }

            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, readSchemaVersion(database));
                assertEquals(2, snapshotCount(appData));
                assertTrue(hasSnapshotReason(appData, "wcode-schema-1"));
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void futureSchemaFailsClosedWithoutSnapshotOrMarkerMutation() throws Exception {
        Path appData = tempDir.resolve("future-schema-app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE future_data(value TEXT NOT NULL)");
            statement.execute("INSERT INTO future_data(value) VALUES ('preserve-me')");
            statement.execute("PRAGMA user_version = 2");
        }

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            assertThrows(IOException.class, () -> JDeskStartup.prepare(appData, "1.1.7"));
            assertEquals(2, readSchemaVersion(database));
            assertEquals(0, snapshotCount(appData));
            assertFalse(Files.exists(appData.resolve("writer-state/jdesk-1.1.7.ready")));
            assertTrue(hasTable(database, "future_data"));
            assertFalse(hasTable(database, "shops"));
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void eachCanaryWriterCreatesOneRollbackPointBeforeItsFirstUse() throws Exception {
        Path appData = tempDir.resolve("canary-writer-app-data");
        Files.createDirectories(appData);

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, snapshotCount(appData));
            }
            try (LocalDataMigrationGate.Session ignored =
                    LocalDataMigrationGate.prepare(appData, "1.1.7", "javafx")) {
                assertEquals(2, snapshotCount(appData));
                assertTrue(hasSnapshotReason(appData, "javafx-writer-1.1.7"));
                assertTrue(Files.isRegularFile(appData.resolve("writer-state/javafx-1.1.7.ready")));
            }
            try (LocalDataMigrationGate.Session ignored =
                    LocalDataMigrationGate.prepare(appData, "1.1.7", "javafx")) {
                assertEquals(2, snapshotCount(appData));
            }
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(2, snapshotCount(appData));
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void failedSchemaMigrationKeepsOldRevisionAndReadyMarkerUnpublished() throws Exception {
        Path appData = tempDir.resolve("failed-schema-app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_marker(value TEXT NOT NULL)");
            statement.execute("CREATE VIEW shops AS SELECT 1 AS id");
        }

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            assertThrows(RuntimeException.class, () -> JDeskStartup.prepare(appData, "1.1.7"));
            assertEquals(0, readSchemaVersion(database));
            assertEquals(1, snapshotCount(appData));
            assertTrue(hasSnapshotReason(appData, "wcode-schema-1"));
            assertFalse(Files.exists(appData.resolve("writer-state/jdesk-1.1.7.ready")));
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void markerTemporarySymlinkFailsClosedWithoutOverwritingReferent() throws Exception {
        Path appData = tempDir.resolve("marker-symlink-app-data");
        Files.createDirectories(appData);

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, snapshotCount(appData));
            }
            Path marker = appData.resolve("writer-state/jdesk-1.1.7.ready");
            Files.writeString(marker, "invalid\n");
            Path referent = tempDir.resolve("marker-canary.txt");
            Files.writeString(referent, "do-not-touch");
            Files.createSymbolicLink(
                    marker.resolveSibling(marker.getFileName() + ".tmp"), referent);

            assertThrows(IOException.class, () -> JDeskStartup.prepare(appData, "1.1.7"));
            assertEquals("do-not-touch", Files.readString(referent));
            assertEquals("invalid\n", Files.readString(marker));
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void anOlderReadyMarkerCannotBypassTheCredentialSchemaSnapshotGate() throws Exception {
        Path appData = tempDir.resolve("old-marker-app-data");
        Files.createDirectories(appData.resolve("writer-state"));
        Path database = appData.resolve("database.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_marker(value TEXT NOT NULL)");
        }
        Files.writeString(
                appData.resolve("writer-state/jdesk-1.1.7.ready"),
                "writerVersion=1.1.7\nsnapshotSha256=none\n");

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
            assertEquals(1, snapshotCount(appData));
            assertTrue(hasTable(database, "shop_credential_mirrors"));
            assertTrue(Files.readString(appData.resolve("writer-state/jdesk-1.1.7.ready"))
                    .contains("dataMigration=wcode-schema-v1\n"));
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void readyMarkerCannotBypassTheGateWhenItsReferencedSnapshotIsCorrupt() throws Exception {
        Path appData = tempDir.resolve("corrupt-marker-app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_marker(value TEXT NOT NULL)");
        }

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, snapshotCount(appData));
            }
            Path snapshotDatabase;
            try (var paths = Files.walk(appData.resolve("snapshots"))) {
                snapshotDatabase = paths.filter(path -> path.getFileName().toString().equals("database.db"))
                        .findFirst()
                        .orElseThrow();
            }
            Files.write(snapshotDatabase, new byte[] {1}, java.nio.file.StandardOpenOption.APPEND);

            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(2, snapshotCount(appData));
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    @Test
    void readyMarkerCannotBypassTheGateWhenItContainsUnexpectedProperties() throws Exception {
        Path appData = tempDir.resolve("unexpected-marker-property-app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_marker(value TEXT NOT NULL)");
        }

        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(1, snapshotCount(appData));
            }
            Path marker = appData.resolve("writer-state/jdesk-1.1.7.ready");
            Files.writeString(
                    marker,
                    "unexpected=true" + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.APPEND);

            try (JDeskStartup.Session ignored = JDeskStartup.prepare(appData, "1.1.7")) {
                assertEquals(2, snapshotCount(appData));
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    private static long snapshotCount(Path appData) throws Exception {
        Path snapshots = appData.resolve("snapshots");
        if (!Files.exists(snapshots)) {
            return 0;
        }
        try (var entries = Files.list(snapshots)) {
            return entries.filter(Files::isDirectory).count();
        }
    }

    private static boolean hasSnapshotReason(Path appData, String reason) throws Exception {
        try (var paths = Files.walk(appData.resolve("snapshots"))) {
            for (Path path : paths.filter(candidate ->
                            candidate.getFileName().toString().equals("snapshot.properties"))
                    .toList()) {
                if (Files.readString(path).contains("reason=" + reason)) return true;
            }
        }
        return false;
    }

    private static boolean hasTable(Path database, String name) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static int readSchemaVersion(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
}
