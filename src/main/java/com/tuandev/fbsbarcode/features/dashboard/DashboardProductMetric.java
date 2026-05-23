package com.tuandev.fbsbarcode.features.dashboard;

import java.util.List;

public record DashboardProductMetric(
        long nmId,
        String name,
        String vendorCode,
        String imageUrl,
        long orders,
        double revenue,
        long opens,
        long carts,
        long wishlists,
        long cancels,
        double addToCartPercent,
        double cartToOrderPercent,
        double productRating,
        double feedbackRating,
        long stock,
        int score,
        List<String> reasons
) {
}
