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
}

