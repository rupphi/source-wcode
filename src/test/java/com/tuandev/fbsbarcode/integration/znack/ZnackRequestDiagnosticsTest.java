package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonParser;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRequestDiagnosticsTest {
    private static final String PAYLOAD = """
            {"gtin":"04631993764363","good_attrs":[{"attr_id":35,"attr_value":"164"}],
             "apiKey":"private-key","password":"secret with spaces","nested":{"access_token":"private-access"}}
            """;

    @Test void httpFailureRetainsRequestPayloadAndResponseWithoutCredentials() {
        var error = assertThrows(ZnackApiClient.ZnackApiException.class,
                () -> client(422, "{\"error\":\"invalid size\",\"refresh_token\":\"private-refresh\"}")
                        .submitNationalCatalogFeed("https://example.test", "private-auth", JsonParser.parseString(PAYLOAD)));
        String report = ZnackErrorDetails.format(error);
        assertTrue(report.contains("POST"));
        assertTrue(report.contains("https://example.test/v3/feed"));
        assertTrue(report.contains("Request payload:"));
        assertTrue(report.contains("04631993764363"));
        assertTrue(report.contains("164"));
        assertTrue(report.contains("invalid size"));
        for (String secret : new String[]{"private-key", "secret with spaces", "private-access", "private-refresh", "private-auth"})
            assertFalse(report.contains(secret), secret);
    }

    @Test void malformedJsonRetainsTheRawResponseAndRequest() {
        var error = assertThrows(IOException.class, () -> client(200, "<html>upstream unavailable</html>")
                .submitNationalCatalogFeed("https://example.test", "token", JsonParser.parseString(PAYLOAD)));
        String report = ZnackErrorDetails.format(error);
        assertTrue(report.contains("Request payload:"));
        assertTrue(report.contains("HTTP status: 200"));
        assertTrue(report.contains("<html>upstream unavailable</html>"));
    }

    @Test void transportFailureHasRequestAndExplicitlyUnavailableResponse() {
        var api = new ZnackApiClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            throw new java.net.SocketTimeoutException("Timed out");
        }).build());
        var error = assertThrows(java.net.SocketTimeoutException.class,
                () -> api.submitNationalCatalogFeed("https://example.test", "token", JsonParser.parseString(PAYLOAD)));
        String report = ZnackErrorDetails.format(error);
        assertTrue(report.contains("04631993764363"));
        assertTrue(report.contains("Response body:\n[unavailable]"));
    }

    @Test void oversizedPayloadIsBoundedAndClearlyMarked() {
        var payload = JsonParser.parseString("{\"value\":\"" + "size detail ".repeat(5000) + "\",\"token\":\"tail-secret\"}");
        var error = assertThrows(IOException.class,
                () -> client(400, "invalid size").submitNationalCatalogFeed("https://example.test", "token", payload));
        String report = ZnackErrorDetails.format(error);
        assertTrue(report.contains("[truncated"));
        assertTrue(report.length() < 40000);
        assertFalse(report.contains("tail-secret"));
    }

    @Test void authenticationExchangeNeverIncludesCredentialsEvenInPlainTextResponse() {
        var error = assertThrows(IOException.class, () -> client(401, "short-auth-secret")
                .signIn("https://example.test", "", JsonParser.parseString("{\"data\":\"short-signature\"}").getAsJsonObject()));
        String report = ZnackErrorDetails.format(error);
        assertFalse(report.contains("short-auth-secret"));
        assertFalse(report.contains("short-signature"));
        assertTrue(report.contains("authentication payload omitted"));
    }

    private static ZnackApiClient client(int status, String body) {
        return new ZnackApiClient(new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(status).message("test").body(ResponseBody.create(body, okhttp3.MediaType.get("application/json")))
                        .build()).build());
    }
}
