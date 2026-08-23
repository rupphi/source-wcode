package com.tuandev.fbsbarcode.ui.ozon;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.ozon.OzonConnectionCheck;
import com.tuandev.fbsbarcode.integration.ozon.OzonExemplarJob;
import com.tuandev.fbsbarcode.integration.ozon.OzonExemplarJobRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonExemplarJobStage;
import com.tuandev.fbsbarcode.integration.ozon.OzonExemplarService;
import com.tuandev.fbsbarcode.integration.ozon.OzonPrintBundleService;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingItemDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductGtinMappingRepository;
import com.tuandev.fbsbarcode.integration.ozon.OzonPreparationResult;
import com.tuandev.fbsbarcode.integration.ozon.OzonShipResult;
import com.tuandev.fbsbarcode.integration.ozon.OzonShipService;
import com.tuandev.fbsbarcode.integration.ozon.OzonSyncReport;
import com.tuandev.fbsbarcode.integration.ozon.OzonSyncWorkflow;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** JavaFX-first Ozon FBS Standard workspace. No customer data or raw API responses are rendered. */
public final class OzonDashboardController {
    private static final String ALL_STATUSES = "*";
    private static final String ACTIVE_STATUSES = "ACTIVE";

    private final OzonPostingRepository postings = new OzonPostingRepository();
    private final OzonProductGtinMappingRepository mappings = new OzonProductGtinMappingRepository();
    private final OzonExemplarJobRepository jobs = new OzonExemplarJobRepository();
    private final OzonSyncWorkflow syncWorkflow = new OzonSyncWorkflow();
    private final OzonExemplarService exemplarService = new OzonExemplarService();
    private final OzonShipService shipService = new OzonShipService();
    private final OzonPrintBundleService printBundleService = new OzonPrintBundleService();

    @FXML private Label titleLabel;
    @FXML private Label accountLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Button refreshButton;
    @FXML private ComboBox<String> statusFilter;
    @FXML private ListView<OzonPostingDto> postingList;
    @FXML private VBox detailPane;
    @FXML private Label postingNumberLabel;
    @FXML private Label postingStatusLabel;
    @FXML private Label shipmentAtLabel;
    @FXML private Label requirementLabel;
    @FXML private Label exemplarStageLabel;
    @FXML private ListView<String> itemList;
    @FXML private ListView<String> exemplarList;
    @FXML private TextField gtinField;
    @FXML private Button saveMappingButton;
    @FXML private Button prepareButton;
    @FXML private Button shipButton;
    @FXML private Button labelButton;

    private Shop shop;
    private boolean busy;
    private Integer busyShopId;
    private long requestToken;
    private BiConsumer<Integer, Boolean> onBusy = (ignoredShop, ignoredBusy) -> { };

