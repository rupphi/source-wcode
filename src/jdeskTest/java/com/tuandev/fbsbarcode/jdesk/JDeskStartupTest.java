package com.tuandev.fbsbarcode.jdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    .contains("dataMigration=shop-credential-mirror-v1\n"));
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
}
