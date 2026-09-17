package com.tuandev.fbsbarcode.integration.znack;

public final class ZnackSanitizer {
    private static final String SECRET_KEY = "(?:authorization|proxy[-_]?authorization|(?:access[-_]?|refresh[-_]?|client[-_]?)?token"
            + "|api[-_]?key|supplier[-_]?key|client[-_]?secret|secret|password|passwd|signature|pin|cookie|set-cookie)";
    private static final String QUOTED_VALUE = "(?:\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+')";
    private ZnackSanitizer() {
    }

    public static String displayCode(String raw) {
        return raw == null ? "" : raw.replace("\u001d", "<GS>").replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
    }

    public static String message(String value) {
        String sanitized = diagnostic(value);
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }

    public static String report(String value) {
        String safe = diagnostic(value);
        return safe.length() <= 120_000 ? safe : safe.substring(0, 119_950) + "\n[truncated diagnostic report]";
    }

    public static String diagnostic(String value) {
        return displayCode(value == null ? "" : value)
                .replaceAll("(?i)([\"']" + SECRET_KEY + "[\"']\\s*:\\s*)(?:" + QUOTED_VALUE + "|[^\\s,}]+)", "$1\"[REDACTED]\"")
                .replaceAll("(?i)(Bearer|Basic)\\s+[^\\s,;\"'}]+", "$1 [REDACTED]")
                .replaceAll("(?i)((?<![\\w])" + SECRET_KEY + "\\s*[=:]\\s*)(?:" + QUOTED_VALUE + "|[^\\s,&;}\"']+)", "$1[REDACTED]")
                .replaceAll("(?i)(https?://)[^\\s/@]+@", "$1[REDACTED]@")
                .replaceAll("(?i)\\b[A-Za-z0-9+/]{80,}={0,2}\\b", "[REDACTED]");
    }

    public static String error(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 5; depth++, current = current.getCause()) {
            String detail = message(current.getMessage());
            if (detail.isBlank() || result.toString().contains(detail)) continue;
            if (!result.isEmpty()) result.append(" <- ");
            result.append(current.getClass().getSimpleName()).append(": ").append(detail);
        }
        return message(result.toString());
    }
}
