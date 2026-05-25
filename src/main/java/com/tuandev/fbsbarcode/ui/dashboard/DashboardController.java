package com.tuandev.fbsbarcode.ui.dashboard;

import com.tuandev.fbsbarcode.features.dashboard.DashboardData;
import com.tuandev.fbsbarcode.features.dashboard.DashboardProductMetric;
import com.tuandev.fbsbarcode.features.dashboard.DashboardService;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class DashboardController {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardController.class);
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);
    private static final NumberFormat MONEY_FORMAT = NumberFormat.getCurrencyInstance(new Locale("ru", "RU"));

    private final DashboardService dashboardService = new DashboardService();

    @FXML private Label titleLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Button refreshButton;
    @FXML private Label productKpiLabel;
    @FXML private Label newOrdersKpiLabel;
    @FXML private Label openSuppliesKpiLabel;
    @FXML private Label productCountLabel;
    @FXML private Label newOrderCountLabel;
    @FXML private Label openSupplyCountLabel;
    @FXML private Label emptyStateLabel;
    @FXML private Label analyticsErrorLabel;
    @FXML private Label topSellingTitleLabel;
    @FXML private Label potentialTitleLabel;
    @FXML private TableView<DashboardProductMetric> topSellingTable;
    @FXML private TableColumn<DashboardProductMetric, DashboardProductMetric> topProductTC;
    @FXML private TableColumn<DashboardProductMetric, Number> topOrdersTC;
    @FXML private TableColumn<DashboardProductMetric, String> topRevenueTC;
    @FXML private TableColumn<DashboardProductMetric, String> topConversionTC;
    @FXML private TableView<DashboardProductMetric> potentialTable;
    @FXML private TableColumn<DashboardProductMetric, String> potentialProductTC;
    @FXML private TableColumn<DashboardProductMetric, String> potentialRatingTC;
    @FXML private TableColumn<DashboardProductMetric, String> potentialDemandTC;
    @FXML private TableColumn<DashboardProductMetric, String> potentialConversionTC;
    @FXML private TableColumn<DashboardProductMetric, Number> potentialStockTC;
    @FXML private TableColumn<DashboardProductMetric, String> potentialReasonTC;

    private Shop shop;
    private long requestToken;
    private Integer loadedShopId;
    private boolean hasLoadedData;
    private boolean loading;

    @FXML
    private void initialize() {
        setupTables();
        applyTranslations();
    }

    public void setShop(Shop shop, boolean forceRefresh) {
        int previousShopId = this.shop == null ? -1 : this.shop.getId();
        this.shop = shop;
        if (shop == null) {
            clear();
            return;
        }
        boolean sameShop = previousShopId == shop.getId();
        if (!forceRefresh && sameShop && (loading || (hasLoadedData && loadedShopId != null && loadedShopId == shop.getId()))) {
            return;
        }
        load(forceRefresh);
    }

    public void refresh() {
        if (shop != null) {
            load(true);
        }
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("dashboard.title"));
        refreshButton.setText(i18n.tr("dashboard.refresh"));
        productKpiLabel.setText(i18n.tr("dashboard.kpi.products"));
        newOrdersKpiLabel.setText(i18n.tr("dashboard.kpi.new_orders"));
        openSuppliesKpiLabel.setText(i18n.tr("dashboard.kpi.open_supplies"));
        emptyStateLabel.setText(i18n.tr("dashboard.empty"));
        analyticsErrorLabel.setText(i18n.tr("dashboard.analytics_error"));
        topSellingTitleLabel.setText(i18n.tr("dashboard.top_selling"));
        potentialTitleLabel.setText(i18n.tr("dashboard.potential_products"));
        topProductTC.setText(i18n.tr("dashboard.col.product"));
        topOrdersTC.setText(i18n.tr("dashboard.col.orders"));
        topRevenueTC.setText(i18n.tr("dashboard.col.revenue"));
        topConversionTC.setText(i18n.tr("dashboard.col.conversion"));
        potentialProductTC.setText(i18n.tr("dashboard.col.product"));
        potentialRatingTC.setText(i18n.tr("dashboard.col.rating"));
        potentialDemandTC.setText(i18n.tr("dashboard.col.demand"));
        potentialConversionTC.setText(i18n.tr("dashboard.col.conversion"));
        potentialStockTC.setText(i18n.tr("dashboard.col.stock"));
        potentialReasonTC.setText(i18n.tr("dashboard.col.reason"));
    }

    @FXML
    private void onRefresh() {
        refresh();
    }

    private void setupTables() {
        topProductTC.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        topProductTC.setCellFactory(column -> new ProductCell());
        topOrdersTC.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().orders()));
        topRevenueTC.setCellValueFactory(cell -> new SimpleStringProperty(MONEY_FORMAT.format(cell.getValue().revenue())));
        topConversionTC.setCellValueFactory(cell -> new SimpleStringProperty(formatConversion(cell.getValue())));

        potentialProductTC.setCellValueFactory(cell -> new SimpleStringProperty(productText(cell.getValue())));
        potentialRatingTC.setCellValueFactory(cell -> new SimpleStringProperty(String.format("%.1f / %.1f",
                cell.getValue().productRating(), cell.getValue().feedbackRating())));
        potentialDemandTC.setCellValueFactory(cell -> new SimpleStringProperty(String.format("%s / %s / %s",
                INTEGER_FORMAT.format(cell.getValue().wishlists()),
                INTEGER_FORMAT.format(cell.getValue().carts()),
                INTEGER_FORMAT.format(cell.getValue().orders()))));
        potentialConversionTC.setCellValueFactory(cell -> new SimpleStringProperty(formatConversion(cell.getValue())));
        potentialStockTC.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().stock()));
        potentialReasonTC.setCellValueFactory(cell -> new SimpleStringProperty(String.join(", ", cell.getValue().reasons())));
    }

    private void load(boolean forceRefresh) {
        long token = ++requestToken;
        Shop currentShop = shop;
        Task<DashboardData> task = new Task<>() {
            @Override
            protected DashboardData call() {
                return dashboardService.load(currentShop, forceRefresh);
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken) {
                return;
            }
            setLoading(false);
            loadedShopId = currentShop.getId();
            hasLoadedData = true;
            setData(task.getValue());
        });
        task.setOnFailed(event -> {
            if (token != requestToken) {
                return;
            }
            setLoading(false);
            LOGGER.error("Không thể tải dashboard cho shop {}", currentShop.getId(), task.getException());
            setData(new DashboardData(new com.tuandev.fbsbarcode.features.dashboard.DashboardKpis(0, 0, 0), List.of(), List.of(),
                    I18nService.getInstance().tr("dashboard.load_error")));
        });
        AppTaskExecutor.execute(task);
    }

    private void setData(DashboardData data) {
        productCountLabel.setText(INTEGER_FORMAT.format(data.kpis().productCount()));
        newOrderCountLabel.setText(INTEGER_FORMAT.format(data.kpis().newOrderCount()));
        openSupplyCountLabel.setText(INTEGER_FORMAT.format(data.kpis().openSupplyCount()));
        topSellingTable.getItems().setAll(data.topSelling());
        potentialTable.getItems().setAll(data.potentialProducts());
        boolean hasLocalProducts = data.kpis().productCount() > 0;
        emptyStateLabel.setVisible(!hasLocalProducts);
        emptyStateLabel.setManaged(!hasLocalProducts);
        analyticsErrorLabel.setText(data.hasAnalyticsError() ? data.analyticsError() : "");
        analyticsErrorLabel.setVisible(data.hasAnalyticsError());
        analyticsErrorLabel.setManaged(data.hasAnalyticsError());
        I18nService i18n = I18nService.getInstance();
        statusLabel.setText(data.hasAnalyticsError() ? i18n.tr("dashboard.status.analytics_unavailable") : i18n.tr("dashboard.status.updated"));
    }

    private void clear() {
        productCountLabel.setText("0");
        newOrderCountLabel.setText("0");
        openSupplyCountLabel.setText("0");
        topSellingTable.getItems().clear();
        potentialTable.getItems().clear();
        statusLabel.setText("");
        loadedShopId = null;
        hasLoadedData = false;
        analyticsErrorLabel.setVisible(false);
        analyticsErrorLabel.setManaged(false);
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setManaged(false);
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        refreshButton.setDisable(loading);
        statusLabel.setText(loading ? I18nService.getInstance().tr("dashboard.status.loading") : "");
    }

    private String productText(DashboardProductMetric metric) {
        return metric.name() + "\n" + "nmID " + metric.nmId() + " | " + nullToDash(metric.vendorCode());
    }

    private String formatConversion(DashboardProductMetric metric) {
        return String.format("%.1f%% / %.1f%%", metric.addToCartPercent(), metric.cartToOrderPercent());
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private final class ProductCell extends TableCell<DashboardProductMetric, DashboardProductMetric> {
        private final ImageView imageView = new ImageView();
        private final Label nameLabel = new Label();
        private final Label metaLabel = new Label();
        private final HBox root = new HBox(8);

        ProductCell() {
            imageView.setFitWidth(42);
            imageView.setFitHeight(56);
            imageView.setPreserveRatio(true);
            nameLabel.setWrapText(true);
            metaLabel.getStyleClass().add("text-muted");
            javafx.scene.layout.VBox textBox = new javafx.scene.layout.VBox(2, nameLabel, metaLabel);
            root.getChildren().setAll(imageView, textBox);
        }

        @Override
        protected void updateItem(DashboardProductMetric item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            nameLabel.setText(item.name());
            metaLabel.setText("nmID " + item.nmId() + " | " + nullToDash(item.vendorCode()));
            if (item.imageUrl() == null || item.imageUrl().isBlank()) {
                imageView.setImage(null);
            } else {
                imageView.setImage(new Image(item.imageUrl(), true));
            }
            setGraphic(root);
        }
    }
}
