package com.tuandev.fbsbarcode.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDataSnapshotServiceTest {
    @TempDir Path tempDir;

    @Test
    void snapshotsCommittedWalDataAndVerifiesChecksumAndIntegrity() throws Exception {
        Path appData = tempDir.resolve("app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "test");
                Connection live = DriverManager.getConnection("jdbc:sqlite:" + liveDatabase);
                Statement statement = live.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA wal_autocheckpoint = 0");
            statement.execute("CREATE TABLE inventory(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("INSERT INTO inventory(name) VALUES ('from-wal')");

            LocalDataSnapshotService service = new LocalDataSnapshotService();
            LocalDataSnapshotService.Snapshot snapshot =
                    service.create(ownership, "1.1.7", 1, "first-jdesk-launch");

            assertTrue(Files.exists(liveDatabase.resolveSibling("database.db-wal")));
            assertTrue(Files.exists(snapshot.database()));
            assertTrue(Files.exists(snapshot.metadata()));
            assertTrue(service.verify(snapshot));
            assertEquals(1, countInventoryRows(snapshot.database()));

            Files.write(snapshot.database(), new byte[] {0x01}, java.nio.file.StandardOpenOption.APPEND);
            assertFalse(service.verify(snapshot));
        }
    }

    private static int countInventoryRows(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM inventory")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
