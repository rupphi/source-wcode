package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategoryWorkflowTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void initDatabaseBackfillsExistingCategoriesForExistingShops() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, api_key TEXT NOT NULL)");
            st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'One', 'token'), (2, 'Two', 'token')");
            st.execute("INSERT INTO categories(id, name) VALUES (10, 'Shoes'), (20, 'Bags')");
        }

        Database.initDatabase();

        assertEquals(4, count("SELECT COUNT(*) FROM shop_categories"));
    }

    @Test
    void initDatabaseDoesNotBackfillAgainWhenShopCategoryTableAlreadyExists() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertShop(2);
        insertCategory(10, "Shoes");
        attachCategory(1, 10);

        Database.initDatabase();

        assertEquals(1, count("SELECT COUNT(*) FROM shop_categories"));
        assertEquals(0, count("SELECT COUNT(*) FROM shop_categories WHERE shop_id = 2 AND category_id = 10"));
    }

    @Test
    void loadCategoriesReturnsOnlyCategoriesAttachedToShopWithShopKizCount() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertShop(2);
        insertCategory(10, "Shoes");
        insertCategory(20, "Bags");
        attachCategory(1, 10);
        attachCategory(2, 20);
        insertKiz(1, 10, "shop-one");
        insertKiz(2, 10, "other-shop-hidden-category");
        insertKiz(2, 20, "shop-two");

        List<Category> categories = new CategoryWorkflow().loadCategories(1);

        assertEquals(1, categories.size());
        assertEquals(10, categories.getFirst().getId());
        assertEquals(1, categories.getFirst().getDisplayId());
        assertEquals(1, categories.getFirst().getCountKiz());
    }

    @Test
    void loadCategoriesAssignsShopLocalDisplayIdsFromOne() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertShop(2);
        insertCategory(10, "Shoes");
        insertCategory(20, "Bags");
        insertCategory(30, "Hats");
        attachCategory(1, 10);
        attachCategory(1, 30);
        attachCategory(2, 20);

        List<Category> categories = new CategoryWorkflow().loadCategories(1);

        assertEquals(List.of(10, 30), categories.stream().map(Category::getId).toList());
        assertEquals(List.of(1, 2), categories.stream().map(Category::getDisplayId).toList());
    }

    @Test
    void createCategoryAttachesOnlyCurrentShop() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertShop(2);

        CategoryWorkflow workflow = new CategoryWorkflow();
        workflow.createCategory(shop(1), new Category(10, "Shoes"));

        assertEquals(1, count("SELECT COUNT(*) FROM shop_categories WHERE shop_id = 1 AND category_id = 10"));
        assertEquals(0, count("SELECT COUNT(*) FROM shop_categories WHERE shop_id = 2 AND category_id = 10"));
    }

    @Test
    void createCategoryCanAutoAssignGlobalIdWhileDisplayIdStaysShopLocal() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertCategory(10, "Shoes");
        attachCategory(1, 10);

        CategoryWorkflow workflow = new CategoryWorkflow();
        workflow.createCategory(shop(1), new Category(0, "Bags"));

        List<Category> categories = workflow.loadCategories(1);
        assertEquals(List.of(10, 11), categories.stream().map(Category::getId).toList());
        assertEquals(List.of(1, 2), categories.stream().map(Category::getDisplayId).toList());
    }

    @Test
    void clearKizCountDeletesOnlyCurrentShopKizRowsAndKeepsCategoryVisible() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertShop(2);
        insertCategory(10, "Shoes");
        attachCategory(1, 10);
        attachCategory(2, 10);
        insertKiz(1, 10, "one");
        insertKiz(2, 10, "two");

        CategoryWorkflow workflow = new CategoryWorkflow();
        workflow.clearKizCount(shop(1), new Category(10, "Shoes", 1));

        assertEquals(0, count("SELECT COUNT(*) FROM kizs WHERE shop_id = 1 AND category_id = 10"));
        assertEquals(1, count("SELECT COUNT(*) FROM kizs WHERE shop_id = 2 AND category_id = 10"));
        assertEquals(1, workflow.loadCategories(1).size());
    }

    @Test
    void deleteCategoryRemovesOnlySelectedShopReferenceAndDeletesGlobalCategoryWhenUnreferenced() throws Exception {
        initializeEmptyDatabase();
        insertShop(1);
        insertShop(2);
        insertCategory(10, "Shoes");
        insertCategory(20, "Bags");
        attachCategory(1, 10);
        attachCategory(2, 10);
        attachCategory(1, 20);
        insertKiz(1, 10, "one");
        insertKiz(2, 10, "two");
        insertKiz(1, 20, "bag");

        CategoryWorkflow workflow = new CategoryWorkflow();
        workflow.deleteCategory(shop(1), new Category(10, "Shoes"));

        assertEquals(0, count("SELECT COUNT(*) FROM shop_categories WHERE shop_id = 1 AND category_id = 10"));
        assertEquals(1, count("SELECT COUNT(*) FROM shop_categories WHERE shop_id = 2 AND category_id = 10"));
        assertEquals(1, count("SELECT COUNT(*) FROM categories WHERE id = 10"));
        assertEquals(0, count("SELECT COUNT(*) FROM kizs WHERE shop_id = 1 AND category_id = 10"));
        assertEquals(1, count("SELECT COUNT(*) FROM kizs WHERE shop_id = 2 AND category_id = 10"));

        workflow.deleteCategory(shop(1), new Category(20, "Bags"));

        assertEquals(0, count("SELECT COUNT(*) FROM categories WHERE id = 20"));
    }

    private void initializeEmptyDatabase() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
    }

    private Shop shop(int id) {
        return new Shop(id, "Shop " + id, "token");
    }

    private void insertShop(int id) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO shops(id, name, api_key) VALUES (?, ?, 'token')")) {
            ps.setInt(1, id);
            ps.setString(2, "Shop " + id);
            ps.executeUpdate();
        }
    }

    private void insertCategory(int id, String name) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO categories(id, name) VALUES (?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void attachCategory(int shopId, int categoryId) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO shop_categories(shop_id, category_id, created_at)
                     VALUES (?, ?, '2026-06-09T00:00:00Z')
                     """)) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.executeUpdate();
        }
    }

    private void insertKiz(int shopId, int categoryId, String code) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO kizs(shop_id, category_id, code) VALUES (?, ?, ?)")) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.setString(3, code);
            ps.executeUpdate();
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
