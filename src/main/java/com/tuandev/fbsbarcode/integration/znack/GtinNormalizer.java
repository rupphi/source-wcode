package com.tuandev.fbsbarcode.integration.znack;

public final class GtinNormalizer {
    private GtinNormalizer() {
    }

    public static String normalize(String value) {
        String gtin = value == null ? "" : value.trim();
        if (gtin.isEmpty() || !gtin.chars().allMatch(digit -> digit >= '0' && digit <= '9') || gtin.length() > 14) {
            throw new IllegalArgumentException("GTIN must contain no more than 14 digits.");
        }
        return "0".repeat(14 - gtin.length()) + gtin;
    }
}
