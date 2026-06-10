package com.tuandev.fbsbarcode.integration.znack;

public final class ZnackSanitizer {
    private ZnackSanitizer() {
    }

    public static String displayCode(String raw) {
        return raw == null ? "" : raw.replace("\u001d", "<GS>").replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
    }

    public static String message(String value) {
        String sanitized = displayCode(value == null ? "" : value)
                .replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)([\"']?(?:clientToken|token|signature|pin)[\"']?\\s*[:=]\\s*)[\"']?[^\\s,}\"']+[\"']?", "$1[REDACTED]")
                .replaceAll("(?i)\\b[A-Za-z0-9+/]{80,}={0,2}\\b", "[REDACTED]");
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }
}
