package com.tuandev.fbsbarcode.integration.znack.registration;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPublicationTest {
    private static final String CARD = """
            {"result":[{"good_id":9,"identified_by":[{"type":"gtin","value":"4631993764363"}],
            "producer_inn":"1234567890","good_signed":true,"good_status":"published",
            "good_detailed_status":["published"],"good_mark_flag":true,"good_turn_flag":true}]}
            """;
    @Test void requiresMatchingOwnerAndSignedPublishedState() {
        var state = RegistrationPublication.parse(JsonParser.parseString(CARD), "04631993764363", "1234567890");
        assertTrue(state.published()); assertTrue(state.readyForKiz()); assertEquals(9L, state.goodId());
        assertThrows(IllegalArgumentException.class, () -> RegistrationPublication.parse(JsonParser.parseString(CARD), "04631993764363", "other"));
        assertThrows(IllegalArgumentException.class, () -> RegistrationPublication.parse(JsonParser.parseString(CARD), "04689039063727", "1234567890"));
    }
    @Test void unsignedArchivedAndUnknownAreNeverPublished() {
        assertFalse(RegistrationPublication.parse(JsonParser.parseString(CARD.replace("\"good_signed\":true", "\"good_signed\":false")), "04631993764363", "1234567890").published());
        assertFalse(RegistrationPublication.parse(JsonParser.parseString(CARD.replace("[\"published\"]", "[\"published\",\"archived\"]")), "04631993764363", "1234567890").published());
        assertFalse(RegistrationPublication.parse(JsonParser.parseString(CARD.replace("published", "unknown")), "04631993764363", "1234567890").published());
        assertFalse(RegistrationPublication.parse(JsonParser.parseString(CARD.replace("\"good_turn_flag\":true", "\"good_turn_flag\":false")), "04631993764363", "1234567890").readyForKiz());
    }
}
