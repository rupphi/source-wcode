package com.tuandev.fbsbarcode.integration.ozon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OzonRequirementGuard {
    private OzonRequirementGuard() {
    }

    public static PreparationPlan plan(OzonPostingDto posting, Map<String, String> skuToGtin) {
        Objects.requireNonNull(posting, "posting");
        Map<String, String> mappings = skuToGtin == null ? Map.of() : Map.copyOf(skuToGtin);
        if (posting.requirements().blocksPreparation()) {
            throw new UnsupportedRequirementException(posting.requirements().unsupportedRequirements());
        }
        if (!posting.isSinglePackageSupported()) {
            throw new UnsupportedRequirementException(List.of("partial_or_multibox_package"));
        }
        Set<String> mandatory = Set.copyOf(posting.requirements().mandatoryMarkProductIds());
        Set<String> optional = Set.copyOf(posting.requirements().optionalMarkProductIds());
        List<RequiredItem> required = new ArrayList<>();
        for (OzonPostingItemDto item : posting.items()) {
            boolean mandatoryMark = mandatory.contains(item.productId());
            boolean optionalMark = optional.contains(item.productId());
            if (!mandatoryMark && !optionalMark) continue;
            String gtin = mappings.get(item.sku());
            if (gtin == null || gtin.isBlank()) {
                if (mandatoryMark) throw new MissingMappingException(item.sku());
                continue;
            }
            required.add(new RequiredItem(item.itemIndex(), item.productId(), item.sku(), gtin, item.quantity(), mandatoryMark));
        }
        if (!mandatory.isEmpty()) {
            Set<String> covered = required.stream().map(RequiredItem::productId).collect(java.util.stream.Collectors.toSet());
            if (!covered.containsAll(mandatory)) {
                throw new MissingMappingException("mandatory_product");
            }
        }
        return new PreparationPlan(posting.postingNumber(), List.copyOf(required));
    }

    public record RequiredItem(
            int itemIndex, String productId, String sku, String gtin, int quantity, boolean mandatory) {
    }

    public record PreparationPlan(String postingNumber, List<RequiredItem> items) {
        public int exemplarCount() {
            return items.stream().mapToInt(RequiredItem::quantity).sum();
        }
    }

    public static final class MissingMappingException extends IllegalStateException {
        public MissingMappingException(String sku) {
            super("A mandatory Ozon marking item has no SKU to GTIN mapping.");
        }
    }

    public static final class UnsupportedRequirementException extends IllegalStateException {
        private final List<String> requirements;

        public UnsupportedRequirementException(List<String> requirements) {
            super("This posting has an Ozon requirement that WCode does not support yet.");
            this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
        }

        public List<String> requirements() {
            return requirements;
        }
    }
}
