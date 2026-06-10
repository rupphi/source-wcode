package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KizMappingController {
    @FXML private Label titleLabel, emptyStateLabel;
    @FXML private Button refreshButton;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private TableView<ZnackGtinInventorySummary> gtinTable;
    @FXML private TableColumn<ZnackGtinInventorySummary,String> gtinColumn, nameColumn, mappingColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,Number> availableColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,String> pipelineColumn, errorColumn, syncedColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,ZnackGtinInventorySummary> actionsColumn;

    private final KizMappingRepository mappingRepository = new KizMappingRepository();
    private Shop shop;
    private ZnackRepository znackRepository;
    private Timeline refreshTimer;
    private boolean loading;
    private long shopGeneration;

    @FXML
    private void initialize() {
        gtinColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().gtin()));
        nameColumn.setCellValueFactory(v -> new SimpleStringProperty(value(v.getValue().productName())));
        mappingColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().mappingRuleCount() == 0
                ? tr("kiz_mapping.status.unmapped") : tr("kiz_mapping.status.mapped")));
        availableColumn.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().available()));
        pipelineColumn.setCellValueFactory(v -> new SimpleStringProperty(localizeStatus(first(
                v.getValue().latestPipelineStage(), v.getValue().latestOrderStatus()))));
        mappingColumn.setCellFactory(column -> statusCell("badge-green", "badge-gray"));
        pipelineColumn.setCellFactory(column -> statusCell("badge-warning", "badge-gray"));
        errorColumn.setCellValueFactory(v -> new SimpleStringProperty(value(v.getValue().latestError())));
        syncedColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().syncedAt() == null
                ? "" : v.getValue().syncedAt().toString()));
        actionsColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleObjectProperty<>(v.getValue()));
        actionsColumn.setCellFactory(column -> new ActionsCell());
        refreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), event -> {
            if (shop != null && !loading) refresh();
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        applyTranslations();
        setLoading(false);
    }

    public void setShop(Shop selected) {
        shopGeneration++;
        shop = selected;
        znackRepository = selected == null ? null : new ZnackRepository(new ShopContext(selected.getId(), selected.getName()));
        setLoading(false);
        if (selected == null) refreshTimer.stop(); else refreshTimer.play();
        refresh();
        if (znackRepository != null) {
            ZnackRepository currentRepository = znackRepository;
            ZnackPurchaseCoordinator currentCoordinator = ZnackPurchaseCoordinator.create(currentRepository);
            Settings currentSettings = currentRepository.getSettings();
            runBackground(() -> currentCoordinator.resume(currentSettings));
        }
    }

    public void applyTranslations() {
        titleLabel.setText(tr("kiz_mapping.title"));
        refreshButton.setText(tr("kiz_mapping.refresh"));
        gtinColumn.setText(tr("znack.field.gtin"));
        nameColumn.setText(tr("znack.field.name"));
        mappingColumn.setText(tr("kiz_mapping.column.mapping"));
        availableColumn.setText(tr("kiz_mapping.column.available"));
        pipelineColumn.setText(tr("kiz_mapping.column.pipeline"));
        errorColumn.setText(tr("kiz_mapping.column.error"));
        syncedColumn.setText(tr("kiz_mapping.column.synced"));
        actionsColumn.setText(tr("kiz_mapping.column.actions"));
        emptyStateLabel.setText(tr("kiz_mapping.empty_gtin"));
    }

    @FXML
    private void onRefresh() {
        if (znackRepository == null) return;
        ZnackRepository current = znackRepository;
        runTask(() -> {
            syncProducts(current);
            return null;
        });
    }

    public void refresh() {
        if (loading) return;
        if (shop == null) {
            gtinTable.getItems().clear();
            updateEmpty();
            return;
        }
        int shopId = shop.getId();
        long generation = shopGeneration;
        Task<List<ZnackGtinInventorySummary>> task = new Task<>() {
            @Override protected List<ZnackGtinInventorySummary> call() {
                return mappingRepository.findGtinSummaries(shopId);
            }
        };
        setLoading(true);
        task.setOnSucceeded(e -> {
            if (generation == shopGeneration && shop != null && shop.getId() == shopId) {
                gtinTable.getItems().setAll(task.getValue());
                setLoading(false);
                updateEmpty();
            }
        });
        task.setOnFailed(e -> {
            if (generation == shopGeneration) {
                setLoading(false);
                AlertService.showError(task.getException().getMessage());
            }
        });
        AppTaskExecutor.execute(task);
    }

    private void showMapping(ZnackGtinInventorySummary summary) {
        if (shop == null || summary == null) return;
        int shopId = shop.getId();
        long generation = shopGeneration;
        Task<MappingDialogData> task = new Task<>() {
            @Override protected MappingDialogData call() {
                List<String> subjects = mappingRepository.findSubjects(shopId);
                Map<String, SelectionState> state = loadState(shopId, summary.gtin());
                Map<String, List<String>> gendersBySubject = new LinkedHashMap<>();
                Map<String, Map<String, String>> ownersBySubject = new LinkedHashMap<>();
                for (String subject : subjects) {
                    gendersBySubject.put(subject, mappingRepository.findGendersForSubject(shopId, subject));
                    ownersBySubject.put(subject, mappingRepository.findOwnersForSubject(shopId, subject));
                }
                return new MappingDialogData(subjects, state, gendersBySubject, ownersBySubject);
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != shopId) return;
            setLoading(false);
            openMappingDialog(shopId, summary, task.getValue());
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            AlertService.showError(friendlyError(task.getException()));
        });
        AppTaskExecutor.execute(task);
    }

    private void openMappingDialog(int shopId, ZnackGtinInventorySummary summary, MappingDialogData data) {
        Dialog<List<ZnackGtinMappingSelection>> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("kiz_mapping.mapping.title"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        ListView<String> subjects = new ListView<>();
        subjects.getItems().setAll(data.subjects());
        VBox genders = new VBox(8);
        Map<String, SelectionState> state = data.state();
        subjects.getSelectionModel().selectedItemProperty().addListener((o, old, subject) ->
                renderGenders(summary.gtin(), subject, genders, state, data));
        if (!subjects.getItems().isEmpty()) subjects.getSelectionModel().selectFirst();
        SplitPane content = new SplitPane(subjects, new ScrollPane(genders));
        content.setPrefSize(720, 460);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save ? flatten(state) : null);
        dialog.showAndWait().ifPresent(selections -> runTask(() -> {
            mappingRepository.replaceRulesForGtin(shopId, summary.gtin(), selections);
            return null;
        }));
    }

    private Map<String, SelectionState> loadState(int shopId, String gtin) {
        Map<String, SelectionState> result = new LinkedHashMap<>();
        for (ZnackGtinMappingRule rule : mappingRepository.findRulesForGtin(shopId, gtin)) {
            SelectionState value = result.computeIfAbsent(rule.subjectName(), ignored -> new SelectionState(false));
            value.wildcard = rule.wildcardGender();
            if (!rule.wildcardGender()) value.genders.add(rule.genderValue());
        }
        return result;
    }

    private void renderGenders(String gtin, String subject, VBox box, Map<String, SelectionState> state,
                               MappingDialogData data) {
        box.getChildren().clear();
        if (subject == null) return;
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        List<String> availableGenders = data.gendersBySubject().getOrDefault(subject, List.of());
        Set<String> otherOwners = owners.values().stream().filter(owner -> owner != null && !owner.equals(gtin))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        SelectionState selection = state.get(subject);
        if (selection == null) {
            selection = new SelectionState(otherOwners.isEmpty());
            if (!otherOwners.isEmpty()) {
                for (String gender : availableGenders) {
                    if (!owners.containsKey(gender) && !owners.containsKey(KizMappingRepository.WILDCARD_GENDER)) {
                        selection.genders.add(gender);
                    }
                }
            }
            state.put(subject, selection);
        }
        SelectionState activeSelection = selection;
        CheckBox all = new CheckBox(tr("kiz_mapping.gender.all"));
        all.setSelected(activeSelection.wildcard);
        String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
        all.setDisable(!otherOwners.isEmpty());
        if (all.isDisabled()) all.setText(all.getText() + " · " + String.join(", ", otherOwners));
        box.getChildren().add(all);
        List<CheckBox> genderChecks = new ArrayList<>();
        for (String gender : availableGenders) {
            String owner = first(owners.get(gender), wildcardOwner);
            boolean ownedByOther = owner != null && !owner.equals(gtin);
            CheckBox check = new CheckBox(displayGender(gender) + (owner != null && !owner.equals(gtin) ? " · " + owner : ""));
            check.setSelected(activeSelection.wildcard || activeSelection.genders.contains(gender));
            check.setDisable(activeSelection.wildcard || ownedByOther);
            check.getProperties().put("ownedByOther", ownedByOther);
            check.selectedProperty().addListener((o, old, selected) -> {
                if (selected) activeSelection.genders.add(gender); else activeSelection.genders.remove(gender);
            });
            genderChecks.add(check);
            box.getChildren().add(check);
        }
        all.selectedProperty().addListener((o, old, selected) -> {
            activeSelection.wildcard = selected;
            if (selected) activeSelection.genders.clear();
            genderChecks.forEach(check -> {
                check.setDisable(selected || Boolean.TRUE.equals(check.getProperties().get("ownedByOther")));
                check.setSelected(selected);
            });
        });
    }

    private void showBuy(ZnackGtinInventorySummary summary) {
        TextInputDialog dialog = new TextInputDialog("1");
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("kiz_mapping.buy.title"));
        dialog.setHeaderText(summary.gtin());
        dialog.setContentText(tr("znack.field.quantity"));
        dialog.showAndWait().ifPresent(text -> {
            try {
                int quantity = Integer.parseInt(text.trim());
                if (quantity <= 0) {
                    AlertService.showError(tr("kiz_mapping.buy.positive"));
                    return;
                }
                ZnackRepository currentRepository = znackRepository;
                ZnackPurchaseCoordinator currentCoordinator = ZnackPurchaseCoordinator.create(currentRepository);
                Settings currentSettings = currentRepository.getSettings();
                runTask(() -> {
                    currentCoordinator.start(currentSettings, summary.gtin(), quantity);
                    return null;
                });
            } catch (NumberFormatException e) {
                AlertService.showError(tr("kiz_mapping.buy.positive"));
            }
        });
    }

    private void showCirculationData(ZnackGtinInventorySummary summary) {
        if (znackRepository == null || summary == null) return;
        ZnackRepository currentRepository = znackRepository;
        long generation = shopGeneration;
        Task<Product> task = new Task<>() {
            @Override protected Product call() {
                return currentRepository.findProduct(summary.gtin()).orElse(null);
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            Product product = task.getValue();
            if (product == null) {
                AlertService.showError(tr("kiz_mapping.error.gtin_missing"));
                refresh();
                return;
            }
            openCirculationDialog(currentRepository, summary, product);
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            AlertService.showError(friendlyError(task.getException()));
        });
        AppTaskExecutor.execute(task);
    }

    private void openCirculationDialog(ZnackRepository currentRepository, ZnackGtinInventorySummary summary,
                                       Product current) {
        Dialog<Product> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("kiz_mapping.circulation.title"));
        TextField tnVed = new TextField(value(current.tnVed()));
        tnVed.setPromptText(tr("znack.field.tn_ved"));
        TextField productionDate = new TextField(value(current.productionDate()));
        productionDate.setPromptText(tr("znack.field.production_date"));
        VBox content = new VBox(8, new Label(summary.gtin() + " · " + value(current.productName())), tnVed, productionDate);
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == save
                ? new Product(current.gtin(), current.productName(), tnVed.getText(), current.certificateType(),
                current.certificateNumber(), current.certificateDate(), productionDate.getText()) : null);
        dialog.showAndWait().ifPresent(product -> runTask(() -> {
            currentRepository.updateProductMetadata(product);
            Settings settings = currentRepository.getSettings();
            ZnackPurchaseCoordinator.create(currentRepository).resumeEligibleIntroductions(settings);
            return null;
        }));
    }

    private List<ZnackGtinMappingSelection> flatten(Map<String, SelectionState> state) {
        List<ZnackGtinMappingSelection> result = new ArrayList<>();
        state.forEach((subject, selection) -> {
            if (selection.wildcard) result.add(new ZnackGtinMappingSelection(subject, null, true));
            else selection.genders.forEach(gender -> result.add(new ZnackGtinMappingSelection(subject, gender, false)));
        });
        return result;
    }

    private void runTask(ThrowingSupplier action) {
        long generation = shopGeneration;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { return action.get(); }
        };
        setLoading(true);
        task.setOnSucceeded(e -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            refresh();
        });
        task.setOnFailed(e -> {
            if (generation != shopGeneration) return;
            setLoading(false);
            AlertService.showError(friendlyError(task.getException()));
            refresh();
        });
        AppTaskExecutor.execute(task);
    }

    private void runBackground(Runnable action) {
        long generation = shopGeneration;
        Task<Void> task = new Task<>() {
            @Override protected Void call() { action.run(); return null; }
        };
        task.setOnSucceeded(e -> {
            if (generation == shopGeneration) refresh();
        });
        AppTaskExecutor.execute(task);
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        refreshButton.setDisable(loading || shop == null);
    }

    private void updateEmpty() {
        boolean empty = gtinTable.getItems().isEmpty();
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
    }

    private String displayGender(String gender) {
        return KizMappingRepository.UNSPECIFIED_GENDER.equals(gender) ? tr("kiz_mapping.gender.unspecified") : gender;
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? value(second) : first;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    private String friendlyError(Throwable error) {
        if (error instanceof CryptoProException crypto) {
            String message = tr("znack.signature.error." + switch (crypto.code()) {
                case CRYPTOPRO_MISSING -> "cryptopro_missing";
                case CRYPTCP_MISSING -> "cryptcp_missing";
                case CRYPTCP_LICENSE_INVALID -> "cryptcp_license";
                case CERTMGR_MISSING -> "certmgr_missing";
                case CADESCOM_MISSING -> "cadescom_missing";
                case TOKEN_OR_CERTIFICATE_ABSENT -> "certificate_absent";
                case PRIVATE_KEY_UNAVAILABLE -> "private_key";
                case CERTIFICATE_EXPIRED -> "expired";
                case CANCELLED -> "cancelled";
                case TIMEOUT -> "timeout";
                case INVALID_SIGNATURE_OUTPUT -> "invalid_output";
                default -> "failed";
            });
            String details = ZnackSanitizer.message(crypto.getMessage());
            return crypto.code() == CryptoProErrorCode.SIGNING_FAILED && !details.isBlank()
                    ? message + "\n\n" + tr("znack.signature.error.details") + ": " + details : message;
        }
        if (ZnackSafety.UNVERIFIED_SIGNATURE.equals(error.getMessage())) return tr("znack.signature.not_verified");
        if (ZnackSafety.MISSING_SHOP_CONFIGURATION.equals(error.getMessage())) return tr("znack.error.shop_configuration");
        return error.getMessage();
    }

    private String localizeStatus(String status) {
        return status == null || status.isBlank() ? "" : I18nService.getInstance()
                .tr("znack.status_value." + status.toLowerCase(java.util.Locale.ROOT), status);
    }

    private TableCell<ZnackGtinInventorySummary, String> statusCell(String activeClass, String emptyClass) {
        return new TableCell<>() {
            private final Label chip = new Label();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setGraphic(null);
                    return;
                }
                chip.setText(item);
                chip.getStyleClass().setAll("badge", item.equals(tr("kiz_mapping.status.unmapped")) ? emptyClass : activeClass);
                setGraphic(chip);
            }
        };
    }

    private final class ActionsCell extends TableCell<ZnackGtinInventorySummary, ZnackGtinInventorySummary> {
        private final Button mapping = new Button();
        private final Button buy = new Button();
        private final Button circulation = new Button();
        private final HBox box = new HBox(6, mapping, buy, circulation);

        private ActionsCell() {
            mapping.setText(tr("kiz_mapping.action.mapping"));
            buy.setText(tr("kiz_mapping.action.buy"));
            circulation.setText(tr("kiz_mapping.action.circulation"));
            mapping.setOnAction(e -> showMapping(getItem()));
            buy.setOnAction(e -> showBuy(getItem()));
            circulation.setOnAction(e -> showCirculationData(getItem()));
        }

        @Override protected void updateItem(ZnackGtinInventorySummary item, boolean empty) {
            super.updateItem(item, empty);
            setGraphic(empty || item == null ? null : box);
        }
    }

    private static final class SelectionState {
        private boolean wildcard;
        private final Set<String> genders = new LinkedHashSet<>();

        private SelectionState(boolean wildcard) {
            this.wildcard = wildcard;
        }
    }

    private record MappingDialogData(List<String> subjects,
                                     Map<String, SelectionState> state,
                                     Map<String, List<String>> gendersBySubject,
                                     Map<String, Map<String, String>> ownersBySubject) {
    }

    @FunctionalInterface private interface ThrowingSupplier {
        Void get() throws Exception;
    }

    private void syncProducts(ZnackRepository repository) throws Exception {
        Settings settings = repository.getSettings();
        ZnackSignatureProvider signer = settings.signerCertificate() == null || settings.signerCertificate().isBlank()
                ? ZnackSignatureProvider.unconfigured()
                : new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        ZnackApiClient api = new ZnackApiClient();
        new ZnackProductService(api, new ZnackAuthService(api, signer), repository).sync(settings);
        ZnackPurchaseCoordinator.create(repository).resumeEligibleIntroductions(settings);
    }
}
