package com.tuandev.fbsbarcode.ui.supply;

import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ProgressIndicator;

import java.util.List;
import java.util.function.Consumer;

public class SupplyListController {
    @FXML
    private ComboBox<WbSupplySummary> supplyComboBox;

    @FXML
    private ProgressIndicator supplyLoading;

    private Consumer<WbSupplySummary> onSupplySelected;
    private boolean suppressSelectionCallback;

    @FXML
    private void initialize() {
        supplyComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressSelectionCallback && newValue != null) {
                notifySelection(newValue);
            }
        });
        setSupplies(List.of());
    }

    public void setSupplies(List<WbSupplySummary> supplies) {
        suppressSelectionCallback = true;
        supplyComboBox.setItems(FXCollections.observableArrayList(supplies));
        supplyComboBox.getSelectionModel().clearSelection();
        suppressSelectionCallback = false;
    }

    public void selectSupply(String supplyId) {
        if (supplyId == null || supplyId.isBlank()) {
            return;
        }
        suppressSelectionCallback = true;
        WbSupplySummary selected = null;
        for (WbSupplySummary item : supplyComboBox.getItems()) {
            if (supplyId.equals(item.getSupplyId())) {
                supplyComboBox.getSelectionModel().select(item);
                selected = item;
                break;
            }
        }
        suppressSelectionCallback = false;
        if (selected != null) {
            notifySelection(selected);
        }
    }

    public WbSupplySummary getSelectedSupply() {
        return supplyComboBox.getSelectionModel().getSelectedItem();
    }

    public void setLoading(boolean loading) {
        supplyLoading.setVisible(loading);
        supplyComboBox.setDisable(loading);
    }

    public void setOnSupplySelected(Consumer<WbSupplySummary> onSupplySelected) {
        this.onSupplySelected = onSupplySelected;
    }

    private void notifySelection(WbSupplySummary supply) {
        if (onSupplySelected != null && supply != null) {
            onSupplySelected.accept(supply);
        }
    }
}
