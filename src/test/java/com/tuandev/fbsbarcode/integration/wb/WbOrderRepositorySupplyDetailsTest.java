package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbOrderRepositorySupplyDetailsTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearAppDataOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldResolveSupplyProductInfoFromSkuWhenOrderMetadataIsMissing() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-1', 'now')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1001, 'ART-1', 'Shoes', 'Brand A', 'Product A', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2001, 1001, '42', '42')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2001, 'SKU-1')
                    """);
            st.execute("""
                    INSERT INTO wb_product_photos(shop_id, nm_id, photo_index, c246x328_url)
                    VALUES (1, 1001, 0, 'image-url')
                    """);
            st.execute("""
                    INSERT INTO wb_product_characteristics(shop_id, nm_id, characteristic_id, name, value_json)
                    VALUES (1, 1001, 14177449, 'Color', '["Black"]')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, article, created_at, synced_at)
                    VALUES (1, 5001, 'ART-1', '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_order_skus(shop_id, order_id, sku)
                    VALUES (1, 5001, 'SKU-1')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-1', 5001)
                    """);
            st.execute("""
                    INSERT INTO wb_order_meta_requirements(shop_id, order_id, meta_key, requirement_type)
                    VALUES (1, 5001, 'sgtin', 'optional')
                    """);
        }

        List<Order> orders = new WbOrderRepository().getOrdersForSupply(1, "WB-GI-1");

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(1001L, order.getNmId());
        assertEquals("Brand A", order.getBrand());
        assertEquals("Product A", order.getName());
        assertEquals("Shoes", order.getSubjectName());
        assertEquals("42", order.getSize());
        assertEquals("Black", order.getColor());
        assertEquals("ART-1", order.getArticle());
        assertEquals("SKU-1", order.getBarcode());
        assertEquals("image-url", order.getImageUrl());
        assertTrue(order.isRequiresKiz());
    }

    @Test
    void shouldResolveSupplyProductInfoFromArticleWhenSkuIsMissing() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-2', 'now')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1002, 'ART-2', 'Bags', 'Brand B', 'Product B', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2002, 1002, '0', '0')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2002, 'SKU-2')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, article, created_at, synced_at)
                    VALUES (1, 5002, 'ART-2', '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-2', 5002)
                    """);
            st.execute("""
                    INSERT INTO wb_order_meta_requirements(shop_id, order_id, meta_key, requirement_type)
                    VALUES (1, 5002, 'uin', 'required')
                    """);
        }

        List<Order> orders = new WbOrderRepository().getOrdersForSupply(1, "WB-GI-2");

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(1002L, order.getNmId());
        assertEquals("Brand B", order.getBrand());
        assertEquals("Product B", order.getName());
        assertEquals("Bags", order.getSubjectName());
        assertEquals("0", order.getSize());
        assertEquals("ART-2", order.getArticle());
        assertEquals("SKU-2", order.getBarcode());
        assertFalse(order.isRequiresKiz());
    }

    @Test
    void shouldResolveSupplyProductInfoFromSkuWhenOrderMetadataDoesNotMatchLocalProduct() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-3', 'now')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1003, 'ART-3', 'Clothes', 'Brand C', 'Product C', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2003, 1003, 'M', 'M')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2003, 'SKU-3')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, nm_id, chrt_id, article, created_at, synced_at)
                    VALUES (1, 5003, 9999, 9999, 'ART-3', '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_order_skus(shop_id, order_id, sku)
                    VALUES (1, 5003, 'SKU-3')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-3', 5003)
                    """);
        }

        List<Order> orders = new WbOrderRepository().getOrdersForSupply(1, "WB-GI-3");

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(1003L, order.getNmId());
        assertEquals("Brand C", order.getBrand());
        assertEquals("Product C", order.getName());
        assertEquals("Clothes", order.getSubjectName());
        assertEquals("M", order.getSize());
        assertEquals("ART-3", order.getArticle());
        assertEquals("SKU-3", order.getBarcode());
    }
}
