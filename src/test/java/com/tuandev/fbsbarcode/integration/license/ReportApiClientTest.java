package com.tuandev.fbsbarcode.integration.license;

import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportApiClientTest {
    @Test void reportSendsDiagnosticMessageWithPayloadAndResponseAndRedactsCredentials() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            try (Buffer buffer = new Buffer()) {
                chain.request().body().writeTo(buffer);
                sent.set(buffer.readUtf8());
            }
            assertEquals("/api/v1/reports", chain.request().url().encodedPath());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(201).message("Created").body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
        }).build();
        String details = "Summary: Invalid size\nRequest payload:\n{\"attr_value\":\"164\",\"apiKey\":\"private-key\"}"
                + "\nResponse body:\n" + "validation detail ".repeat(400);
        new ReportApiClient(client, "https://report.test").send(new ReportApiClient.Report(
                "license", "device", "Shop", "CARD_REGISTRATION", "Qa-01", "", details, "1.1.33"));
        String message = JsonParser.parseString(sent.get()).getAsJsonObject().get("message").getAsString();
        assertTrue(message.contains("Request payload:"));
        assertTrue(message.contains("164"));
        assertTrue(message.endsWith("validation detail ".repeat(400)));
        assertFalse(message.contains("private-key"));
    }
}
