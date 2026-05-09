package com.tuandev.fbsbarcode.ui.supply;

public record OrderSortOptions(
        boolean bySubject,
        boolean byArticle,
        boolean byColor,
        boolean bySize
) {
    public static OrderSortOptions defaultOptions() {
        return new OrderSortOptions(true, true, true, true);
    }
}
