package com.tuandev.fbsbarcode.integration.znack.registration;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WbRegisteredGtinWriterTest {
    private static final String CARD = """
            {"nmID":10,"vendorCode":"ART","brand":"Brand","title":"Trousers","description":"keep",
            "kizMarked":true,"wholesale":{"enabled":true,"quantum":2},"dimensions":{"length":3,"width":4,"height":5,"weightBrutto":0.5},
            "characteristics":[{"id":1,"name":"Color","value":["black"]}],"photos":[{"big":"keep"}],
            "sizes":[{"chrtID":1,"techSize":"L","wbSize":"48","skus":["old"]},
                     {"chrtID":2,"techSize":"XL","wbSize":"50","skus":["other"]}]}
            """;
    @Test void appendsOnlyExactSizeAndPreservesOtherEditableFields() {
        var original = JsonParser.parseString(CARD).getAsJsonObject();
        var payload = WbRegisteredGtinWriter.append(original, 10, 1, "04631993764363");
        assertEquals(1, original.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").size());
        assertEquals(2, payload.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").size());
        assertEquals(original.getAsJsonArray("sizes").get(1), payload.getAsJsonArray("sizes").get(1));
        assertEquals(original.get("wholesale"), payload.get("wholesale"));
        assertEquals(original.get("kizMarked"), payload.get("kizMarked"));
        assertEquals("keep", payload.get("description").getAsString());
        assertFalse(payload.has("photos"));
        assertEquals(payload, WbRegisteredGtinWriter.append(payload, 10, 1, "04631993764363"));
    }
    @Test void failsClosedOnMissingOrAmbiguousSizeAndWrongCard() {
        var card = JsonParser.parseString(CARD).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> WbRegisteredGtinWriter.append(card, 11, 1, "04631993764363"));
        assertThrows(IllegalArgumentException.class, () -> WbRegisteredGtinWriter.append(card, 10, 3, "04631993764363"));
        card.getAsJsonArray("sizes").add(card.getAsJsonArray("sizes").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> WbRegisteredGtinWriter.append(card, 10, 1, "04631993764363"));
    }
    @Test void refusesPartialCardToAvoidOverwritingEditableFieldsWithDefaults() {
        var card = JsonParser.parseString(CARD).getAsJsonObject();
        card.remove("dimensions");
        assertThrows(IllegalArgumentException.class, () -> WbRegisteredGtinWriter.append(card,10,1,"04631993764363"));
    }
}
