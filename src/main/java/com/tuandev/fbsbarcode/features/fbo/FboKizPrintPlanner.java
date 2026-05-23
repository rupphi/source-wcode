package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.models.Kiz;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FboKizPrintPlanner {
    private final KizMappingRepository kizMappingRepository;

    public FboKizPrintPlanner() {
        this(new KizMappingRepository());
    }

    FboKizPrintPlanner(KizMappingRepository kizMappingRepository) {
        this.kizMappingRepository = kizMappingRepository;
    }

    public FboPrintPlan plan(int shopId, List<FboBarcodePrintItem> items) {
        List<FboBarcodePrintItem> safeItems = items == null ? List.of() : items.stream()
                .filter(item -> item != null && item.product() != null && item.quantity() > 0)
                .toList();
        if (safeItems.isEmpty()) {
            return new FboPrintPlan(List.of(), List.of());
        }

        Map<Long, Integer> mappings = kizMappingRepository.findMappings(shopId, safeItems.stream()
                .map(FboBarcodePrintItem::product)
                .filter(FboProductSku::requiresKiz)
                .map(FboProductSku::nmId)
                .distinct()
                .toList());
        Map<Integer, Integer> neededByCategory = new LinkedHashMap<>();
        List<String> missingMappings = new ArrayList<>();
        for (FboBarcodePrintItem item : safeItems) {
            FboProductSku product = item.product();
            if (!product.requiresKiz()) {
                continue;
            }
            Integer categoryId = mappings.get(product.nmId());
            if (categoryId == null) {
                missingMappings.add("nmId " + product.nmId()
                        + " | article " + safe(product.vendorCode())
                        + " | size " + safe(product.size()));
                continue;
            }
            neededByCategory.merge(categoryId, Math.max(0, item.quantity()), Integer::sum);
        }
        if (!missingMappings.isEmpty()) {
            throw new IllegalStateException("Sản phẩm cần KIZ nhưng chưa map:\n" + String.join("\n", missingMappings));
        }

        Map<Integer, String> categoryNames = findCategoryNames(neededByCategory.keySet().stream().toList());
        Map<Integer, List<Kiz>> kizByCategory = new HashMap<>();
        List<String> shortageErrors = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : neededByCategory.entrySet()) {
            int categoryId = entry.getKey();
            int required = entry.getValue();
            List<Kiz> kizs = KizService.getKizs(shopId, categoryId, required);
            if (kizs.size() < required) {
                shortageErrors.add("Không đủ KIZ cho category " + categoryId
                        + " - " + categoryNames.getOrDefault(categoryId, "-")
                        + ": cần " + required + ", còn " + kizs.size());
            }
            kizByCategory.put(categoryId, kizs);
        }
        if (!shortageErrors.isEmpty()) {
            throw new IllegalStateException(String.join("\n", shortageErrors));
        }

        Map<Integer, Integer> nextIndexByCategory = new HashMap<>();
        List<FboPrintPage> pages = new ArrayList<>();
        List<Kiz> usedKizs = new ArrayList<>();
        int pairNumber = 1;
        for (FboBarcodePrintItem item : safeItems) {
            FboProductSku product = item.product();
            for (int i = 0; i < item.quantity(); i++) {
                String kizCode = null;
                if (product.requiresKiz()) {
                    int categoryId = mappings.get(product.nmId());
                    int index = nextIndexByCategory.merge(categoryId, 1, Integer::sum) - 1;
                    Kiz kiz = kizByCategory.get(categoryId).get(index);
                    kizCode = kiz.getCode();
                    usedKizs.add(kiz);
                }
                pages.add(FboPrintPage.barcodeWithKiz(product, kizCode, pairNumber));
                pages.add(FboPrintPage.barcodeWithKiz(product, kizCode, pairNumber));
                pairNumber++;
            }
        }
        return new FboPrintPlan(List.copyOf(pages), List.copyOf(usedKizs));
    }

    private Map<Integer, String> findCategoryNames(List<Integer> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(categoryIds.size(), "?"));
        String sql = "SELECT id, name FROM categories WHERE id IN (" + placeholders + ")";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < categoryIds.size(); i++) {
                ps.setInt(i + 1, categoryIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<Integer, String> names = new HashMap<>();
                while (rs.next()) {
                    names.put(rs.getInt("id"), rs.getString("name"));
                }
                return names;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
