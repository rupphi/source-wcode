package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WbOrderSyncServiceTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path appData;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','token')");
            statement.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,synced_at) VALUES(1,'WB-NEW',0,0,'now')");
            statement.execute("INSERT INTO wb_orders(shop_id,order_id,supply_id,synced_at) VALUES(1,9101,'WB-OLD','now')");
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void supplySyncBackfillsMissingOrderDetailsAndReconcilesMembership() throws Exception {
        WbApiClient api = new WbApiClient() {
            @Override
            public WbSupplyOrderIdsResponse getSupplyOrderIds(String apiKey, String supplyId) {
                return GSON.fromJson("{\"orderIds\":[9101,9102]}", WbSupplyOrderIdsResponse.class);
            }

            @Override
            public WbOrdersResponse getOrders(String apiKey, long next, int limit, Long dateFrom, Long dateTo) {
                return GSON.fromJson("""
                        {"next":0,"orders":[
                          {"id":9102,"article":"ART-NEW","createdAt":"2026-09-15T00:00:00Z","supplyId":"WB-NEW"}
                        ]}
                        """, WbOrdersResponse.class);
            }
        };
        WbOrderSyncService service = new WbOrderSyncService(api, new WbOrderRepository(),
                new WbSupplyRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());

        assertEquals(2, service.syncSupplyOrders(new Shop(1, "WB", Marketplace.WILDBERRIES, null, "token"), "WB-NEW"));

        WbOrderRepository repository = new WbOrderRepository();
        assertEquals(List.of(), repository.getMissingOrderIds(1, List.of(9101L, 9102L)));
        assertEquals(List.of(9101L, 9102L), repository.getOrderIdsForSupply(1, "WB-NEW"));
        assertEquals(2, new WbSupplyRepository().findSupplySummary(1, "WB-NEW").getItemCount());
    }
}
