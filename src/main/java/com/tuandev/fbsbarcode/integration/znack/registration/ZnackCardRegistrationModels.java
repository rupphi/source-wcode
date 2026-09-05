package com.tuandev.fbsbarcode.integration.znack.registration;

import java.util.List;
import java.util.Map;

public final class ZnackCardRegistrationModels {
    private ZnackCardRegistrationModels() {
    }

    public enum Status {
        NOT_CREATED,
        CHECKING,
        GTIN_GENERATED,
        FEED_SUBMITTED,
        PROCESSING,
        READY_TO_SIGN,
        SIGNING,
        PUBLISHED,
        ERROR
    }

    public record Sku(long nmId, long chrtId, String vendorCode, String subjectName, String brand,
                      String title, String color, String size, List<String> barcodes, String imageUrl,
                      boolean needKiz, String gtin, Long goodId, String feedId, Status status,
                      String errorMessage, boolean wbUpdated) {
        public Sku {
            barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
            status = status == null ? Status.NOT_CREATED : status;
        }

        public String sourceBarcode() {
            return barcodes.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        }
    }

    public record SearchCriteria(int shopId, String query, List<String> subjects, String status,
                                 int limit, int offset) {
    }

    public record Category(long id, String name) {
        @Override public String toString() { return name + " (" + id + ")"; }
    }

    public record Attribute(long id, String name, String fieldType, boolean presetOnly,
                            boolean multiple, boolean firstLayer, boolean secondLayer,
                            List<String> presets) {
        public Attribute {
            presets = presets == null ? List.of() : List.copyOf(presets);
        }
    }

    public record Gs1Status(long limit, long usage, int existingDrafts, boolean quotaKnown) {
        public Gs1Status(long limit, long usage, int existingDrafts) {
            this(limit, usage, existingDrafts, true);
        }

        public long remaining() { return Math.max(0L, limit - usage); }
        public boolean canGenerate() { return !quotaKnown || remaining() > 0; }
    }

    public record Draft(String tnved, long categoryId, String goodName, String brand,
                        Map<Long, String> attributes) {
        public Draft {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
