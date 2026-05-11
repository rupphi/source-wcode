package com.tuandev.fbsbarcode.features.print.history;

public record PrintHistoryJobSummary(
        long id,
        int shopId,
        String shopName,
        String supplyId,
        String supplyName,
        String printedAt,
        int itemCount,
        Integer templateId,
        String templateName,
        String templateLayoutJson,
        String status,
        String errorMessage
) {
    public boolean canReprint() {
        return "success".equalsIgnoreCase(status);
    }
}
