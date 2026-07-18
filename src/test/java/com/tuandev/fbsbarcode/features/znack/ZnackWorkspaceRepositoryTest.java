package com.tuandev.fbsbarcode.features.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZnackWorkspaceRepositoryTest {
    private static final String GTIN_A = "04601234567890";
    private static final String GTIN_B = "04601234567891";
    private static final String GTIN_C = "04601234567892";

    @TempDir
    Path appData;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop A','secret-a')");
        execute("INSERT INTO shops(id,name,api_key) VALUES(2,'Shop B','secret-b')");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void pagesAndFiltersActiveAndDeletedProductsWithoutTechnicalOrCrossShopRows() throws Exception {
        product(1, GTIN_A, "100% Cotton", "Shoes", "6403", null);
        product(1, GTIN_B, "Boot B", "Shoes", "6403", null);
        product(1, GTIN_C, "Coat C", "Clothes", "6201", "2026-07-18T00:00:00Z");
        product(1, "02900699308808", "Technical", "Shoes", "", null);
        product(2, "04601234567893", "Other shop", "Shoes", "", null);
        ZnackWorkspaceRepository repository = new ZnackWorkspaceRepository();

        assertEquals(List.of("Shoes"), repository.findCategories(1, false));
        assertEquals(List.of("Clothes"), repository.findCategories(1, true));
        assertEquals(List.of(GTIN_A, GTIN_B), repository.findProductsPage(
                        1, "", List.of("Shoes"), false, 10, 0)
                .stream().map(ZnackWorkspaceRepository.ProductSummary::gtin).toList());
        assertEquals(List.of(GTIN_A), repository.findProductsPage(
                        1, "%", List.of(), false, 10, 0)
                .stream().map(ZnackWorkspaceRepository.ProductSummary::gtin).toList());
        ZnackWorkspaceRepository.ProductSummary deleted = repository.findProductsPage(
                1, "coat", List.of(), true, 10, 0).getFirst();
        assertEquals(GTIN_C, deleted.gtin());
        assertTrue(deleted.deleted());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findProductsPage(1, "", List.of(), false, 102, 0));
    }

    @Test
    void visibilityBatchAndAuditAreAtomicAndShopScoped() throws Exception {
        product(1, GTIN_A, "A", "Shoes", "", null);
        product(1, GTIN_B, "B", "Shoes", "", null);
        product(2, GTIN_C, "C", "Shoes", "", null);
        ZnackWorkspaceRepository repository = new ZnackWorkspaceRepository();

        repository.setProductVisibility(1, "Shop A", List.of(GTIN_A, GTIN_B), true);
        assertTrue(deleted(1, GTIN_A));
        assertTrue(deleted(1, GTIN_B));
        assertEquals(2, count("znack_operation_logs", "shop_id=1 AND message='HIDDEN'"));

        assertThrows(ZnackWorkspaceRepository.VisibilityConflictException.class,
                () -> repository.setProductVisibility(1, "Shop A", List.of(GTIN_A, GTIN_C), false));
        assertTrue(deleted(1, GTIN_A));
        assertFalse(deleted(2, GTIN_C));
        assertEquals(0, count("znack_operation_logs", "shop_id=1 AND message='RESTORED'"));

        repository.setProductVisibility(1, "Shop A", List.of(GTIN_A, GTIN_B), false);
        assertFalse(deleted(1, GTIN_A));
        assertFalse(deleted(1, GTIN_B));
        assertEquals(2, count("znack_operation_logs", "shop_id=1 AND message='RESTORED'"));
    }

    private void product(
            int shopId, String gtin, String name, String category, String tnVed, String deletedAt)
            throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO znack_products(
                            shop_id,gtin,product_name,category,tn_ved,cis_type,
                            good_mark_flag,good_turn_flag,readiness_checked_at,deleted_at,synced_at)
                        VALUES(?,?,?,?,?,'UNIT',1,0,'2026-07-18T00:00:00Z',?,'2026-07-18T00:00:00Z')
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            statement.setString(3, name);
            statement.setString(4, category);
            statement.setString(5, tnVed);
            statement.setString(6, deletedAt);
            statement.executeUpdate();
        }
    }

    private boolean deleted(int shopId, String gtin) throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT deleted_at IS NOT NULL FROM znack_products WHERE shop_id=? AND gtin=?")) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        }
    }

    private int count(String table, String predicate) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
