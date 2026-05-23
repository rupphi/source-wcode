package com.tuandev.fbsbarcode.features.dashboard;

import java.util.List;

public record DashboardData(
        DashboardKpis kpis,
        List<DashboardProductMetric> topSelling,
        List<DashboardProductMetric> potentialProducts,
        String analyticsError
) {
    public boolean hasAnalyticsError() {
        return analyticsError != null && !analyticsError.isBlank();
    }
}
