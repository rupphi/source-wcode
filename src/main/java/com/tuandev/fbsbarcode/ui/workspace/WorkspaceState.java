package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;

import java.util.ArrayList;
import java.util.List;

final class WorkspaceState {
    private List<Shop> shops = new ArrayList<>();
    private List<Order> displayedOrders = new ArrayList<>();
    private List<Order> loadedOrdersRaw = new ArrayList<>();
    private Shop selectedShop;
    private Integer pendingSelectShopId;
    private String loadedSupplyId;
    private boolean supplyEnriching;

    List<Shop> getShops() {
        return shops;
    }

    void setShops(List<Shop> shops) {
        this.shops = shops == null ? new ArrayList<>() : new ArrayList<>(shops);
    }

    List<Order> getDisplayedOrders() {
        return displayedOrders;
    }

    void setDisplayedOrders(List<Order> displayedOrders) {
        this.displayedOrders = displayedOrders == null ? new ArrayList<>() : new ArrayList<>(displayedOrders);
    }

    List<Order> getLoadedOrdersRaw() {
        return loadedOrdersRaw;
    }

    void setLoadedOrdersRaw(List<Order> loadedOrdersRaw) {
        this.loadedOrdersRaw = loadedOrdersRaw == null ? new ArrayList<>() : new ArrayList<>(loadedOrdersRaw);
    }

    Shop getSelectedShop() {
        return selectedShop;
    }

    void setSelectedShop(Shop selectedShop) {
        this.selectedShop = selectedShop;
    }

    Integer getPendingSelectShopId() {
        return pendingSelectShopId;
    }

    void setPendingSelectShopId(Integer pendingSelectShopId) {
        this.pendingSelectShopId = pendingSelectShopId;
    }

    String getLoadedSupplyId() {
        return loadedSupplyId;
    }

    void setLoadedSupplyId(String loadedSupplyId) {
        this.loadedSupplyId = loadedSupplyId;
    }

    boolean isSupplyEnriching() {
        return supplyEnriching;
    }

    void setSupplyEnriching(boolean supplyEnriching) {
        this.supplyEnriching = supplyEnriching;
    }

    void clearWorkspace() {
        selectedShop = null;
        clearLoadedSupply();
        displayedOrders.clear();
    }

    void clearLoadedSupply() {
        loadedSupplyId = null;
        loadedOrdersRaw = new ArrayList<>();
        displayedOrders = new ArrayList<>();
        supplyEnriching = false;
    }
}
