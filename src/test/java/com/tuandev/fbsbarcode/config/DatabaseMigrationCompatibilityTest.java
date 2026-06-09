package com.tuandev.fbsbarcode.config;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.features.kiz.CategoryWorkflow;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbOrderRepository;
import com.tuandev.fbsbarcode.integration.wb.WbOrderStatusDto;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
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
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void initDatabaseMigratesLegacyDatabaseWithoutLosingUserData() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        createLegacyDatabase();

        Database.initDatabase();

        assertEquals(1, count("SELECT COUNT(*) FROM shops"));
        assertEquals(1, count("SELECT COUNT(*) FROM categories"));
        assertEquals(1, count("SELECT COUNT(*) FROM kizs"));
        assertEquals(1, count("SELECT COUNT(*) FROM print_jobs"));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_orders"));
        assertEquals(1, count("SELECT COUNT(*) FROM shop_categories WHERE shop_id = 1 AND category_id = 10"));

        assertTrue(hasColumn("print_job_items", "ru_size"));
        assertTrue(hasColumn("shops", "wb_supplies_next"));
        assertTrue(hasColumn("shops", "wb_orders_next"));
        assertTrue(hasColumn("wb_product_cards", "wholesale_enabled"));
        assertTrue(hasColumn("wb_product_cards", "dimension_weight_brutto"));
        assertTrue(hasColumn("wb_product_photos", "hq_url"));
        assertTrue(hasColumn("wb_product_sizes", "wb_size"));
        assertTrue(hasColumn("wb_supplies", "closed_at"));
        assertTrue(hasColumn("wb_supplies", "cross_border_type"));
        assertTrue(hasColumn("wb_orders", "seller_date"));
        assertTrue(hasColumn("wb_orders", "address_full"));
    }

    @Test
    void migratedLegacyDatabaseSupportsCommonLocalUserActions() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        createLegacyDatabase();
        Database.initDatabase();

        List<Shop> shops = new ShopRepository().findAll();
        assertEquals(1, shops.size());
        Shop shop = shops.getFirst();

        CategoryWorkflow categoryWorkflow = new CategoryWorkflow();
        List<Category> categories = categoryWorkflow.loadCategories(shop.getId());
        assertEquals(1, categories.size());
        assertEquals("Legacy Category", categories.getFirst().getName());

        categoryWorkflow.createCategory(shop, new Category(0, "Created After Update"));
        assertEquals(2, categoryWorkflow.loadCategories(shop.getId()).size());

        WbSupplyRepository supplyRepository = new WbSupplyRepository();
        List<WbSupplySummary> supplies = supplyRepository.getSupplySummaries(shop.getId());
        assertEquals(1, supplies.size());
        assertEquals(1, supplies.getFirst().getItemCount());

        WbOrderRepository orderRepository = new WbOrderRepository();
        orderRepository.updateOrderStatuses(shop.getId(), List.of(status(1001L, "new", "waiting")));

        List<Order> packingOrders = orderRepository.getOrdersForPackingStatus(shop.getId(), "new");
        assertFalse(packingOrders.isEmpty());

        orderRepository.replaceSupplyOrders(shop.getId(), "WB-GI-OLD", List.of(1001L));

        List<Long> orderIds = orderRepository.getOrderIdsForSupply(shop.getId(), "WB-GI-OLD");
        assertEquals(List.of(1001L), orderIds);

        List<Order> supplyOrders = orderRepository.getOrdersForSupply(shop.getId(), "WB-GI-OLD");
        assertEquals(1, supplyOrders.size());
        assertEquals(1001L, supplyOrders.getFirst().getId());
        assertEquals("Legacy Product", supplyOrders.getFirst().getName());
    }

    private void createLegacyDatabase() throws Exception {
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE shops(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        api_key TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE categories(
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE kizs(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        code TEXT NOT NULL,
                        shop_id INTEGER NOT NULL,
                        category_id INTEGER NOT NULL,
                        FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
                        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE config(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            st.execute("""
                    CREATE TABLE app_config(
                        key TEXT PRIMARY KEY,
                        value TEXT
                    )
                    """);
            st.execute("""
                    CREATE TABLE print_jobs(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id INTEGER NOT NULL,
                        shop_name TEXT,
                        supply_id TEXT,
                        supply_name TEXT,
                        printed_at TEXT NOT NULL,
                        item_count INTEGER NOT NULL,
                        template_id INTEGER,
                        template_name TEXT,
                        template_layout_json TEXT NOT NULL,
                        status TEXT NOT NULL,
                        error_message TEXT,
                        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE print_job_items(
                        print_job_id INTEGER NOT NULL,
                        sort_index INTEGER NOT NULL,
                        order_id INTEGER NOT NULL,
                        brand TEXT,
                        name TEXT,
                        subject_name TEXT,
                        size TEXT,
                        color TEXT,
                        article TEXT,
                        barcode TEXT,
                        sticker TEXT,
                        sticker_code TEXT,
                        kiz TEXT,
                        image_cache_key TEXT,
                        PRIMARY KEY (print_job_id, sort_index),
                        FOREIGN KEY (print_job_id) REFERENCES print_jobs(id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE wb_product_cards(
                        shop_id INTEGER NOT NULL,
                        nm_id INTEGER NOT NULL,
                        imt_id INTEGER,
                        nm_uuid TEXT,
                        subject_id INTEGER,
                        subject_name TEXT,
                        vendor_code TEXT,
                        kiz_marked INTEGER,
                        need_kiz INTEGER,
                        brand TEXT,
                        title TEXT,
                        description TEXT,
                        video_url TEXT,
                        is_swatch_try_on INTEGER,
                        created_at TEXT,
                        updated_at TEXT,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY (shop_id, nm_id),
                        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE wb_product_photos(
                        shop_id INTEGER NOT NULL,
                        nm_id INTEGER NOT NULL,
                        photo_index INTEGER NOT NULL,
                        big_url TEXT,
                        c246x328_url TEXT,
                        c516x688_url TEXT,
                        square_url TEXT,
                        tm_url TEXT,
                        PRIMARY KEY (shop_id, nm_id, photo_index),
                        FOREIGN KEY (shop_id, nm_id) REFERENCES wb_product_cards(shop_id, nm_id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE wb_product_sizes(
                        shop_id INTEGER NOT NULL,
                        chrt_id INTEGER NOT NULL,
                        nm_id INTEGER NOT NULL,
                        tech_size TEXT,
                        PRIMARY KEY (shop_id, chrt_id),
                        FOREIGN KEY (shop_id, nm_id) REFERENCES wb_product_cards(shop_id, nm_id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE wb_supplies(
                        shop_id INTEGER NOT NULL,
                        supply_id TEXT NOT NULL,
                        is_b2b INTEGER,
                        done INTEGER,
                        order_count INTEGER,
                        created_at TEXT,
                        reject_dt TEXT,
                        name TEXT,
                        recommended_wh_id INTEGER,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY (shop_id, supply_id),
                        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE wb_orders(
                        shop_id INTEGER NOT NULL,
                        order_id INTEGER NOT NULL,
                        order_uid TEXT,
                        rid TEXT,
                        supply_id TEXT,
                        delivery_type TEXT,
                        ddate TEXT,
                        comment TEXT,
                        user_id INTEGER,
                        article TEXT,
                        color_code TEXT,
                        warehouse_id INTEGER,
                        office_id INTEGER,
                        nm_id INTEGER,
                        chrt_id INTEGER,
                        price INTEGER,
                        final_price INTEGER,
                        created_at TEXT,
                        synced_at TEXT NOT NULL,
                        is_cancellable INTEGER,
                        PRIMARY KEY (shop_id, order_id),
                        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);

            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Legacy Shop', 'token')");
            st.execute("INSERT INTO categories(id, name) VALUES (10, 'Legacy Category')");
            st.execute("INSERT INTO kizs(id, code, shop_id, category_id) VALUES (1, 'legacy-kiz', 1, 10)");
            st.execute("""
                    INSERT INTO print_jobs(id, shop_id, shop_name, printed_at, item_count, template_layout_json, status)
                    VALUES (1, 1, 'Legacy Shop', '2026-06-01T00:00:00Z', 1, '{}', 'DONE')
                    """);
            st.execute("""
                    INSERT INTO print_job_items(print_job_id, sort_index, order_id, name, size)
                    VALUES (1, 0, 1001, 'Legacy Item', 'M')
                    """);
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, title, synced_at)
                    VALUES (1, 2001, 'Legacy Product', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_photos(shop_id, nm_id, photo_index, c246x328_url)
                    VALUES (1, 2001, 0, 'image-url')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size)
                    VALUES (1, 3001, 2001, 'M')
                    """);
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, done, order_count, synced_at)
                    VALUES (1, 'WB-GI-OLD', 0, 1, 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, user_id, article, nm_id, chrt_id, price, final_price, created_at, synced_at)
                    VALUES (1, 1001, 12345, 'LEGACY-ART', 2001, 3001, 10000, 10000, '2026-06-01T00:00:00Z', 'now')
                    """);
        }
    }

    private boolean hasColumn(String tableName, String columnName) throws Exception {
        try (Connection conn = Database.getConnection();
             ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private int count(String sql) throws Exception {
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private WbOrderStatusDto status(long orderId, String supplierStatus, String wbStatus) {
        return GSON.fromJson("""
                {
                  "id": %d,
                  "supplierStatus": "%s",
                  "wbStatus": "%s",
                  "isCancellable": true
                }
                """.formatted(orderId, supplierStatus, wbStatus), WbOrderStatusDto.class);
    }
}
