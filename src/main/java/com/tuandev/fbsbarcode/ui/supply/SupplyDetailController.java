package com.tuandev.fbsbarcode.ui.supply;

import com.tuandev.fbsbarcode.models.Order;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;

public class SupplyDetailController {
    @FXML
    private Label supplyTitleLabel;

    @FXML
    private Label supplyMetaLabel;

    @FXML
    private Label emptyStateLabel;

    @FXML
    private ProgressIndicator orderLoading;

    @FXML
    private ProgressIndicator stickerLoading;

    @FXML
    private HBox stickerLoadingBox;

    @FXML
    private Label stickerLoadingLabel;

    @FXML
    private HBox sortOptionsBox;

    @FXML
    private TableView<Order> orderTable;

    @FXML
    private TableColumn<Order, Integer> noTC;

    @FXML
    private TableColumn<Order, Long> idTC;

    @FXML
    private TableColumn<Order, String> nameTC;

    @FXML
    private TableColumn<Order, String> subjectNameTC;

    @FXML
    private TableColumn<Order, String> articleTC;

    @FXML
    private TableColumn<Order, String> colorTC;

    @FXML
    private TableColumn<Order, String> sizeTC;

    @FXML
    private TableColumn<Order, String> stickerTC;

    @FXML
    private TableColumn<Order, String> barcodeTC;

    @FXML
    private TableColumn<Order, String> stickerCodeTC;

    @FXML
    private CheckBox sortBySubjectCheckBox;

    @FXML
    private CheckBox sortByArticleCheckBox;

    @FXML
    private CheckBox sortByColorCheckBox;

    @FXML
    private CheckBox sortBySizeCheckBox;

    private Consumer<OrderSortOptions> onSortOptionsChanged;

    @FXML
    private void initialize() {
        noTC.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Integer integer, boolean empty) {
                super.updateItem(integer, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
                setAlignment(Pos.CENTER_LEFT);
            }
        });
        idTC.setCellValueFactory(new PropertyValueFactory<>("id"));
        nameTC.setCellValueFactory(new PropertyValueFactory<>("name"));
        subjectNameTC.setCellValueFactory(new PropertyValueFactory<>("subjectName"));
        articleTC.setCellValueFactory(new PropertyValueFactory<>("article"));
        colorTC.setCellValueFactory(new PropertyValueFactory<>("color"));
        sizeTC.setCellValueFactory(new PropertyValueFactory<>("size"));
        stickerTC.setCellValueFactory(new PropertyValueFactory<>("sticker"));
        barcodeTC.setCellValueFactory(new PropertyValueFactory<>("barcode"));
        stickerCodeTC.setCellValueFactory(new PropertyValueFactory<>("stickerCode"));
        orderTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        centerColumn(idTC);
        centerColumn(nameTC);
        centerColumn(subjectNameTC);
        centerColumn(articleTC);
        centerColumn(colorTC);
        centerColumn(sizeTC);
        centerColumn(stickerTC);
        centerColumn(barcodeTC);
        centerColumn(stickerCodeTC);
        disableColumnSorting();
        bindSortCheckboxes();
        setSupplyInfo("Chưa chọn supply", "Chọn một supply để xem đơn hàng");
        setStickerLoading(false);
        setOrders(List.of());
    }

    public void setSupplyInfo(String title, String meta) {
        supplyTitleLabel.setText(title);
        boolean showMeta = meta != null && !meta.isBlank();
        supplyMetaLabel.setText(showMeta ? meta : "");
        supplyMetaLabel.setVisible(showMeta);
        supplyMetaLabel.setManaged(showMeta);
    }

    public void setOrders(List<Order> orders) {
        orderTable.setItems(FXCollections.observableArrayList(orders));
        boolean hasOrders = orders != null && !orders.isEmpty();
        orderTable.setVisible(hasOrders);
        orderTable.setManaged(hasOrders);
        sortOptionsBox.setVisible(hasOrders);
        sortOptionsBox.setManaged(hasOrders);
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setManaged(false);
        orderTable.refresh();
    }

    public void showEmptyState(String title, String message) {
        setSupplyInfo(title, "");
        setOrders(List.of());
        emptyStateLabel.setText("");
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setManaged(false);
    }

    public void setLoading(boolean loading) {
        orderLoading.setVisible(loading);
    }

    public void setStickerLoading(boolean loading) {
        stickerLoading.setVisible(loading);
        stickerLoadingBox.setVisible(loading);
        stickerLoadingBox.setManaged(loading);
    }

    public void setOnSortOptionsChanged(Consumer<OrderSortOptions> onSortOptionsChanged) {
        this.onSortOptionsChanged = onSortOptionsChanged;
    }

    public OrderSortOptions getSortOptions() {
        return new OrderSortOptions(
                sortBySubjectCheckBox.isSelected(),
                sortByArticleCheckBox.isSelected(),
                sortByColorCheckBox.isSelected(),
                sortBySizeCheckBox.isSelected()
        );
    }

    private void disableColumnSorting() {
        orderTable.setSortPolicy(param -> false);
        for (TableColumn<Order, ?> column : orderTable.getColumns()) {
            column.setSortable(false);
            column.setReorderable(false);
        }
    }

    private void bindSortCheckboxes() {
        sortBySubjectCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
        sortByArticleCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
        sortByColorCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
        sortBySizeCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> notifySortChanged());
    }

    private void notifySortChanged() {
        if (onSortOptionsChanged != null) {
            onSortOptionsChanged.accept(getSortOptions());
        }
    }

    private <T> void centerColumn(TableColumn<Order, T> column) {
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.valueOf(item));
                setAlignment(Pos.CENTER_LEFT);
            }
        });
    }
}
