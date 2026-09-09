package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.models.Kiz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FboKizPrintPlanner {
    private final KizMappingRepository mappingRepository;
    private final ZnackGtinInventoryService inventoryService;

    public FboKizPrintPlanner() {
        this(new KizMappingRepository(), new ZnackGtinInventoryService());
    }

    FboKizPrintPlanner(KizMappingRepository mappingRepository, ZnackGtinInventoryService inventoryService) {
        this.mappingRepository = mappingRepository;
        this.inventoryService = inventoryService;
    }

    public FboPrintPlan plan(int shopId, List<FboBarcodePrintItem> items) {
        return plan(shopId, items, null);
    }

    public FboPrintPlan planForPrint(com.tuandev.fbsbarcode.models.Shop shop, List<FboBarcodePrintItem> items) {
        com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard.requireWildberries(shop);
        return plan(shop.getId(), items, shop);
    }

    private FboPrintPlan plan(int shopId, List<FboBarcodePrintItem> items, com.tuandev.fbsbarcode.models.Shop explicitShop) {
        List<FboBarcodePrintItem> safeItems = items == null ? List.of() : items.stream()
                .filter(item -> item != null && item.product() != null && item.quantity() > 0).toList();
        if (safeItems.isEmpty()) return new FboPrintPlan(List.of(), List.of());

        Map<Long, String> mappings = mappingRepository.findMappings(shopId, safeItems.stream()
                .map(FboBarcodePrintItem::product).filter(FboProductSku::requiresKiz)
                .map(FboProductSku::nmId).distinct().toList());
        Map<String, Integer> neededByGtin = new LinkedHashMap<>();
        Map<FboProductSku, String> sizeMappings = new HashMap<>();
        java.util.Set<String> registeredGtins = new java.util.HashSet<>();
        List<String> missing = new ArrayList<>();
        for (FboBarcodePrintItem item : safeItems) {
            if (!item.product().requiresKiz()) continue;
            String gtin = mappings.get(item.product().nmId());
            String registered = mappingRepository.registeredGtin(shopId, item.product().nmId(), item.product().sku());
            if (registered != null) { gtin = registered; registeredGtins.add(gtin); }
            sizeMappings.put(item.product(), gtin);
            if (gtin == null) {
                missing.add("nmId " + item.product().nmId() + " | article " + safe(item.product().vendorCode()));
            } else {
                neededByGtin.merge(gtin, item.quantity(), Integer::sum);
            }
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Products requiring KIZ are not mapped:\n" + String.join("\n", missing));

        Map<String, List<Kiz>> reservedByGtin = new HashMap<>();
        List<Kiz> allReserved = new ArrayList<>();
        try {
            for (Map.Entry<String, Integer> entry : neededByGtin.entrySet()) {
                if (explicitShop != null && registeredGtins.contains(entry.getKey())) {
                    String demand = "FBO:" + safeItems.stream().map(item -> item.product().nmId() + ":"
                            + item.product().sku() + ":" + item.quantity()).sorted().toList();
                    try {
                        new com.tuandev.fbsbarcode.integration.znack.registration.WbPrintDemand()
                                .awaitAvailable(explicitShop, entry.getKey(), entry.getValue(), demand);
                    } catch (java.io.IOException error) { throw new IllegalStateException(error.getMessage(), error); }
                }
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Print cancelled before KIZ reservation.");
                List<Kiz> reserved = inventoryService.reserveAvailable(shopId, entry.getKey(), entry.getValue());
                reservedByGtin.put(entry.getKey(), reserved);
                allReserved.addAll(reserved);
            }
        } catch (RuntimeException e) {
            inventoryService.release(shopId, allReserved);
            throw e;
        }

        Map<String, Integer> nextIndex = new HashMap<>();
        List<FboPrintPage> pages = new ArrayList<>();
        int pairNumber = 1;
        for (FboBarcodePrintItem item : safeItems) {
            for (int i = 0; i < item.quantity(); i++) {
                String code = null;
                if (item.product().requiresKiz()) {
                    String gtin = sizeMappings.get(item.product());
                    code = reservedByGtin.get(gtin).get(nextIndex.merge(gtin, 1, Integer::sum) - 1).getCode();
                }
                pages.add(FboPrintPage.barcode(item.product(), pairNumber));
                pages.add(FboPrintPage.barcode(item.product(), pairNumber));
                if (code != null && !code.isBlank()) {
                    pages.add(FboPrintPage.kiz(item.product(), code, pairNumber));
                }
                pairNumber++;
            }
        }
        return new FboPrintPlan(List.copyOf(pages), List.copyOf(allReserved));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
