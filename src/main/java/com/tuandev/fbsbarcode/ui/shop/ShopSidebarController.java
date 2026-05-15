package com.tuandev.fbsbarcode.ui.shop;

import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class ShopSidebarController {
    @FXML
    private Button btnPrintHistory;

    private Runnable onPacking;
    private Runnable onPrintHistory;
    private Runnable onAddShop;
    private Runnable onOpenSettings;

    public void setOnPrintHistory(Runnable onPrintHistory) {
        this.onPrintHistory = onPrintHistory;
    }

    public void setOnPacking(Runnable onPacking) {
        this.onPacking = onPacking;
    }

    public void setOnAddShop(Runnable onAddShop) {
        this.onAddShop = onAddShop;
    }

    public void setOnOpenSettings(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    @FXML
    private void onPacking() {
        if (onPacking != null) {
            onPacking.run();
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
