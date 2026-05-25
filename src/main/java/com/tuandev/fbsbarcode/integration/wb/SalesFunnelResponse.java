package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

public class SalesFunnelResponse {
    private static final Type ITEM_LIST_TYPE = new TypeToken<List<SalesFunnelProductItem>>() {}.getType();

    private JsonElement data;
    private List<SalesFunnelProductItem> products;

    public List<SalesFunnelProductItem> getItems() {
        if (data != null) {
            if (data.isJsonArray()) {
                return WbJson.GSON.fromJson(data, ITEM_LIST_TYPE);
            }
            if (data.isJsonObject() && data.getAsJsonObject().has("products")) {
                return WbJson.GSON.fromJson(data.getAsJsonObject().get("products"), ITEM_LIST_TYPE);
            }
        }
        return products == null ? List.of() : products;
    }

    public static class SalesFunnelProductItem {
        private SalesFunnelProduct product;
        private SalesFunnelStatistic statistic;
        private SalesFunnelPeriodStats selectedPeriod;
        private SalesFunnelPeriodStats pastPeriod;
        private SalesFunnelComparison comparison;

        public SalesFunnelProduct getProduct() {
            return product == null ? new SalesFunnelProduct() : product;
        }

        public SalesFunnelStatistic getStatistic() {
            return statistic == null ? new SalesFunnelStatistic() : statistic;
        }

        public SalesFunnelPeriodStats getSelected() {
            SalesFunnelPeriodStats selected = selectedPeriod != null ? selectedPeriod : getStatistic().getSelectedPeriod();
            if (selected == null) {
                selected = new SalesFunnelPeriodStats();
            }
            selected.inherit(getStatistic().getConversions(), getStatistic().getStocks(), getProduct().getStocks());
            return selected;
        }

        public SalesFunnelComparison getComparison() {
            SalesFunnelComparison value = comparison != null ? comparison : getStatistic().getComparison();
            return value == null ? new SalesFunnelComparison() : value;
        }
    }

    public static class SalesFunnelProduct {
        @SerializedName(value = "nmID", alternate = {"nmId", "nm_id"})
        private Long nmId;
        private String vendorCode;
        private String brandName;
        private String name;
        private String title;
        private Double productRating;
        private Double feedbackRating;
        private Double rating;
        private SalesFunnelStocks stocks;

        public Long getNmId() {
            return nmId;
        }

        public String getVendorCode() {
            return vendorCode;
        }

        public String getDisplayName() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return title == null ? "" : title;
        }

        public double getProductRating() {
            return firstNumber(productRating, rating);
        }

        public double getFeedbackRating() {
            return firstNumber(feedbackRating, rating);
        }

        public SalesFunnelStocks getStocks() {
            return stocks == null ? new SalesFunnelStocks() : stocks;
        }
    }

    public static class SalesFunnelStatistic {
        private SalesFunnelPeriodStats selected;
        private SalesFunnelPeriodStats past;
        private SalesFunnelPeriodStats selectedPeriod;
        private SalesFunnelPeriodStats pastPeriod;
        private SalesFunnelComparison comparison;
        private SalesFunnelConversions conversions;
        private SalesFunnelStocks stocks;

        public SalesFunnelPeriodStats getSelectedPeriod() {
            return selected != null ? selected : selectedPeriod;
        }

        public SalesFunnelComparison getComparison() {
            return comparison;
        }

        public SalesFunnelConversions getConversions() {
            return conversions;
        }

        public SalesFunnelStocks getStocks() {
            return stocks;
        }
    }

    public static class SalesFunnelPeriodStats {
        private Long openCount;
        private Long cartCount;
        private Long orderCount;
        private Double orderSum;
        private Long addToWishlist;
        private Long cancelCount;
        private SalesFunnelConversions conversions;
        private SalesFunnelStocks stocks;
        private Double addToCartPercent;
        private Double cartToOrderPercent;
        private Double localizationPercent;

        public long getOpenCount() {
            return safe(openCount);
        }

        public long getCartCount() {
            return safe(cartCount);
        }

        public long getOrderCount() {
            return safe(orderCount);
        }

        public double getOrderSum() {
            return safe(orderSum);
        }

        public long getAddToWishlist() {
            return safe(addToWishlist);
        }

        public long getCancelCount() {
            return safe(cancelCount);
        }

        public double getAddToCartPercent() {
            return conversions == null ? safe(addToCartPercent) : firstNumber(conversions.addToCartPercent, addToCartPercent);
        }

        public double getCartToOrderPercent() {
            return conversions == null ? safe(cartToOrderPercent) : firstNumber(conversions.cartToOrderPercent, cartToOrderPercent);
        }

        public double getLocalizationPercent() {
            return safe(localizationPercent);
        }

        public SalesFunnelStocks getStocks() {
            return stocks == null ? new SalesFunnelStocks() : stocks;
        }

        private void inherit(SalesFunnelConversions fallbackConversions, SalesFunnelStocks fallbackStocks, SalesFunnelStocks productStocks) {
            if (conversions == null) {
                conversions = fallbackConversions;
            }
            if (stocks == null) {
                stocks = fallbackStocks == null ? productStocks : fallbackStocks;
            }
        }
    }

    public static class SalesFunnelComparison {
        private Double orderCountDynamic;
        private Double cartCountDynamic;
        private Double addToWishlistDynamic;

        public double getOrderCountDynamic() {
            return safe(orderCountDynamic);
        }

        public double getCartCountDynamic() {
            return safe(cartCountDynamic);
        }

        public double getAddToWishlistDynamic() {
            return safe(addToWishlistDynamic);
        }
    }

    public static class SalesFunnelConversions {
        private Double addToCartPercent;
        private Double cartToOrderPercent;
    }

    public static class SalesFunnelStocks {
        private Long balanceSum;

        public long getBalanceSum() {
            return safe(balanceSum);
        }
    }

    private static long safe(Long value) {
        return value == null ? 0L : value;
    }

    private static double safe(Double value) {
        return value == null ? 0.0 : value;
    }

    private static double firstNumber(Double first, Double second) {
        return first == null ? safe(second) : first;
    }
}
