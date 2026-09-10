package com.tuandev.fbsbarcode.integration.znack.registration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WbPrintDemandTest {
    @Test void buysOnlyDeficitAndNeverRebuysWhilePreviousCodesArePending() {
        assertEquals(3, WbPrintDemand.missing(5, 2, false));
        assertEquals(0, WbPrintDemand.missing(5, 2, true));
        assertEquals(0, WbPrintDemand.missing(5, 7, false));
        assertThrows(IllegalArgumentException.class, () -> WbPrintDemand.missing(-1, 0, false));
    }

    @Test void outstandingPipelineNullDoesNotThrowNpeWhenOwnIsNull(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        try {
            com.tuandev.fbsbarcode.config.Database.initDatabase();
            var store = new WbPrintDemandStore();
            assertNull(store.outstandingPipeline(1, "04600000000000"));
            com.tuandev.fbsbarcode.integration.znack.ZnackPurchasePipelineState own = null;
            Long outstanding = own != null ? Long.valueOf(own.id()) : store.outstandingPipeline(1, "04600000000000");
            assertNull(outstanding);
        } finally {
            System.clearProperty("wcode.appdata.dir");
        }
    }

    @Test
    void awaitAvailableAuthorizesShopInSigningSession(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        try {
            com.tuandev.fbsbarcode.config.Database.initDatabase();
            com.tuandev.fbsbarcode.integration.znack.ZnackSigningSession.resetForTests();
            int shopId = 4242;
            String gtin = "04600000000000";
            var shop = new com.tuandev.fbsbarcode.models.Shop(shopId, "WB Shop", "test-api-key");

            assertFalse(com.tuandev.fbsbarcode.integration.znack.ZnackSigningSession.isShopAuthorized(shopId));

            try (java.sql.Connection c = com.tuandev.fbsbarcode.config.Database.getConnection();
                 java.sql.Statement st = c.createStatement()) {
                st.executeUpdate("INSERT OR IGNORE INTO shops (id, name, marketplace, api_key) " +
                        "VALUES (" + shopId + ", 'WB Shop', 'WILDBERRIES', 'key')");
                st.executeUpdate("INSERT OR IGNORE INTO znack_products (shop_id, gtin, synced_at) " +
                        "VALUES (" + shopId + ", '" + gtin + "', '2026-01-01')");
                st.executeUpdate("INSERT OR IGNORE INTO kiz_orders (id, shop_id, gtin, quantity, local_status, created_at, updated_at) " +
                        "VALUES (1, " + shopId + ", '" + gtin + "', 1, 'COMPLETED', '2026-01-01', '2026-01-01')");
                st.executeUpdate("INSERT INTO kiz_codes (shop_id, order_id, raw_code, display_code, gtin, status, legal_status, created_at, updated_at) " +
                        "VALUES (" + shopId + ", 1, '010460000000000021TEST123', 'TEST123', '" + gtin + "', 'AVAILABLE', 'IN_CIRCULATION', '2026-01-01', '2026-01-01')");
            }

            new WbPrintDemand().awaitAvailable(shop, gtin, 1, "test-demand-key");

            assertTrue(com.tuandev.fbsbarcode.integration.znack.ZnackSigningSession.isShopAuthorized(shopId));
        } finally {
            System.clearProperty("wcode.appdata.dir");
            com.tuandev.fbsbarcode.integration.znack.ZnackSigningSession.resetForTests();
        }
    }
}

