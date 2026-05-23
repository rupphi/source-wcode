package com.tuandev.fbsbarcode.features.kizmapping;

import java.util.List;

public record KizMappingSearchCriteria(
        int shopId,
        String query,
        List<String> subjectNames,
        int limit,
        int offset
) {
}
