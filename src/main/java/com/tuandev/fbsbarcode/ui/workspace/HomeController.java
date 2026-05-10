package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncReport;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.features.kiz.CategoryWorkflow;
import com.tuandev.fbsbarcode.shared.ConfigService;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.features.print.OrderExportWorkflow;
import com.tuandev.fbsbarcode.features.print.PrintAuthorizationDialogService;
import com.tuandev.fbsbarcode.features.print.PrintTemplateDesignerService;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.supply.OrderSortingService;
import com.tuandev.fbsbarcode.integration.update.UpdateDialogService;
import com.tuandev.fbsbarcode.integration.update.UpdateInfo;
import com.tuandev.fbsbarcode.integration.update.UpdateInstallerService;
import com.tuandev.fbsbarcode.integration.update.UpdateService;
import com.tuandev.fbsbarcode.features.shop.ShopWorkflow;
import com.tuandev.fbsbarcode.features.supply.SupplyLoadWorkflow;
import com.tuandev.fbsbarcode.ui.kiz.KizPanelController;
import com.tuandev.fbsbarcode.ui.shop.ShopSidebarController;
import com.tuandev.fbsbarcode.ui.supply.OrderSortOptions;
import com.tuandev.fbsbarcode.ui.supply.SupplyDetailController;
import com.tuandev.fbsbarcode.ui.supply.SupplyListController;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

