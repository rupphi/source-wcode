package com.tuandev.fbsbarcode.integration.znack;

import java.util.Locale;

/** Transport timeouts, separate from cancellation and permanent card validation errors. */
public final class ZnackTimeouts {
    private ZnackTimeouts() { }

    public static boolean isTimeout(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException)
                return false;
            if (cause instanceof ZnackApiClient.ZnackApiException api)
                return api.statusCode() == 408 || api.statusCode() == 504;
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) return true;
            if (cause instanceof java.io.IOException && isStoredTimeout(cause.getMessage())) return true;
        }
        return false;
    }

    public static boolean isStoredTimeout(String details) {
        String summary = ZnackErrorDetails.storedSummary(details).trim().toLowerCase(Locale.ROOT);
        return summary.equals("timeout") || summary.equals("timed out") || summary.equals("read timed out")
                || summary.equals("connect timed out") || summary.equals("connection timed out")
                || summary.startsWith("java.net.sockettimeoutexception:")
                || summary.equals("java.io.interruptedioexception: timeout");
    }
}
