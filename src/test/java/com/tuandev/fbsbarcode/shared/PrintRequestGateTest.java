package com.tuandev.fbsbarcode.shared;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrintRequestGateTest {
    @Test void keepsShopLockedAcrossStagesAndStaleCloseCannotUnlockNextPrint() {
        var first = PrintRequestGate.tryAcquire(98123);
        assertNotNull(first);
        assertNull(PrintRequestGate.tryAcquire(98123));
        try (var other = PrintRequestGate.tryAcquire(98124)) { assertNotNull(other); }
        first.close();
        try (var next = PrintRequestGate.tryAcquire(98123)) {
            assertNotNull(next);
            first.close();
            assertNull(PrintRequestGate.tryAcquire(98123));
        }
    }
}
