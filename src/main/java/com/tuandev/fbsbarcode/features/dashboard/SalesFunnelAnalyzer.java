package com.tuandev.fbsbarcode.features.dashboard;

import com.tuandev.fbsbarcode.integration.wb.SalesFunnelResponse;
import com.tuandev.fbsbarcode.integration.wb.SalesFunnelResponse.SalesFunnelProductItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class SalesFunnelAnalyzer {
    public List<DashboardProductMetric> topSelling(List<SalesFunnelProductItem> items, Map<Long, DashboardProductInfo> products) {
        return metrics(items, products).stream()
                .sorted(Comparator.comparingLong(DashboardProductMetric::orders).reversed()
                        .thenComparing(Comparator.comparingDouble(DashboardProductMetric::revenue).reversed()))
                .limit(10)
                .toList();
    }

    public List<DashboardProductMetric> potentialProducts(List<SalesFunnelProductItem> items, Map<Long, DashboardProductInfo> products) {
        return metrics(items, products).stream()
                .filter(metric -> metric.score() >= 4)
                .sorted(Comparator.comparingInt(DashboardProductMetric::score).reversed()
                        .thenComparing(Comparator.comparingLong(DashboardProductMetric::orders).reversed()))
                .limit(10)
                .toList();
    }

    public List<Long> nmIds(List<SalesFunnelProductItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(item -> item.getProduct().getNmId())
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private List<DashboardProductMetric> metrics(List<SalesFunnelProductItem> items, Map<Long, DashboardProductInfo> products) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DashboardProductMetric> result = new ArrayList<>();
        for (SalesFunnelProductItem item : items) {
            if (item == null || item.getProduct().getNmId() == null || item.getProduct().getNmId() <= 0) {
                continue;
            }
            result.add(metric(item, products == null ? Map.of() : products));
        }
        return result;
    }

    private DashboardProductMetric metric(SalesFunnelProductItem item, Map<Long, DashboardProductInfo> products) {
        SalesFunnelResponse.SalesFunnelProduct product = item.getProduct();
        SalesFunnelResponse.SalesFunnelPeriodStats selected = item.getSelected();
        SalesFunnelResponse.SalesFunnelComparison comparison = item.getComparison();
        long nmId = product.getNmId();
        DashboardProductInfo local = products.get(nmId);

        boolean demand = selected.getOpenCount() > 0
                && (selected.getCartCount() > 0 || selected.getOrderCount() > 0 || selected.getAddToWishlist() > 0);
        boolean conversion = selected.getAddToCartPercent() >= 10.0 && selected.getCartToOrderPercent() >= 40.0;
        boolean trust = product.getProductRating() >= 4.0 && product.getFeedbackRating() >= 4.0;
        boolean lowRisk = selected.getCancelCount() == 0
                || selected.getCancelCount() / (double) Math.max(selected.getOrderCount(), 1L) <= 0.20;
        boolean growth = comparison.getOrderCountDynamic() > 0
                || comparison.getCartCountDynamic() > 0
                || comparison.getAddToWishlistDynamic() > 0
                || selected.getLocalizationPercent() < 60.0
                || (selected.getStocks().getBalanceSum() <= 5 && demand);

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (demand) {
            score++;
            reasons.add("có nhu cầu");
        }
        if (conversion) {
            score++;
            reasons.add("conversion tốt");
        }
        if (trust) {
            score++;
            reasons.add("rating cao");
        }
        if (lowRisk) {
            score++;
            reasons.add("hủy thấp");
        }
        if (growth) {
            score++;
            reasons.add("còn dư địa tăng");
        }

        String localTitle = local == null ? "" : local.title();
        String name = firstNonBlank(product.getDisplayName(), localTitle, "nmID " + nmId);
        String vendorCode = firstNonBlank(product.getVendorCode(), local == null ? "" : local.vendorCode());
        String imageUrl = local == null ? "" : local.imageUrl();
        return new DashboardProductMetric(
                nmId,
                name,
                vendorCode,
                imageUrl,
                selected.getOrderCount(),
                selected.getOrderSum(),
                selected.getOpenCount(),
                selected.getCartCount(),
                selected.getAddToWishlist(),
                selected.getCancelCount(),
                selected.getAddToCartPercent(),
                selected.getCartToOrderPercent(),
                product.getProductRating(),
                product.getFeedbackRating(),
                selected.getStocks().getBalanceSum(),
                score,
                List.copyOf(reasons)
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
