package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.features.fbo.FboBarcodePdfExporter;
import com.tuandev.fbsbarcode.features.fbo.FboBarcodePrintItem;
import com.tuandev.fbsbarcode.features.fbo.FboKizPrintPlanner;
import com.tuandev.fbsbarcode.features.fbo.FboProductRepository;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPlan;
import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncReport;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbTokenInspector;
import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.features.kiz.CategoryWorkflow;
import com.tuandev.fbsbarcode.shared.ConfigService;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.I18nService;
import com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator;
import com.tuandev.fbsbarcode.features.print.PrintJobOptions;
import com.tuandev.fbsbarcode.features.print.PrintOptionsDialogService;
import com.tuandev.fbsbarcode.features.print.OrderExportWorkflow;
import com.tuandev.fbsbarcode.features.print.PrintAuthorizationDialogService;
import com.tuandev.fbsbarcode.features.print.PrintAuthorizationService;
import com.tuandev.fbsbarcode.features.print.PrintTemplateDesignerService;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.supply.OrderSortPreferenceService;
import com.tuandev.fbsbarcode.features.supply.OrderSortingService;
import com.tuandev.fbsbarcode.integration.update.UpdateDialogService;
import com.tuandev.fbsbarcode.integration.update.UpdateInfo;
import com.tuandev.fbsbarcode.integration.update.UpdateInstallerService;
import com.tuandev.fbsbarcode.integration.update.UpdateService;
import com.tuandev.fbsbarcode.features.shop.ShopWorkflow;
import com.tuandev.fbsbarcode.features.supply.SupplyLoadWorkflow;
import com.tuandev.fbsbarcode.ui.history.PrintHistoryController;
import com.tuandev.fbsbarcode.ui.dashboard.DashboardController;
import com.tuandev.fbsbarcode.ui.fbo.FboPackingController;
import com.tuandev.fbsbarcode.ui.kiz.KizPanelController;
import com.tuandev.fbsbarcode.ui.kizmapping.KizMappingController;
import com.tuandev.fbsbarcode.ui.packing.PackingController;
import com.tuandev.fbsbarcode.ui.shop.ShopSidebarController;
import com.tuandev.fbsbarcode.ui.supply.OrderSortOptions;
import com.tuandev.fbsbarcode.ui.supply.SupplyDetailController;
import com.tuandev.fbsbarcode.ui.supply.SupplyListController;
import com.tuandev.fbsbarcode.ui.supply.SupplyManagementController;
import javafx.concurrent.Task;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.Node;
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
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class HomeController implements Initializable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HomeController.class);

    private final OrderExportWorkflow orderExportWorkflow = new OrderExportWorkflow();
    private final ShopWorkflow shopWorkflow = new ShopWorkflow();
    private final CategoryWorkflow categoryWorkflow = new CategoryWorkflow();
    private final WbSyncWorkflow wbSyncWorkflow = new WbSyncWorkflow();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();
    private final PackingWorkflow packingWorkflow = new PackingWorkflow();
    private final SupplyLoadWorkflow supplyLoadWorkflow = new SupplyLoadWorkflow();
    private final OrderSortingService orderSortingService = new OrderSortingService();
    private final OrderSortPreferenceService orderSortPreferenceService = new OrderSortPreferenceService();
    private final PrintAuthorizationDialogService printAuthorizationDialogService = new PrintAuthorizationDialogService();
    private final PrintAuthorizationService printAuthorizationService = new PrintAuthorizationService();
    private final PrintOptionsDialogService printOptionsDialogService = new PrintOptionsDialogService();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final PrintTemplateDesignerService printTemplateDesignerService = new PrintTemplateDesignerService();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();
    private final FboProductRepository fboProductRepository = new FboProductRepository();
    private final FboBarcodePdfExporter fboBarcodePdfExporter = new FboBarcodePdfExporter();
    private final FboKizPrintPlanner fboKizPrintPlanner = new FboKizPrintPlanner();
    private final KizAttachmentCoordinator kizAttachmentCoordinator = KizAttachmentCoordinator.getInstance();
    private final WorkspaceState state = new WorkspaceState();
    private final WorkspaceActivityTracker activityTracker = new WorkspaceActivityTracker();
    private final UpdateService updateService = new UpdateService();
    private final UpdateInstallerService updateInstallerService = new UpdateInstallerService();
    private final I18nService i18nService = I18nService.getInstance();

    public StackPane sidebarContainer;
    public StackPane headerContainer;
    public BorderPane contentPane;
    public StackPane dynamicContentContainer;

    private BorderPane supplyManagementView;
    private VBox dashboardView;
    private VBox printHistoryView;
    private VBox packingView;
    private VBox fboPackingView;
    private Node kizMappingView;
    private SupplyManagementController supplyManagementController;
    private DashboardController dashboardController;
    private PrintHistoryController printHistoryController;
    private PackingController packingController;
    private FboPackingController fboPackingController;
    private KizMappingController kizMappingController;

    private FileChooser fileChooser;
    private ShopSidebarController shopSidebarController;
    private WorkspaceHeaderController workspaceHeaderController;
    private SupplyListController supplyListController;
    private SupplyDetailController supplyDetailController;
    private KizPanelController kizPanelController;
    private WbSupplySummary loadedSupplySummary;
    private final PauseTransition fboSearchDebounce = new PauseTransition(javafx.util.Duration.millis(250));
    private static final int FBO_PAGE_SIZE = 50;
    private boolean appActivated;
    private boolean fboLoading;
    private boolean fboHasMore;
    private final Consumer<com.tuandev.fbsbarcode.shared.AppLanguage> languageListener =
            language -> Platform.runLater(this::applyTranslations);
    private final Consumer<KizAttachmentCoordinator.KizAttachmentProgress> kizProgressListener =
            progress -> Platform.runLater(() -> onKizAttachmentProgress(progress));
    private final Set<Integer> productKizSyncingShopIds = ConcurrentHashMap.newKeySet();
    private boolean disposed;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        Database.initDatabase();
        printTemplateService.ensureDefaultTemplateExists();
        appActivated = printAuthorizationService.isAuthorized();

        fileChooser = new FileChooser();
        File initialDirectory = AppPaths.preferredFileChooserDirectory();
        if (initialDirectory != null) {
            fileChooser.setInitialDirectory(initialDirectory);
        }

        initializeSidebar();
        initializeHeader();
        loadDynamicViews();
        initializeSupplyViews();
        initializeKizPanel();
        initializeBackgroundKizProgress();
        i18nService.addListener(languageListener);

        contentPane.setVisible(false);
        showDashboard();
        updateHeaderState();
        updateExportAvailability();
        loadShops();
        checkForUpdates();
    }

    private void loadDynamicViews() {
        FXMLLoader dashboardLoader = FxmlViewLoader.loader(DashboardController.class, "dashboard-view.fxml");
        dashboardView = FxmlViewLoader.load(dashboardLoader);
        dashboardController = dashboardLoader.getController();
        dashboardController.applyTranslations();

        FXMLLoader supplyLoader = FxmlViewLoader.loader(SupplyManagementController.class, "supply-management-view.fxml");
        supplyManagementView = FxmlViewLoader.load(supplyLoader);
        supplyManagementController = supplyLoader.getController();

        FXMLLoader printHistoryLoader = FxmlViewLoader.loader(PrintHistoryController.class, "print-history-view.fxml");
        printHistoryView = FxmlViewLoader.load(printHistoryLoader);
        printHistoryController = printHistoryLoader.getController();
        printHistoryController.setOnReprint(this::reprintHistoryJob);
        printHistoryController.applyTranslations();

        FXMLLoader packingLoader = FxmlViewLoader.loader(PackingController.class, "packing-view.fxml");
        packingView = FxmlViewLoader.load(packingLoader);
        packingController = packingLoader.getController();
        packingController.setOnPrintSupply(this::openSupplyForPrint);
        packingController.applyTranslations();

        FXMLLoader fboLoader = FxmlViewLoader.loader(FboPackingController.class, "fbo-packing-view.fxml");
        fboPackingView = FxmlViewLoader.load(fboLoader);
        fboPackingController = fboLoader.getController();
        fboPackingController.setOnSearchChanged(this::scheduleFboReload);
        fboPackingController.setOnLoadMoreRequested(this::loadMoreFboProducts);
        fboPackingController.setOnPrint(this::printFboBarcodes);
        fboPackingController.setOnQuickPrint(this::printSingleFboBarcode);
        fboPackingController.applyTranslations();

        FXMLLoader kizMappingLoader = FxmlViewLoader.loader(KizMappingController.class, "kiz-mapping-view.fxml");
        kizMappingView = FxmlViewLoader.load(kizMappingLoader);
        kizMappingController = kizMappingLoader.getController();
        kizMappingController.setOnKizInventoryChanged(this::loadCategories);
        kizMappingController.applyTranslations();
    }

    private void showPrintHistory() {
        clearKizDraft();
        setDynamicContent(printHistoryView);
        refreshPrintHistory();
    }

    private void showDashboard() {
        clearKizDraft();
        setDynamicContent(dashboardView);
        refreshDashboard(false);
    }

    private void showPacking() {
        clearKizDraft();
        setDynamicContent(packingView);
        refreshPackingView();
    }

    private void showFboPacking() {
        clearKizDraft();
        setDynamicContent(fboPackingView);
        refreshFboView();
    }

    private void showKizMapping() {
        clearKizDraft();
        setDynamicContent(kizMappingView);
        refreshKizMappingView();
    }

    private void refreshPrintHistory() {
        if (printHistoryController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            printHistoryController.setJobs(List.of());
            return;
        }
        printHistoryController.setJobs(printHistoryService.getJobs(shop.getId()));
    }

    private void refreshPackingView() {
        if (packingController == null) {
            return;
        }
        packingController.setShop(state.getSelectedShop(), state.isSelectedShopTokenValid());
    }

    private void refreshDashboard(boolean forceRefresh) {
        if (dashboardController != null) {
            dashboardController.setShop(state.getSelectedShop(), forceRefresh);
        }
    }

    private void refreshFboView() {
        if (fboPackingController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            fboPackingController.setSubjects(List.of());
            fboPackingController.replaceProducts(List.of(), false);
            return;
        }
        fboPackingController.setSubjects(fboProductRepository.findSubjects(shop.getId()));
        reloadFboProducts();
    }

    private void refreshKizMappingView() {
        if (kizMappingController != null) {
            kizMappingController.setShop(state.getSelectedShop());
        }
    }

    private void scheduleFboReload() {
        fboSearchDebounce.setOnFinished(event -> reloadFboProducts());
        fboSearchDebounce.playFromStart();
    }

    private void reloadFboProducts() {
        loadFboProducts(false);
    }

    private void loadMoreFboProducts() {
        if (fboHasMore && !fboLoading) {
            loadFboProducts(true);
        }
    }

    private void loadFboProducts(boolean append) {
        if (fboPackingController == null || fboLoading) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            fboPackingController.replaceProducts(List.of(), false);
            return;
        }
        fboLoading = true;
        int offset = append ? fboPackingController.rowCount() : 0;
        Task<List<FboProductSku>> task = new Task<>() {
            @Override
            protected List<FboProductSku> call() {
                return fboProductRepository.search(fboPackingController.criteria(shop.getId(), FBO_PAGE_SIZE + 1, offset));
            }
        };
        fboPackingController.setLoading(true);
        task.setOnSucceeded(event -> {
            fboLoading = false;
            fboPackingController.setLoading(false);
            List<FboProductSku> loaded = task.getValue() == null ? List.of() : task.getValue();
            fboHasMore = loaded.size() > FBO_PAGE_SIZE;
            List<FboProductSku> page = fboHasMore ? loaded.subList(0, FBO_PAGE_SIZE) : loaded;
            if (append) {
                fboPackingController.appendProducts(page, fboHasMore);
            } else {
                fboPackingController.replaceProducts(page, fboHasMore);
            }
        });
        task.setOnFailed(event -> {
            fboLoading = false;
            fboPackingController.setLoading(false);
            LOGGER.error("Không thể tải sản phẩm FBO", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void openSupplyForPrint(WbSupplySummary supply) {
        setSupplyListVisible(false);
        setDynamicContent(supplyManagementView);
        refreshSupplyList(supply.getSupplyId());
    }

    private void setDynamicContent(Node view) {
        clearFboQuantitiesIfLeaving(view);
        dynamicContentContainer.getChildren().setAll(view);
        view.setOpacity(0.0);
        FadeTransition fade = new FadeTransition(javafx.util.Duration.millis(120), view);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    private void clearFboQuantitiesIfLeaving(Node nextView) {
        if (fboPackingController == null || fboPackingView == null || nextView == fboPackingView) {
            return;
        }
        if (dynamicContentContainer.getChildren().contains(fboPackingView)) {
            fboPackingController.clearQuantities();
        }
    }

    private void setSupplyListVisible(boolean visible) {
        if (supplyManagementController == null || supplyManagementController.supplyListContainer == null) {
            return;
        }
        supplyManagementController.supplyListContainer.setVisible(visible);
        supplyManagementController.supplyListContainer.setManaged(visible);
    }

    private void reprintHistoryJob(PrintHistoryJobSummary job) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        if (!printAuthorizationDialogService.ensureAuthorized()) {
            return;
        }

        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }

        File orderDetailsFile = new File(file.getParent(), "NHAT_HANG-" + file.getName());
        Task<OrderExportWorkflow.ExportResult> task = new Task<>() {
            @Override
            protected OrderExportWorkflow.ExportResult call() throws Exception {
                return printHistoryService.reprint(job, file, orderDetailsFile);
            }
        };
        task.setOnRunning(e -> markShopRunning(shop.getId(), true));
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            LOGGER.error("In lại thất bại cho lịch sử in {}", job.id(), task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            tryOpenFile(orderDetailsFile);
            tryOpenFile(file);
            refreshPrintHistory();
        });
        AppTaskExecutor.execute(task);
    }

    private void printFboBarcodes(List<FboBarcodePrintItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            return;
        }
        if (!appActivated && !showActivationInputDialog()) {
            return;
        }
        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                FboPrintPlan plan = fboKizPrintPlanner.plan(shop.getId(), items);
                fboBarcodePdfExporter.exportPlan(plan, file);
                if (!plan.usedKizs().isEmpty()) {
                    KizService.deleteKizs(plan.usedKizs());
                }
                return null;
            }
        };
        task.setOnFailed(event -> {
            LOGGER.error("Không thể in barcode FBO", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(event -> {
            fboPackingController.clearQuantities();
            loadCategories();
            tryOpenFile(file);
        });
        AppTaskExecutor.execute(task);
    }

    private void printSingleFboBarcode(FboProductSku product) {
        if (product == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        if (shop == null) {
            return;
        }
        if (!appActivated && !showActivationInputDialog()) {
            return;
        }
        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                FboPrintPlan plan = fboKizPrintPlanner.plan(shop.getId(), List.of(new FboBarcodePrintItem(product, 1)));
                fboBarcodePdfExporter.exportPlan(plan, file);
                if (!plan.usedKizs().isEmpty()) {
                    KizService.deleteKizs(plan.usedKizs());
                }
                return null;
            }
        };
        task.setOnFailed(event -> {
            LOGGER.error("Không thể in nhanh barcode FBO", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(event -> {
            loadCategories();
            tryOpenFile(file);
        });
        AppTaskExecutor.execute(task);
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

    private void checkVersionManually() {
        Task<UpdateInfo> task = new Task<>() {
            @Override
            protected UpdateInfo call() {
                return updateService.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> {
            UpdateInfo info = task.getValue();
            if (info != null) {
                showUpdateDialog(info);
            } else {
                AlertService.showInfo(
                        i18nService.tr("version.current.title"),
                        i18nService.tr("version.current.header"),
                        MessageFormat.format(i18nService.tr("version.current.content"), BuildConfig.getAppVersion())
                );
            }
        });
        task.setOnFailed(e -> {
            LOGGER.warn("Manual update check failed", task.getException());
            AlertService.showError(i18nService.tr("version.check_failed"));
        });
        AppTaskExecutor.execute(task);
    }

    private void showActivationDialog() {
        if (!printAuthorizationService.isAuthorized()) {
            showActivationInputDialog();
            return;
        }

        ButtonType deleteButton = new ButtonType(i18nService.tr("activation.delete"), ButtonBar.ButtonData.OTHER);
        ButtonType closeButton = new ButtonType(i18nService.tr("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        AlertService.applyTheme(alert);
        alert.setTitle(i18nService.tr("activation.status.title"));
        alert.setHeaderText(i18nService.tr("activation.status.header"));
        alert.setContentText(MessageFormat.format(
                i18nService.tr("activation.status.content"),
                i18nService.tr("activation.status.authorized"),
                safeText(ConfigService.getActivatedAt())
        ));
        alert.getDialogPane().getButtonTypes().setAll(deleteButton, closeButton);
        alert.showAndWait().ifPresent(choice -> {
            if (choice == deleteButton) {
                printAuthorizationService.clearRememberedAuthorization();
                setAppActivated(false);
                AlertService.showInfo(
                        i18nService.tr("activation.deleted.title"),
                        i18nService.tr("activation.deleted.header"),
                        i18nService.tr("activation.deleted.content")
                );
            }
        });
    }

    private boolean showActivationInputDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(i18nService.tr("activation.input.title"));
        dialog.setHeaderText(i18nService.tr("activation.input.header"));
        dialog.getDialogPane().setMinWidth(420);

        ButtonType activateButton = new ButtonType(i18nService.tr("activation.activate"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(i18nService.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(activateButton, cancelButton);

        PasswordField keyField = new PasswordField();
        keyField.setPromptText(i18nService.tr("activation.input.content"));

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        VBox content = new VBox(10,
                new Label(i18nService.tr("activation.input.content")),
                keyField,
                errorLabel
        );
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);

        Node activateNode = dialog.getDialogPane().lookupButton(activateButton);
        activateNode.addEventFilter(ActionEvent.ACTION, event -> {
            if (!printAuthorizationService.matches(keyField.getText())) {
                errorLabel.setText(i18nService.tr("print_auth.invalid"));
                errorLabel.setManaged(true);
                errorLabel.setVisible(true);
                keyField.requestFocus();
                keyField.selectAll();
                event.consume();
                return;
            }
            printAuthorizationService.rememberAuthorized();
            if (!printAuthorizationService.isAuthorized()) {
                errorLabel.setText(i18nService.tr("print_auth.remember_failed"));
                errorLabel.setManaged(true);
                errorLabel.setVisible(true);
                event.consume();
            }
        });

        Optional<ButtonType> choice = dialog.showAndWait();
        boolean activated = choice.isPresent() && choice.get() == activateButton && printAuthorizationService.isAuthorized();
        if (activated) {
            setAppActivated(true);
            AlertService.showInfo(
                    i18nService.tr("activation.success.title"),
                    i18nService.tr("activation.success.header"),
                    i18nService.tr("activation.success.content")
            );
        }
        return activated;
    }

    private void setAppActivated(boolean activated) {
        appActivated = activated;
        if (shopSidebarController != null) {
            shopSidebarController.setActivated(activated);
        }
    }

    private void showAboutDialog() {
        AlertService.showInfo(
                i18nService.tr("about.title"),
                MessageFormat.format(i18nService.tr("about.header"), BuildConfig.getAppVersion()),
                i18nService.tr("about.content")
        );
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void preparePdfSaveChooser() {
        fileChooser.setTitle(i18nService.tr("filechooser.save_pdf"));
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(i18nService.tr("filechooser.pdf"), "*.pdf"));
        File downloadsDirectory = AppPaths.preferredDownloadsDirectory();
        if (downloadsDirectory != null) {
            fileChooser.setInitialDirectory(downloadsDirectory);
        }
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
        UpdateDialogService dialogService = new UpdateDialogService();
        Task<java.nio.file.Path> task = new Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                updateMessage(i18nService.tr("update.progress.connecting"));
                updateProgress(-1, 1);
                return updateInstallerService.downloadInstaller(info, (downloadedBytes, totalBytes) -> {
                    if (totalBytes > 0) {
                        updateProgress(downloadedBytes, totalBytes);
                        updateMessage(String.format(i18nService.tr("update.progress.downloaded"),
                                formatBytes(downloadedBytes),
                                formatBytes(totalBytes)));
                    } else {
                        updateProgress(-1, 1);
                        updateMessage(i18nService.tr("update.progress.downloading"));
                    }
                });
            }
        };
        var progressDialog = dialogService.showDownloadProgressDialog(info, task);
        task.setOnFailed(e -> {
            progressDialog.close();
            LOGGER.error("Không thể tải bản cập nhật {}", info.getVersion(), task.getException());
            AlertService.showError(i18nService.tr("update.error.download_generic"));
        });
        task.setOnSucceeded(e -> {
            progressDialog.close();
            try {
                updateInstallerService.launchInstallerAfterExit(task.getValue());
                Platform.exit();
            } catch (IOException ex) {
                LOGGER.error("Không thể khởi chạy installer cập nhật {}", info.getVersion(), ex);
                AlertService.showError(i18nService.tr("update.error.launch_failed"));
            }
        });
        progressDialog.show();
        AppTaskExecutor.execute(task);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
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
            AlertService.showWarning(
                    i18nService.tr("common.notice"),
                    i18nService.tr("workspace.warning.refresh_orders.title"),
                    null
            );
            return;
        }
        if (!printAuthorizationDialogService.ensureAuthorized()) {
            return;
        }
        Optional<PrintJobOptions> printOptions = printOptionsDialogService.chooseOptions();
        if (printOptions.isEmpty()) {
            return;
        }
        List<Order> exportOrders = new ArrayList<>(state.getDisplayedOrders());
        String supplyId = state.getLoadedSupplyId();
        String supplyName = state.getLoadedSupplyName();

        preparePdfSaveChooser();
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }

        File orderDetailsFile = new File(file.getParent(), "NHAT_HANG-" + file.getName());
        Task<OrderExportWorkflow.ExportResult> task = new Task<>() {
            @Override
            protected OrderExportWorkflow.ExportResult call() throws Exception {
                List<Order> ordersWithStickers = supplyLoadWorkflow.enrichStickers(shop, exportOrders);
                return orderExportWorkflow.export(
                        new OrderExportWorkflow.ExportRequest(
                                shop,
                                supplyId,
                                supplyName,
                                ordersWithStickers,
                                printOptions.get(),
                                file,
                                orderDetailsFile
                        )
                );
            }
        };

        task.setOnRunning(e -> {
            markShopRunning(shop.getId(), true);
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(true, i18nService.tr("supply.preparing_pdf"));
            }
        });
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(false);
            }
            Throwable ex = task.getException();
            LOGGER.error("Export thất bại cho shop {}", shop.getId(), ex);
            AlertService.showError(ex.getMessage());
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            OrderExportWorkflow.ExportResult result = task.getValue();
            state.setLoadedOrdersRaw(result.exportedOrders());
            applySortAndDisplayOrders();
            loadCategories();
            clearKizDraft();
            tryOpenFile(orderDetailsFile);
            tryOpenFile(file);
            enqueueBackgroundKizAttachment(shop, supplyId, supplyName, result);
            if (isPrintHistoryVisible()) {
                refreshPrintHistory();
            }
            if (isPackingVisible()) {
                refreshPackingView();
            }
            refreshCurrentKizAttachmentProgress();
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
            renderShops();
            loadShops();
        });
    }

    public void onDeleteShop(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        int shopId = shop.getId();
        if (activityTracker.isSyncing(shopId) || kizAttachmentCoordinator.hasActiveJobForShop(shopId)) {
            AlertService.showWarning(
                    i18nService.tr("workspace.delete_shop.busy.title"),
                    i18nService.tr("workspace.delete_shop.busy.header"),
                    MessageFormat.format(i18nService.tr("workspace.delete_shop.busy.content"), shop.getName())
            );
            return;
        }

        Optional<ButtonType> result = AlertService.showConfirmation(
                i18nService.tr("workspace.delete_shop.confirm.title"),
                MessageFormat.format(i18nService.tr("workspace.delete_shop.confirm.header"), shop.getName()),
                i18nService.tr("workspace.delete_shop.confirm.content")
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
                ConfigService.setLastSelectedShopId(null);
            }
            loadShops();
        });
        AppTaskExecutor.execute(task);
    }

    public void onSyncWildberries(ActionEvent actionEvent) {
        Shop shop = requireSelectedShop();
        if (shop != null) {
            if (!state.isSelectedShopTokenValid()) {
                AlertService.showWarning(
                        i18nService.tr("wb.token.title"),
                        i18nService.tr("workspace.sync.token_invalid"),
                        state.getSelectedShopTokenMessage()
                );
                return;
            }
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
                ConfigService.setLastSelectedShopId(null);
                return;
            }
            if (state.getPendingSelectShopId() != null) {
                selectShopById(state.getPendingSelectShopId());
                state.setPendingSelectShopId(null);
            } else if (state.getSelectedShop() != null) {
                selectShopById(state.getSelectedShop().getId());
            } else if (ConfigService.getLastSelectedShopId() != null) {
                if (!selectShopByIdIfExists(ConfigService.getLastSelectedShopId())) {
                    selectShop(state.getShops().get(0));
                }
            } else {
                selectShop(state.getShops().get(0));
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
        fileChooser.setTitle(i18nService.tr("filechooser.open_pdf"));
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(i18nService.tr("filechooser.pdf"), "*.pdf"));
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
        task.setOnRunning(ex -> {
            markShopRunning(shop.getId(), true);
            setSupplyKizPanelLoading(true);
        });
        task.setOnSucceeded(ex -> {
            markShopRunning(shop.getId(), false);
            setSupplyKizPanelLoading(false);
            if (isCurrentShop(shop.getId())) {
                loadCategories();
            }
        });
        task.setOnFailed(ex -> {
            markShopRunning(shop.getId(), false);
            setSupplyKizPanelLoading(false);
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
        AlertService.applyTheme(alert);
        alert.setTitle(i18nService.tr("category.delete.title"));
        alert.setHeaderText(MessageFormat.format(i18nService.tr("category.delete.header"), category.getName()));
        ButtonType buttonTypeConfirm = new ButtonType(i18nService.tr("common.delete"), ButtonBar.ButtonData.YES);
        ButtonType buttonTypeCancel = new ButtonType(i18nService.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(buttonTypeConfirm, buttonTypeCancel);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == buttonTypeConfirm) {
            categoryWorkflow.deleteCategory(shop, category);
            loadCategories();
        }
    }

    private void setSupplyKizPanelLoading(boolean loading) {
        if (kizPanelController != null) {
            kizPanelController.setLoading(loading);
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
            AlertService.showError(i18nService.tr("category.error.id_number"));
        } catch (SQLException e) {
            LOGGER.error("Không thể thêm category", e);
            AlertService.showError(i18nService.tr("category.error.id_exists"));
        }
    }

    private void selectShopById(int shopId) {
        selectShopByIdIfExists(shopId);
    }

    private boolean selectShopByIdIfExists(int shopId) {
        Shop shop = state.getShops().stream()
                .filter(item -> item.getId() == shopId)
                .findFirst()
                .orElse(null);
        if (shop != null) {
            selectShop(shop);
            return true;
        }
        return false;
    }

    private void selectShop(Shop shop) {
        state.setSelectedShop(shop);
        ConfigService.setLastSelectedShopId(shop.getId());
        renderShops();
        boolean tokenValid = applySelectedShopTokenState(shop, true);
        loadCategories();
        resetLoadedSupply();
        showWorkspace();
        updateHeaderState();
        if (isPrintHistoryVisible()) {
            refreshPrintHistory();
        }
        if (isDashboardVisible()) {
            refreshDashboard(false);
        }
        if (isFboPackingVisible()) {
            refreshFboView();
        }
        if (isKizMappingVisible()) {
            refreshKizMappingView();
        }
        refreshSupplyList();
        refreshPackingView();
        if (!tokenValid) {
            return;
        }
        supplyListController.setLoading(true);
        startShopSync(shop, false);
    }

    private void startShopSync(Shop shop, boolean manual) {
        if (shop == null) {
            return;
        }
        if (!state.isSelectedShopTokenValid()) {
            if (manual) {
                AlertService.showWarning(
                        i18nService.tr("wb.token.title"),
                        i18nService.tr("workspace.sync.token_invalid"),
                        state.getSelectedShopTokenMessage()
                );
            }
            return;
        }
        boolean started = activityTracker.markSyncStarted(shop.getId());
        if (isCurrentShop(shop.getId())) {
            if (!manual) {
                supplyListController.setLoading(true);
            }
            updateHeaderState();
        }
        if (!started) {
            return;
        }
        markProductKizSyncing(shop.getId(), true);

        Task<WbSyncReport> task = new Task<>() {
            @Override
            protected WbSyncReport call() throws Exception {
                return manual ? wbSyncWorkflow.syncAll(shop) : wbSyncWorkflow.syncOverview(shop);
            }
        };
        task.setOnRunning(e -> {
            if (isCurrentShop(shop.getId()) && !manual) {
                supplyListController.setLoading(true);
            }
        });
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            markProductKizSyncing(shop.getId(), false);
            Throwable ex = task.getException();
            LOGGER.error("Đồng bộ WB thất bại cho shop {}", shop.getId(), ex);
            refreshSupplyListIfCurrent(shop.getId());
            if (manual && isCurrentShop(shop.getId())) {
                AlertService.showError(ex.getMessage());
            }
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            markProductKizSyncing(shop.getId(), false);
            if (isCurrentShop(shop.getId())) {
                loadCategories();
            }
            refreshSupplyListIfCurrent(shop.getId());
            if (isPackingVisible()) {
                refreshPackingView();
            }
            if (isFboPackingVisible()) {
                refreshFboView();
            }
            if (isDashboardVisible()) {
                refreshDashboard(false);
            }
            if (isKizMappingVisible()) {
                refreshKizMappingView();
            }
        });
        AppTaskExecutor.execute(task);
    }

    private void refreshSupplyList() {
        refreshSupplyList(state.getLoadedSupplyId(), true);
    }

    private void refreshSupplyList(String supplyIdToRestore) {
        refreshSupplyList(supplyIdToRestore, true);
    }

    private void refreshSupplyList(String supplyIdToRestore, boolean notifySelection) {
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
        if (notifySelection) {
            supplyListController.setSupplies(supplies);
        } else {
            supplyListController.refreshSupplies(supplies, supplyIdToRestore);
        }
        if (supplyIdToRestore != null && supplies.stream().anyMatch(supply -> supplyIdToRestore.equals(supply.getSupplyId()))) {
            if (notifySelection) {
                supplyListController.selectSupply(supplyIdToRestore);
            }
        } else {
            resetLoadedSupply();
            supplyDetailController.showEmptyState("", "");
        }
        updateHeaderState();
    }

    private void loadSupply(WbSupplySummary supply) {
        Shop shop = requireSelectedShop();
        if (shop == null || supply == null) {
            return;
        }
        resetLoadedSupply();
        clearKizDraft();
        loadedSupplySummary = supply;
        long requestToken = state.nextSupplyRequestToken();
        state.setLoadedSupplyId(supply.getSupplyId());
        state.setLoadedSupplyName(supply.getName());
        supplyDetailController.setLoading(true);
        refreshCurrentKizAttachmentProgress();
        supplyDetailController.setSupplyInfo(formatSupplyTitle(supply), "");

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
            state.setLoadedOrdersRaw(localTask.getValue());
            supplyDetailController.setSupplyInfo(formatSupplyTitle(supply), "");
            applySortAndDisplayOrders();
            supplyDetailController.setLoading(false);
            updateExportAvailability();
            refreshCurrentKizAttachmentProgress();
            startSupplyRefresh(shop, supply, requestToken);
        });
        AppTaskExecutor.execute(localTask);
    }

    private void startSupplyRefresh(Shop shop, WbSupplySummary supply, long requestToken) {
        Task<List<Order>> refreshTask = new Task<>() {
            @Override
            protected List<Order> call() throws Exception {
                return supplyLoadWorkflow.refreshSupplyData(shop, supply.getSupplyId());
            }
        };
        refreshTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            updateExportAvailability();
            LOGGER.warn("Không thể refresh supply {} ở nền", supply.getSupplyId(), refreshTask.getException());
        });
        refreshTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            state.setLoadedOrdersRaw(refreshTask.getValue());
            applySortAndDisplayOrders();
            supplyDetailController.setSupplyInfo(formatSupplyTitle(supply), "");
            if (supplyListController != null) {
                supplyListController.updateSupplySummary(new WbSupplySummary(
                        supply.getSupplyId(),
                        supply.getName(),
                        supply.isDone(),
                        supply.getB2b(),
                        supply.getCreatedAt(),
                        refreshTask.getValue() == null ? 0 : refreshTask.getValue().size()
                ));
            }
            startSilentImageWarmup(shop, supply, requestToken);
        });
        AppTaskExecutor.execute(refreshTask);
    }

    private void startSilentImageWarmup(Shop shop, WbSupplySummary supply, long requestToken) {
        List<Order> orders = state.getLoadedOrdersRaw();
        if (orders == null || orders.isEmpty()) {
            return;
        }
        Task<Void> imageTask = new Task<>() {
            @Override
            protected Void call() {
                supplyLoadWorkflow.ensureImages(orders);
                return null;
            }
        };
        imageTask.setOnFailed(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            LOGGER.debug("Không thể nạp ảnh nền cho supply {}", supply.getSupplyId(), imageTask.getException());
        });
        imageTask.setOnSucceeded(e -> {
            if (!isCurrentSupplyRequest(shop.getId(), supply.getSupplyId(), requestToken)) {
                return;
            }
            supplyDetailController.refreshOrders();
        });
        AppTaskExecutor.execute(imageTask);
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
        shopSidebarController.setOnDashboard(this::showDashboard);
        shopSidebarController.setOnPacking(this::showPacking);
        shopSidebarController.setOnFboPacking(this::showFboPacking);
        shopSidebarController.setOnKizMapping(this::showKizMapping);
        shopSidebarController.setOnPrintHistory(this::showPrintHistory);
        shopSidebarController.setOnCheckVersion(this::checkVersionManually);
        shopSidebarController.setOnActivation(this::showActivationDialog);
        shopSidebarController.setOnAbout(this::showAboutDialog);
        shopSidebarController.setOnLanguageChanged(i18nService::setLanguage);
        shopSidebarController.setSelectedLanguage(i18nService.getCurrentLanguage());
        shopSidebarController.setActivated(appActivated);
        shopSidebarController.applyTranslations();
        sidebarContainer.getChildren().setAll(root);
    }

    private void initializeHeader() {
        FXMLLoader loader = FxmlViewLoader.loader(WorkspaceHeaderController.class, "workspace-header-view.fxml");
        HBox root = FxmlViewLoader.load(loader);
        workspaceHeaderController = loader.getController();
        workspaceHeaderController.setOnSync(() -> onSyncWildberries(new ActionEvent()));
        workspaceHeaderController.setOnEditShop(() -> onUpdateShop(new ActionEvent()));
        workspaceHeaderController.setOnDeleteShop(() -> onDeleteShop(new ActionEvent()));
        workspaceHeaderController.setOnShopSelected(this::selectShop);
        workspaceHeaderController.applyTranslations();
        headerContainer.getChildren().setAll(root);
    }

    private void initializeSupplyViews() {
        FXMLLoader supplyListLoader = FxmlViewLoader.loader(SupplyListController.class, "supply-list-view.fxml");
        VBox supplyListRoot = FxmlViewLoader.load(supplyListLoader);
        supplyListController = supplyListLoader.getController();
        supplyListController.setOnSupplySelected(this::loadSupply);
        supplyListController.setOnRefetchRequested(this::onRefetchSupplies);
        supplyListController.applyTranslations();
        supplyManagementController.supplyListContainer.getChildren().setAll(supplyListRoot);

        FXMLLoader supplyDetailLoader = FxmlViewLoader.loader(SupplyDetailController.class, "supply-detail-view.fxml");
        VBox supplyDetailRoot = FxmlViewLoader.load(supplyDetailLoader);
        supplyDetailController = supplyDetailLoader.getController();
        supplyDetailController.setSortOptions(orderSortPreferenceService.load());
        supplyDetailController.setOnSortOptionsChanged(options -> {
            orderSortPreferenceService.save(options);
            applySortAndDisplayOrders();
        });
        supplyDetailController.setOnPrint(() -> onExport(new ActionEvent()));
        supplyDetailController.setOnBack(this::showPacking);
        supplyDetailController.setOnDeliver(this::deliverLoadedSupply);
        supplyDetailController.applyTranslations();
        supplyManagementController.supplyDetailContainer.getChildren().setAll(supplyDetailRoot);
    }

    private void initializeKizPanel() {
        FXMLLoader loader = FxmlViewLoader.loader(KizPanelController.class, "kiz-panel-view.fxml");
        VBox root = FxmlViewLoader.load(loader);
        kizPanelController = loader.getController();
        kizPanelController.setOnAddCategory(this::onAddCategory);
        kizPanelController.applyTranslations();
        supplyManagementController.kizPanelContainer.getChildren().setAll(root);
    }

    private void renderShops() {
        workspaceHeaderController.setShops(state.getShops(), state.getSelectedShop());
    }

    private void applyTranslations() {
        if (disposed) {
            return;
        }
        if (shopSidebarController != null) {
            shopSidebarController.setSelectedLanguage(i18nService.getCurrentLanguage());
            shopSidebarController.applyTranslations();
        }
        if (workspaceHeaderController != null) {
            workspaceHeaderController.applyTranslations();
        }
        if (packingController != null) {
            packingController.applyTranslations();
        }
        if (dashboardController != null) {
            dashboardController.applyTranslations();
        }
        if (fboPackingController != null) {
            fboPackingController.applyTranslations();
        }
        if (kizMappingController != null) {
            kizMappingController.applyTranslations();
        }
        if (printHistoryController != null) {
            printHistoryController.applyTranslations();
        }
        if (supplyListController != null) {
            supplyListController.applyTranslations();
        }
        if (supplyDetailController != null) {
            supplyDetailController.applyTranslations();
            if (state.getLoadedSupplyId() == null) {
                supplyDetailController.setSupplyInfo(
                        i18nService.tr("supply.not_selected"),
                        i18nService.tr("supply.select_prompt")
                );
            }
        }
        if (kizPanelController != null) {
            kizPanelController.applyTranslations();
        }
    }

    private void clearWorkspaceView() {
        contentPane.setVisible(false);
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
            refreshCurrentKizAttachmentProgress();
        }
        if (packingController != null) {
            packingController.setShop(null, false);
        }
        if (dashboardController != null) {
            dashboardController.setShop(null, false);
        }
        if (kizMappingController != null) {
            kizMappingController.setShop(null);
        }
        resetLoadedSupply();
    }

    private void showWorkspace() {
        contentPane.setVisible(true);
        clearKizDraft();
        supplyDetailController.showEmptyState("", "");
    }

    private void resetLoadedSupply() {
        loadedSupplySummary = null;
        state.clearLoadedSupply();
        if (supplyDetailController != null) {
            supplyDetailController.setLoading(false);
            refreshCurrentKizAttachmentProgress();
            supplyDetailController.setSupplyInfo("", "");
            supplyDetailController.setDeliverEnabled(false);
            supplyDetailController.setOrders(List.of());
        }
        clearKizDraft();
        updateExportAvailability();
    }

    private void refreshSupplyListIfCurrent(int shopId) {
        if (isCurrentShop(shopId)) {
            refreshSupplyList();
        }
    }

    private void onRefetchSupplies() {
        Shop shop = requireSelectedShop();
        if (shop == null) {
            return;
        }
        if (!state.isSelectedShopTokenValid()) {
            AlertService.showWarning(
                    i18nService.tr("wb.token.title"),
                    i18nService.tr("workspace.refetch.token_invalid"),
                    state.getSelectedShopTokenMessage()
            );
            return;
        }
        startSupplyListRefetch(shop);
    }

    private void startSupplyListRefetch(Shop shop) {
        if (shop == null) {
            return;
        }
        boolean started = activityTracker.markSyncStarted(shop.getId());
        if (isCurrentShop(shop.getId())) {
            supplyListController.setLoading(true);
            updateHeaderState();
        }
        if (!started) {
            return;
        }

        String selectedSupplyId = state.getLoadedSupplyId();
        Task<WbSyncReport> task = new Task<>() {
            @Override
            protected WbSyncReport call() throws Exception {
                return wbSyncWorkflow.refetchSupplies(shop);
            }
        };
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            Throwable ex = task.getException();
            LOGGER.error("Refetch supply thất bại cho shop {}", shop.getId(), ex);
            if (isCurrentShop(shop.getId())) {
                refreshSupplyList(selectedSupplyId);
                AlertService.showError(ex.getMessage());
            }
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            if (isCurrentShop(shop.getId())) {
                refreshSupplyList(selectedSupplyId);
                if (isPackingVisible()) {
                    refreshPackingView();
                }
            }
        });
        AppTaskExecutor.execute(task);
    }

    private boolean isCurrentShop(int shopId) {
        return state.getSelectedShop() != null && state.getSelectedShop().getId() == shopId;
    }

    private boolean isPrintHistoryVisible() {
        return dynamicContentContainer.getChildren().contains(printHistoryView);
    }

    private boolean isDashboardVisible() {
        return dynamicContentContainer.getChildren().contains(dashboardView);
    }

    private boolean isPackingVisible() {
        return dynamicContentContainer.getChildren().contains(packingView);
    }

    private boolean isFboPackingVisible() {
        return dynamicContentContainer.getChildren().contains(fboPackingView);
    }

    private boolean isKizMappingVisible() {
        return dynamicContentContainer.getChildren().contains(kizMappingView);
    }

    private boolean isCurrentSupplyRequest(int shopId, String supplyId, long requestToken) {
        return isCurrentShop(shopId)
                && Objects.equals(state.getLoadedSupplyId(), supplyId)
                && state.getSupplyRequestToken() == requestToken;
    }

    private Shop requireSelectedShop() {
        if (state.getSelectedShop() == null) {
            AlertService.showError(i18nService.tr("workspace.error.select_shop"));
            return null;
        }
        return state.getSelectedShop();
    }

    private boolean applySelectedShopTokenState(Shop shop, boolean showWarning) {
        WbTokenInspector.TokenStatus status = WbTokenInspector.inspect(shop);
        state.setSelectedShopTokenValid(status.valid());
        state.setSelectedShopTokenMessage(status.message());
        if (!status.valid() && showWarning && status.message() != null && !status.message().isBlank()) {
            AlertService.showWarning(
                    i18nService.tr("wb.token.title"),
                    i18nService.tr("workspace.token.update_required"),
                    status.message()
            );
        }
        return status.valid();
    }

    private void markShopRunning(int shopId, boolean running) {
        activityTracker.markRunning(shopId, running);
        if (isCurrentShop(shopId)) {
            updateHeaderState();
        }
    }

    private void markProductKizSyncing(int shopId, boolean syncing) {
        if (syncing) {
            productKizSyncingShopIds.add(shopId);
        } else {
            productKizSyncingShopIds.remove(shopId);
        }
        if (isCurrentShop(shopId)) {
            updateHeaderState();
        }
    }

    private void updateHeaderState() {
        Shop selectedShop = state.getSelectedShop();
        boolean hasShop = selectedShop != null;
        boolean running = hasShop && isShopBusy(selectedShop.getId());
        workspaceHeaderController.setBusy(running);
        workspaceHeaderController.setProductKizSyncLoading(hasShop && productKizSyncingShopIds.contains(selectedShop.getId()));
        boolean tokenValid = !hasShop || state.isSelectedShopTokenValid();
        workspaceHeaderController.setControls(hasShop, running, canExport(), tokenValid);
        if (supplyDetailController != null) {
            supplyDetailController.setPrintEnabled(canExport());
            supplyDetailController.setDeliverEnabled(canDeliverLoadedSupply());
        }
        if (supplyListController != null) {
            supplyListController.setRefetchEnabled(hasShop && tokenValid);
        }
    }

    private void updateExportAvailability() {
        updateHeaderState();
    }

    private boolean canExport() {
        Shop shop = state.getSelectedShop();
        boolean running = shop != null && isShopBusy(shop.getId());
        return shop != null
                && state.getLoadedSupplyId() != null
                && !state.getDisplayedOrders().isEmpty()
                && !running
                && state.isSelectedShopTokenValid();
    }

    private boolean canDeliverLoadedSupply() {
        Shop shop = state.getSelectedShop();
        boolean running = shop != null && isShopBusy(shop.getId());
        return shop != null
                && loadedSupplySummary != null
                && !running
                && state.isSelectedShopTokenValid()
                && !kizAttachmentCoordinator.hasActiveJobForSupply(shop.getId(), loadedSupplySummary.getSupplyId())
                && packingWorkflow.canDeliver(shop.getId(), loadedSupplySummary);
    }

    private void initializeBackgroundKizProgress() {
        kizAttachmentCoordinator.addListener(kizProgressListener);
    }

    private void onKizAttachmentProgress(KizAttachmentCoordinator.KizAttachmentProgress progress) {
        if (disposed) {
            return;
        }
        if (progress == null) {
            return;
        }
        updateHeaderState();
        if (matchesCurrentSupply(progress.shopId(), progress.supplyId()) && supplyDetailController != null) {
            if (progress.active()) {
                supplyDetailController.setStickerLoading(true, progress.message());
            } else {
                supplyDetailController.setStickerLoading(false);
            }
        }
        if (!progress.active()) {
            if (isCurrentShop(progress.shopId())) {
                loadCategories();
            }
            if (matchesCurrentSupply(progress.shopId(), progress.supplyId())) {
                removeCompletedKizAssignments(progress.successfulKizCodes());
            }
            if (!progress.failures().isEmpty() && isCurrentShop(progress.shopId())) {
                AlertService.showWarning(
                        i18nService.tr("workspace.kiz_incomplete.title"),
                        progress.message(),
                        String.join(System.lineSeparator(), progress.failures())
                );
            }
            if (isCurrentShop(progress.shopId())) {
                refreshSupplyListIfCurrent(progress.shopId());
            }
            updateExportAvailability();
        }
    }

    private void removeCompletedKizAssignments(List<String> successfulKizCodes) {
        if (successfulKizCodes == null || successfulKizCodes.isEmpty()) {
            return;
        }
        List<Order> orders = state.getLoadedOrdersRaw();
        if (orders == null || orders.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Order order : orders) {
            if (order == null || order.getKiz() == null || order.getKiz().isBlank()) {
                continue;
            }
            if (successfulKizCodes.contains(order.getKiz())) {
                order.setKiz(null);
                changed = true;
            }
        }
        if (changed) {
            applySortAndDisplayOrders();
        }
    }

    private void enqueueBackgroundKizAttachment(Shop shop,
                                                String supplyId,
                                                String supplyName,
                                                OrderExportWorkflow.ExportResult result) {
        if (result == null || result.kizAttachments() == null || result.kizAttachments().isEmpty()) {
            if (supplyDetailController != null) {
                supplyDetailController.setStickerLoading(false);
            }
            return;
        }
        kizAttachmentCoordinator.enqueue(shop, supplyId, supplyName, result.kizAttachments());
    }

    private void refreshCurrentKizAttachmentProgress() {
        if (supplyDetailController == null) {
            return;
        }
        Shop shop = state.getSelectedShop();
        String supplyId = state.getLoadedSupplyId();
        if (shop == null || supplyId == null || supplyId.isBlank()) {
            supplyDetailController.setStickerLoading(false);
            return;
        }
        kizAttachmentCoordinator.findActiveJobForSupply(shop.getId(), supplyId)
                .ifPresentOrElse(
                        progress -> supplyDetailController.setStickerLoading(true, progress.message()),
                        () -> supplyDetailController.setStickerLoading(false)
                );
    }

    private boolean matchesCurrentSupply(int shopId, String supplyId) {
        return isCurrentShop(shopId) && Objects.equals(state.getLoadedSupplyId(), supplyId);
    }

    private boolean isShopBusy(int shopId) {
        return activityTracker.isRunning(shopId) || kizAttachmentCoordinator.hasActiveJobForShop(shopId);
    }

    private void clearKizDraft() {
        // KIZ assignment is driven by nmId mapping; no manual draft is kept.
    }

    private void deliverLoadedSupply() {
        Shop shop = requireSelectedShop();
        WbSupplySummary supply = loadedSupplySummary;
        if (shop == null || supply == null || !state.isSelectedShopTokenValid()) {
            return;
        }
        ButtonType deliver = new ButtonType(i18nService.tr("supply.deliver"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                MessageFormat.format(i18nService.tr("workspace.deliver.confirm.content"), supply.getSupplyId()),
                deliver,
                ButtonType.CANCEL);
        AlertService.applyTheme(confirm);
        confirm.setHeaderText(i18nService.tr("workspace.deliver.confirm.header"));
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != deliver) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                packingWorkflow.deliverSupply(shop, supply);
                return null;
            }
        };
        task.setOnRunning(e -> markShopRunning(shop.getId(), true));
        task.setOnFailed(e -> {
            markShopRunning(shop.getId(), false);
            LOGGER.error("Không thể chuyển supply {} sang giao hàng", supply.getSupplyId(), task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        task.setOnSucceeded(e -> {
            markShopRunning(shop.getId(), false);
            refreshSupplyList();
            showPacking();
        });
        AppTaskExecutor.execute(task);
    }

    private String formatSupplyTitle(WbSupplySummary supply) {
        return MessageFormat.format(i18nService.tr("supply.title"), supply.getSupplyId());
    }

    private void tryOpenFile(File file) {
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException ex) {
            LOGGER.error("Không thể mở file {}", file, ex);
        }
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        i18nService.removeListener(languageListener);
        kizAttachmentCoordinator.removeListener(kizProgressListener);
    }
}
