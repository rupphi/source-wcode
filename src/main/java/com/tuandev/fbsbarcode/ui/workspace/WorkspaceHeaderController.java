package com.tuandev.fbsbarcode.ui.workspace;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import com.tuandev.fbsbarcode.models.Shop;
import java.util.List;
import java.util.function.Consumer;

public class WorkspaceHeaderController {
    @FXML
    private ComboBox<Shop> shopComboBox;

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
    private Consumer<Shop> onShopSelected;

    @FXML
    public void initialize() {
        shopComboBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Shop item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        shopComboBox.setButtonCell(shopComboBox.getCellFactory().call(null));
        shopComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && onShopSelected != null) {
                onShopSelected.accept(newVal);
            }
        });
    }

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

    public void setOnShopSelected(Consumer<Shop> onShopSelected) {
        this.onShopSelected = onShopSelected;
    }

    public void setShops(List<Shop> shops, Shop selectedShop) {
        Shop currentSelection = shopComboBox.getValue();
        shopComboBox.getItems().setAll(shops);
        if (selectedShop != null) {
            shopComboBox.getSelectionModel().select(selectedShop);
        } else if (currentSelection != null) {
            shopComboBox.getSelectionModel().select(currentSelection);
        }
    }

    public void setBusy(boolean busy) {
        syncLoading.setVisible(busy);
    }

    public void setControls(boolean hasShop, boolean busy, boolean exportEnabled, boolean tokenValid) {
        syncButton.setDisable(!hasShop || busy || !tokenValid);
        editShopButton.setDisable(!hasShop || busy);
        deleteShopButton.setDisable(!hasShop || busy || !tokenValid);
        exportButton.setDisable(!exportEnabled || busy || !tokenValid);
    }
}
