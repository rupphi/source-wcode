package com.tuandev.fbsbarcode.integration.ozon;

import java.util.List;

public record OzonProductDto(
        String productId,
        String offerId,
        String sku,
        String name,
        String primaryImageUrl,
        boolean archived,
        String updatedAt,
        List<String> barcodes) {
    public OzonProductDto {
        productId = OzonApiClient.requireExternalId(productId, "product id");
        offerId = safe(offerId);
        sku = safe(sku);
        name = bounded(name, 1000);
        primaryImageUrl = bounded(primaryImageUrl, 4096);
        updatedAt = bounded(updatedAt, 80);
        barcodes = barcodes == null ? List.of() : barcodes.stream()
                .map(OzonProductDto::safe)
                .filter(value -> !value.isEmpty())
                .distinct()
                .limit(100)
                .toList();
    }

    private static String safe(String value) {
        return bounded(value, 512);
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\p{Cntrl}", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
