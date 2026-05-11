package com.tuandev.fbsbarcode.features.supply;

import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.IOException;
import java.util.List;

public class SupplyLoadWorkflow {
    private final WbSyncWorkflow wbSyncWorkflow = new WbSyncWorkflow();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();

    public List<Order> loadLocal(Shop shop, String supplyId) {
        return wbSupplyWorkflow.loadOrdersForSupplyLocal(shop, supplyId);
    }

    public List<Order> refreshSupplyData(Shop shop, String supplyId) throws IOException {
        wbSyncWorkflow.syncSupplyOrdersAndStatuses(shop, supplyId);
        return wbSupplyWorkflow.loadOrdersForSupplyLocal(shop, supplyId);
    }

    public List<Order> enrichStickers(Shop shop, List<Order> orders) throws IOException {
        wbSupplyWorkflow.enrichOrderStickers(shop, orders);
        return orders;
    }
}
