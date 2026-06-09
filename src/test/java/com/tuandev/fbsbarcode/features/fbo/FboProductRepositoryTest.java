package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FboProductRepositoryTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldExposeRuSizeFromWbSizeWhileKeepingSizeFallback() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1001, 'ART-1', 'Shoes', 'Brand', 'Product', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2001, 1001, 'EU-42', '42')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2001, 'SKU-1')
                    """);
        }

        List<FboProductSku> products = new FboProductRepository().search(new FboProductSearchCriteria(1, "", List.of(), 10, 0));

        assertEquals(1, products.size());
        assertEquals("EU-42", products.getFirst().size());
        assertEquals("42", products.getFirst().ruSize());
    }
}
