package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class OzonPackingPlanTest {

    @Test
    void matchesProductWhenPostingItemProductIdEqualsSkuAndDiffersFromCatalogProductId() throws Exception {
        var item = new OzonPostingItemDto(0, "5549801365", "5549801365",
                "917_коричневый/кофейный(L/XL)", "Leggings", 1, "RUB", "1000");

        var catalogProduct = new OzonProductDto(
                "6053833212", "917_коричневый/кофейный(L/XL)", "5549801365",
                "Leggings", "http://example.com/img.png", "seller-art", "brown", "L/XL",
                false, "2026-09-10", List.of("OZN5549801365"));

        var otherProduct = new OzonProductDto(
                "6053833149", "917_коричневый/кофейный(S/M)", "5549801357",
                "Leggings S/M", "http://example.com/img.png", "seller-art", "brown", "S/M",
                false, "2026-09-10", List.of("OZN5549801357"));

        var candidates = OzonPackingPlan.matchCandidates(List.of(catalogProduct, otherProduct), item);
        assertEquals(1, candidates.size());
        assertEquals("6053833212", candidates.getFirst().productId());
        assertEquals("5549801365", candidates.getFirst().sku());

        var posting = new OzonPostingDto(
                "45818967-0625-1", "ord-1", "ord-1", "awaiting_deliver", "", "",
                "", "", "", "", new OzonRequirements(List.of(), List.of(), List.of()),
                List.of(), false, List.of(item));

        var plan = OzonPackingPlan.create(posting, List.of(catalogProduct, otherProduct), List.of());
        assertNotNull(plan);
        assertEquals(1, plan.lines().size());
        assertEquals("OZN5549801365", plan.lines().getFirst().barcode());
    }

    @Test
    void matchesProductByOfferIdWhenSkuIsBlank() throws Exception {
        var item = new OzonPostingItemDto(0, "", "", "unique-offer-123", "Product", 2, "RUB", "500");

        var catalogProduct = new OzonProductDto(
                "cat-prod-1", "unique-offer-123", "cat-sku-1",
                "Product", "", "unique-offer-123", "", "",
                false, "", List.of("BC123"));

        var candidates = OzonPackingPlan.matchCandidates(List.of(catalogProduct), item);
        assertEquals(1, candidates.size());
        assertEquals("cat-prod-1", candidates.getFirst().productId());
    }

    @Test
    void throwsAmbiguousWhenProductIdentityNotFound() {
        var item = new OzonPostingItemDto(0, "unknown-sku", "unknown-sku", "unknown-offer", "Product", 1, "RUB", "100");

        var posting = new OzonPostingDto(
                "POST-MISSING", "ord-1", "ord-1", "awaiting_deliver", "", "",
                "", "", "", "", new OzonRequirements(List.of(), List.of(), List.of()),
                List.of(), false, List.of(item));

        IOException error = assertThrows(IOException.class, () ->
                OzonPackingPlan.create(posting, List.of(), List.of()));

        assertTrue(error.getMessage().contains("Refresh Ozon catalog: product identity is missing or ambiguous: unknown-offer"));
    }
}