public class HomeController implements Initializable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HomeController.class);

    private final OrderExportWorkflow orderExportWorkflow = new OrderExportWorkflow();
    private final ShopWorkflow shopWorkflow = new ShopWorkflow();
    private final CategoryWorkflow categoryWorkflow = new CategoryWorkflow();
    private final WbSyncWorkflow wbSyncWorkflow = new WbSyncWorkflow();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();
    private final SupplyLoadWorkflow supplyLoadWorkflow = new SupplyLoadWorkflow();
    private final OrderSortingService orderSortingService = new OrderSortingService();
    private final PrintAuthorizationDialogService printAuthorizationDialogService = new PrintAuthorizationDialogService();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final PrintTemplateDesignerService printTemplateDesignerService = new PrintTemplateDesignerService();
    private final WorkspaceState state = new WorkspaceState();
    private final WorkspaceActivityTracker activityTracker = new WorkspaceActivityTracker();
    private final UpdateService updateService = new UpdateService();
    private final UpdateInstallerService updateInstallerService = new UpdateInstallerService();

    public StackPane sidebarContainer;
    public StackPane headerContainer;
    public BorderPane contentPane;
    public StackPane supplyListContainer;
    public StackPane supplyDetailContainer;
    public StackPane kizPanelContainer;

    private FileChooser fileChooser;
    private ShopSidebarController shopSidebarController;
    private WorkspaceHeaderController workspaceHeaderController;
    private SupplyListController supplyListController;
    private SupplyDetailController supplyDetailController;
    private KizPanelController kizPanelController;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        Database.initDatabase();
        printTemplateService.ensureDefaultTemplateExists();

        fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home"), "Downloads"));

        initializeSidebar();
        initializeHeader();
        initializeSupplyViews();
        initializeKizPanel();

        contentPane.setVisible(false);
        kizPanelContainer.setVisible(false);
        updateHeaderState();
        updateExportAvailability();
        loadShops();
        checkForUpdates();
    }

    private void checkForUpdates() {
        javafx.concurrent.Task<UpdateInfo> task = new javafx.concurrent.Task<>() {
            @Override
            protected UpdateInfo call() {
                return updateService.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> {
            UpdateInfo info = task.getValue();
            if (info != null) {
                showUpdateDialog(info);
            }
        });
        task.setOnFailed(e ->
            LOGGER.warn("Update check failed", task.getException())
        );
        AppTaskExecutor.execute(task);
    }

    private void showUpdateDialog(UpdateInfo info) {
        UpdateDialogService dialogService = new UpdateDialogService();
        UpdateDialogService.UpdateChoice choice = dialogService.showDialog(info);
        switch (choice) {
            case DOWNLOAD:
                if (updateInstallerService.supportsInAppInstall(info)) {
                    startInstallerUpdate(info);
                } else {
                    UpdateDialogService.openDownloadUrl(info);
                }
                break;
            case SKIP:
                ConfigService.setSkippedVersion(info.getVersion());
                break;
            case LATER:
                break;
        }
    }

    private void startInstallerUpdate(UpdateInfo info) {
        Task<java.nio.file.Path> task = new Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                return updateInstallerService.downloadInstaller(info);
            }
        };
        task.setOnFailed(e -> {
            LOGGER.error("Không thể tải bản cập nhật {}", info.getVersion(), task.getException());
            AlertService.showError("Không thể tải bản cập nhật. Vui lòng thử lại sau.");
        });
        task.setOnSucceeded(e -> {
            try {
                updateInstallerService.launchInstallerAfterExit(task.getValue());
                Platform.exit();
            } catch (IOException ex) {
                LOGGER.error("Không thể khởi chạy installer cập nhật {}", info.getVersion(), ex);
                AlertService.showError("Đã tải xong nhưng không thể mở installer cập nhật.");
            }
        });
        AppTaskExecutor.execute(task);
    }

    public void onAddShop(ActionEvent actionEvent) {
        shopWorkflow.requestCreateShop().ifPresent(shop -> {
            int count = shopWorkflow.createShop(shop);
            if (count > 0) {
                state.setPendingSelectShopId(null);
                Task<List<Shop>> refreshTask = new Task<>() {
                    @Override
                    protected List<Shop> call() {
                        return shopWorkflow.loadShops();
                    }
                };
                refreshTask.setOnSucceeded(e -> {
                    List<Shop> loadedShops = refreshTask.getValue();
                    loadedShops.stream()
                            .max(Comparator.comparingInt(Shop::getId))
                            .ifPresent(createdShop -> {
                                state.setPendingSelectShopId(createdShop.getId());
                                state.setShops(loadedShops);
                                renderShops();
                                selectShopById(createdShop.getId());
                            });
                    updateHeaderState();
                });
                refreshTask.setOnFailed(e -> AlertService.showError(refreshTask.getException().getMessage()));
                AppTaskExecutor.execute(refreshTask);
            }
        });
    }

    public void onExport(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        if (state.getDisplayedOrders().isEmpty()) {
            AlertService.showWarning("Thông báo", "Vui lòng cập nhật đơn hàng", null);
            return;
        }
        if (!printAuthorizationDialogService.ensureAuthorized()) {
            return;
        }

        fileChooser.setTitle("Open PDF File");
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }

        File orderDetailsFile = new File(file.getParent(), "NHAT_HANG-" + file.getName());
        Task<OrderExportWorkflow.ExportResult> task = new Task<>() {
            @Override
            protected OrderExportWorkflow.ExportResult call() throws Exception {
                return orderExportWorkflow.export(
                        new OrderExportWorkflow.ExportRequest(
                                shop,
                                state.getDisplayedOrders(),
                                kizPanelController.getKizCommand(),
                                file,
                                orderDetailsFile
                        )
                );
            }
        };

        task.setOnRunning(e -> markShopRunning(shop.getId(), true));
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            Throwable ex = task.getException();
            LOGGER.error("Export thất bại cho shop {}", shop.getId(), ex);
            AlertService.showError(ex.getMessage());
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            state.setLoadedOrdersRaw(task.getValue().exportedOrders());
            applySortAndDisplayOrders();
            loadCategories();
            tryOpenFile(orderDetailsFile);
            tryOpenFile(file);
        });

        AppTaskExecutor.execute(task);
    }

    public void onSettings(ActionEvent event) {
        printTemplateDesignerService.showDialog();
    }

    public void onUpdateShop(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        shopWorkflow.requestUpdateShop(shop).ifPresent(updated -> {
            shopWorkflow.updateShop(shop.getId(), updated);
            shop.setName(updated.getName());
            shop.setApiKey(updated.getApiKey());
            workspaceHeaderController.setCurrentShopName(updated.getName());
            loadShops();
        });
    }

    public void onDeleteShop(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        int shopId = shop.getId();
        if (activityTracker.isSyncing(shopId)) {
            AlertService.showWarning(
                    "Đang đồng bộ",
                    "Không thể xóa cửa hàng lúc này",
                    "Cửa hàng " + shop.getName() + " đang đồng bộ dữ liệu WB. Vui lòng chờ đồng bộ hoàn tất rồi thử lại."
            );
            return;
        }

        Optional<ButtonType> result = AlertService.showConfirmation(
                "Xóa cửa hàng",
                "Xóa cửa hàng " + shop.getName() + "?",
                "Toàn bộ dữ liệu KIZ và dữ liệu đồng bộ WB của shop này sẽ bị xóa."
        );
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() {
                return shopWorkflow.deleteShop(shopId);
            }
        };
        task.setOnRunning(e -> markShopRunning(shopId, true));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            LOGGER.error("Xóa cửa hàng thất bại cho shop {}", shopId, ex);
            markShopRunning(shopId, false);
            AlertService.showError(ex.getMessage());
        });
        task.setOnSucceeded(e -> {
            WbSupplyWorkflow.clearImageCache();
            activityTracker.clear(shopId);
            if (isCurrentShop(shopId)) {
                state.clearWorkspace();
                clearWorkspaceView();
            }
            loadShops();
        });
        AppTaskExecutor.execute(task);
    }

    public void onSyncWildberries(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop != null) {
            startShopSync(shop, true);
        }
    }

    private void loadShops() {
        Task<List<Shop>> task = new Task<>() {
            @Override
            protected List<Shop> call() throws Exception {
                return shopWorkflow.loadShops();
            }
        };
        task.setOnSucceeded(e -> {
            state.setShops(task.getValue());
            renderShops();
            if (state.getShops().isEmpty()) {
                state.clearWorkspace();
                clearWorkspaceView();
                return;
            }
            if (state.getPendingSelectShopId() != null) {
                selectShopById(state.getPendingSelectShopId());
                state.setPendingSelectShopId(null);
            } else if (state.getSelectedShop() != null) {
                selectShopById(state.getSelectedShop().getId());
            }
        });
        task.setOnFailed(e -> AlertService.showError(task.getException().getMessage()));
        AppTaskExecutor.execute(task);
    }

    private void loadCategories() {
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            kizPanelController.clearCategories();
            return;
        }
        Task<List<Category>> task = new Task<>() {
            @Override
            protected List<Category> call() throws Exception {
                return categoryWorkflow.loadCategories(shop.getId());
            }
        };
        task.setOnSucceeded(e -> {
            if (!isCurrentShop(shop.getId())) {
                return;
            }
            List<Category> categories = task.getValue();
            kizPanelController.setCategories(categories, this::importKizForCategory, this::confirmDeleteCategory);
        });
        task.setOnFailed(e -> AlertService.showError(task.getException().getMessage()));
        AppTaskExecutor.execute(task);
    }

    private void importKizForCategory(Category category) {
        Shop shop = state.getSelectedShop();
        if (shop == null || category == null) {
            return;
        }
        fileChooser.setTitle("Open PDF File");
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        File file = fileChooser.showOpenDialog(null);
        if (file == null) {
            return;
        }

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return categoryWorkflow.importKizFromPdf(file, shop, category);
            }
        };
        task.setOnRunning(ex -> markShopRunning(shop.getId(), true));
        task.setOnSucceeded(ex -> {
            markShopRunning(shop.getId(), false);
            if (isCurrentShop(shop.getId())) {
                loadCategories();
            }
        });
        task.setOnFailed(ex -> {
            markShopRunning(shop.getId(), false);
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void confirmDeleteCategory(Category category) {
        Shop shop = state.getSelectedShop();
        if (shop == null || category == null) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Xóa danh mục");
        alert.setHeaderText("Bạn chắc chắn muốn xóa danh mục " + category.getName() + " không?");
        ButtonType buttonTypeConfirm = new ButtonType("Xóa", ButtonBar.ButtonData.YES);
        ButtonType buttonTypeCancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(buttonTypeConfirm, buttonTypeCancel);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == buttonTypeConfirm) {
            categoryWorkflow.deleteCategory(shop, category);
            loadCategories();
        }
    }

    private void onAddCategory() {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        try {
            Optional<Category> categoryResult = categoryWorkflow.requestCreateCategory();
            if (categoryResult.isPresent()) {
                int rowCount = categoryWorkflow.createCategory(categoryResult.get());
                if (rowCount > 0) {
                    loadCategories();
                }
            }
        } catch (NumberFormatException e) {
            AlertService.showError("Id là số nguyên");
        } catch (SQLException e) {
            LOGGER.error("Không thể thêm category", e);
            AlertService.showError("ID đã tồn tại! Vui lòng nhập ID khác");
        }
    }

    private void selectShopById(int shopId) {
        Shop shop = state.getShops().stream()
                .filter(item -> item.getId() == shopId)
                .findFirst()
                .orElse(null);
        if (shop != null) {
            selectShop(shop);
        }
    }

    private void selectShop(Shop shop) {
        state.setSelectedShop(shop);
        renderShops();
        workspaceHeaderController.setCurrentShopName(shop.getName());
        loadCategories();
        resetLoadedSupply();
        showWorkspace();
        supplyListController.setLoading(true);
        updateHeaderState();
        startShopSync(shop, false);
    }

    private void startShopSync(Shop shop, boolean manual) {
        if (shop == null) {
            return;
        }
        boolean started = activityTracker.markSyncStarted(shop.getId());
        if (!started) {
            markShopRunning(shop.getId(), true);
            return;
        }

        Task<WbSyncReport> task = new Task<>() {
            @Override
            protected WbSyncReport call() throws Exception {
                return manual ? wbSyncWorkflow.syncAll(shop) : wbSyncWorkflow.syncOverview(shop);
            }
        };
        task.setOnRunning(e -> {
            if (manual) {
                markShopRunning(shop.getId(), true);
            } else if (isCurrentShop(shop.getId())) {
                supplyListController.setLoading(true);
            }
        });
        task.setOnFailed(e -> {
            activityTracker.markRunning(shop.getId(), false);
            Throwable ex = task.getException();
            LOGGER.error("Đồng bộ WB thất bại cho shop {}", shop.getId(), ex);
            refreshSupplyListIfCurrent(shop.getId());
            if (manual && isCurrentShop(shop.getId())) {
                AlertService.showError(ex.getMessage());
            } else {
                updateHeaderState();
            }
        });
        task.setOnSucceeded(e -> {
            activityTracker.markRunning(shop.getId(), false);
            refreshSupplyListIfCurrent(shop.getId());
        });
        AppTaskExecutor.execute(task);
    }

    private void refreshSupplyList() {
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            clearSupplyViews();
            updateHeaderState();
            return;
        }
        supplyListController.setLoading(false);
        List<WbSupplySummary> supplies = wbSupplyWorkflow.getSupplies(shop.getId()).stream()
                .filter(supply -> !supply.isDone())
                .toList();
        supplyListController.setSupplies(supplies);
        resetLoadedSupply();
        supplyDetailController.showEmptyState("", "");
        updateHeaderState();
    }

    private void loadSupply(WbSupplySummary supply) {
        Shop shop = requireSelectedShop();
        if (shop == null || supply == null) {
            return;
        }
        resetLoadedSupply();
        long requestToken = state.nextSupplyRequestToken();
        state.setLoadedSupplyId(supply.getSupplyId());
        supplyDetailController.setLoading(true);
        supplyDetailController.setStickerLoading(false);
        supplyDetailController.setSupplyInfo("Supply " + supply.getSupplyId(), "");

        Task<List<Order>> localTask = new Task<>() {
            @Override
            protected List<Order> call() throws Exception {
                return supplyLoadWorkflow.loadLocal(shop, supply.getSupplyId());
            }
        };
        localTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.setLoading(false);
            LOGGER.error("Không thể mở supply {}", supply.getSupplyId(), localTask.getException());
            supplyDetailController.showEmptyState("", "");
            AlertService.showError(localTask.getException().getMessage());
        });
        localTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.setLoading(false);
            state.setLoadedOrdersRaw(localTask.getValue());
            supplyDetailController.setSupplyInfo("Supply " + supply.getSupplyId(), "");
            applySortAndDisplayOrders();
            updateExportAvailability();
            startSupplyRefresh(shop, supply, requestToken);
        });
        AppTaskExecutor.execute(localTask);
    }

    private void startSupplyRefresh(Shop shop, WbSupplySummary supply, long requestToken) {
        state.setSupplyEnriching(true);
        supplyDetailController.setStickerLoading(true);
        updateExportAvailability();

        Task<List<Order>> refreshTask = new Task<>() {
            @Override
            protected List<Order> call() throws Exception {
                return supplyLoadWorkflow.refreshFromWildberries(shop, supply.getSupplyId());
            }
        };
        refreshTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            state.setSupplyEnriching(false);
            supplyDetailController.setStickerLoading(false);
            updateExportAvailability();
            LOGGER.warn("Không thể refresh supply {} ở nền", supply.getSupplyId(), refreshTask.getException());
        });
        refreshTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            state.setSupplyEnriching(false);
            supplyDetailController.setStickerLoading(false);
            state.setLoadedOrdersRaw(refreshTask.getValue());
            applySortAndDisplayOrders();
            supplyDetailController.setSupplyInfo("Supply " + supply.getSupplyId(), "");
            updateExportAvailability();
        });
        AppTaskExecutor.execute(refreshTask);
    }

    private void applySortAndDisplayOrders() {
        if (supplyDetailController == null) {
            return;
        }
        OrderSortOptions options = supplyDetailController.getSortOptions();
        List<Order> sortedOrders = orderSortingService.sort(state.getLoadedOrdersRaw(), options);
        state.setDisplayedOrders(sortedOrders);
        supplyDetailController.setOrders(sortedOrders);
        updateExportAvailability();
    }

    private void initializeSidebar() {
        FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
        VBox root = FxmlViewLoader.load(loader);
        shopSidebarController = loader.getController();
        shopSidebarController.setOnAddShop(() -> onAddShop(new ActionEvent()));
        shopSidebarController.setOnOpenSettings(() -> onSettings(new ActionEvent()));
        shopSidebarController.setOnShopSelected(this::selectShop);
        sidebarContainer.getChildren().setAll(root);
    }

    private void initializeHeader() {
        FXMLLoader loader = FxmlViewLoader.loader(WorkspaceHeaderController.class, "workspace-header-view.fxml");
        HBox root = FxmlViewLoader.load(loader);
        workspaceHeaderController = loader.getController();
        workspaceHeaderController.setOnSync(() -> onSyncWildberries(new ActionEvent()));
        workspaceHeaderController.setOnExport(() -> onExport(new ActionEvent()));
        workspaceHeaderController.setOnEditShop(() -> onUpdateShop(new ActionEvent()));
        workspaceHeaderController.setOnDeleteShop(() -> onDeleteShop(new ActionEvent()));
        headerContainer.getChildren().setAll(root);
    }

    private void initializeSupplyViews() {
        FXMLLoader supplyListLoader = FxmlViewLoader.loader(SupplyListController.class, "supply-list-view.fxml");
        VBox supplyListRoot = FxmlViewLoader.load(supplyListLoader);
        supplyListController = supplyListLoader.getController();
        supplyListController.setOnSupplySelected(this::loadSupply);
        supplyListContainer.getChildren().setAll(supplyListRoot);

        FXMLLoader supplyDetailLoader = FxmlViewLoader.loader(SupplyDetailController.class, "supply-detail-view.fxml");
        VBox supplyDetailRoot = FxmlViewLoader.load(supplyDetailLoader);
        supplyDetailController = supplyDetailLoader.getController();
        supplyDetailController.setOnSortOptionsChanged(options -> applySortAndDisplayOrders());
        supplyDetailContainer.getChildren().setAll(supplyDetailRoot);
    }

    private void initializeKizPanel() {
        FXMLLoader loader = FxmlViewLoader.loader(KizPanelController.class, "kiz-panel-view.fxml");
        VBox root = FxmlViewLoader.load(loader);
        kizPanelController = loader.getController();
        kizPanelController.setOnAddCategory(this::onAddCategory);
        kizPanelContainer.getChildren().setAll(root);
    }

    private void renderShops() {
        shopSidebarController.setShops(state.getShops(), state.getSelectedShop() == null ? null : state.getSelectedShop().getId());
    }

    private void clearWorkspaceView() {
        contentPane.setVisible(false);
        kizPanelContainer.setVisible(false);
        workspaceHeaderController.setCurrentShopName("");
        kizPanelController.clearCategories();
        clearSupplyViews();
        updateHeaderState();
    }

    private void clearSupplyViews() {
        if (supplyListController != null) {
            supplyListController.setSupplies(List.of());
            supplyListController.setLoading(false);
        }
        if (supplyDetailController != null) {
            supplyDetailController.showEmptyState("", "");
            supplyDetailController.setLoading(false);
            supplyDetailController.setStickerLoading(false);
        }
        resetLoadedSupply();
    }

    private void showWorkspace() {
        contentPane.setVisible(true);
        kizPanelContainer.setVisible(true);
        supplyDetailController.showEmptyState("", "");
    }

    private void resetLoadedSupply() {
        state.clearLoadedSupply();
        if (supplyDetailController != null) {
            supplyDetailController.setLoading(false);
            supplyDetailController.setStickerLoading(false);
            supplyDetailController.setSupplyInfo("", "");
            supplyDetailController.setOrders(List.of());
        }
        updateExportAvailability();
    }

    private void refreshSupplyListIfCurrent(int shopId) {
        if (isCurrentShop(shopId)) {
            refreshSupplyList();
        }
    }

    private boolean isCurrentShop(int shopId) {
        return state.getSelectedShop() != null && state.getSelectedShop().getId() == shopId;
    }

    private boolean isCurrentSupplyRequest(int shopId, String supplyId, long requestToken) {
        return isCurrentShop(shopId)
                && Objects.equals(state.getLoadedSupplyId(), supplyId)
                && state.getSupplyRequestToken() == requestToken;
    }

    private Shop requireSelectedShop() {
        if (state.getSelectedShop() == null) {
            AlertService.showError("Vui lòng chọn cửa hàng");
            return null;
        }
        return state.getSelectedShop();
    }

    private void markShopRunning(int shopId, boolean running) {
        activityTracker.markRunning(shopId, running);
        if (isCurrentShop(shopId)) {
            updateHeaderState();
        }
    }

    private void updateHeaderState() {
        Shop selectedShop = state.getSelectedShop();
        boolean hasShop = selectedShop != null;
        boolean running = hasShop && activityTracker.isRunning(selectedShop.getId());
        workspaceHeaderController.setBusy(running);
        workspaceHeaderController.setControls(hasShop, running, canExport());
    }

    private void updateExportAvailability() {
        updateHeaderState();
    }

    private boolean canExport() {
        Shop shop = state.getSelectedShop();
        boolean running = shop != null && activityTracker.isRunning(shop.getId());
        return shop != null
                && state.getLoadedSupplyId() != null
                && !state.getDisplayedOrders().isEmpty()
                && !running
                && !state.isSupplyEnriching();
    }

    private void tryOpenFile(File file) {
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException ex) {
            LOGGER.error("Không thể mở file {}", file, ex);
        }
    }
}
