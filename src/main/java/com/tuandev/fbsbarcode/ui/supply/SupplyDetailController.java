package com.tuandev.fbsbarcode.ui.supply;

import com.tuandev.fbsbarcode.models.Order;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.function.Consumer;

public class SupplyDetailController {
    private static final String DEFAULT_STICKER_LOADING_TEXT = "Загрузка стикеров WB...";
    private boolean updatingSortControls;

    @FXML
    private Label supplyTitleLabel;

    @FXML
    private Label supplyMetaLabel;

    @FXML
    private Label supplyStatusLabel;

    @FXML
    private Label emptyStateLabel;

    @FXML
    private ProgressIndicator orderLoading;

    @FXML
    private ProgressIndicator orderLoadingInline;

    @FXML
    private HBox orderLoadingBox;

    @FXML
    private Label orderLoadingLabel;

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
    private TableColumn<Order, byte[]> imageTC;

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

    @FXML
    private Button printButton;

    @FXML
    private Button backButton;

    @FXML
    private Button deliverButton;

    private Consumer<OrderSortOptions> onSortOptionsChanged;
    private Runnable onPrint;
    private Runnable onBack;
    private Runnable onDeliver;

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
        imageTC.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getImage()));
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
        configureImageColumn();
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
        setPrintEnabled(false);
        setDeliverEnabled(false);
        setSupplyStatus("");
    }

    @FXML
    private void onPrint() {
        if (onPrint != null) {
            onPrint.run();
        }
    }

    @FXML
    private void onBack() {
        if (onBack != null) {
            onBack.run();
        }
    }

    @FXML
    private void onDeliver() {
        if (onDeliver != null) {
            onDeliver.run();
        }
    }

    public void setSupplyInfo(String title, String meta) {
        supplyTitleLabel.setText(title);
        boolean showMeta = meta != null && !meta.isBlank();
        supplyMetaLabel.setText(showMeta ? meta : "");
        supplyMetaLabel.setVisible(showMeta);
        supplyMetaLabel.setManaged(showMeta);
    }

    public void setOrders(List<Order> orders) {
        orderTable.getItems().setAll(orders == null ? List.of() : orders);
        boolean hasOrders = orders != null && !orders.isEmpty();
        orderTable.setVisible(hasOrders);
        orderTable.setManaged(hasOrders);
        sortOptionsBox.setVisible(hasOrders);
        sortOptionsBox.setManaged(hasOrders);
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setManaged(false);
    }

    public void refreshOrders() {
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
        if (orderLoadingInline != null) {
            orderLoadingInline.setVisible(loading);
        }
        if (orderLoadingBox != null) {
            orderLoadingBox.setVisible(loading);
            orderLoadingBox.setManaged(loading);
        }
        if (orderLoadingLabel != null) {
            orderLoadingLabel.setText("Загрузка заказов и данных поставки...");
        }
        orderTable.setDisable(loading);
        sortOptionsBox.setDisable(loading);
    }

    public void setStickerLoading(boolean loading) {
        setStickerLoading(loading, DEFAULT_STICKER_LOADING_TEXT);
    }

    public void setStickerLoading(boolean loading, String message) {
        stickerLoading.setVisible(loading);
        stickerLoadingBox.setVisible(loading);
        stickerLoadingBox.setManaged(loading);
        stickerLoadingLabel.setText(message == null || message.isBlank() ? DEFAULT_STICKER_LOADING_TEXT : message);
    }

    public void setOnSortOptionsChanged(Consumer<OrderSortOptions> onSortOptionsChanged) {
        this.onSortOptionsChanged = onSortOptionsChanged;
    }

    public void setOnPrint(Runnable onPrint) {
        this.onPrint = onPrint;
    }

    public void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    public void setOnDeliver(Runnable onDeliver) {
        this.onDeliver = onDeliver;
    }

    public void setPrintEnabled(boolean enabled) {
        printButton.setDisable(!enabled);
    }

    public void setDeliverEnabled(boolean enabled) {
        deliverButton.setDisable(!enabled);
    }

    public void setSupplyStatus(String status) {
        boolean visible = status != null && !status.isBlank();
        supplyStatusLabel.setText(visible ? status : "");
        supplyStatusLabel.setVisible(visible);
        supplyStatusLabel.setManaged(visible);
    }

    public OrderSortOptions getSortOptions() {
        return new OrderSortOptions(
                sortBySubjectCheckBox.isSelected(),
                sortByArticleCheckBox.isSelected(),
                sortByColorCheckBox.isSelected(),
                sortBySizeCheckBox.isSelected()
        );
    }

    public void setSortOptions(OrderSortOptions options) {
        OrderSortOptions safe = options == null ? OrderSortOptions.defaultOptions() : options;
        updatingSortControls = true;
        try {
            sortBySubjectCheckBox.setSelected(safe.bySubject());
            sortByArticleCheckBox.setSelected(safe.byArticle());
            sortByColorCheckBox.setSelected(safe.byColor());
            sortBySizeCheckBox.setSelected(safe.bySize());
        } finally {
            updatingSortControls = false;
        }
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
        if (updatingSortControls) {
            return;
        }
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

    private void configureImageColumn() {
        imageTC.setCellFactory(column -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private final StackPane placeholder = new StackPane();

            {
                imageView.setFitWidth(36);
                imageView.setFitHeight(48);
                imageView.setPreserveRatio(true);
                placeholder.getStyleClass().add("image-placeholder");
                placeholder.setPrefSize(36, 48);
                placeholder.setMinSize(36, 48);
                placeholder.setMaxSize(36, 48);
                setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(byte[] item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                if (item == null || item.length == 0) {
                    setGraphic(placeholder);
                    setText(null);
                    return;
                }
                try {
                    imageView.setImage(new Image(new ByteArrayInputStream(item)));
                    setGraphic(imageView);
                    setText(null);
                } catch (Exception ex) {
                    setGraphic(placeholder);
                    setText(null);
                }
            }
        });
    }
}
