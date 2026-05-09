package com.tuandev.fbsbarcode.ui.workspace;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;

public class WorkspaceHeaderController {
    @FXML
    private Label currentShopLabel;

    @FXML
    private ProgressIndicator syncLoading;

    @FXML
    private Button syncButton;

    @FXML
    private Button exportButton;

    @FXML
    private Button editShopButton;

    @FXML
    private Button deleteShopButton;

    private Runnable onSync;
    private Runnable onExport;
    private Runnable onEditShop;
    private Runnable onDeleteShop;

    @FXML
    private void onSync() {
        if (onSync != null) {
            onSync.run();
        }
    }

    @FXML
    private void onExport() {
        if (onExport != null) {
            onExport.run();
        }
    }

    @FXML
    private void onEditShop() {
        if (onEditShop != null) {
            onEditShop.run();
        }
    }

    @FXML
    private void onDeleteShop() {
        if (onDeleteShop != null) {
            onDeleteShop.run();
        }
    }

    public void setOnSync(Runnable onSync) {
        this.onSync = onSync;
    }

    public void setOnExport(Runnable onExport) {
        this.onExport = onExport;
    }

    public void setOnEditShop(Runnable onEditShop) {
        this.onEditShop = onEditShop;
    }

    public void setOnDeleteShop(Runnable onDeleteShop) {
        this.onDeleteShop = onDeleteShop;
    }

    public void setCurrentShopName(String shopName) {
        currentShopLabel.setText(shopName == null ? "" : shopName);
    }

    public void setBusy(boolean busy) {
        syncLoading.setVisible(busy);
    }

    public void setControls(boolean hasShop, boolean busy, boolean exportEnabled) {
        syncButton.setDisable(!hasShop || busy);
        editShopButton.setDisable(!hasShop || busy);
        deleteShopButton.setDisable(!hasShop || busy);
        exportButton.setDisable(!exportEnabled || busy);
    }
}
