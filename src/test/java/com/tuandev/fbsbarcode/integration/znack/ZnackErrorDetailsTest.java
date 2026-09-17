package com.tuandev.fbsbarcode.integration.znack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZnackErrorDetailsTest {
    @Test void longQuotedSecretsAreRedactedWithoutLosingFollowingFields() {
        String raw = "{\"password\":\"" + "private value ".repeat(3000) + "\",\"attr_value\":\"164\"}";
        String safe = ZnackSanitizer.diagnostic(raw);
        assertTrue(safe.contains("164"));
        assertFalse(safe.contains("private value"));
    }
    @Test void userMessageStaysShortWhileStoredReportRetainsDiagnostics() {
        String details = "Summary: Invalid size\nWCode version: test\nRequest payload:\n{\"attr_value\":\"164\"}";
        org.junit.jupiter.api.Assertions.assertEquals("Invalid size", ZnackErrorMessages.display(details));
        assertTrue(ZnackErrorDetails.formatStored(details).contains("Request payload:"));
    }
    @Test
    void redactsQuotedCredentialsAndUrlParametersWithoutRemovingUsefulFields() {
        String safe = ZnackSanitizer.diagnostic("""
                https://example.test/feed?api_key=private-key&feed_id=123&token=private-token
                {"password":"two words \\"secret\\"","Authorization":"Basic private-auth",
                 "client_secret":"private-client","good_name":"Брюки","attr_value":"164"}
                """);
        assertTrue(safe.contains("feed_id=123"));
        assertTrue(safe.contains("164"));
        assertFalse(safe.contains("private-"));
        assertFalse(safe.contains("two words"));
        assertFalse(safe.contains("\\\"secret\\\""));
    }

    @Test
    void includesFullHttpContextAndRedactsSecrets() {
        String longMessage = "validation detail; ".repeat(90);
        String longBody = "{\"error\":\"" + longMessage + "\",\"token\":\"secret-token\"}";
        var error = new ZnackApiClient.ZnackApiException(
                "Znack API request failed", 422, longBody, "POST", "https://example.test/v3/feed");

        String details = ZnackErrorDetails.format(error);

        assertTrue(details.contains("Summary: Znack API request failed (HTTP 422)"));
        assertTrue(details.contains("HTTP status: 422"));
        assertTrue(details.contains("Method: POST"));
        assertTrue(details.contains("URL: https://example.test/v3/feed"));
        assertTrue(details.contains(longMessage));
        assertTrue(details.contains("[REDACTED]"));
        assertFalse(details.contains("secret-token"));
    }
}
