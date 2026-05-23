package com.tuandev.fbsbarcode.features.print;

import com.google.zxing.WriterException;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
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
    private final KizMappingRepository kizMappingRepository = new KizMappingRepository();

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = List.of();
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        String printedAt = Instant.now().toString();
        boolean successRecorded = false;
        try {
            usedKizs = assignKizCodes(workingOrders, request.shop());

            wbSupplyWorkflow.ensureOrderImages(workingOrders);
            exportPdfFiles(template, request.outputFile(), request.detailsFile(), request.shop(), request.supplyId(), request.supplyName(), printedAt, workingOrders, request.printOptions());
            long printJobId = printHistoryService.recordSuccessfulJob(request.shop(), request.supplyId(), request.supplyName(), printedAt, template, workingOrders);
            successRecorded = true;
            return new ExportResult(workingOrders, usedKizs, printJobId, buildKizAttachmentAssignments(workingOrders, usedKizs));
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
            copy.setCreatedAt(order.getCreatedAt());
            copy.setPrice(order.getPrice());
            copy.setSupplierStatus(order.getSupplierStatus());
            copy.setWbStatus(order.getWbStatus());
            copy.setNmId(order.getNmId());
            copy.setRequiresKiz(order.isRequiresKiz());
            copies.add(copy);
        }
        return copies;
    }

    private List<Kiz> assignKizCodes(List<Order> orders, Shop shop) {
        for (Order order : orders) {
            order.setKiz(null);
        }

        List<Kiz> usedKizs = new ArrayList<>();
        List<Long> nmIds = orders.stream()
                .map(Order::getNmId)
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        java.util.Map<Long, Integer> mappingByNmId = kizMappingRepository.findMappings(shop.getId(), nmIds);
        java.util.Map<Integer, List<Order>> ordersByCategory = new java.util.LinkedHashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            Integer categoryId = order.getNmId() == null ? null : mappingByNmId.get(order.getNmId());
            if (categoryId == null) {
                if (order.isRequiresKiz()) {
                    throw new IllegalStateException("Order thứ " + (i + 1) + " cần KIZ nhưng nmId chưa được map: " + order.getNmId());
                }
                continue;
            }
            ordersByCategory.computeIfAbsent(categoryId, key -> new ArrayList<>()).add(order);
        }

        for (java.util.Map.Entry<Integer, List<Order>> entry : ordersByCategory.entrySet()) {
            int categoryId = entry.getKey();
            List<Order> categoryOrders = entry.getValue();
            List<Kiz> kizList = KizService.getKizs(shop.getId(), categoryId, categoryOrders.size());
            if (kizList.size() != categoryOrders.size()) {
                throw new IllegalStateException("Không đủ KIZ cho category " + categoryId
                        + ": cần " + categoryOrders.size() + ", còn " + kizList.size());
            }
            for (int i = 0; i < categoryOrders.size(); i++) {
                Kiz kiz = kizList.get(i);
                categoryOrders.get(i).setKiz(kiz.getCode());
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
                                List<Order> orders,
                                PrintJobOptions printOptions) throws IOException, WriterException {
        barcodePrintService.export(template, orders, outputFile, printOptions);
        orderDetailsPdfExporter.export(detailsFile, orders, new OrderDetailsPdfExporter.PrintDetailsMetadata(
                supplyId,
                supplyName,
                shop == null ? null : shop.getName(),
                printedAt,
                orders.size()
        ));
    }

    private static List<KizAttachmentAssignment> buildKizAttachmentAssignments(List<Order> orders, List<Kiz> usedKizs) {
        if (orders == null || orders.isEmpty() || usedKizs == null || usedKizs.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, Kiz> kizByCode = usedKizs.stream()
                .collect(java.util.stream.Collectors.toMap(Kiz::getCode, value -> value, (left, right) -> left));
        List<KizAttachmentAssignment> assignments = new ArrayList<>();
        for (Order order : orders) {
            if (order.getId() == null || order.getKiz() == null || order.getKiz().isBlank()) {
                continue;
            }
            Kiz sourceKiz = kizByCode.get(order.getKiz());
            if (sourceKiz == null) {
                LOGGER.warn("Không tìm thấy KIZ nguồn để enqueue background attach cho order {}", order.getId());
                continue;
            }
            assignments.add(new KizAttachmentAssignment(order.getId(), order.getKiz(), sourceKiz));
        }
        return assignments;
    }

    public record ExportRequest(
            Shop shop,
            String supplyId,
            String supplyName,
            List<Order> orders,
            PrintJobOptions printOptions,
            File outputFile,
            File detailsFile
    ) {
    }

    public record ExportResult(
            List<Order> exportedOrders,
            List<Kiz> consumedKizs,
            long printJobId,
            List<KizAttachmentAssignment> kizAttachments
    ) {
    }

    public record KizAttachmentAssignment(Long orderId, String kizCode, Kiz sourceKiz) {
    }
}
