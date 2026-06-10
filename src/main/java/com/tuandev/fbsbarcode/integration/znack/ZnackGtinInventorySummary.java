package com.tuandev.fbsbarcode.integration.znack;

import java.time.Instant;

public record ZnackGtinInventorySummary(String gtin, String productName, int available, int reserved,
                                        int consumed, int mappingRuleCount, String latestOrderStatus,
                                        String latestPipelineStage, String latestError, Instant syncedAt) {
}
