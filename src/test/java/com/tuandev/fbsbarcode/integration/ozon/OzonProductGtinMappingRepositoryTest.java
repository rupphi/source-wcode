package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonProductGtinMappingRepositoryTest {
    private static final String FIRST_GTIN = "04645588781154";
    private static final String SECOND_GTIN = "04645588781161";

    @TempDir
    Path appData;

    private OzonProductGtinMappingRepository mappings;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES"
                    + "(1,'" + FIRST_GTIN + "','Black suit','Clothing','2026-08-19T00:00:00Z'),"
                    + "(1,'" + SECOND_GTIN + "','Blue suit','Clothing','2026-08-19T00:00:00Z')");
        }
        new OzonCatalogRepository().upsertPage(1, List.of(
                product("101", "SKU-A", "offer-a"),
                product("102", "SKU-B", "offer-b"),
                product("103", "SKU-C", "offer-c")), "cursor");
        mappings = new OzonProductGtinMappingRepository();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void replaceForGtinMapsSelectedCatalogSkusAndRemovesDeselectedOnes() {
        mappings.put(1, "SKU-A", FIRST_GTIN);
        mappings.put(1, "SKU-B", SECOND_GTIN);

        mappings.replaceForGtin(1, FIRST_GTIN, List.of("SKU-B", "SKU-C"));

        assertEquals(Map.of("SKU-B", FIRST_GTIN, "SKU-C", FIRST_GTIN), mappings.findAll(1));
        assertEquals(2, new KizMappingRepository().findGtinSummaries(1).stream()
                .filter(summary -> FIRST_GTIN.equals(summary.gtin()))
                .findFirst().orElseThrow().mappingRuleCount());
    }

    @Test
    void replaceForGtinRejectsSkuOutsideTheOzonCatalog() {
        assertThrows(IllegalArgumentException.class,
                () -> mappings.replaceForGtin(1, FIRST_GTIN, List.of("UNKNOWN-SKU")));
    }

    private static OzonProductDto product(String productId, String sku, String offerId) {
        return new OzonProductDto(productId, offerId, sku, "Product " + sku, "", false, "", List.of());
    }
}
