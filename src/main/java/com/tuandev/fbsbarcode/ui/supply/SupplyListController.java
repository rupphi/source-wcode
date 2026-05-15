package com.tuandev.fbsbarcode.ui.supply;

import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import javafx.fxml.FXML;
import javafx.scene.control.ListCell;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;

import java.util.List;
import java.util.function.Consumer;

public class SupplyListController {
    @FXML
    private ComboBox<WbSupplySummary> supplyComboBox;

    @FXML
    private ProgressIndicator supplyLoading;

    @FXML
    private Button refetchButton;

    @FXML
    private Label emptyStateLabel;

    private Consumer<WbSupplySummary> onSupplySelected;
    private Runnable onRefetchRequested;
    private boolean suppressSelectionCallback;

    @FXML
    private void initialize() {
        supplyComboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(WbSupplySummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatSupply(item));
            }
        });
        supplyComboBox.setButtonCell(supplyComboBox.getCellFactory().call(null));
        supplyComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressSelectionCallback && newValue != null) {
                notifySelection(newValue);
            }
        });
        setSupplies(List.of());
    }

    public void setSupplies(List<WbSupplySummary> supplies) {
        suppressSelectionCallback = true;
        supplyComboBox.getItems().setAll(supplies == null ? List.of() : supplies);
        supplyComboBox.getSelectionModel().clearSelection();
        suppressSelectionCallback = false;
        updateEmptyState();
    }

    public void refreshSupplies(List<WbSupplySummary> supplies, String selectedSupplyId) {
        suppressSelectionCallback = true;
        supplyComboBox.getItems().setAll(supplies == null ? List.of() : supplies);
        if (selectedSupplyId == null || selectedSupplyId.isBlank()) {
            supplyComboBox.getSelectionModel().clearSelection();
        } else {
            WbSupplySummary selected = null;
            for (WbSupplySummary item : supplyComboBox.getItems()) {
                if (selectedSupplyId.equals(item.getSupplyId())) {
                    selected = item;
                    break;
                }
            }
            if (selected != null) {
                supplyComboBox.getSelectionModel().select(selected);
            } else {
                supplyComboBox.getSelectionModel().clearSelection();
            }
        }
        suppressSelectionCallback = false;
        updateEmptyState();
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

    public void updateSupplySummary(WbSupplySummary updatedSupply) {
        if (updatedSupply == null) {
            return;
        }
        int selectedIndex = supplyComboBox.getSelectionModel().getSelectedIndex();
        for (int i = 0; i < supplyComboBox.getItems().size(); i++) {
            WbSupplySummary existing = supplyComboBox.getItems().get(i);
            if (!updatedSupply.getSupplyId().equals(existing.getSupplyId())) {
                continue;
            }
            supplyComboBox.getItems().set(i, updatedSupply);
            if (selectedIndex == i) {
                suppressSelectionCallback = true;
                supplyComboBox.getSelectionModel().select(i);
                suppressSelectionCallback = false;
            }
            return;
        }
    }

    public WbSupplySummary getSelectedSupply() {
        return supplyComboBox.getSelectionModel().getSelectedItem();
    }

    public void setLoading(boolean loading) {
        supplyLoading.setVisible(loading);
        supplyComboBox.setDisable(loading);
        refetchButton.setDisable(loading);
        updateEmptyState();
    }

    public void setRefetchEnabled(boolean enabled) {
        if (!supplyLoading.isVisible()) {
            refetchButton.setDisable(!enabled);
        }
    }

    public void setOnSupplySelected(Consumer<WbSupplySummary> onSupplySelected) {
        this.onSupplySelected = onSupplySelected;
    }

    public void setOnRefetchRequested(Runnable onRefetchRequested) {
        this.onRefetchRequested = onRefetchRequested;
    }

    @FXML
    private void onRefetch() {
        if (onRefetchRequested != null) {
            onRefetchRequested.run();
        }
    }

    private void notifySelection(WbSupplySummary supply) {
        if (onSupplySelected != null && supply != null) {
            onSupplySelected.accept(supply);
        }
    }

    private String formatSupply(WbSupplySummary supply) {
        String name = supply.getName() == null || supply.getName().isBlank() ? "" : " - " + supply.getName();
        return supply.getSupplyId() + name + " (" + supply.getItemCount() + ")";
    }

    private void updateEmptyState() {
        boolean show = !supplyLoading.isVisible() && supplyComboBox.getItems().isEmpty();
        emptyStateLabel.setVisible(show);
        emptyStateLabel.setManaged(show);
    }
}
