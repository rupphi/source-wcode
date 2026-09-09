package com.tuandev.fbsbarcode.integration.znack.registration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationDocumentsTest {
    @Test void keepsBothTypesAndNormalizesDates() {
        var documents = RegistrationDocuments.parse("DECL-1", "02.09.2026", "CERT-2", "2026-09-03");
        assertEquals(2, documents.size());
        assertEquals("CONFORMITY_DECLARATION", documents.get(0).type());
        assertEquals("CONFORMITY_CERTIFICATE", documents.get(1).type());
        assertEquals("2026-09-02", documents.get(0).date());
    }
    @Test void permitsCertificateOnlyButRejectsPartialAndInvalidDocuments() {
        assertEquals(1, RegistrationDocuments.parse("", "", "CERT", "2026-09-03").size());
        assertThrows(IllegalArgumentException.class, () -> RegistrationDocuments.parse("DECL", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> RegistrationDocuments.parse("", "", "CERT", "31.02.2026"));
        assertThrows(IllegalArgumentException.class, () -> RegistrationDocuments.parse("", "", "", ""));
    }
    @Test void mapsBothDocumentAttributes() {
        var sku = new ZnackCardRegistrationModels.Sku(1, 2, 3, "ART", "Брюки", "Brand", "Брюки",
                "Черный", "L", java.util.List.of(), "", true, "", null, "",
                ZnackCardRegistrationModels.Status.NOT_CREATED, "", false);
        var mapped = new ZnackWbAttributeMapper().mapDocuments(sku, java.util.List.of(), java.util.List.of(),
                "6204", "6204", RegistrationDocuments.parse("DECL", "02.09.2026", "CERT", "03.09.2026"));
        assertTrue(mapped.complete());
        assertEquals("DECL:::2026-09-02", mapped.attributes().get(23557L));
        assertEquals("CERT:::2026-09-03", mapped.attributes().get(23561L));
    }
}
