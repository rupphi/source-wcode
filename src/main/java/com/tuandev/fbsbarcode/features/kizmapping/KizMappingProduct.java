package com.tuandev.fbsbarcode.features.kizmapping;

public record KizMappingProduct(
        long nmId,
        String imageUrl,
        String title,
        String subjectName,
        String gender,
        String vendorCode,
        Integer kizCategoryId
) {
}
