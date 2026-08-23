package com.tuandev.fbsbarcode.config;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.shared.LocalDataMigrationGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationCompatibilityTest {
    @TempDir Path temp;

    @AfterEach void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void migratesV119SchemaZeroDatabaseWithoutChangingIdsOrCredentials() throws Exception {
        assertV119Migration(0);
    }

    @Test
    void migratesV119SchemaOneDatabaseWithoutChangingIdsOrCredentials() throws Exception {
        assertV119Migration(1);
    }

    private void assertV119Migration(int legacySchemaVersion) throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY,name TEXT NOT NULL,api_key TEXT NOT NULL)");
            st.execute("CREATE TABLE legacy_orders(id INTEGER PRIMARY KEY,shop_id INTEGER NOT NULL "
                    + "REFERENCES shops(id),external_id TEXT NOT NULL)");
            st.execute("INSERT INTO shops VALUES(41,'Existing WB','preserve-token')");
            st.execute("INSERT INTO legacy_orders VALUES(7,41,'90071992547409931234')");
            st.execute("PRAGMA user_version=" + legacySchemaVersion);
        }

        try (LocalDataMigrationGate.Session ignored =
                LocalDataMigrationGate.prepare(temp, "1.1.10", "javafx")) {
            assertEquals(2, scalarInt("PRAGMA user_version"));
            assertEquals("WILDBERRIES", scalarText("SELECT marketplace FROM shops WHERE id=41"));
            assertEquals("preserve-token", scalarText("SELECT api_key FROM shops WHERE id=41"));
            assertEquals("90071992547409931234", scalarText("SELECT external_id FROM legacy_orders WHERE id=7"));
            assertEquals("ok", scalarText("PRAGMA integrity_check"));
            assertEquals(0, resultRowCount("PRAGMA foreign_key_check"));
            assertTrue(tableExists("ozon_postings"));
        }
        try (var paths = java.nio.file.Files.walk(temp.resolve("snapshots"))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().equals("database.db")));
        }
    }

    @Test
    void dropsLegacyKizTablesAndKeepsZnackAuditIdempotently() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY,name TEXT NOT NULL,api_key TEXT NOT NULL)");
            st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT)");
            st.execute("CREATE TABLE shop_categories(shop_id INTEGER,category_id INTEGER,created_at TEXT)");
            st.execute("CREATE TABLE kizs(id INTEGER PRIMARY KEY,code TEXT,shop_id INTEGER,category_id INTEGER)");
            st.execute("CREATE TABLE wb_product_kiz_mappings(shop_id INTEGER,nm_id INTEGER,kiz_category_id INTEGER,updated_at TEXT)");
            st.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT)");
            st.execute("INSERT INTO shops VALUES(1,'Shop','token')");
        }

        Database.initDatabase();
        ZnackRepository repository = new ZnackRepository(new ShopContext(1, "Shop"));
        repository.upsertProducts(List.of(new Product("123", "Product", null, null, null, null, null)));
        repository.log("AUDIT", "123", "INFO", "kept", null);
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT)");
            st.execute("CREATE TABLE shop_categories(shop_id INTEGER,category_id INTEGER,created_at TEXT)");
            st.execute("CREATE TABLE kizs(id INTEGER PRIMARY KEY,code TEXT,shop_id INTEGER,category_id INTEGER)");
            st.execute("CREATE TABLE wb_product_kiz_mappings(shop_id INTEGER,nm_id INTEGER,kiz_category_id INTEGER,updated_at TEXT)");
        }
        Database.initDatabase();

        for (String table : new String[]{"categories","shop_categories","kizs","wb_product_kiz_mappings"}) {
            assertFalse(tableExists(table), table);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM shops"));
        assertEquals(1, count("SELECT COUNT(*) FROM znack_operation_logs"));
        assertEquals(1, count("SELECT COUNT(*) FROM znack_products WHERE gtin='00000000000123'"));
    }

    @Test
    void mergesEquivalentShortAndPaddedGtinsWithoutLosingReferences() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','token')");
            st.execute("""
                    INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,synced_at)
                    VALUES(1,'123','Short','6201','2026-01-01T00:00:00Z'),
                          (1,'00000000000123','Padded',NULL,'2026-01-02T00:00:00Z')
                    """);
            st.execute("""
                    INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at)
                    VALUES(1,1,'123',1,'DRAFT','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')
                    """);
            st.execute("""
                    INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,stage,created_at,updated_at)
                    VALUES(1,'123',1,'POLLING_ORDER','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
                          (1,'00000000000123',1,'POLLING_ORDER','2026-01-02T00:00:00Z','2026-01-02T00:00:00Z')
                    """);
        }

        Database.initDatabase();

        assertEquals(1, count("SELECT COUNT(*) FROM znack_products WHERE gtin='00000000000123'"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_orders WHERE gtin='00000000000123'"));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM znack_purchase_pipelines
                WHERE gtin='00000000000123' AND stage='POLLING_ORDER'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM znack_purchase_pipelines
                WHERE gtin='00000000000123' AND stage='FAILED'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM znack_products
                WHERE gtin='00000000000123' AND tn_ved='6201'
                """));
    }

    private boolean tableExists(String name) throws Exception {
        try (Connection c = Database.getConnection(); var ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private int count(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private String scalarText(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int resultRowCount(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            int rows = 0;
            while (rs.next()) rows++;
            return rows;
        }
    }
}
