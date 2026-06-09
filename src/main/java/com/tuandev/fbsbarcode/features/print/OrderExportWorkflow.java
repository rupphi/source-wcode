package com.tuandev.fbsbarcode.features.print;

import com.google.zxing.WriterException;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.kizmapping.AutoKizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.AutoKizMappingResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class OrderExportWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderExportWorkflow.class);
    private final BarcodePrintService barcodePrintService = new BarcodePrintService();
    private final OrderDetailsPdfExporter orderDetailsPdfExporter = new OrderDetailsPdfExporter();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();
    private final KizMappingRepository kizMappingRepository = new KizMappingRepository();
    private final AutoKizMappingRepository autoKizMappingRepository = new AutoKizMappingRepository();

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = List.of();
        Set<Long> replaceExistingOrderIds = Set.of();
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        String printedAt = Instant.now().toString();
        boolean successRecorded = false;
        try {
            KizAssignmentResult assignmentResult = assignKizCodes(workingOrders, request.shop());
            usedKizs = assignmentResult.usedKizs();
            replaceExistingOrderIds = assignmentResult.replaceExistingOrderIds();

            wbSupplyWorkflow.ensureOrderImages(workingOrders);
            exportPdfFiles(template, request.outputFile(), request.detailsFile(), request.shop(), request.supplyId(), request.supplyName(), printedAt, workingOrders, request.printOptions());
            long printJobId = printHistoryService.recordSuccessfulJob(request.shop(), request.supplyId(), request.supplyName(), printedAt, template, workingOrders);
            successRecorded = true;
            return new ExportResult(workingOrders, usedKizs, printJobId,
                    buildKizAttachmentAssignments(workingOrders, usedKizs, replaceExistingOrderIds));
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

    public void verifyKizAvailability(List<Order> orders, Shop shop) throws IOException, IllegalStateException {
        assignKizCodes(copyOrders(orders), shop);
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
            copy.setRuSize(order.getRuSize());
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

    private KizAssignmentResult assignKizCodes(List<Order> orders, Shop shop) throws IOException {
        for (Order order : orders) {
            order.setKiz(null);
        }

        List<Kiz> usedKizs = new ArrayList<>();
        Set<Long> replaceExistingOrderIds = new LinkedHashSet<>();
        List<Long> orderIds = orders.stream()
                .map(Order::getId)
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        java.util.Map<Long, KizService.SgtinMetadata> metadataByOrderId = KizService.getSgtinMetadata(shop.getApiKey(), orderIds);
        List<Long> nmIds = orders.stream()
                .map(Order::getNmId)
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        java.util.Map<Long, Integer> mappingByNmId = kizMappingRepository.findMappings(shop.getId(), nmIds);
        Set<Long> kizRequiredNmIds = kizMappingRepository.findKizRequiredNmIds(shop.getId(), nmIds);
        if (!kizRequiredNmIds.isEmpty()) {
            AutoKizMappingResult mappingResult = autoKizMappingRepository.autoCreateAndMap(shop.getId());
            if (mappingResult.mappingsCreated() > 0 || mappingResult.categoriesCreated() > 0) {
                LOGGER.info("Auto KIZ mapping before print for shop {} created {} categories and {} mappings",
                        shop.getId(), mappingResult.categoriesCreated(), mappingResult.mappingsCreated());
                mappingByNmId = kizMappingRepository.findMappings(shop.getId(), nmIds);
            }
        }
        java.util.Map<Integer, List<Order>> ordersByCategory = new java.util.LinkedHashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            KizService.SgtinMetadata sgtinMetadata = order.getId() == null ? null : metadataByOrderId.get(order.getId());
            Integer categoryId = order.getNmId() == null ? null : mappingByNmId.get(order.getNmId());
            if (sgtinMetadata != null && sgtinMetadata.available()) {
                order.setRequiresKiz(true);
                if (sgtinMetadata.hasAppliedValue()) {
                    if (categoryId == null) {
                        order.setKiz(sgtinMetadata.appliedValue());
                        continue;
                    }
                    replaceExistingOrderIds.add(order.getId());
                }
            }
            if (categoryId == null) {
                if (order.isRequiresKiz() || isProductKizRequired(order, kizRequiredNmIds)) {
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

        return new KizAssignmentResult(usedKizs, Set.copyOf(replaceExistingOrderIds));
    }

    private boolean isProductKizRequired(Order order, Set<Long> kizRequiredNmIds) {
        return order != null
                && order.getNmId() != null
                && kizRequiredNmIds != null
                && kizRequiredNmIds.contains(order.getNmId());
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

    private static List<KizAttachmentAssignment> buildKizAttachmentAssignments(List<Order> orders,
                                                                              List<Kiz> usedKizs,
                                                                              Set<Long> replaceExistingOrderIds) {
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
            if (!order.isRequiresKiz()) {
                continue;
            }
            Kiz sourceKiz = kizByCode.get(order.getKiz());
            if (sourceKiz == null) {
                LOGGER.warn("Không tìm thấy KIZ nguồn để enqueue background attach cho order {}", order.getId());
                continue;
            }
            boolean replaceExisting = replaceExistingOrderIds != null && replaceExistingOrderIds.contains(order.getId());
            assignments.add(new KizAttachmentAssignment(order.getId(), order.getKiz(), sourceKiz, replaceExisting));
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

    public record KizAttachmentAssignment(Long orderId, String kizCode, Kiz sourceKiz, boolean replaceExisting) {
    }

    private record KizAssignmentResult(List<Kiz> usedKizs, Set<Long> replaceExistingOrderIds) {
    }
}
