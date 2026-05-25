package com.tuandev.fbsbarcode.features.kizmapping;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoKizMappingRepositoryTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldCreateCategoryFromNewSubjectAndMapProduct() throws Exception {
        initializeFixture();
        insertProduct(1, 1001L, "Dress", 1, 0);

        AutoKizMappingResult result = new AutoKizMappingRepository().autoCreateAndMap(1);

        assertEquals(new AutoKizMappingResult(1, 1), result);
        assertEquals("Dress", categoryName(11));
        assertEquals(11, mappingFor(1, 1001L));
    }

    @Test
    void shouldReuseExistingCategoryIgnoringCaseAndWhitespace() throws Exception {
        initializeFixture();
        insertProduct(1, 1001L, "  shoes  ", 1, 0);
        insertProduct(1, 1002L, "SHOES", 1, 0);

        AutoKizMappingResult result = new AutoKizMappingRepository().autoCreateAndMap(1);

        assertEquals(new AutoKizMappingResult(0, 2), result);
        assertEquals(10, mappingFor(1, 1001L));
        assertEquals(10, mappingFor(1, 1002L));
        assertEquals(1, count("SELECT COUNT(*) FROM categories WHERE LOWER(TRIM(name)) = 'shoes'"));
    }

    @Test
    void shouldCreateOnlyOneCategoryForManyProductsWithSameSubject() throws Exception {
        initializeFixture();
        insertProduct(1, 1001L, "Bags", 1, 0);
        insertProduct(1, 1002L, "bags", 0, 1);

        AutoKizMappingResult result = new AutoKizMappingRepository().autoCreateAndMap(1);

        assertEquals(new AutoKizMappingResult(1, 2), result);
        assertEquals(11, mappingFor(1, 1001L));
        assertEquals(11, mappingFor(1, 1002L));
    }

    @Test
    void shouldMapOnlyProductsThatNeedKizAndHaveSubject() throws Exception {
        initializeFixture();
        insertProduct(1, 1001L, "Bags", 1, 0);
        insertProduct(1, 1002L, "Hats", 0, 0);
        insertProduct(1, 1003L, "   ", 1, 0);

        AutoKizMappingResult result = new AutoKizMappingRepository().autoCreateAndMap(1);

        assertEquals(new AutoKizMappingResult(1, 1), result);
        assertEquals(Map.of(1001L, 11), mappings());
    }

    @Test
    void shouldNotOverwriteExistingMapping() throws Exception {
        initializeFixture();
        insertCategory(20, "Manual");
        insertProduct(1, 1001L, "Bags", 1, 0);
        insertMapping(1, 1001L, 20);

        AutoKizMappingResult result = new AutoKizMappingRepository().autoCreateAndMap(1);

        assertEquals(new AutoKizMappingResult(0, 0), result);
        assertEquals(20, mappingFor(1, 1001L));
        assertEquals(0, count("SELECT COUNT(*) FROM categories WHERE name = 'Bags'"));
    }

    @Test
    void shouldCreateNothingWhenNoProductsNeedKiz() throws Exception {
        initializeFixture();
        insertProduct(1, 1001L, "Bags", 0, 0);

        AutoKizMappingResult result = new AutoKizMappingRepository().autoCreateAndMap(1);

        assertEquals(new AutoKizMappingResult(0, 0), result);
        assertEquals(1, count("SELECT COUNT(*) FROM categories"));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_product_kiz_mappings"));
    }

    private void initializeFixture() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
        }
        insertCategory(10, "Shoes");
    }

    private void insertCategory(int id, String name) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO categories(id, name) VALUES (?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void insertProduct(int shopId, long nmId, String subject, int needKiz, int kizMarked) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO wb_product_cards (shop_id, nm_id, title, subject_name, vendor_code, need_kiz, kiz_marked, synced_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, '2026-05-23T00:00:00Z')
                     """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setString(3, "Product " + nmId);
            ps.setString(4, subject);
            ps.setString(5, "vendor-" + nmId);
            ps.setInt(6, needKiz);
            ps.setInt(7, kizMarked);
            ps.executeUpdate();
        }
    }

    private void insertMapping(int shopId, long nmId, int categoryId) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO wb_product_kiz_mappings(shop_id, nm_id, kiz_category_id, updated_at)
                     VALUES (?, ?, ?, '2026-05-23T00:00:00Z')
                     """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setInt(3, categoryId);
            ps.executeUpdate();
        }
    }

    private int mappingFor(int shopId, long nmId) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT kiz_category_id FROM wb_product_kiz_mappings WHERE shop_id = ? AND nm_id = ?")) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Map<Long, Integer> mappings() throws Exception {
        return new KizMappingRepository().findMappings(1, java.util.List.of(1001L, 1002L, 1003L));
    }

    private String categoryName(int id) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM categories WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private int count(String sql) throws Exception {
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
