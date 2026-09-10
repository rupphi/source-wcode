package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.google.zxing.WriterException;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AtomicFilePublisher;
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
    private final ZnackGtinInventoryService inventoryService = new ZnackGtinInventoryService();
    private final MetadataLoader metadataLoader;

    public OrderExportWorkflow() {
        this(KizService::getSgtinMetadata);
    }

    OrderExportWorkflow(MetadataLoader metadataLoader) {
        this.metadataLoader = java.util.Objects.requireNonNull(metadataLoader);
    }

    @FunctionalInterface
    interface MetadataLoader {
        java.util.Map<Long, KizService.SgtinMetadata> load(String apiKey, List<Long> orderIds) throws IOException;
    }

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        return export(request, null);
    }

    public ExportResult export(ExportRequest request, PreparedPrint prepared) throws IOException, WriterException {
        MarketplaceGuard.requireWildberries(request.shop());
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = List.of();
        Set<Long> replaceExistingOrderIds = Set.of();
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        String printedAt = Instant.now().toString();
        boolean successRecorded = false;
        boolean inventoryConsumed = false;
        File barcodeStaging = null;
        File detailsStaging = null;
        try {
            KizAssignmentResult assignmentResult;
            if (prepared == null) assignmentResult = assignKizCodes(workingOrders, request.shop());
            else {
                if (prepared.shopId != request.shop().getId() || prepared.closed.get())
                    throw new IllegalStateException("Prepared print no longer belongs to this shop.");
                if (!workingOrders.stream().map(Order::getId).toList().equals(prepared.orders.stream().map(Order::getId).toList()))
                    throw new IllegalStateException("Prepared order identities changed.");
                for (int i = 0; i < workingOrders.size(); i++) {
                    workingOrders.get(i).setKiz(prepared.orders.get(i).getKiz());
                    workingOrders.get(i).setRequiresKiz(prepared.orders.get(i).isRequiresKiz());
                }
                assignmentResult = prepared.assignment;
            }
            usedKizs = assignmentResult.usedKizs();
            replaceExistingOrderIds = assignmentResult.replaceExistingOrderIds();

            wbSupplyWorkflow.ensureOrderImages(workingOrders);
            barcodeStaging = AtomicFilePublisher.stagingFile(request.outputFile(), ".pdf");
            detailsStaging = AtomicFilePublisher.stagingFile(request.detailsFile(), ".pdf");
            exportPdfFiles(template, barcodeStaging, detailsStaging, request.shop(), request.supplyId(),
                    request.supplyName(), printedAt, workingOrders, request.printOptions());
            inventoryService.consume(request.shop().getId(), usedKizs);
            inventoryConsumed = true;
            if (prepared != null) prepared.closed.set(true);
            AtomicFilePublisher.publish(barcodeStaging, request.outputFile());
            AtomicFilePublisher.publish(detailsStaging, request.detailsFile());
            long printJobId = printHistoryService.recordSuccessfulJob(request.shop(), request.supplyId(), request.supplyName(), printedAt, template, workingOrders);
            successRecorded = true;
            return new ExportResult(workingOrders, usedKizs, printJobId,
                    buildKizAttachmentAssignments(workingOrders, usedKizs, replaceExistingOrderIds));
        } catch (IOException | WriterException | RuntimeException ex) {
            if (!inventoryConsumed) {
                inventoryService.release(request.shop().getId(), usedKizs);
            }
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
        } finally {
            AtomicFilePublisher.deleteQuietly(barcodeStaging);
            AtomicFilePublisher.deleteQuietly(detailsStaging);
        }
    }

    public void verifyKizAvailability(List<Order> orders, Shop shop) throws IOException, IllegalStateException {
        MarketplaceGuard.requireWildberries(shop);
        KizAssignmentResult result = assignKizCodes(copyOrders(orders), shop);
        inventoryService.release(shop.getId(), result.usedKizs());
    }

    public void prepareExplicitPrint(List<Order> orders, Shop shop) throws IOException {
        try (PreparedPrint prepared = reserveExplicitPrint(orders, shop)) { }
    }

    public PreparedPrint reserveExplicitPrint(List<Order> orders, Shop shop) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        List<Order> copies = copyOrders(orders);
        return new PreparedPrint(shop.getId(), copies, assignKizCodes(copies, shop, true));
    }

    public final class PreparedPrint implements AutoCloseable {
        private final int shopId;
        private final List<Order> orders;
        private final KizAssignmentResult assignment;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private PreparedPrint(int shopId, List<Order> orders, KizAssignmentResult assignment) {
            this.shopId = shopId; this.orders = orders; this.assignment = assignment;
        }
        public List<Order> orders() { return orders; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) inventoryService.release(shopId, assignment.usedKizs());
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
        return assignKizCodes(orders, shop, false);
    }

    private KizAssignmentResult assignKizCodes(List<Order> orders, Shop shop, boolean explicitPrint) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Print cancelled before preparation.");
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
        java.util.Map<Long, KizService.SgtinMetadata> metadataByOrderId = metadataLoader.load(shop.getApiKey(), orderIds);
        List<Long> nmIds = orders.stream()
                .map(Order::getNmId)
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        java.util.Map<Long, String> mappingByNmId = kizMappingRepository.findMappings(shop.getId(), nmIds);
        Set<Long> kizRequiredNmIds = kizMappingRepository.findKizRequiredNmIds(shop.getId(), nmIds);
        java.util.Map<String, List<Order>> ordersByGtin = new java.util.LinkedHashMap<>();
        Set<String> registeredGtins = new LinkedHashSet<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            KizService.SgtinMetadata sgtinMetadata = order.getId() == null ? null : metadataByOrderId.get(order.getId());
            String gtin = order.getNmId() == null ? null : mappingByNmId.get(order.getNmId());
            if (sgtinMetadata != null && sgtinMetadata.available() && sgtinMetadata.hasAppliedValue()) {
                order.setRequiresKiz(true);
                order.setKiz(sgtinMetadata.appliedValue());
                continue;
            }
            if (order.getNmId() != null) {
                try {
                    String registered = kizMappingRepository.registeredGtin(shop.getId(), order.getNmId(), order.getBarcode());
                    if (registered != null) { gtin = registered; registeredGtins.add(gtin); }
                } catch (IllegalStateException error) {
                    // If no category fallback mapping exists, preserve the registration awaiting message
                    if (gtin == null) throw error;
                }
            }
            boolean requiresKiz = order.isRequiresKiz() || isProductKizRequired(order, kizRequiredNmIds)
                    || (sgtinMetadata != null && sgtinMetadata.available());
            if (sgtinMetadata != null && sgtinMetadata.available()) {
                order.setRequiresKiz(true);
                if (sgtinMetadata.hasAppliedValue()) {
                    String appliedGtin = KizService.extractGtin(sgtinMetadata.appliedValue());
                    if (gtin == null || gtin.equals(appliedGtin)) {
                        order.setKiz(sgtinMetadata.appliedValue());
                        continue;
                    }
                    replaceExistingOrderIds.add(order.getId());
                }
            }
            if (!requiresKiz) {
                continue;
            }
            if (gtin == null) {
                throw new IllegalStateException("Order thứ " + (i + 1) + " cần KIZ nhưng nmId chưa được map: " + order.getNmId());
            }
            ordersByGtin.computeIfAbsent(gtin, key -> new ArrayList<>()).add(order);
        }

        try {
            for (java.util.Map.Entry<String, List<Order>> entry : ordersByGtin.entrySet()) {
                List<Order> gtinOrders = entry.getValue();
                if (explicitPrint) {
                    int available = inventoryService.availableCount(shop.getId(), entry.getKey());
                    if (available < gtinOrders.size()) {
                        String demand = "FBS:" + gtinOrders.stream().map(Order::getId).sorted().toList();
                        new com.tuandev.fbsbarcode.integration.znack.registration.WbPrintDemand()
                                .awaitAvailable(shop, entry.getKey(), gtinOrders.size(), demand);
                    }
                }
                if (Thread.currentThread().isInterrupted()) throw new IOException("Print cancelled before KIZ reservation.");
                List<Kiz> kizList = inventoryService.reserveAvailable(shop.getId(), entry.getKey(), gtinOrders.size());
                usedKizs.addAll(kizList);
                for (int i = 0; i < gtinOrders.size(); i++) {
                Kiz kiz = kizList.get(i);
                    gtinOrders.get(i).setKiz(kiz.getCode());
                }
            }
        } catch (RuntimeException | IOException e) {
            inventoryService.release(shop.getId(), usedKizs);
            throw e;
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
