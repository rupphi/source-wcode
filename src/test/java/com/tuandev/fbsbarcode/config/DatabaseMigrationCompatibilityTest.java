package com.tuandev.fbsbarcode.config;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
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

class DatabaseMigrationCompatibilityTest {
    @TempDir Path temp;

    @AfterEach void clear() {
        System.clearProperty("wcode.appdata.dir");
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
}
