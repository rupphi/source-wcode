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
}
