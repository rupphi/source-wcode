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
    @FXML private TableColumn<ZnackGtinInventorySummary,String> pipelineColumn, errorColumn;
    @FXML private TableColumn<ZnackGtinInventorySummary,ZnackGtinInventorySummary> actionsColumn;

    private final KizMappingRepository mappingRepository = new KizMappingRepository();
    private Shop shop;
    private ZnackRepository znackRepository;
    private Timeline refreshTimer;
    private boolean loading;
    private boolean syncing;
    private long shopGeneration;

    @FXML
    private void initialize() {
        gtinColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().gtin()));
        nameColumn.setCellValueFactory(v -> new SimpleStringProperty(value(v.getValue().productName())));
        mappingColumn.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().mappingRuleCount() == 0
                ? tr("kiz_mapping.status.unmapped")
                : v.getValue().mappingRuleCount() + " " + tr("kiz_mapping.status.rules")));
        availableColumn.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().available()));
        pipelineColumn.setCellValueFactory(v -> new SimpleStringProperty(localizeStatus(first(
                v.getValue().latestPipelineStage(), v.getValue().latestOrderStatus()))));
        mappingColumn.setCellFactory(column -> statusCell("badge-green", "badge-gray"));
        pipelineColumn.setCellFactory(column -> statusCell("badge-warning", "badge-gray"));
        errorColumn.setCellValueFactory(v -> new SimpleStringProperty(value(v.getValue().latestError())));
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
        syncing = false;
        znackRepository = selected == null ? null : new ZnackRepository(new ShopContext(selected.getId(), selected.getName()));
        setLoading(false);
        if (selected == null) refreshTimer.stop(); else refreshTimer.play();
        refresh();
        if (znackRepository != null) {
            ZnackRepository currentRepository = znackRepository;
            Settings currentSettings = currentRepository.getSettings();
            runBackground(() -> ZnackPurchaseCoordinator.create(currentRepository).resume(currentSettings));
        }
    }

    public void syncOnOpen() {
        refresh();
        if (znackRepository != null && hasVerifiedSignature(znackRepository.getSettings())) requestSync(false);
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
        actionsColumn.setText(tr("kiz_mapping.column.actions"));
        emptyStateLabel.setText(tr("kiz_mapping.empty_gtin"));
    }

    @FXML
    private void onRefresh() {
        requestSync(true);
    }

    private void requestSync(boolean showErrors) {
        if (znackRepository == null || syncing) return;
        long generation = shopGeneration;
        int shopId = shop.getId();
        ZnackRepository current = znackRepository;
        Task<List<ZnackGtinInventorySummary>> task = new Task<>() {
            @Override protected List<ZnackGtinInventorySummary> call() throws Exception {
                try {
                    syncProducts(current);
                    return mappingRepository.findGtinSummaries(shopId);
                } catch (Exception error) {
                    current.log("GTIN_SYNC", null, "ERROR", error.getMessage(), null);
                    throw error;
                }
            }
        };
        syncing = true;
        setLoadingState();
        task.setOnSucceeded(event -> {
            if (generation != shopGeneration || shop == null || shop.getId() != shopId) return;
            syncing = false;
            gtinTable.getItems().setAll(task.getValue());
            setLoadingState();
            updateEmpty();
        });
        task.setOnFailed(event -> {
            if (generation != shopGeneration) return;
            syncing = false;
            setLoadingState();
            if (showErrors) AlertService.showError(friendlyError(task.getException()));
            refresh();
        });
        AppTaskExecutor.execute(task);
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

        VBox subjects = new VBox(4);
        VBox genders = new VBox(8);
        VBox selectedRules = new VBox(8);
        Map<String, SelectionState> state = data.state();
        String[] activeSubject = {data.subjects().stream().filter(state::containsKey).findFirst()
                .orElse(data.subjects().isEmpty() ? null : data.subjects().getFirst())};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            renderCategories(summary.gtin(), subjects, activeSubject, state, data, refresh[0]);
            renderGenders(summary.gtin(), activeSubject[0], genders, state, data, refresh[0]);
            renderSelectedRules(selectedRules, state, refresh[0]);
        };
        ScrollPane subjectScroll = new ScrollPane(subjects);
        subjectScroll.setFitToWidth(true);
        VBox subjectPane = titledPane(tr("kiz_mapping.mapping.categories"), subjectScroll);
        VBox genderPane = titledPane(tr("kiz_mapping.mapping.genders"), new ScrollPane(genders));
        VBox summaryPane = titledPane(tr("kiz_mapping.mapping.summary"), new ScrollPane(selectedRules));
        SplitPane content = new SplitPane(subjectPane, genderPane, summaryPane);
        content.setDividerPositions(0.32, 0.68);
        content.setPrefSize(980, 520);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save ? flatten(state) : null);
        refresh[0].run();
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

    void renderCategories(String gtin, VBox box, String[] activeSubject, Map<String, SelectionState> state,
                          MappingDialogData data, Runnable refresh) {
        box.getChildren().clear();
        for (String subject : data.subjects()) {
            SelectionState selection = state.get(subject);
            Set<String> otherOwners = otherOwners(gtin, subject, data);
            boolean blocked = selection == null && fullyOwnedByOther(gtin, subject, data);
            CheckBox enabled = new CheckBox();
            enabled.getProperties().put("mappingSubject", subject);
            enabled.getProperties().put("ownedByOther", blocked);
            enabled.setSelected(selection != null && !selection.empty() || blocked);
            enabled.setIndeterminate(selection == null && !blocked && !otherOwners.isEmpty());
            enabled.setDisable(blocked);
            Label name = new Label(subject);
            Label count = new Label(blocked ? String.join(", ", otherOwners) : selectionCount(selection));
            count.getStyleClass().add("text-muted");
            javafx.scene.layout.Pane spacer = new javafx.scene.layout.Pane();
            HBox row = new HBox(8, enabled, name, spacer, count);
            HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            row.getStyleClass().add("surface");
            row.getProperties().put("mappingSubject", subject);
            if (subject.equals(activeSubject[0])) row.setStyle("-fx-border-color: -accent;");
            enabled.setOnAction(event -> {
                event.consume();
                activeSubject[0] = subject;
                if (state.containsKey(subject)) state.remove(subject);
                else enableSubject(gtin, subject, state, data);
                refresh.run();
            });
            row.setOnMouseClicked(event -> {
                activeSubject[0] = subject;
                refresh.run();
            });
            box.getChildren().add(row);
        }
    }

    private void enableSubject(String gtin, String subject, Map<String, SelectionState> state, MappingDialogData data) {
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        boolean hasOtherOwner = owners.values().stream().anyMatch(owner -> owner != null && !owner.equals(gtin));
        SelectionState selection = new SelectionState(!hasOtherOwner);
        if (hasOtherOwner) {
            String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
            for (String gender : data.gendersBySubject().getOrDefault(subject, List.of())) {
                String owner = first(owners.get(gender), wildcardOwner);
                if (owner == null || owner.equals(gtin)) selection.genders.add(gender);
            }
        }
        if (!selection.empty()) state.put(subject, selection);
    }

    void renderGenders(String gtin, String subject, VBox box, Map<String, SelectionState> state,
                       MappingDialogData data, Runnable refresh) {
        box.getChildren().clear();
        if (subject == null) return;
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        List<String> availableGenders = data.gendersBySubject().getOrDefault(subject, List.of());
        Set<String> otherOwners = otherOwners(gtin, subject, data);
        SelectionState selection = state.get(subject);
        boolean enabled = selection != null;
        if (selection == null) {
            Label help = new Label(tr("kiz_mapping.mapping.enable_category"));
            help.getStyleClass().add("text-muted");
            help.setWrapText(true);
            box.getChildren().add(help);
            selection = new SelectionState(false);
        }
        SelectionState activeSelection = selection;
        CheckBox all = new CheckBox(tr("kiz_mapping.gender.all"));
        all.getProperties().put("mappingGender", KizMappingRepository.WILDCARD_GENDER);
        all.setSelected(activeSelection.wildcard || (!enabled && owners.get(KizMappingRepository.WILDCARD_GENDER) != null));
        all.setIndeterminate(enabled && !activeSelection.wildcard && !activeSelection.genders.isEmpty());
        String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
        all.setDisable(!enabled || !otherOwners.isEmpty());
        if (!otherOwners.isEmpty()) all.setText(all.getText() + " · " + String.join(", ", otherOwners));
        all.getProperties().put("ownedByOther", !otherOwners.isEmpty());
        box.getChildren().add(all);
        for (String gender : availableGenders) {
            String owner = first(owners.get(gender), wildcardOwner);
            boolean ownedByOther = owner != null && !owner.equals(gtin);
            CheckBox check = new CheckBox(displayGender(gender) + (owner != null && !owner.equals(gtin) ? " · " + owner : ""));
            check.setSelected(ownedByOther || activeSelection.wildcard || activeSelection.genders.contains(gender));
            check.setDisable(!enabled || ownedByOther);
            check.getProperties().put("mappingGender", gender);
            check.getProperties().put("ownedByOther", ownedByOther);
            check.setOnAction(event -> {
                boolean selectedNow = check.isSelected();
                if (activeSelection.wildcard) {
                    activeSelection.wildcard = false;
                    activeSelection.genders.clear();
                    for (String candidate : availableGenders) {
                        String candidateOwner = first(owners.get(candidate), wildcardOwner);
                        boolean candidateOwnedByOther = candidateOwner != null && !candidateOwner.equals(gtin);
                        if (!candidateOwnedByOther && (!candidate.equals(gender) || selectedNow)) {
                            activeSelection.genders.add(candidate);
                        }
                    }
                } else if (selectedNow) {
                    activeSelection.genders.add(gender);
                } else {
                    activeSelection.genders.remove(gender);
                }
                if (activeSelection.empty()) state.remove(subject);
                refresh.run();
            });
            box.getChildren().add(check);
        }
        all.setOnAction(event -> {
            activeSelection.wildcard = all.isSelected();
            activeSelection.genders.clear();
            if (!activeSelection.wildcard) {
                for (String gender : availableGenders) {
                    String owner = first(owners.get(gender), wildcardOwner);
                    if (owner == null || owner.equals(gtin)) activeSelection.genders.add(gender);
                }
            }
            if (activeSelection.empty()) state.remove(subject);
            refresh.run();
        });
    }

    private Set<String> otherOwners(String gtin, String subject, MappingDialogData data) {
        return data.ownersBySubject().getOrDefault(subject, Map.of()).values().stream()
                .filter(owner -> owner != null && !owner.equals(gtin))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean fullyOwnedByOther(String gtin, String subject, MappingDialogData data) {
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
        if (wildcardOwner != null && !wildcardOwner.equals(gtin)) return true;
        List<String> genders = data.gendersBySubject().getOrDefault(subject, List.of());
        return !genders.isEmpty() && genders.stream().allMatch(gender -> {
            String owner = owners.get(gender);
            return owner != null && !owner.equals(gtin);
        });
    }

    private void renderSelectedRules(VBox box, Map<String, SelectionState> state, Runnable refresh) {
        box.getChildren().clear();
        state.forEach((subject, selection) -> {
            if (selection.empty()) return;
            Label name = new Label(subject);
            name.getStyleClass().add("text-strong");
            Label genders = new Label(selection.wildcard
                    ? tr("kiz_mapping.gender.all")
                    : selection.genders.stream().map(this::displayGender).collect(java.util.stream.Collectors.joining(", ")));
            genders.getStyleClass().add("text-muted");
            genders.setWrapText(true);
            Button remove = new Button(tr("common.delete"));
            remove.setOnAction(event -> {
                state.remove(subject);
                refresh.run();
            });
            HBox row = new HBox(8, new VBox(3, name, genders), new javafx.scene.layout.Pane(), remove);
            HBox.setHgrow(row.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
            row.getStyleClass().add("surface");
            box.getChildren().add(row);
        });
        if (box.getChildren().isEmpty()) {
            Label empty = new Label(tr("kiz_mapping.mapping.summary_empty"));
            empty.getStyleClass().add("text-muted");
            empty.setWrapText(true);
            box.getChildren().add(empty);
        }
    }

    private VBox titledPane(String title, javafx.scene.Node content) {
        Label label = new Label(title);
        label.getStyleClass().add("h3");
        VBox pane = new VBox(8, label, content);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
        return pane;
    }

    private String selectionCount(SelectionState selection) {
        if (selection == null || selection.empty()) return "";
        return selection.wildcard ? tr("kiz_mapping.gender.all") : String.valueOf(selection.genders.size());
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

    List<ZnackGtinMappingSelection> flatten(Map<String, SelectionState> state) {
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
        setLoadingState();
    }

    private void setLoadingState() {
        boolean active = loading || syncing;
        loadingIndicator.setVisible(active);
        loadingIndicator.setManaged(active);
        refreshButton.setDisable(active || shop == null);
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
        return first == null || first.isBlank() ? second : first;
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
        private final HBox box = new HBox(6, mapping, buy);
        private final Tooltip technicalGtinTooltip = new Tooltip(tr("supply.gtin_inventory.error.technical_gtin"));

        private ActionsCell() {
            mapping.setText(tr("kiz_mapping.action.mapping"));
            buy.setText(tr("kiz_mapping.action.buy"));
            mapping.setOnAction(e -> showMapping(getItem()));
            buy.setOnAction(e -> showBuy(getItem()));
        }

        @Override protected void updateItem(ZnackGtinInventorySummary item, boolean empty) {
            super.updateItem(item, empty);
            boolean technical = !empty && item != null && GtinNormalizer.isTechnicalRange(item.gtin());
            buy.setDisable(technical);
            buy.setTooltip(technical ? technicalGtinTooltip : null);
            setGraphic(empty || item == null ? null : box);
        }
    }

    static final class SelectionState {
        boolean wildcard;
        final Set<String> genders = new LinkedHashSet<>();

        SelectionState(boolean wildcard) {
            this.wildcard = wildcard;
        }

        private boolean empty() {
            return !wildcard && genders.isEmpty();
        }
    }

    record MappingDialogData(List<String> subjects,
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

    private boolean hasVerifiedSignature(Settings settings) {
        return settings != null && settings.signerCertificate() != null && !settings.signerCertificate().isBlank()
                && settings.signerTestedAt() != null;
    }
}
