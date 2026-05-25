package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import com.tuandev.fbsbarcode.models.Shop;
import java.util.List;
import java.util.function.Consumer;

public class WorkspaceHeaderController {
    @FXML
    private ComboBox<Shop> shopComboBox;

    @FXML
    private Button syncButton;

    @FXML
    private Button editShopButton;

    @FXML
    private Button deleteShopButton;

    @FXML
    private ProgressIndicator syncLoadingIndicator;

    @FXML
    private Label syncLoadingLabel;

    private Runnable onSync;
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
        // Header loading removed. Keep local loading indicators near the active content instead.
    }

    public void setProductKizSyncLoading(boolean loading) {
        syncLoadingIndicator.setVisible(loading);
        syncLoadingIndicator.setManaged(loading);
        syncLoadingLabel.setVisible(loading);
        syncLoadingLabel.setManaged(loading);
    }

    public void setControls(boolean hasShop, boolean busy, boolean exportEnabled, boolean tokenValid) {
        syncButton.setDisable(!hasShop || busy || !tokenValid);
        editShopButton.setDisable(!hasShop || busy);
        deleteShopButton.setDisable(!hasShop || busy || !tokenValid);
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        shopComboBox.setPromptText(i18n.tr("header.shop_prompt"));
        syncButton.setText(" " + i18n.tr("header.sync"));
        syncLoadingLabel.setText(i18n.tr("header.sync_products_kiz"));
    }
}
