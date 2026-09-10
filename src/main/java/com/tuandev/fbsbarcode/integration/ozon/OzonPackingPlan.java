package com.tuandev.fbsbarcode.integration.ozon;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Frozen catalog and posting identities shared by both warehouse PDFs. */
record OzonPackingPlan(OzonPostingDto posting, List<Line> lines) {
    record Line(OzonPostingItemDto item, OzonProductDto product, String barcode,
                int firstUnit, List<OzonExemplarJobRepository.KizBinding> bindings) { }

    static OzonPackingPlan create(OzonPostingDto posting, List<OzonProductDto> products,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        List<Line> lines = new ArrayList<>();
        int unit = 1;
        int matched = 0;
        var itemIds = new java.util.HashSet<Integer>();
        for (var item : posting.items()) {
            if (item.quantity() < 1 || !itemIds.add(item.itemIndex()))
                throw new IOException("Invalid or duplicate Ozon posting item.");
            var candidates = matchCandidates(products, item);
            if (candidates.size() != 1) throw new IOException("Refresh Ozon catalog: product identity is missing or ambiguous: " + item.offerId());
            var product = candidates.getFirst();
            String barcode = product.barcodes().stream().filter(b -> !b.isBlank()).sorted().findFirst().orElse(product.sku());
            if (barcode.isBlank()) throw new IOException("Ozon product has no printable barcode: " + item.offerId());
            var itemBindings = bindings.stream().filter(b -> b.itemIndex() == item.itemIndex())
                    .sorted(java.util.Comparator.comparingInt(OzonExemplarJobRepository.KizBinding::exemplarIndex)).toList();
            if (!itemBindings.isEmpty()) {
                if (itemBindings.size() != item.quantity()) throw new IOException("Incomplete Ozon unit KIZ assignments.");
                for (int i = 0; i < itemBindings.size(); i++) {
                    if (itemBindings.get(i).exemplarIndex() != i)
                        throw new IOException("Duplicate or missing Ozon unit KIZ assignment.");
                }
            }
            matched += itemBindings.size();
            lines.add(new Line(item, product, barcode, unit, itemBindings));
            unit = Math.addExact(unit, item.quantity());
        }
        if (matched != bindings.size()) throw new IOException("Ozon KIZ no longer matches the posting.");
        return new OzonPackingPlan(posting, List.copyOf(lines));
    }

    int units() { return lines.stream().mapToInt(line -> line.item().quantity()).sum(); }

    static List<OzonProductDto> matchCandidates(List<OzonProductDto> products, OzonPostingItemDto item) {
        if (products == null || item == null) return List.of();
        List<OzonProductDto> matches = products.stream().filter(p ->
                (!item.sku().isBlank() && (item.sku().equals(p.sku()) || item.sku().equals(p.productId())))
                || (!item.productId().isBlank() && (item.productId().equals(p.productId()) || item.productId().equals(p.sku())))
        ).distinct().toList();
        if (matches.size() == 1) return matches;

        if (!item.offerId().isBlank()) {
            List<OzonProductDto> offerMatches = products.stream()
                    .filter(p -> item.offerId().equals(p.offerId()))
                    .distinct().toList();
            if (offerMatches.size() == 1) return offerMatches;
            if (!offerMatches.isEmpty()) return offerMatches;
        }
        return matches;
    }
}
