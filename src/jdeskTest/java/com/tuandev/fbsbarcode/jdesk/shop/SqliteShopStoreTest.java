package com.tuandev.fbsbarcode.jdesk.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.ShopCredentialSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteShopStoreTest {
    @TempDir
    Path temporaryDirectory;

    private String url;
    private SqliteShopStore store;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:sqlite:" + temporaryDirectory.resolve("shops.db");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,api_key TEXT NOT NULL)");
            statement.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT)");
            statement.execute("CREATE TABLE local_rows(id INTEGER PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE)");
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(41,'Existing','existing-secret')");
            statement.execute("INSERT INTO local_rows(id,shop_id) VALUES(1,41)");
            statement.execute("INSERT INTO app_config(key,value) VALUES('last_selected_shop_id','41')");
            ShopCredentialSchema.initialize(connection);
        }
        store = new SqliteShopStore(this::connection, () -> "shop-api-key-v1-fixture");
    }

    @Test
    void createUsesSameConnectionGeneratedIdAndAtomicallySelectsIt() throws Exception {
        ShopCommandService.ShopState state = store.create("Created", "new-secret");

        assertEquals(42, state.selectedShopId());
        assertEquals("42", configValue());
        assertEquals("new-secret", token(42));
        assertFalse(state.toString().contains("new-secret"));
    }

    @Test
    void updateCanRetainOrReplaceTokenAndRejectsMissingShop() throws Exception {
        store.update(41, "Renamed", null);
        assertEquals("existing-secret", token(41));

        store.update(41, "Renamed again", "replacement-secret");
        assertEquals("replacement-secret", token(41));
        assertThrows(ShopCommandService.ShopStoreException.class,
                () -> store.update(999, "Missing", null));
    }

    @Test
    void deletingSelectedShopCascadesAndSelectsLowestRemainingId() throws Exception {
        ShopCommandService.ShopState created = store.create("Second", "second-secret");
        store.select(41);

        ShopCommandService.ShopState state = store.delete(41);

        assertEquals(created.selectedShopId(), state.selectedShopId());
        assertEquals(String.valueOf(created.selectedShopId()), configValue());
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM local_rows")) {
            try (ResultSet result = statement.executeQuery()) {
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void selectAndDeleteRejectMissingShopWithoutChangingSelection() throws Exception {
        assertThrows(ShopCommandService.ShopStoreException.class, () -> store.select(999));
        assertThrows(ShopCommandService.ShopStoreException.class, () -> store.delete(999));
        assertEquals("41", configValue());
    }

    @Test
    void createRollsBackTheShopWhenSelectionWriteFails() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_selection BEFORE UPDATE ON app_config "
                    + "WHEN NEW.key='last_selected_shop_id' BEGIN SELECT RAISE(ABORT,'selection rejected'); END");
        }

        assertThrows(RuntimeException.class, () -> store.create("Must roll back", "temporary-secret"));

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM shops");
                ResultSet result = statement.executeQuery()) {
            assertEquals(1, result.getInt(1));
        }
        assertEquals("41", configValue());
    }

    @Test
    void deleteRejectsPersistedActiveZnackPipeline() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE znack_purchase_pipelines("
                    + "id INTEGER PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,stage TEXT NOT NULL)");
            statement.execute("INSERT INTO znack_purchase_pipelines(id,shop_id,stage) VALUES(1,41,'POLLING_ORDER')");
        }

        ShopCommandService.ShopStoreException error = assertThrows(
                ShopCommandService.ShopStoreException.class, () -> store.delete(41));

        assertEquals("shop_busy", error.kind());
        assertEquals("existing-secret", token(41));
        assertEquals("41", configValue());
    }

    @Test
    void createAndTokenReplacementAdvanceVersionWhileRetainDoesNot() throws Exception {
        ShopCredentialMirrorTest.FakeVault vault = new ShopCredentialMirrorTest.FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);

        ShopCommandService.ShopState created = store.create("Created", "first-secret");
        int shopId = created.selectedShopId();
        store.reconcile(mirror);
        CredentialState first = credentialState(shopId);
        assertEquals(1, first.version());
        assertEquals(1, first.mirroredVersion());
        assertEquals(ShopCredentialMirror.fingerprint("first-secret"), first.fingerprint());

        store.update(shopId, "Renamed", null);
        store.reconcile(mirror);
        assertEquals(first, credentialState(shopId));

        store.update(shopId, "Renamed", "first-secret");
        store.reconcile(mirror);
        assertEquals(first, credentialState(shopId));

        store.update(shopId, "Renamed", "second-secret");
        assertEquals("second-secret", token(shopId));
        CredentialState pending = credentialState(shopId);
        assertEquals(2, pending.version());
        assertEquals(1, pending.mirroredVersion());
        store.reconcile(mirror);
        assertEquals(2, credentialState(shopId).mirroredVersion());
    }

    @Test
    void discoversOutOfBandJavaFxTokenChangeAndReconcilesFromLegacyAuthority() throws Exception {
        ShopCredentialMirrorTest.FakeVault vault = new ShopCredentialMirrorTest.FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        store.reconcile(mirror);
        CredentialState original = credentialState(41);

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE shops SET api_key=? WHERE id=41")) {
            statement.setString(1, "javafx-newest-secret");
            statement.executeUpdate();
        }
        store.reconcile(mirror);

        CredentialState reconciled = credentialState(41);
        assertEquals(original.version() + 1, reconciled.version());
        assertEquals(reconciled.version(), reconciled.mirroredVersion());
        assertEquals(ShopCredentialMirror.fingerprint("javafx-newest-secret"), reconciled.fingerprint());
        assertEquals("javafx-newest-secret", token(41));
    }

    @Test
    void repairsOsEntryDeletedOrCorruptedAfterItWasAcknowledged() throws Exception {
        ShopCredentialMirrorTest.FakeVault vault = new ShopCredentialMirrorTest.FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        store.reconcile(mirror);
        CredentialState state = credentialState(41);

        vault.values.remove(state.secretKey());
        store.reconcile(mirror);
        assertTrue(vault.values.containsKey(state.secretKey()));

        vault.values.put(state.secretKey(), "corrupt");
        store.reconcile(mirror);
        assertFalse("corrupt".equals(vault.values.get(state.secretKey())));
        assertEquals(state.version(), credentialState(41).mirroredVersion());
    }

    @Test
    void vaultPutGetAndSqliteAckFailuresStayPendingAndRetryWithoutChangingLegacyToken() throws Exception {
        ShopCredentialMirrorTest.FakeVault vault = new ShopCredentialMirrorTest.FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        store.update(41, "Existing", "newest-secret");

        vault.failPutBeforeMutation = true;
        store.reconcile(mirror);
        assertNull(credentialState(41).mirroredVersion());
        assertEquals("newest-secret", token(41));

        vault.failPutBeforeMutation = false;
        vault.failGet = true;
        store.reconcile(mirror);
        assertNull(credentialState(41).mirroredVersion());

        vault.failGet = false;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_mirror_ack BEFORE UPDATE OF mirrored_version "
                    + "ON shop_credential_mirrors BEGIN SELECT RAISE(ABORT,'ack rejected'); END");
        }
        store.reconcile(mirror);
        assertNull(credentialState(41).mirroredVersion());
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_mirror_ack");
        }

        store.reconcile(mirror);
        assertEquals(1, credentialState(41).mirroredVersion());
        assertEquals("newest-secret", token(41));
    }

    @Test
    void deleteCommitsTokenFreeTombstoneAndRetriesVaultCleanup() throws Exception {
        ShopCredentialMirrorTest.FakeVault vault = new ShopCredentialMirrorTest.FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        store.reconcile(mirror);
        String key = credentialState(41).secretKey();
        assertTrue(vault.values.containsKey(key));

        vault.failDeleteBeforeMutation = true;
        store.delete(41);
        store.reconcile(mirror);
        assertEquals(1, count("SELECT COUNT(*) FROM shop_credential_tombstones"));
        assertTrue(vault.values.containsKey(key));
        assertEquals(0, count("SELECT COUNT(*) FROM shops WHERE id=41"));
        assertFalse(tombstoneText().contains("existing-secret"));

        vault.failDeleteBeforeMutation = false;
        store.reconcile(mirror);
        assertFalse(vault.values.containsKey(key));
        assertEquals(0, count("SELECT COUNT(*) FROM shop_credential_tombstones"));
    }

    @Test
    void deleteAfterMutationAndTombstoneAckFailuresRemainIdempotentlyRetryable() throws Exception {
        ShopCredentialMirrorTest.FakeVault vault = new ShopCredentialMirrorTest.FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        store.reconcile(mirror);
        String key = credentialState(41).secretKey();

        vault.failDeleteAfterMutation = true;
        store.delete(41);
        store.reconcile(mirror);
        assertFalse(vault.values.containsKey(key));
        assertEquals(1, count("SELECT COUNT(*) FROM shop_credential_tombstones"));

        vault.failDeleteAfterMutation = false;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_tombstone_ack BEFORE DELETE "
                    + "ON shop_credential_tombstones BEGIN SELECT RAISE(ABORT,'ack rejected'); END");
        }
        store.reconcile(mirror);
        assertEquals(1, count("SELECT COUNT(*) FROM shop_credential_tombstones"));
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_tombstone_ack");
        }

        store.reconcile(mirror);
        assertEquals(0, count("SELECT COUNT(*) FROM shop_credential_tombstones"));
        assertEquals(0, count("SELECT COUNT(*) FROM shops"));
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private String configValue() throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT value FROM app_config WHERE key='last_selected_shop_id'");
                ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private String token(int shopId) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT api_key FROM shops WHERE id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private CredentialState credentialState(int shopId) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT secret_key,credential_version,credential_fingerprint,mirrored_version,mirrored_fingerprint "
                        + "FROM shop_credential_mirrors WHERE shop_id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Long mirroredVersion = result.getObject("mirrored_version") == null
                        ? null : result.getLong("mirrored_version");
                return new CredentialState(
                        result.getString("secret_key"),
                        result.getLong("credential_version"),
                        result.getString("credential_fingerprint"),
                        mirroredVersion,
                        result.getString("mirrored_fingerprint"));
            }
        }
    }

    private int count(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private String tombstoneText() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT * FROM shop_credential_tombstones")) {
            return result.next()
                    ? result.getString("secret_key") + result.getLong("credential_version")
                            + result.getString("credential_fingerprint")
                    : "";
        }
    }

    private record CredentialState(
            String secretKey,
            long version,
            String fingerprint,
            Long mirroredVersion,
            String mirroredFingerprint) {
    }
}
