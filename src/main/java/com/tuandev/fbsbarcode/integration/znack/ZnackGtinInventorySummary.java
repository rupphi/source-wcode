package com.tuandev.fbsbarcode.integration.znack;

import java.time.Instant;

public record ZnackGtinInventorySummary(String gtin, String productName, int available, int reserved,
                                        int consumed, int mappingRuleCount, String latestOrderStatus,
                                        String latestPipelineStage, String latestError, Instant syncedAt) {

    /** Case-insensitive match against the GTIN or product name; {@code query} must be lower-case. */
    public boolean matchesSearch(String query) {
        return contains(gtin, query) || contains(productName, query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
