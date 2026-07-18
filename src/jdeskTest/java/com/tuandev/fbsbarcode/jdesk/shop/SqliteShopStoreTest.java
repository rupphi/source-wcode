package com.tuandev.fbsbarcode.jdesk.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        }
        store = new SqliteShopStore(this::connection);
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
}
