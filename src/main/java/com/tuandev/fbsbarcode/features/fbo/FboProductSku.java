package com.tuandev.fbsbarcode.features.fbo;

public record FboProductSku(
        long nmId,
        String vendorCode,
        String subjectName,
        String brand,
        String title,
        String color,
        String size,
        String sku,
        String imageUrl,
        boolean requiresKiz
) {
}
