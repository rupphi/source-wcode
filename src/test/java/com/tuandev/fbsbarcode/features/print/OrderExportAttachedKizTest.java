package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OrderExportAttachedKizTest {
    @TempDir Path directory;
    private Shop shop;
    @BeforeEach void setup() throws Exception {
        System.setProperty("wcode.appdata.dir", directory.toString());
        Database.initDatabase();
        shop = new Shop(1, "WB test", "test");
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB test','test')");
            statement.execute("""
                    INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,vendor_code,source_barcode,
                        status,created_at,updated_at) VALUES(1,20,10,'ART','old-barcode','PROCESSING','','')
                    """);
        }
    }
    @AfterEach void cleanup() { System.clearProperty("wcode.appdata.dir"); Thread.interrupted(); }

    @Test void pendingRegistrationDoesNotBlockAnOrderAlreadyHavingAppliedKiz() {
        String applied = "010463199376436321SERIAL1234567\u001d91TEST\u001d92SIGNATURE";
        var workflow = new OrderExportWorkflow((token, ids) -> Map.of(1L, new KizService.SgtinMetadata(true, applied)));
        assertDoesNotThrow(() -> workflow.prepareExplicitPrint(List.of(order()), shop));
    }

    @Test void pendingRegistrationStillBlocksNewKizAssignmentWithoutAnAppliedCode() {
        var workflow = new OrderExportWorkflow((token, ids) -> Map.of(1L, new KizService.SgtinMetadata(true, null)));
        var error = assertThrows(IllegalStateException.class, () -> workflow.prepareExplicitPrint(List.of(order()), shop));
        assertTrue(error.getMessage().contains("awaiting signed publication"));
    }

    @Test void cancelledPreparationDoesNotStartMetadataOrReservationWork() {
        var workflow = new OrderExportWorkflow((token, ids) -> {
            throw new AssertionError("Cancelled print must not perform metadata reads");
        });
        Thread.currentThread().interrupt();
        assertThrows(java.io.IOException.class, () -> workflow.prepareExplicitPrint(List.of(order()), shop));
    }

    @Test void pendingRegistrationFallsBackToCategoryMappingWhenAvailable() throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,subject_name,synced_at) VALUES(1,10,'ART','Clothes','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES(1,'04630000000001','Old Product','Clothes','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO znack_gtin_mapping_rules(shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at) VALUES(1,'04630000000001','Clothes','*',1,'2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'04630000000001',1,'COMPLETED','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(101,1,1,'010463000000000121SERIAL\\u001d91TEST\\u001d92SIG','KIZ-1','04630000000001','AVAILABLE','IN_CIRCULATION','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
        }
        var workflow = new OrderExportWorkflow((token, ids) -> Map.of(1L, new KizService.SgtinMetadata(true, null)));
        try (var prepared = workflow.reserveExplicitPrint(List.of(order()), shop)) {
            assertEquals("010463000000000121SERIAL\\u001d91TEST\\u001d92SIG", prepared.orders().getFirst().getKiz());
        }
    }

    @Test void publishedRegistrationOverridesCategoryMappingEvenWithoutWbUpdate() throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,subject_name,synced_at) VALUES(1,10,'ART','Clothes','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES(1,'04630000000001','Old Product','Clothes','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO znack_gtin_mapping_rules(shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at) VALUES(1,'04630000000001','Clothes','*',1,'2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'04630000000001',1,'COMPLETED','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(101,1,1,'010463000000000121SERIAL\\u001d91TEST\\u001d92SIG','KIZ-1','04630000000001','AVAILABLE','IN_CIRCULATION','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");

            statement.execute("UPDATE znack_card_registrations SET status='PUBLISHED', gtin='04630000000002', wb_updated=0 WHERE nm_id=10");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES(1,'04630000000002','New Product','Clothes','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(2,1,'04630000000002',1,'COMPLETED','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(102,1,2,'010463000000000221SERIAL\\u001d91TEST\\u001d92SIG','KIZ-2','04630000000002','AVAILABLE','IN_CIRCULATION','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
        }
        var workflow = new OrderExportWorkflow((token, ids) -> Map.of(1L, new KizService.SgtinMetadata(true, null)));
        try (var prepared = workflow.reserveExplicitPrint(List.of(order()), shop)) {
            assertEquals("010463000000000221SERIAL\\u001d91TEST\\u001d92SIG", prepared.orders().getFirst().getKiz());
        }
    }

    private static Order order() {
        Order order = new Order(1L, null, "Brand", "Product", "L", "black", "ART", null, "old-barcode");
        order.setNmId(10L);
        order.setRequiresKiz(true);
        return order;
    }
}
