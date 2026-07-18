package com.tuandev.fbsbarcode.jdesk.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.LocalDataSnapshotService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WCodeRecoveryCliTest {
    @TempDir Path tempDir;

    @Test
    void listsVerifiesAndRestoresOnlyAfterExplicitConfirmation() throws Exception {
        Path appData = tempDir.resolve("app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE inventory(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("INSERT INTO inventory(name) VALUES ('rollback-value')");
        }
        LocalDataSnapshotService.Snapshot snapshot;
        try (AppDataLock ownership = AppDataLock.acquire(appData, "test-setup")) {
            snapshot = new LocalDataSnapshotService()
                    .create(ownership, "1.1.7", 1, "before-migration");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO inventory(name) VALUES ('newer-value')");
        }
        String snapshotId = snapshot.database().getParent().getFileName().toString();

        Invocation listed = invoke(appData, "list");
        assertEquals(0, listed.exitCode());
        assertTrue(listed.out().contains(snapshotId));
        assertTrue(listed.out().contains("verified=true"));
        assertFalse(listed.out().contains(appData.toString()));

        Invocation verified = invoke(appData, "verify", snapshotId);
        assertEquals(0, verified.exitCode());
        assertTrue(verified.out().contains("verified=true"));

        Invocation refused = invoke(appData, "restore", snapshotId);
        assertEquals(2, refused.exitCode());
        assertTrue(refused.err().contains("--confirm"));
        assertEquals(2, inventoryCount(database));

        Invocation restored = invoke(appData, "restore", snapshotId, "--confirm");
        assertEquals(0, restored.exitCode());
        assertTrue(restored.out().contains("Restore complete"));
        assertEquals(1, inventoryCount(database));
    }

    @Test
    void refusesRecoveryWhileAnotherProcessOwnsAppData() throws Exception {
        Path appData = tempDir.resolve("owned-app-data");
        try (AppDataLock ignored = AppDataLock.acquire(appData, "running-app")) {
            Invocation invocation = invoke(appData, "list");

            assertEquals(4, invocation.exitCode());
            assertTrue(invocation.err().contains("close every WCode instance"));
        }
    }

    private Invocation invoke(Path appData, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = WCodeRecoveryCli.run(
                appData,
                "1.1.7",
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Invocation(
                exitCode,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static int inventoryCount(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM inventory")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private record Invocation(int exitCode, String out, String err) {
    }
}
