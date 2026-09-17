package com.tuandev.fbsbarcode.integration.znack;

import okhttp3.Request;
import okio.Buffer;

/** Immutable error-local context; never a shared history of another shop's requests. */
public final class ZnackRequestDiagnostics extends Exception {
    private static final int BODY_LIMIT = 24_000;

    public ZnackRequestDiagnostics(String method, String url, String payload, Integer status, String response) {
        super("HTTP EXCHANGE\nMethod: " + method + "\nURL: " + ZnackSanitizer.diagnostic(url)
                + "\nRequest payload:\n" + safeBody(payload)
                + "\nHTTP status: " + (status == null ? "[not captured]" : status)
                + "\nResponse body:\n" + safeBody(response), null, false, false);
    }

    public static String safeBody(String body) {
        if (body == null) return "[unavailable]";
        if (body.isBlank()) return "[empty]";
        String safe = ZnackSanitizer.diagnostic(body);
        return safe.length() <= BODY_LIMIT ? safe
                : safe.substring(0, BODY_LIMIT) + "\n[truncated; " + safe.length() + " sanitized characters total]";
    }

    static void attach(Throwable error, Request request, Integer status, String response) {
        String payload = "";
        boolean authentication = request.url().encodedPath().toLowerCase(java.util.Locale.ROOT).contains("/auth/");
        if (authentication) {
            payload = "[authentication payload omitted]";
            response = "[authentication response omitted]";
        } else if (request.body() != null) {
            if (request.body().isOneShot() || request.body().isDuplex()) {
                payload = "[streaming payload unavailable]";
            } else try (Buffer buffer = new Buffer()) {
                request.body().writeTo(buffer);
                payload = buffer.readUtf8();
            } catch (Exception unavailable) {
                payload = "[request payload unavailable]";
            }
        }
        error.addSuppressed(new ZnackRequestDiagnostics(request.method(), request.url().toString(), payload, status, response));
    }
}
