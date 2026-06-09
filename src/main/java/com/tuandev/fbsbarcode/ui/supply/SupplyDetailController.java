package com.tuandev.fbsbarcode.ui.supply;

import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.shared.I18nService;
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
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.function.Consumer;

public class SupplyDetailController {
    private boolean updatingSortControls;

    @FXML
    private Label supplyTitleLabel;

    @FXML
    private Label supplyMetaLabel;

    @FXML
    private Label emptyStateLabel;

    @FXML
    private ProgressIndicator orderLoadingInline;

    @FXML
    private VBox orderLoadingBox;

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
    private TableColumn<Order, String> idTC;

    @FXML
    private TableColumn<Order, byte[]> imageTC;

    @FXML
    private TableColumn<Order, String> nameTC;

    // Consolidating category, article, color and size into nameTC

    @FXML
    private TableColumn<Order, String> priceTC;

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
        idTC.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(formatOrderDate(data.getValue().getCreatedAt())));
        idTC.setCellFactory(column -> new OrderIdDateCell());
        imageTC.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getImage()));
        nameTC.setCellValueFactory(new PropertyValueFactory<>("name"));
        priceTC.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(formatPrice(data.getValue().getPrice())));
        orderTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        configureImageColumn();
        nameTC.setCellFactory(column -> new OrderDetailsCell());
        centerColumn(priceTC);
        disableColumnSorting();
        bindSortCheckboxes();
        applyTranslations();
        setSupplyInfo(I18nService.getInstance().tr("supply.not_selected"), I18nService.getInstance().tr("supply.select_prompt"));
        setStickerLoading(false);
        setOrders(List.of());
        setPrintEnabled(false);
        setDeliverEnabled(false);
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
        boolean showMessage = message != null && !message.isBlank();
        emptyStateLabel.setText(showMessage ? message : "");
        emptyStateLabel.setVisible(showMessage);
        emptyStateLabel.setManaged(showMessage);
    }

    public void setLoading(boolean loading) {
        setLoading(loading, I18nService.getInstance().tr("supply.loading_orders"));
    }

    public void setLoading(boolean loading, String message) {
        if (orderLoadingInline != null) {
            orderLoadingInline.setVisible(loading);
        }
        if (orderLoadingBox != null) {
            orderLoadingBox.setVisible(loading);
            orderLoadingBox.setManaged(loading);
        }
        if (orderLoadingLabel != null) {
            orderLoadingLabel.setText(message == null || message.isBlank()
                    ? I18nService.getInstance().tr("supply.loading_orders")
                    : message);
        }
        if (loading) {
            orderTable.setVisible(false);
            orderTable.setManaged(false);
            sortOptionsBox.setVisible(false);
            sortOptionsBox.setManaged(false);
            emptyStateLabel.setVisible(false);
            emptyStateLabel.setManaged(false);
        }
        orderTable.setDisable(loading);
        sortOptionsBox.setDisable(loading);
    }

    public void setStickerLoading(boolean loading) {
        setStickerLoading(loading, I18nService.getInstance().tr("supply.loading_stickers"));
    }

    public void setStickerLoading(boolean loading, String message) {
        stickerLoading.setVisible(loading);
        stickerLoadingBox.setVisible(loading);
        stickerLoadingBox.setManaged(loading);
        stickerLoadingLabel.setText(message == null || message.isBlank() ? I18nService.getInstance().tr("supply.loading_stickers") : message);
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

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        printButton.setText(i18n.tr("supply.print"));
        deliverButton.setText(i18n.tr("supply.deliver"));
        sortBySubjectCheckBox.setText(i18n.tr("supply.sort.subject"));
        sortByArticleCheckBox.setText(i18n.tr("supply.sort.article"));
        sortByColorCheckBox.setText(i18n.tr("supply.sort.color"));
        sortBySizeCheckBox.setText(i18n.tr("supply.sort.size"));
        noTC.setText(i18n.tr("supply.col.no"));
        idTC.setText(i18n.tr("supply.col.task_number"));
        imageTC.setText(i18n.tr("supply.col.photo"));
        nameTC.setText(i18n.tr("supply.col.details"));
        priceTC.setText(i18n.tr("supply.col.price"));
        if (orderLoadingLabel != null) {
            orderLoadingLabel.setText(i18n.tr("supply.loading_orders"));
        }
        if (stickerLoadingLabel != null && !stickerLoadingBox.isVisible()) {
            stickerLoadingLabel.setText(i18n.tr("supply.loading_stickers"));
        }
    }

    public void setPrintEnabled(boolean enabled) {
        printButton.setDisable(!enabled);
    }

    public void setDeliverEnabled(boolean enabled) {
        deliverButton.setDisable(!enabled);
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private final class OrderDetailsCell extends TableCell<Order, String> {
        private final VBox vbox = new VBox(4);
        private final Label titleLabel = new Label();
        private final Label metaLabel = new Label();
        private final Label statusLabel = new Label();

        OrderDetailsCell() {
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -text-primary;");
            metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -text-muted;");
            statusLabel.getStyleClass().add("badge");
            vbox.getChildren().addAll(titleLabel, metaLabel, statusLabel);
            vbox.setAlignment(Pos.CENTER_LEFT);
            vbox.setPadding(new javafx.geometry.Insets(4, 0, 4, 0));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
                return;
            }
            Order order = getTableRow() == null ? null : getTableRow().getItem();
            if (order == null) {
                setGraphic(null);
                return;
            }
            titleLabel.setText(nullToEmpty(order.getName()));

            String brand = nullToEmpty(order.getBrand());
            String article = nullToEmpty(order.getArticle());
            String size = nullToEmpty(order.getSize());
            if (size.isEmpty()) {
                size = nullToEmpty(order.getRuSize());
            }

            StringBuilder metaBuilder = new StringBuilder();
            if (!brand.isEmpty()) {
                metaBuilder.append(brand).append(" • ");
            }
            metaBuilder.append(article);
            if (!size.isEmpty()) {
                metaBuilder.append(" • Size: ").append(size);
            }
            metaLabel.setText(metaBuilder.toString());

            statusLabel.getStyleClass().removeAll("badge-gray", "badge-green", "badge-red");

            if (order.isRequiresKiz()) {
                statusLabel.setVisible(true);
                statusLabel.setManaged(true);

                String kizCode = order.getKiz();
                String error = com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator.getInstance().getAttachmentError(order.getId());

                if (kizCode != null && !kizCode.isBlank()) {
                    statusLabel.setText(I18nService.getInstance().tr("supply.status.kiz_attached"));
                    statusLabel.getStyleClass().add("badge-green");
                } else if (error != null) {
                    statusLabel.setText(I18nService.getInstance().tr("supply.status.kiz_error"));
                    statusLabel.getStyleClass().add("badge-red");
                } else {
                    statusLabel.setText(I18nService.getInstance().tr("supply.status.kiz_pending"));
                    statusLabel.getStyleClass().add("badge-gray");
                }
            } else {
                statusLabel.setVisible(false);
                statusLabel.setManaged(false);
            }

            setGraphic(vbox);
        }
    }

    private String formatPrice(Integer price) {
        if (price == null) {
            return "";
        }
        if (price % 100 == 0) {
            return (price / 100) + " ₽";
        } else {
            return String.format(java.util.Locale.US, "%.2f ₽", price / 100.0);
        }
    }

    private String formatOrderDate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(value);
            java.time.ZonedDateTime dateTime = instant.atZone(java.time.ZoneId.systemDefault());
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception ex) {
            return value;
        }
    }

    private final class OrderIdDateCell extends TableCell<Order, String> {
        private final javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(4);
        private final Label idLabel = new Label();
        private final Label dateLabel = new Label();

        OrderIdDateCell() {
            idLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -text-primary;");
            dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -text-muted;");
            vbox.getChildren().addAll(idLabel, dateLabel);
            vbox.setAlignment(Pos.CENTER_LEFT);
            vbox.setPadding(new javafx.geometry.Insets(4, 0, 4, 0));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
                return;
            }
            Order order = getTableRow() == null ? null : getTableRow().getItem();
            if (order == null) {
                setGraphic(null);
                return;
            }
            idLabel.setText(String.valueOf(order.getId()));
            dateLabel.setText(formatOrderDate(order.getCreatedAt()));
            setGraphic(vbox);
        }
    }
}
