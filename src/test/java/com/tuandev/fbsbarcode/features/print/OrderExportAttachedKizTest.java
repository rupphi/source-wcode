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

    private static Order order() {
        Order order = new Order(1L, null, "Brand", "Product", "L", "black", "ART", null, "old-barcode");
        order.setNmId(10L);
        order.setRequiresKiz(true);
        return order;
    }
}
