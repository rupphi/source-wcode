package com.tuandev.fbsbarcode.features.kizmapping;

import java.util.List;

public record KizMappingImportResult(int updatedCount, int clearedCount, List<String> errors) {
    public boolean success() {
        return errors == null || errors.isEmpty();
    }
}
