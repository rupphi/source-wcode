package com.tuandev.fbsbarcode.services;

import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.models.Sticker;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OrderImportWorkflow {
    public List<Order> importOrders(File file, Shop shop) throws Exception {
        List<Order> orders = OrderService.getOrdersToExcel(file);

        Comparator<String> naturalStringComparator =
                Comparator.nullsLast(NaturalOrderComparator::compareIgnoreCase);

        orders.sort(
                Comparator.comparing(Order::getArticle, naturalStringComparator)
                        .thenComparing(Order::getId, Comparator.nullsLast(Long::compareTo))
        );

        if (orders.isEmpty()) {
            return orders;
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<Sticker> stickers = OrderService.getStickers(shop.getApiKey(), orderIds);
        Map<Long, String> stickerMap = stickers.stream()
                .collect(Collectors.toMap(
                        Sticker::getOrderId,
                        Sticker::getBarcode
                ));

        for (Order order : orders) {
            String barcode = stickerMap.get(order.getId());
            if (barcode != null) {
                order.setStickerCode(barcode);
            }
        }

        return orders;
    }
}
