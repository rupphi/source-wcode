package com.tuandev.fbsbarcode.integration.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZnackCertificateSettingsRepositoryTest {
    @TempDir
    Path appData;

    private ZnackRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(7,'Main shop','secret')");
        }
        repository = new ZnackRepository(new ShopContext(7, "Main shop"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void verifiedCertificateAndAuditCommitTogetherWhileStaleSnapshotRollsBack() throws Exception {
        Settings initial = settings("selector-a", null, "metadata-a");
        repository.saveSettings(initial);
        Settings verified = settings(
                "selector-b", Instant.parse("2026-07-18T00:00:00Z"), "metadata-b");

        repository.saveVerifiedCertificate(initial, verified);

        assertEquals(verified, repository.getSettings());
        assertEquals(1, signatureAuditCount());

        Settings concurrent = settings("selector-c", null, "metadata-c");
        repository.saveSettings(concurrent);
        assertThrows(
                ZnackRepository.SettingsConflictException.class,
                () -> repository.saveVerifiedCertificate(verified, settings(
                        "selector-d", Instant.parse("2026-07-18T01:00:00Z"), "metadata-d")));

        assertEquals(concurrent, repository.getSettings());
        assertEquals(1, signatureAuditCount());
    }

    private int signatureAuditCount() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*) FROM znack_operation_logs
                        WHERE shop_id=7 AND action='SIGNATURE_TEST' AND message='VERIFIED'
                        """)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static Settings settings(String selector, Instant testedAt, String metadata) {
        return new Settings(
                "https://private.example", "https://private-suz.example", "OMS", "CONNECTION",
                "7700000000", "7700000000", "7700000000", "/private/signer", selector, "[]",
                "", "", "/private/pdf", false, "/private/cert-list", "[]", metadata, testedAt,
                "/private/certmgr", "/private/cryptcp", "/private/csptest", 60, "",
                Settings.DEFAULT_DOCUMENT_TYPE);
    }
}
