package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSigningResult;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAutoSignTest {
    private static final String GTIN = "04631993764363";
    private final AtomicInteger signatures = new AtomicInteger();
    private final AtomicInteger submissions = new AtomicInteger();
    private String document = "{\"result\":{\"xmls\":[{\"goodId\":9,\"xml\":\"<card>Джинсы</card>\"}]}}";
    private boolean timeout;
    private String signedResponse = "{\"result\":{\"signed\":[9]}}";

    private ZnackNationalCatalogService catalog() {
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement nationalCatalogSigningDocument(String base, String token, JsonObject request) {
                assertEquals(GTIN, request.getAsJsonArray("gtins").get(0).getAsString());
                return JsonParser.parseString(document);
            }
            @Override public JsonElement signNationalCatalogProduct(String base, String token, JsonArray batch) throws java.io.IOException {
                submissions.incrementAndGet();
                assertEquals(9, batch.get(0).getAsJsonObject().get("goodId").getAsLong());
                assertEquals("<card>Джинсы</card>", new String(Base64.getDecoder().decode(
                        batch.get(0).getAsJsonObject().get("base64Xml").getAsString()), StandardCharsets.UTF_8));
                if (timeout) throw new java.net.SocketTimeoutException("timeout");
                return JsonParser.parseString(signedResponse);
            }
        };
        return new ZnackNationalCatalogService(api, null, (bytes, context) -> {
            signatures.incrementAndGet();
            return new CryptoProSigningResult(new byte[]{1,2,3}, "");
        }, ZnackModels.Settings.empty());
    }
    private RegistrationPublication state(String status) {
        return RegistrationPublication.parse(JsonParser.parseString("""
                {"result":[{"good_id":9,"identified_by":[{"type":"gtin","value":"04631993764363"}],
                "producer_inn":"1234567890","good_signed":false,"good_status":"%s",
                "good_detailed_status":["%s"]}]}
                """.formatted(status, status)), GTIN, "1234567890");
    }
    @Test void approvedUnsignedCardsAreAutomaticallySignedWithoutAnOptIn() throws Exception {
        for (String status : new String[]{"moderation", "draft", "errors", "archived", "published"})
            assertFalse(RegistrationAutoSigner.signIfReady(state(status), catalog(), "token", GTIN, () -> {}));
        assertEquals(0, signatures.get());
        assertTrue(RegistrationAutoSigner.signIfReady(state("notsigned"), catalog(), "token", GTIN, () -> {}));
        assertEquals(1, signatures.get()); assertEquals(1, submissions.get());
    }
    @Test void rejectsWrongDocumentBeforeSigning() {
        document = document.replace("\"goodId\":9", "\"goodId\":10");
        assertThrows(IllegalArgumentException.class, () -> RegistrationAutoSigner.signIfReady(
                state("notsigned"), catalog(), "token", GTIN, () -> {}));
        assertEquals(0, signatures.get()); assertEquals(0, submissions.get());
    }
    @Test void changingIdentityAfterDocumentFetchPreventsSignature() {
        assertThrows(IllegalStateException.class, () -> RegistrationAutoSigner.signIfReady(
                state("notsigned"), catalog(), "token", GTIN, () -> { throw new IllegalStateException("Identity changed"); }));
        assertEquals(0, signatures.get()); assertEquals(0, submissions.get());
    }
    @Test void changingIdentityWhileCryptoProIsSigningPreventsSubmission() {
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> RegistrationAutoSigner.signIfReady(
                state("notsigned"), catalog(), "token", GTIN, () -> {
                    if (checks.incrementAndGet() == 2) throw new IllegalStateException("Identity changed");
                }));
        assertEquals(1, signatures.get()); assertEquals(0, submissions.get());
    }
    @Test void blankAndAmbiguousDocumentsAreRejectedBeforeSigning() {
        for (String xmls : new String[]{"[{\"goodId\":9,\"xml\":\"\"}]",
                "[{\"goodId\":9,\"xml\":\"a\"},{\"goodId\":10,\"xml\":\"b\"}]"}) {
            document = "{\"result\":{\"xmls\":" + xmls + "}}";
            assertThrows(IllegalArgumentException.class, () -> RegistrationAutoSigner.signIfReady(
                    state("notsigned"), catalog(), "token", GTIN, () -> {}));
        }
        assertEquals(0, signatures.get()); assertEquals(0, submissions.get());
    }
    @Test void ambiguousTimeoutIsNotImmediatelyReplayed() {
        timeout = true;
        assertThrows(java.net.SocketTimeoutException.class, () -> RegistrationAutoSigner.signIfReady(
                state("notsigned"), catalog(), "token", GTIN, () -> {}));
        assertEquals(1, submissions.get());
    }
    @Test void rejectedSignatureDoesNotCountAsSuccess() {
        signedResponse = "{\"result\":{\"signed\":[],\"errors\":[]}}";
        assertThrows(IllegalStateException.class, () -> RegistrationAutoSigner.signIfReady(
                state("notsigned"), catalog(), "token", GTIN, () -> {}));
    }
}
