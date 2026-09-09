package com.tuandev.fbsbarcode.integration.znack.registration;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

class RegistrationSelectionTest {
    static Sku sku(long id, Status status) {
        return new Sku(10, id, 1, "ART", "Trousers", "Brand", "Name", "black", "L", List.of("WB" + id),
                "", true, null, null, null, status, null, false);
    }
    @Test void selectsAcrossPagesAndRejectsExistingCards() {
        var selection = new RegistrationSelection();
        selection.reset(1, "filter");
        selection.selectAll(List.of(sku(1, Status.NOT_CREATED), sku(2, Status.PUBLISHED)));
        selection.selectAll(List.of(sku(3, Status.NOT_CREATED), sku(4, Status.PROCESSING)));
        assertEquals(List.of(1L, 3L), selection.snapshot().stream().map(Sku::chrtId).toList());
        selection.set(sku(1, Status.NOT_CREATED), false);
        assertEquals(1, selection.snapshot().size());
        selection.reset(1, "filter");
        assertEquals(1, selection.snapshot().size());
        selection.reset(2, "filter");
        assertTrue(selection.snapshot().isEmpty());
    }
    @Test void clearsWhenFiltersChangeAndDropsRowsNoLongerEligible() {
        var selection = new RegistrationSelection();
        selection.reset(1, "all");
        selection.selectAll(List.of(sku(1, Status.NOT_CREATED), sku(2, Status.NOT_CREATED)));
        selection.retainEligible(List.of(sku(1, Status.PROCESSING), sku(2, Status.NOT_CREATED)));
        assertEquals(List.of(2L), selection.snapshot().stream().map(Sku::chrtId).toList());
        selection.reset(1, "search");
        assertTrue(selection.snapshot().isEmpty());
    }
}
