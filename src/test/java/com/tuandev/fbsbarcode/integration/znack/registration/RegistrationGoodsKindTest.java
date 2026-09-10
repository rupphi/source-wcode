package com.tuandev.fbsbarcode.integration.znack.registration;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

class RegistrationGoodsKindTest {
    private Attribute attribute() { return new Attribute(12, "Вид товара", "string", true, false, false, false, List.of("ЛОСИНЫ", "БРЮКИ"), List.of("")); }
    private RegistrationDraftPreparer.Prepared prepared(String tnved, long category) {
        var sku = RegistrationSelectionTest.sku(1, Status.NOT_CREATED);
        var draft = new Draft(tnved, "6104", category, "Name", "Brand", Map.of(35L,"XXL"), Map.of(35L,"INT"));
        return new RegistrationDraftPreparer.Prepared(sku, draft, List.of(),
                new RegistrationDraftPreparer.KindGroup(sku.subjectId(), sku.subjectName(), tnved, category, "Category", attribute()));
    }
    @Test void groupsSeparateExactTnvedAndCategory() {
        assertEquals(prepared("6104630000",1).kindGroup(), prepared("6104630000",1).kindGroup());
        assertNotEquals(prepared("6104630000",1).kindGroup(), prepared("6104690000",1).kindGroup());
        assertNotEquals(prepared("6104630000",1).kindGroup(), prepared("6104630000",2).kindGroup());
    }
    @Test void acceptsOnlyExactUpstreamChoiceAndPreservesOtherFields() {
        var input = prepared("6104630000",1);
        assertFalse(input.draft().attributes().containsKey(12L));
        assertThrows(IllegalArgumentException.class, () -> input.chooseKind("Леггинсы"));
        assertThrows(IllegalArgumentException.class, () -> input.chooseKind(""));
        var selected = input.chooseKind("ЛОСИНЫ");
        assertEquals("ЛОСИНЫ", selected.draft().attributes().get(12L));
        assertEquals("XXL", selected.draft().attributes().get(35L));
        assertEquals("", selected.draft().attributeTypes().get(12L));
        assertEquals(input.draft().tnved(), selected.draft().tnved());
    }
    @Test void noPresetsCannotBeReplacedWithAnInventedValue() {
        var input = prepared("6104630000", 1);
        var group = input.kindGroup();
        var empty = new Attribute(12, "Вид товара", "string", true, false, false, false, List.of(), List.of(""));
        var unavailable = new RegistrationDraftPreparer.Prepared(input.sku(), input.draft(), List.of(),
                new RegistrationDraftPreparer.KindGroup(group.subjectId(), group.subjectName(), group.tnved(),
                        group.categoryId(), group.categoryName(), empty));
        assertTrue(unavailable.kindOptions().isEmpty());
        assertNotEquals(input.kindGroup(), unavailable.kindGroup());
        assertThrows(IllegalArgumentException.class, () -> unavailable.chooseKind("БРЮКИ"));
    }
}
