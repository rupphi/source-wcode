package com.tuandev.fbsbarcode.services;

import com.google.zxing.WriterException;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OrderExportWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderExportWorkflow.class);

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = assignKizCodes(
                workingOrders,
                request.shop(),
                request.kizCommand()
        );

        exportPdfFiles(request.printType(), request.outputFile(), request.detailsFile(), workingOrders);

        if (!usedKizs.isEmpty()) {
            List<String> failures = attachKizCodes(request.shop(), workingOrders);
            if (!failures.isEmpty()) {
                throw new IllegalStateException(String.join(System.lineSeparator(), failures));
            }
            KizService.deleteKizs(usedKizs);
        }

        return new ExportResult(workingOrders, usedKizs);
    }

    private static List<Order> copyOrders(List<Order> orders) {
        List<Order> copies = new ArrayList<>(orders.size());
        for (Order order : orders) {
            Order copy = new Order(
                    order.getId(),
                    order.getImage(),
                    order.getBrand(),
                    order.getName(),
                    order.getSize(),
                    order.getColor(),
                    order.getArticle(),
                    order.getSticker(),
                    order.getBarcode()
            );
            copy.setKiz(order.getKiz());
            copy.setStickerCode(order.getStickerCode());
            copies.add(copy);
        }
        return copies;
    }

    private static List<Kiz> assignKizCodes(List<Order> orders, Shop shop, String commandText) {
        for (Order order : orders) {
            order.setKiz(null);
        }

        List<Kiz> usedKizs = new ArrayList<>();
        List<KizCommandParser.KizRange> ranges = KizCommandParser.parse(commandText);
        for (KizCommandParser.KizRange range : ranges) {
            if (range.to() > orders.size()) {
                throw new IllegalArgumentException("Vị trí order vượt quá số lượng đơn: " + range.rawLine());
            }

            List<Kiz> kizList = KizService.getKizs(shop.getId(), range.categoryId(), range.count());
            if (kizList.size() != range.count()) {
                throw new IllegalStateException("Không lấy đủ KIZ cho dòng: " + range.rawLine());
            }

            for (int offset = 0; offset < range.count(); offset++) {
                int orderIndex = range.from() - 1 + offset;
                Order order = orders.get(orderIndex);
                if (order.getKiz() != null) {
                    throw new IllegalStateException("Order thứ " + (orderIndex + 1) + " bị gán KIZ trùng nhau");
                }

                Kiz kiz = kizList.get(offset);
                order.setKiz(kiz.getCode());
                usedKizs.add(kiz);
            }
        }

        return usedKizs;
    }

    private static void exportPdfFiles(int printType, File outputFile, File detailsFile, List<Order> orders) throws IOException, WriterException {
        if (printType == 1) {
            GenerateBarcode.type1(orders, outputFile);
        } else if (printType == 2) {
            GenerateBarcode.type2(orders, outputFile);
        } else {
            GenerateBarcode.type3(orders, outputFile);
        }

        OrderService.exportOrdersToPdf(detailsFile, orders);
    }

    private static List<String> attachKizCodes(Shop shop, List<Order> orders) throws IOException {
        List<String> failures = new ArrayList<>();

        for (Order order : orders) {
            if (order.getKiz() == null || order.getKiz().isBlank()) {
                continue;
            }

            KizService.AttachCodeResult result = KizService.addDataMatrixCodeToOrder(
                    shop.getApiKey(),
                    order.getId(),
                    order.getKiz()
            );

            if (!result.success()) {
                String message = "Gắn KIZ thất bại cho order " + order.getId() + " (HTTP " + result.statusCode() + ")";
                if (!result.responseBody().isBlank()) {
                    message += ": " + result.responseBody();
                }
                failures.add(message);
                LOGGER.warn("Attach KIZ failed for order {} with status {} and body {}", order.getId(), result.statusCode(), result.responseBody());
            }
        }

        return failures;
    }

    public record ExportRequest(
            Shop shop,
            List<Order> orders,
            String kizCommand,
            int printType,
            File outputFile,
            File detailsFile
    ) {
    }

    public record ExportResult(List<Order> exportedOrders, List<Kiz> consumedKizs) {
    }
}
