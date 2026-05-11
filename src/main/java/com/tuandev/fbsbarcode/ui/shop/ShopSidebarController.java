package com.tuandev.fbsbarcode.ui.shop;

import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class ShopSidebarController {
    @FXML
    private Button btnDashboard;
    
    @FXML
    private Button btnSupplies;

    @FXML
    private Button btnPrintHistory;

    private Runnable onDashboard;
    private Runnable onSupplies;
    private Runnable onPrintHistory;
    private Runnable onAddShop;
    private Runnable onOpenSettings;

    public void setOnDashboard(Runnable onDashboard) {
        this.onDashboard = onDashboard;
    }

    public void setOnSupplies(Runnable onSupplies) {
        this.onSupplies = onSupplies;
    }

    public void setOnPrintHistory(Runnable onPrintHistory) {
        this.onPrintHistory = onPrintHistory;
    }

    public void setOnAddShop(Runnable onAddShop) {
        this.onAddShop = onAddShop;
    }

    public void setOnOpenSettings(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    @FXML
    private void onDashboard() {
        if (onDashboard != null) {
            onDashboard.run();
        }
    }

    @FXML
    private void onSupplies() {
        if (onSupplies != null) {
            onSupplies.run();
        }
    }

    @FXML
    private void onPrintHistory() {
        if (onPrintHistory != null) {
            onPrintHistory.run();
        }
    }

    @FXML
    private void onAddShop() {
        if (onAddShop != null) {
            onAddShop.run();
        }
    }

    @FXML
    private void onSettings() {
        if (onOpenSettings != null) {
            onOpenSettings.run();
        }
    }
}
