package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZnackNationalCatalogServiceTest {
    @Test
    void parsesGs1QuotaWithoutConsumingDrafts() {
        var status = ZnackNationalCatalogService.parseGs1(JsonParser.parseString("""
                {"result":{"monthly-limit":{"limit":"100","usage":"17"},"drafts":[{"gtin":"04600000000001"}]}}
                """));
        assertEquals(83, status.remaining());
        assertEquals(1, status.existingDrafts());
        assertTrue(status.canGenerate());
        assertTrue(status.quotaKnown());
    }

    @Test
    void allowsPreflightToContinueWhenExistingDraftLookupHasNoQuotaBlock() {
        var status = ZnackNationalCatalogService.parseGs1(JsonParser.parseString("""
                {"result":{"drafts":[]}}
                """));
        assertFalse(status.quotaKnown());
        assertEquals(0, status.existingDrafts());
        assertTrue(status.canGenerate());
    }

    @Test
    void keepsLeadingZeroAndCertificateInFeed() {
        Draft draft = new Draft("6109100000", 30068, "Брюки, арт. WB-1, размер 44", "WCode",
                Map.of(2478L, "Брюки, арт. WB-1, размер 44", 2504L, "WCode",
                        ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID, "ЕАЭС RU C-RU.01:::2026-09-05"));
        JsonObject payload = ZnackNationalCatalogService.buildPayload("04627877922394", draft,
                "https://basket.example/product.jpg");

        assertEquals("04627877922394", payload.get("gtin").getAsString());
        assertEquals("шт", payload.getAsJsonArray("identified_by").get(0).getAsJsonObject()
                .get("unit").getAsString());
        assertEquals(30068, payload.getAsJsonArray("categories").get(0).getAsLong());
        assertTrue(payload.getAsJsonArray("good_attrs").asList().stream()
                .anyMatch(item -> item.getAsJsonObject().get("attr_id").getAsLong()
                        == ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID));
    }

    @Test
    void prefersActiveLeafCategories() {
        var categories = ZnackNationalCatalogService.parseCategories(JsonParser.parseString("""
                {"result":[
                  {"cat_id":30068,"cat_name":"Одежда","category_active":false},
                  {"cat_id":30933,"cat_name":"Брюки","category_active":true}
                ]}
                """));
        assertEquals(1, categories.size());
        assertEquals(30933, categories.get(0).id());
    }

    @Test
    void looksUpTheRegisteredFourDigitGroupAfterAnUnmappedTenDigitTnved() {
        assertEquals(List.of("6104690001", "6104"),
                ZnackNationalCatalogService.categoryLookupCodes("6104690001"));
        assertEquals(List.of("6104"), ZnackNationalCatalogService.categoryLookupCodes("6104"));
    }

    @Test
    void preflightRetainsTheFullTnvedWhenItsFourDigitGroupProvidesTheCategory() throws Exception {
        List<String> lookups = new ArrayList<>();
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement generatedGtins(String base, String token) {
                return JsonParser.parseString("{\"result\":{\"drafts\":[]}}");
            }

            @Override public JsonElement nationalCatalogCategories(String base, String token, String tnved)
                    throws IOException {
                lookups.add(tnved);
                return "6104".equals(tnved)
                        ? JsonParser.parseString("{\"result\":[{\"cat_id\":30933,\"cat_name\":\"Брюки\",\"category_active\":true}]}")
                        : JsonParser.parseString("{\"result\":[]}");
            }
        };
        ZnackAuthService auth = new ZnackAuthService(api, null) {
            @Override public String trueApiToken(ZnackModels.Settings settings) {
                return "shop-token";
            }
        };
        var service = new ZnackNationalCatalogService(api, auth, null, ZnackModels.Settings.empty());

        var result = service.preflight("6104 69 000 1");

        assertEquals(List.of("6104690001", "6104"), lookups);
        assertEquals("6104690001", result.tnved());
        assertEquals("6104", result.categoryTnved());
        assertEquals(30933, result.categories().get(0).id());
    }
}
