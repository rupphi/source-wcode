package com.tuandev.fbsbarcode.jdesk.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.config.ShopCredentialSchema;
import dev.jdesk.api.PlatformInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiagnosticsCollectorTest {
    private static final String SECRET = "collector-secret-canary";
    @TempDir Path temp;

    @AfterEach
    void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void collectsAggregateHealthAndWritesBoundedRedactedAtomicBundle() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.resolve("private-" + SECRET).toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            ShopCredentialSchema.initialize(connection);
            statement.execute("INSERT INTO shops(name,api_key) VALUES('" + SECRET + "','" + SECRET + "')");
            statement.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,name,synced_at) "
                    + "SELECT id,'private-id',0,0,'" + SECRET + "','2026-01-01T00:00:00Z' FROM shops");
        }
        LocalDiagnosticsCollector collector = new LocalDiagnosticsCollector();
        DiagnosticsCommandService.DiagnosticsSummary summary = collector.collect(
                new PlatformInfo("Mac OS X", "26.5.1", "aarch64"));

        assertEquals("healthy", summary.databaseStatus());
        assertEquals(1, summary.shopCount());
        assertEquals(1, summary.supplyCount());
        assertFalse(summary.toString().contains(SECRET));

        Path target = temp.resolve("support.zip");
        new SupportBundleWriter().write(target, summary);
        assertTrue(Files.isRegularFile(target));
        try (ZipFile zip = new ZipFile(target.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(2, zip.size());
            String manifest = new String(
                    zip.getInputStream(zip.getEntry("diagnostics.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertFalse(manifest.contains(SECRET));
            assertFalse(manifest.contains(temp.toString()));
            assertTrue(manifest.contains("\"databaseStatus\":\"healthy\""));
            assertTrue(zip.getEntry("README.txt").getSize() < 4096);
        }
    }

    @Test
    void rejectsSymlinkTargetWithoutTouchingItsReferent() throws Exception {
        Path referent = temp.resolve("referent.txt");
        Files.writeString(referent, "preserve", StandardCharsets.UTF_8);
        Path link = temp.resolve("support.zip");
        Files.createSymbolicLink(link, referent.getFileName());

        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> new SupportBundleWriter().write(link, new LocalDiagnosticsCollector().fallback(
                        new PlatformInfo("Linux", "1", "x86_64"))));
        assertEquals("preserve", Files.readString(referent));
    }

    @Test
    void missingDatabaseReportsUnavailableWithoutCreatingAppData() {
        Path missing = temp.resolve("missing-app-data");
        System.setProperty("wcode.appdata.dir", missing.toString());

        DiagnosticsCommandService.DiagnosticsSummary summary = new LocalDiagnosticsCollector().collect(
                new PlatformInfo("Linux", "1", "x86_64"));

        assertEquals("unavailable", summary.databaseStatus());
        assertFalse(Files.exists(missing));
    }
}
