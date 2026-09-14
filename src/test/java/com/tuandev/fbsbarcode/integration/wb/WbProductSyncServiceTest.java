package com.tuandev.fbsbarcode.integration.wb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WbProductSyncServiceTest {
    private static final Gson GSON = new Gson();

    @TempDir Path appData;
    private final Shop shop = new Shop(1, "WB", Marketplace.WILDBERRIES, null, "token");

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key,wb_products_cursor_updated_at,"
                    + "wb_products_cursor_nm_id) VALUES "
                    + "(1,'WB','WILDBERRIES','token','2026-09-01T00:00:00Z',999),"
                    + "(2,'Other','WILDBERRIES','other',NULL,NULL)");
        }
        WbProductRepository products = new WbProductRepository();
        products.saveProductBatch(1, List.of(card(101, "Old title"), card(202, "Deleted on WB")));
        products.saveProductBatch(2, List.of(card(202, "Other shop copy")));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void fullCatalogRefreshRemovesCardsMissingFromWbForOnlyTheSelectedShop() throws Exception {
        RecordingApi api = new RecordingApi(response(List.of(card(101, "Current title"))));
        WbProductSyncService service = new WbProductSyncService(
                api, new WbProductRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());

        service.sync(shop);

        assertNull(api.firstUpdatedAtCursor);
        assertNull(api.firstNmIdCursor);
        assertEquals(1, count("SELECT COUNT(*) FROM wb_product_cards WHERE shop_id=1 AND nm_id=101"));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_product_cards WHERE shop_id=1 AND nm_id=202"));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_product_photos WHERE shop_id=1 AND nm_id=202"));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_product_cards WHERE shop_id=2 AND nm_id=202"));
    }

    @Test
    void incompleteCatalogRefreshNeverDeletesLocalCards() throws Exception {
        WbProductCardsResponse firstPage = response(List.of(card(101, "Current title")), 100);
        WbApiClient interruptedApi = new WbApiClient() {
            private int calls;

            @Override
            public WbProductCardsResponse getProductCards(
                    String apiKey, String locale, String updatedAtCursor, Long nmIdCursor, int limit)
                    throws IOException {
                if (calls++ == 0) return firstPage;
                throw new IOException("simulated WB interruption");
            }
        };
        WbProductSyncService service = new WbProductSyncService(
                interruptedApi, new WbProductRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());

        assertThrows(IOException.class, () -> service.sync(shop));

        assertEquals(1, count("SELECT COUNT(*) FROM wb_product_cards WHERE shop_id=1 AND nm_id=202"));
    }

    @Test
    void malformedSuccessfulResponseNeverDeletesLocalCards() throws Exception {
        WbApiClient malformedApi = new WbApiClient() {
            @Override
            public WbProductCardsResponse getProductCards(
                    String apiKey, String locale, String updatedAtCursor, Long nmIdCursor, int limit) {
                return GSON.fromJson("{\"cursor\":{\"total\":0}}", WbProductCardsResponse.class);
            }
        };
        WbProductSyncService service = new WbProductSyncService(
                malformedApi, new WbProductRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());

        assertThrows(IOException.class, () -> service.sync(shop));

        assertEquals(1, count("SELECT COUNT(*) FROM wb_product_cards WHERE shop_id=1 AND nm_id=202"));
    }

    private int count(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static WbProductCard card(long nmId, String title) {
        return GSON.fromJson("""
                {
                  "nmID": %d,
                  "vendorCode": "article-%d",
                  "title": "%s",
                  "photos": [{"c246x328":"https://images.example/%d.webp"}],
                  "sizes": [],
                  "characteristics": [],
                  "tags": [],
                  "createdAt": "2026-08-01T00:00:00Z",
                  "updatedAt": "2026-09-02T00:00:00Z"
                }
                """.formatted(nmId, nmId, title, nmId), WbProductCard.class);
    }

    private static WbProductCardsResponse response(List<WbProductCard> cards) {
        return response(cards, 1);
    }

    private static WbProductCardsResponse response(List<WbProductCard> cards, int total) {
        return GSON.fromJson("""
                {"cards":%s,"cursor":{"updatedAt":"2026-09-02T00:00:00Z","nmID":101,"total":%d}}
                """.formatted(GSON.toJson(cards), total), WbProductCardsResponse.class);
    }

    private static final class RecordingApi extends WbApiClient {
        private final WbProductCardsResponse response;
        private String firstUpdatedAtCursor;
        private Long firstNmIdCursor;

        private RecordingApi(WbProductCardsResponse response) {
            this.response = response;
        }

        @Override
        public WbProductCardsResponse getProductCards(
                String apiKey, String locale, String updatedAtCursor, Long nmIdCursor, int limit)
                throws IOException {
            firstUpdatedAtCursor = updatedAtCursor;
            firstNmIdCursor = nmIdCursor;
            return response;
        }
    }
}