    @FXML
    private void initialize() {
        statusFilter.getItems().setAll(
                ACTIVE_STATUSES, "awaiting_packaging", "awaiting_deliver", "delivering", "delivered", "cancelled",
                ALL_STATUSES);
        statusFilter.setCellFactory(ignored -> statusFilterCell());
        statusFilter.setButtonCell(statusFilterCell());
        statusFilter.getSelectionModel().select(ACTIVE_STATUSES);
        statusFilter.valueProperty().addListener((ignored, oldValue, newValue) -> loadLocal());
        postingList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(OzonPostingDto posting, boolean empty) {
                super.updateItem(posting, empty);
                if (empty || posting == null) {
                    setText(null);
                } else {
                    int quantity = posting.items().stream().mapToInt(OzonPostingItemDto::quantity).sum();
                    setText(posting.postingNumber() + "  •  " + posting.status() + "  •  " + quantity);
                }
            }
        });
        postingList.getSelectionModel().selectedItemProperty()
                .addListener((ignored, oldValue, newValue) -> showDetail(newValue));
        itemList.getSelectionModel().selectedIndexProperty()
                .addListener((ignored, oldValue, newValue) -> showMapping());
        applyTranslations();
        clear();
    }

    public void setShop(Shop shop, boolean syncRemote) {
        Shop selected = shop != null && shop.getMarketplace() == Marketplace.OZON ? shop : null;
        boolean sameContext = sameShopContext(this.shop, selected);
        if (!sameContext) {
            requestToken++;
            setBusy(false);
        }
        this.shop = selected;
        if (this.shop == null) {
            clear();
            return;
        }
        accountLabel.setText(I18nService.getInstance().tr("ozon.dashboard.client_id") + ": "
                + safeIdentity(this.shop.getClientId()));
        loadLocal();
        if (syncRemote) sync();
    }

    public void sync() {
        Shop selected = shop;
        if (selected == null || busy) return;
        long token = ++requestToken;
        Task<SyncResult> task = new Task<>() {
            @Override
            protected SyncResult call() throws Exception {
                OzonConnectionCheck connection = syncWorkflow.checkConnection(selected);
                OzonSyncReport report = syncWorkflow.syncOverview(selected);
                return new SyncResult(connection, report);
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            SyncResult result = task.getValue();
            accountLabel.setText(I18nService.getInstance().tr("ozon.dashboard.client_id") + ": "
                    + safeIdentity(result.connection().clientId()) + "  •  "
                    + result.connection().warehouseCount() + " "
                    + I18nService.getInstance().tr("ozon.dashboard.warehouses"));
            statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.synced") + ": "
                    + result.report().products() + " / " + result.report().postings());
            loadLocal();
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    public void setOnBusy(BiConsumer<Integer, Boolean> onBusy) {
        this.onBusy = onBusy == null ? (ignoredShop, ignoredBusy) -> { } : onBusy;
    }

    /** Shows the actionable Ozon packing/label queue when opened from the sidebar. */
    public void showPackingQueue() {
        if (!ACTIVE_STATUSES.equals(statusFilter.getValue())) {
            statusFilter.getSelectionModel().select(ACTIVE_STATUSES);
        } else {
            loadLocal();
        }
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("ozon.dashboard.title"));
        refreshButton.setText(i18n.tr("ozon.dashboard.refresh"));
        prepareButton.setText(i18n.tr("ozon.dashboard.prepare"));
        shipButton.setText(i18n.tr("ozon.dashboard.ship"));
        labelButton.setText(i18n.tr("ozon.dashboard.label"));
        saveMappingButton.setText(i18n.tr("ozon.dashboard.mapping_save"));
        gtinField.setPromptText(i18n.tr("ozon.dashboard.gtin"));
        statusFilter.setPromptText(i18n.tr("ozon.dashboard.status_filter"));
    }

    @FXML
    private void onRefresh() {
        sync();
    }

    @FXML
    private void onPrepare() {
        OzonPostingDto posting = postingList.getSelectionModel().getSelectedItem();
        Shop selected = shop;
        if (posting == null || selected == null || busy) return;
        runPostingTask(selected, posting, new Task<>() {
            @Override
            protected String call() throws Exception {
                OzonPreparationResult result = exemplarService.prepare(selected, posting.postingNumber());
                return I18nService.getInstance().tr("ozon.dashboard.prepare_result") + ": " + result.stage();
            }
        });
    }

    @FXML
    private void onShip() {
        OzonPostingDto posting = postingList.getSelectionModel().getSelectedItem();
        Shop selected = shop;
        if (posting == null || selected == null || busy) return;
        var confirmation = AlertService.showConfirmation(
                I18nService.getInstance().tr("ozon.dashboard.ship_confirm_title"),
                I18nService.getInstance().tr("ozon.dashboard.ship_confirm_header"),
                posting.postingNumber());
        if (confirmation.isEmpty() || confirmation.get() != ButtonType.OK) return;
        runPostingTask(selected, posting, new Task<>() {
            @Override
            protected String call() throws Exception {
                OzonShipResult result = shipService.ship(selected, posting.postingNumber(), true);
                return I18nService.getInstance().tr("ozon.dashboard.ship_result") + ": " + result.status();
            }
        });
    }

    @FXML
    private void onDownloadLabel() {
        OzonPostingDto posting = postingList.getSelectionModel().getSelectedItem();
        Shop selected = shop;
        if (posting == null || selected == null || busy) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18nService.getInstance().tr("ozon.dashboard.label"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        chooser.setInitialFileName("OZON-" + safeFilename(posting.postingNumber()) + ".pdf");
        File downloads = AppPaths.preferredDownloadsDirectory();
        if (downloads != null) chooser.setInitialDirectory(downloads);
        File target = chooser.showSaveDialog(postingList.getScene() == null ? null : postingList.getScene().getWindow());
        if (target == null) return;
        runPostingTask(selected, posting, new Task<>() {
            @Override
            protected String call() throws Exception {
                File picking = pickingTarget(target);
                OzonPrintBundleService.ExportResult result = printBundleService.export(
                        selected, posting.postingNumber(), target, picking);
                return I18nService.getInstance().tr("ozon.dashboard.label_ready")
                        + ": " + result.totalPages() + " pages / " + picking.getName();
            }
        });
    }

    @FXML
    private void onSaveMapping() {
        OzonPostingDto posting = postingList.getSelectionModel().getSelectedItem();
        Shop selected = shop;
        int index = itemList.getSelectionModel().getSelectedIndex();
        if (posting == null || selected == null || index < 0 || index >= posting.items().size() || busy) return;
        OzonPostingItemDto item = posting.items().get(index);
        try {
            mappings.put(selected.getId(), item.sku(), gtinField.getText());
            statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.mapping_saved"));
            showDetail(posting);
        } catch (RuntimeException exception) {
            AlertService.showError(I18nService.getInstance().tr("ozon.dashboard.mapping_invalid"));
        }
    }

    private void runPostingTask(Shop selected, OzonPostingDto posting, Task<String> task) {
        long token = ++requestToken;
        setBusy(true);
        task.setOnSucceeded(event -> {
            if (token != requestToken || !isCurrent(selected)) return;
            setBusy(false);
            statusLabel.setText(task.getValue());
            loadLocal(posting.postingNumber());
        });
        task.setOnFailed(event -> finishFailure(token, selected, task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void finishFailure(long token, Shop selected, Throwable failure) {
        if (token != requestToken || !isCurrent(selected)) return;
        setBusy(false);
        String message = failure == null || failure.getMessage() == null
                ? I18nService.getInstance().tr("ozon.dashboard.error") : failure.getMessage();
        statusLabel.setText(I18nService.getInstance().tr("ozon.dashboard.error"));
        AlertService.showError(message);
        loadLocal();
    }

    private void loadLocal() {
        loadLocal(null);
    }

    private void loadLocal(String restorePostingNumber) {
        Shop selected = shop;
        if (selected == null) {
            postingList.getItems().clear();
            showDetail(null);
            return;
        }
        String filter = statusFilter.getValue();
        List<OzonPostingDto> values = ACTIVE_STATUSES.equals(filter)
                ? postings.findActive(selected.getId(), 500, 0)
                : postings.findByStatus(selected.getId(), ALL_STATUSES.equals(filter) ? null : filter, 500, 0);
        postingList.getItems().setAll(values);
        OzonPostingDto selection = values.stream()
                .filter(value -> Objects.equals(value.postingNumber(), restorePostingNumber))
                .findFirst().orElse(values.isEmpty() ? null : values.getFirst());
        postingList.getSelectionModel().select(selection);
        showDetail(selection);
    }

    private void showDetail(OzonPostingDto posting) {
        boolean selected = posting != null;
        detailPane.setVisible(selected);
        detailPane.setManaged(selected);
        if (!selected || shop == null) {
            itemList.getItems().clear();
            exemplarList.getItems().clear();
            gtinField.clear();
            gtinField.setDisable(true);
            saveMappingButton.setDisable(true);
            prepareButton.setDisable(true);
            shipButton.setDisable(true);
            labelButton.setDisable(true);
            return;
        }
        postingNumberLabel.setText(posting.postingNumber());
        postingStatusLabel.setText(posting.status() + (posting.substatus().isBlank() ? "" : " / " + posting.substatus()));
        shipmentAtLabel.setText(posting.shipmentAt().isBlank() ? "-" : posting.shipmentAt());
        requirementLabel.setText(requirementSummary(posting));
        OzonExemplarJob job = jobs.find(shop.getId(), posting.postingNumber());
        exemplarStageLabel.setText(job == null ? "-" : job.stage().name());
        exemplarList.getItems().setAll(job == null ? List.of() : jobs.summaries(job.id()).stream()
                .map(OzonDashboardController::exemplarText).toList());
        itemList.getItems().setAll(posting.items().stream().map(OzonDashboardController::itemText).toList());
        if (!posting.items().isEmpty()) itemList.getSelectionModel().selectFirst();
        showMapping();
        boolean unsupported = posting.requirements().blocksPreparation() || !posting.isSinglePackageSupported();
        boolean shippable = posting.canShip();
        boolean marksAccepted = posting.requirements().mandatoryMarkProductIds().isEmpty()
                && posting.requirements().optionalMarkProductIds().isEmpty()
                || job != null && job.stage() == OzonExemplarJobStage.ACCEPTED;
        prepareButton.setDisable(busy || unsupported || !"awaiting_packaging".equalsIgnoreCase(posting.status()));
        shipButton.setDisable(busy || unsupported || !shippable || !marksAccepted);
        labelButton.setDisable(busy || !canPrintLabel(posting));
    }

    private void showMapping() {
        OzonPostingDto posting = postingList.getSelectionModel().getSelectedItem();
        int index = itemList.getSelectionModel().getSelectedIndex();
        boolean available = shop != null && posting != null && index >= 0 && index < posting.items().size();
        if (!available) {
            gtinField.clear();
            gtinField.setDisable(true);
            saveMappingButton.setDisable(true);
            return;
        }
        OzonPostingItemDto item = posting.items().get(index);
        String gtin = item.sku().isBlank() ? null : mappings.findGtin(shop.getId(), item.sku());
        gtinField.setText(gtin == null ? "" : gtin);
        gtinField.setDisable(busy || item.sku().isBlank());
        saveMappingButton.setDisable(busy || item.sku().isBlank());
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) {
            busyShopId = shop == null ? null : shop.getId();
            if (busyShopId != null) onBusy.accept(busyShopId, true);
        } else if (busyShopId != null) {
            onBusy.accept(busyShopId, false);
            busyShopId = null;
        }
        loadingIndicator.setVisible(busy);
        loadingIndicator.setManaged(busy);
        refreshButton.setDisable(busy || shop == null);
        showDetail(postingList.getSelectionModel().getSelectedItem());
    }

    private void clear() {
        postingList.getItems().clear();
        accountLabel.setText("");
        statusLabel.setText("");
        setBusy(false);
        showDetail(null);
    }

    private boolean isCurrent(Shop selected) {
        return shop != null && selected != null && shop.getId() == selected.getId();
    }

    private static boolean sameShopContext(Shop first, Shop second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.getId() == second.getId()
                && first.getMarketplace() == second.getMarketplace()
                && Objects.equals(first.getClientId(), second.getClientId())
                && Objects.equals(first.getApiKey(), second.getApiKey());
    }

    private static boolean canPrintLabel(OzonPostingDto posting) {
        return switch (posting.status().toLowerCase(java.util.Locale.ROOT)) {
            case "awaiting_deliver", "delivering", "delivered" -> true;
            default -> false;
        };
    }

    private static ListCell<String> statusFilterCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : statusFilterText(value));
            }
        };
    }

    private static String statusFilterText(String value) {
        String suffix = switch (value) {
            case ACTIVE_STATUSES -> "active";
            case ALL_STATUSES -> "all";
            default -> value;
        };
        return I18nService.getInstance().tr("ozon.dashboard.status_value." + suffix, value);
    }

    private static String requirementSummary(OzonPostingDto posting) {
        if (!posting.requirements().unsupportedRequirements().isEmpty()) {
            return "Blocked: " + String.join(", ", posting.requirements().unsupportedRequirements());
        }
        return "mandatory=" + posting.requirements().mandatoryMarkProductIds().size()
                + ", optional=" + posting.requirements().optionalMarkProductIds().size();
    }

    private static String itemText(OzonPostingItemDto item) {
        return item.name() + "  •  SKU " + item.sku() + "  × " + item.quantity();
    }

    private static String exemplarText(OzonExemplarJobRepository.ExemplarSummary exemplar) {
        String status = exemplar.checkStatus() == null ? "pending" : exemplar.checkStatus();
        String kiz = exemplar.kizId() == null ? "-" : "#" + exemplar.kizId();
        return "Item " + (exemplar.itemIndex() + 1) + " / exemplar " + (exemplar.exemplarIndex() + 1)
                + "  •  ID " + exemplar.exemplarId() + "  •  KIZ " + kiz + "  •  " + status;
    }

    private static String safeIdentity(String value) {
        if (value == null || value.isBlank()) return "-";
        String safe = value.replaceAll("\\p{Cntrl}", " ").strip();
        return safe.length() <= 80 ? safe : safe.substring(0, 80);
    }

    private static String safeFilename(String value) {
        String safe = value == null ? "posting" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "posting" : safe;
    }

    private static File pickingTarget(File labelTarget) {
        String name = labelTarget.getName();
        String base = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                ? name.substring(0, name.length() - 4) : name;
        File parent = labelTarget.getAbsoluteFile().getParentFile();
        return new File(parent, base + "-picking.pdf");
    }

    private record SyncResult(OzonConnectionCheck connection, OzonSyncReport report) {
    }
}
