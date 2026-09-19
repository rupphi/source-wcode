package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonObject;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZnackTimeoutRetryTest {
    @Test void cancellationAndPermanentRejectionsAreNotTimeouts() {
        assertFalse(ZnackTimeouts.isTimeout(new InterruptedIOException("interrupted")));
        assertFalse(ZnackTimeouts.isTimeout(new ZnackApiClient.ZnackApiException("failure", 422, "timeout is not a valid size")));
        assertFalse(ZnackTimeouts.isStoredTimeout("Invalid size; request payload: timeout"));
        assertTrue(ZnackTimeouts.isTimeout(new IllegalStateException("operation failed", new InterruptedIOException("timeout"))));
        assertTrue(ZnackTimeouts.isTimeout(new ZnackApiClient.ZnackApiException("failure", 504, "Gateway Timeout")));
        Thread.currentThread().interrupt();
        try { assertFalse(ZnackTimeouts.isTimeout(new java.net.SocketTimeoutException("timeout"))); }
        finally { Thread.interrupted(); }
    }

    @Test void codeBufferDownloadIsNotBlindlyReplayed() {
        var calls = new AtomicInteger();
        var delays = new ArrayList<Long>();
        assertThrows(InterruptedIOException.class,
                () -> client(calls, 99, delays).codes("https://example.test", "token", "oms", "order", 2, "gtin"));
        assertEquals(1, calls.get());
        assertTrue(delays.isEmpty());
    }

    @Test void safePostLookupRetriesButHttpValidationDoesNot() throws Exception {
        var calls = new AtomicInteger();
        client(calls, 1, new ArrayList<>()).productInfo("https://example.test", "token", "gtin");
        assertEquals(2, calls.get());
        calls.set(0);
        var api = new ZnackApiClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(422).message("Rejected").body(ResponseBody.create("invalid size", MediaType.get("text/plain"))).build();
        }).build(), delay -> fail("Permanent errors must not retry"));
        assertThrows(ZnackApiClient.ZnackApiException.class,
                () -> api.productCards("https://example.test", "token", "gtin"));
        assertEquals(1, calls.get());
    }

    @Test void safeReadRetriesTimeoutThenReturnsWithoutAnError() throws Exception {
        var calls = new AtomicInteger();
        var delays = new ArrayList<Long>();
        var client = client(calls, 2, delays);
        assertNotNull(client.productCards("https://example.test", "token", "04601234567890"));
        assertEquals(3, calls.get());
        assertEquals(java.util.List.of(1000L, 2000L), delays);
    }

    @Test void mutationAndGtinAllocationAreNeverReplayedAfterTimeout() {
        var calls = new AtomicInteger();
        var delays = new ArrayList<Long>();
        var client = client(calls, 99, delays);
        assertThrows(InterruptedIOException.class,
                () -> client.submitNationalCatalogFeed("https://example.test", "token", new JsonObject()));
        assertEquals(1, calls.get());
        assertThrows(InterruptedIOException.class,
                () -> client.generateGtins("https://example.test", "token", 1));
        assertEquals(2, calls.get());
        assertTrue(delays.isEmpty());
    }

    @Test void persistentReadTimeoutIsBoundedAndRetainsDiagnostics() {
        var calls = new AtomicInteger();
        var error = assertThrows(InterruptedIOException.class,
                () -> client(calls, 99, new ArrayList<>()).productCards("https://example.test", "token", "04601234567890"));
        assertEquals(3, calls.get());
        assertTrue(ZnackErrorDetails.format(error).contains("HTTP EXCHANGE"));
    }

    private static ZnackApiClient client(AtomicInteger calls, int failures, ArrayList<Long> delays) {
        return new ZnackApiClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            if (calls.incrementAndGet() <= failures) throw new InterruptedIOException("timeout");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
        }).build(), delays::add);
    }
}
