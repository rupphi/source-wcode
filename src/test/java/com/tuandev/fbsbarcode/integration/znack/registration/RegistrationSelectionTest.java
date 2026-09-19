package com.tuandev.fbsbarcode.integration.znack.registration;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

class RegistrationSelectionTest {
    @Test void selectAllIncludesFailedCardsWithExistingGtinAndRejectedFeed() {
        var selection = new RegistrationSelection();
        var failed = registeredSku(3, Status.ERROR);
        selection.selectAll(List.of(registeredSku(1, Status.PROCESSING), registeredSku(2, Status.PROCESSING),
                failed, registeredSku(4, Status.ERROR), registeredSku(5, Status.ERROR),
                registeredSku(6, Status.ERROR), registeredSku(7, Status.ERROR)));

        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), selection.snapshot().stream().map(Sku::chrtId).toList());
        assertEquals(failed, selection.snapshot().getFirst());
        selection.set(failed, false);
        assertFalse(selection.contains(failed));
        selection.set(failed, true);
        assertTrue(selection.contains(failed));
    }

    @Test void onlyNewAndFailedRowsAreEligibleForBulkRegistration() {
        for (Status status : Status.values()) {
            assertEquals(status == Status.NOT_CREATED || status == Status.ERROR,
                    RegistrationSelection.eligible(sku(1, status)), status.name());
            assertEquals(status == Status.ERROR,
                    RegistrationSelection.eligible(registeredSku(1, status)), status.name());
        }
        assertFalse(RegistrationSelection.eligible(null));
    }

    @Test void refreshKeepsFailedSelectionsButDropsRetriesAlreadySubmitted() {
        var selection = new RegistrationSelection();
        selection.selectAll(List.of(registeredSku(1, Status.ERROR), registeredSku(2, Status.ERROR)));
        selection.retainEligible(List.of(registeredSku(1, Status.ERROR), registeredSku(2, Status.QUEUED)));
        assertEquals(List.of(1L), selection.snapshot().stream().map(Sku::chrtId).toList());
    }

    private static Sku registeredSku(long id, Status status) {
        var base = sku(id, status);
        return new Sku(base.nmId(), id, base.subjectId(), base.vendorCode(), base.subjectName(), base.brand(),
                base.title(), base.color(), base.size(), base.barcodes(), base.imageUrl(), base.needKiz(),
                "04631993764363", 123L, "existing-feed", status, "Invalid size", false);
    }

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
