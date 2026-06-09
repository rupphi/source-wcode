package com.tuandev.fbsbarcode.features.kizmapping;

import java.util.List;

public record KizMappingSearchCriteria(
        int shopId,
        String query,
        List<String> subjectNames,
        List<String> genderValues,
        int limit,
        int offset
) {
    public KizMappingSearchCriteria(int shopId, String query, List<String> subjectNames, int limit, int offset) {
        this(shopId, query, subjectNames, List.of(), limit, offset);
    }
}
