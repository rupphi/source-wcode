package com.tuandev.fbsbarcode.features.print;

import com.google.zxing.WriterException;
import com.tuandev.fbsbarcode.features.kiz.KizCommandParser;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class OrderExportWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderExportWorkflow.class);
    private final BarcodePrintService barcodePrintService = new BarcodePrintService();
    private final OrderDetailsPdfExporter orderDetailsPdfExporter = new OrderDetailsPdfExporter();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = List.of();
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        String printedAt = Instant.now().toString();
        boolean successRecorded = false;
        try {
            usedKizs = assignKizCodes(
                    workingOrders,
                    request.shop(),
                    request.kizCommand()
            );

            wbSupplyWorkflow.ensureOrderImages(workingOrders);
            exportPdfFiles(template, request.outputFile(), request.detailsFile(), request.shop(), request.supplyId(), request.supplyName(), printedAt, workingOrders);
            printHistoryService.recordSuccessfulJob(request.shop(), request.supplyId(), request.supplyName(), printedAt, template, workingOrders);
            successRecorded = true;

            if (!usedKizs.isEmpty()) {
                List<String> failures = attachKizCodes(request.shop(), workingOrders);
                if (!failures.isEmpty()) {
                    throw new IllegalStateException(String.join(System.lineSeparator(), failures));
                }
                KizService.deleteKizs(usedKizs);
            }

            return new ExportResult(workingOrders, usedKizs);
        } catch (IOException | WriterException | RuntimeException ex) {
            if (!successRecorded) {
                printHistoryService.recordFailedJob(
                        request.shop(),
                        request.supplyId(),
                        request.supplyName(),
                        printedAt,
                        template,
                        request.orders().size(),
                        ex.getMessage()
                );
            }
            throw ex;
        }
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
            copy.setImageUrl(order.getImageUrl());
            copy.setSubjectName(order.getSubjectName());
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

    private void exportPdfFiles(PrintTemplate template,
                                File outputFile,
                                File detailsFile,
                                Shop shop,
                                String supplyId,
                                String supplyName,
                                String printedAt,
                                List<Order> orders) throws IOException, WriterException {
        barcodePrintService.export(template, orders, outputFile);
        orderDetailsPdfExporter.export(detailsFile, orders, new OrderDetailsPdfExporter.PrintDetailsMetadata(
                supplyId,
                supplyName,
                shop == null ? null : shop.getName(),
                printedAt,
                orders.size()
        ));
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
            String supplyId,
            String supplyName,
            List<Order> orders,
            String kizCommand,
            File outputFile,
            File detailsFile
    ) {
    }

    public record ExportResult(List<Order> exportedOrders, List<Kiz> consumedKizs) {
    }
}
