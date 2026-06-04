package com.tuandev.fbsbarcode.features.packing;

import com.tuandev.fbsbarcode.features.print.SupplyBarcodePdfExporter;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.integration.wb.WbActionLogRepository;
import com.tuandev.fbsbarcode.integration.wb.WbApiClient;
import com.tuandev.fbsbarcode.integration.wb.WbCreateSupplyResponse;
import com.tuandev.fbsbarcode.integration.wb.WbOrderRepository;
import com.tuandev.fbsbarcode.integration.wb.WbOrderSyncService;
import com.tuandev.fbsbarcode.integration.wb.WbProductSyncService;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class PackingWorkflow {
    private static final int ADD_ORDER_BATCH_SIZE = 100;
    private static final DateTimeFormatter SHIPMENT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WbApiClient apiClient = new WbApiClient();
    private final WbOrderRepository orderRepository = new WbOrderRepository();
    private final WbProductSyncService productSyncService = new WbProductSyncService();
    private final WbSupplyRepository supplyRepository = new WbSupplyRepository();
    private final WbSupplyWorkflow supplyWorkflow = new WbSupplyWorkflow();
    private final WbSyncWorkflow syncWorkflow = new WbSyncWorkflow();
    private final WbOrderSyncService orderSyncService = new WbOrderSyncService();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();
    private final WbActionLogRepository actionLogRepository = new WbActionLogRepository();

    public PackingBoard loadBoard(Shop shop) {
        List<Order> newOrders = supplyWorkflow.populateCachedOrderImages(orderRepository.getOrdersForPackingStatus(shop.getId(), "new"));
        List<WbSupplySummary> allSupplies = supplyWorkflow.getSupplies(shop.getId());
        List<WbSupplySummary> preparationSupplies = allSupplies.stream()
                .filter(supply -> !supply.isDone())
                .toList();
        List<WbSupplySummary> dispatchSupplies = allSupplies.stream()
                .filter(WbSupplySummary::isDone)
                .toList();
        return new PackingBoard(newOrders, preparationSupplies, dispatchSupplies);
    }

    public void refreshBoardData(Shop shop) throws IOException {
        try {
            orderSyncService.syncNewOrders(shop);
            syncMissingProductsForNewOrders(shop);
            syncWorkflow.refetchSupplies(shop);
        } catch (IOException ex) {
            if (isTimeout(ex)) {
                return;
            }
            throw ex;
        }
    }

    public List<Order> loadSupplyOrders(Shop shop, String supplyId) {
        return supplyWorkflow.loadOrdersForSupplyLocal(shop, supplyId);
    }

    public String defaultShipmentName() {
        return "Shipment " + LocalDate.now().format(SHIPMENT_DATE_FORMAT);
    }

    public String createShipment(Shop shop, String name, List<Long> orderIds) throws IOException {
        String requestJson = "{\"name\":\"" + sanitize(name) + "\",\"orders\":" + orderIds + "}";
        try {
            WbCreateSupplyResponse response = apiClient.createSupply(shop.getApiKey(), name);
            String supplyId = response == null ? null : response.getId();
            if (supplyId == null || supplyId.isBlank()) {
                throw new IOException("WB không trả về supplyId sau khi tạo shipment");
            }
            supplyRepository.saveCreatedSupply(shop.getId(), supplyId, name);
            addOrdersToSupply(shop, supplyId, orderIds);
            actionLogRepository.record(shop.getId(), "CREATE_SUPPLY", supplyId, orderIds, "success", requestJson, "{\"id\":\"" + supplyId + "\"}", null);
            return supplyId;
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "CREATE_SUPPLY", null, orderIds, "failed", requestJson, null, ex.getMessage());
            throw ex;
        }
    }

    public void addOrdersToSupply(Shop shop, String supplyId, List<Long> orderIds) throws IOException {
        List<Long> safeOrderIds = orderIds == null ? List.of() : new ArrayList<>(orderIds);
        try {
            for (int i = 0; i < safeOrderIds.size(); i += ADD_ORDER_BATCH_SIZE) {
                List<Long> batch = safeOrderIds.subList(i, Math.min(i + ADD_ORDER_BATCH_SIZE, safeOrderIds.size()));
                apiClient.addOrdersToSupply(shop.getApiKey(), supplyId, batch);
                actionLogRepository.record(shop.getId(), "ADD_ORDERS_TO_SUPPLY", supplyId, batch, "success", "{\"orders\":" + batch + "}", null, null);
            }
            // Reflect successful WB assignment locally even if follow-up sync is slow.
            orderRepository.replaceSupplyOrders(shop.getId(), supplyId, safeOrderIds);
            try {
                syncWorkflow.syncSupplyOrdersAndStatuses(shop, supplyId);
            } catch (IOException ex) {
                if (!isTimeout(ex)) {
                    throw ex;
                }
            }
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "ADD_ORDERS_TO_SUPPLY", supplyId, safeOrderIds, "failed", "{\"orders\":" + safeOrderIds + "}", null, ex.getMessage());
            throw ex;
        }
    }

    public void deliverSupply(Shop shop, WbSupplySummary supply) throws IOException {
        if (!printHistoryService.hasSuccessfulJobForSupply(shop.getId(), supply.getSupplyId())) {
            throw new IllegalStateException("Сначала распечатайте этикетки для поставки.");
        }
        if (orderRepository.hasRequiredMetaWithoutPrintedKiz(shop.getId(), supply.getSupplyId())) {
            throw new IllegalStateException("В поставке есть товары с обязательной маркировкой без KIZ.");
        }
        try {
            apiClient.deliverSupply(shop.getApiKey(), supply.getSupplyId());
            supplyRepository.markSupplyDelivered(shop.getId(), supply.getSupplyId());
            syncWorkflow.syncSupplyOrdersAndStatuses(shop, supply.getSupplyId());
            actionLogRepository.record(shop.getId(), "DELIVER_SUPPLY", supply.getSupplyId(), List.of(), "success", null, null, null);
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "DELIVER_SUPPLY", supply.getSupplyId(), List.of(), "failed", null, null, ex.getMessage());
            throw ex;
        }
    }

    public byte[] getSupplyBarcode(Shop shop, WbSupplySummary supply) throws IOException {
        try {
            byte[] bytes = apiClient.getSupplyBarcode(shop.getApiKey(), supply.getSupplyId(), "png");
            actionLogRepository.record(shop.getId(), "GET_SUPPLY_BARCODE", supply.getSupplyId(), List.of(), "success", null, "bytes=" + bytes.length, null);
            return bytes;
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "GET_SUPPLY_BARCODE", supply.getSupplyId(), List.of(), "failed", null, null, ex.getMessage());
            throw ex;
        }
    }

    public byte[] getSupplyBarcodePdf(Shop shop, WbSupplySummary supply) throws IOException {
        byte[] imageBytes = getSupplyBarcode(shop, supply);
        return SupplyBarcodePdfExporter.exportSingleLabel(imageBytes);
    }

    public boolean canDeliver(int shopId, WbSupplySummary supply) {
        return supply != null
                && !supply.isDone()
                && supply.getItemCount() > 0
                && printHistoryService.hasSuccessfulJobForSupply(shopId, supply.getSupplyId());
    }

    private void syncMissingProductsForNewOrders(Shop shop) throws IOException {
        if (!orderRepository.hasMissingProductsForPackingStatus(shop.getId(), "new")) {
            return;
        }
        try {
            productSyncService.sync(shop);
        } catch (WbApiException ex) {
            if (ex.isContentPermissionError()) {
                return;
            }
            if (ex.isRateLimited()) {
                return;
            }
            throw ex;
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private boolean isTimeout(IOException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record PackingBoard(List<Order> newOrders, List<WbSupplySummary> preparationSupplies, List<WbSupplySummary> dispatchSupplies) {
    }
}
