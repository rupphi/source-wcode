package com.tuandev.fbsbarcode.ui.znackregistration;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorDetails;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Category;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.SearchCriteria;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationRepository;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationWorkflow;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackNationalCatalogService;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackWbAttributeMapper;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ZnackCardRegistrationController {
    private static final int PAGE_SIZE = 500;
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private final ZnackCardRegistrationRepository repository = new ZnackCardRegistrationRepository();
    private final ZnackCardRegistrationWorkflow workflow = new ZnackCardRegistrationWorkflow(repository);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private final List<String> selectedSubjects = new ArrayList<>();
    private final List<CheckBox> subjectChecks = new ArrayList<>();
    private Shop shop;
    private boolean loading;

    @FXML private Label titleLabel;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private TextField searchField;
    @FXML private MenuButton categoryMenuButton;
    @FXML private ComboBox<StatusFilter> statusFilter;
    @FXML private Button clearFiltersButton;
    @FXML private Button refreshButton;
    @FXML private Button configButton;
    @FXML private TableView<Sku> productTable;
    @FXML private TableColumn<Sku, Sku> imageColumn;
    @FXML private TableColumn<Sku, String> nameColumn;
    @FXML private TableColumn<Sku, String> articleColumn;
    @FXML private TableColumn<Sku, String> colorColumn;
    @FXML private TableColumn<Sku, String> sizeColumn;
    @FXML private TableColumn<Sku, String> barcodeColumn;
    @FXML private TableColumn<Sku, String> statusColumn;
    @FXML private TableColumn<Sku, Sku> actionColumn;

    @FXML
    private void initialize() {
        configureColumns();
        statusFilter.getItems().setAll(StatusFilter.values());
        statusFilter.getSelectionModel().select(StatusFilter.ALL);
        statusFilter.valueProperty().addListener((obs, old, value) -> reload());
        searchField.textProperty().addListener((obs, old, value) -> {
            debounce.setOnFinished(event -> reload());
            debounce.playFromStart();
        });
        applyTranslations();
    }

    public void setShop(Shop shop) {
        this.shop = shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null;
        selectedSubjects.clear();
        loadSubjects();
        reload();
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("znack.registration.title"));
        searchField.setPromptText(i18n.tr("znack.registration.search"));
        refreshButton.setText(i18n.tr("znack.registration.refresh"));
        configButton.setText(i18n.tr("znack.registration.config"));
        clearFiltersButton.setText(i18n.tr("znack.registration.clear"));
        loadingLabel.setText(i18n.tr("znack.registration.loading"));
        emptyLabel.setText(i18n.tr("znack.registration.empty"));
        imageColumn.setText(i18n.tr("fbo.column.image"));
        nameColumn.setText(i18n.tr("fbo.column.name"));
        articleColumn.setText("Article");
        colorColumn.setText(i18n.tr("fbo.column.color"));
        sizeColumn.setText(i18n.tr("fbo.column.size"));
        barcodeColumn.setText("Barcode WB / GTIN");
        statusColumn.setText(i18n.tr("znack.registration.status"));
        actionColumn.setText(i18n.tr("znack.registration.action"));
        updateCategoryText();
        productTable.refresh();
    }

    @FXML private void onRefresh() { reload(); }

    @FXML
    private void onConfig() {
        if (shop == null) return;
        List<ZnackCardRegistrationModels.Subject> subjects = repository.findSubjectOptions(shop.getId());
        if (subjects.isEmpty()) {
            showWarning(tr("znack.registration.no_wb_products"));
            return;
        }
        ZnackModels.Settings current = settings();
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(tr("znack.registration.config"));
        dialog.setHeaderText(tr("znack.registration.config_header"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ComboBox<DocumentType> documentType = new ComboBox<>();
        documentType.getItems().setAll(DocumentType.values());
        documentType.setValue(DocumentType.from(current.documentType()));
        TextField documentNumber = new TextField(value(current.documentNumber()));
        TextField documentDate = new TextField(value(current.documentDate()));
        documentDate.setPromptText("dd.MM.yyyy");
        ComboBox<ZnackCardRegistrationModels.Subject> subject = new ComboBox<>();
        subject.getItems().setAll(subjects);
        subject.setMaxWidth(Double.MAX_VALUE);
        Sku selected = productTable.getSelectionModel().getSelectedItem();
        subject.setValue(subjects.stream().filter(item -> selected != null && item.id() == selected.subjectId())
                .findFirst().orElse(subjects.get(0)));
        TextField tnved = new TextField();
        tnved.setPromptText("10 digits");
        Runnable loadRule = () -> {
            var item = subject.getValue();
            tnved.setText(item == null ? "" : first(repository.tnvedRule(shop.getId(), item.id()),
                    repository.suggestedTnved(shop.getId(), item.id())));
        };
        subject.valueProperty().addListener((obs, old, item) -> loadRule.run());
        loadRule.run();

        grid.addRow(0, new Label(tr("znack.registration.document_type") + " *"), documentType);
        grid.addRow(1, new Label(tr("znack.registration.document_number") + " *"), documentNumber);
        grid.addRow(2, new Label(tr("znack.registration.document_date") + " *"), documentDate);
        grid.addRow(3, new Label(tr("znack.registration.wb_subject") + " *"), subject);
        grid.addRow(4, new Label("TN VED (10) *"), tnved);
        Label note = new Label(tr("znack.registration.config_note"));
        note.setWrapText(true);
        note.setMaxWidth(560);
        grid.add(note, 0, 5, 2, 1);
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(220),
                new javafx.scene.layout.ColumnConstraints(360));
        dialog.getDialogPane().setContent(grid);

        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                if (documentNumber.getText().isBlank() || documentDate.getText().isBlank()
                        || subject.getValue() == null || tnved.getText().replaceAll("\\D", "").length() != 10) {
                    throw new IllegalArgumentException(tr("znack.registration.config_required"));
                }
                LocalDate parsed = LocalDate.parse(normalizedDocumentDate(documentDate.getText()));
                documentDate.setText(parsed.format(RU_DATE));
            } catch (IllegalArgumentException error) {
                event.consume();
                showWarning(error.getMessage());
            }
        });
        dialog.setResultConverter(button -> button == save);
        if (dialog.showAndWait().orElse(false)) {
            DocumentType type = documentType.getValue();
            ZnackRepository znack = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName()));
            znack.saveSettings(current.withDefaultGoodsDocument(type.settingsCode,
                    documentNumber.getText().trim(), documentDate.getText().trim()));
            repository.saveTnvedRule(shop.getId(), subject.getValue(), tnved.getText());
            showInfo(tr("znack.registration.config_saved"));
        }
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        selectedSubjects.clear();
        subjectChecks.forEach(check -> check.setSelected(false));
        statusFilter.getSelectionModel().select(StatusFilter.ALL);
        updateCategoryText();
        reload();
    }

    private void loadSubjects() {
        categoryMenuButton.getItems().clear();
        subjectChecks.clear();
        if (shop == null) return;
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return repository.findSubjects(shop.getId()); }
        };
        task.setOnSucceeded(event -> buildSubjectMenu(task.getValue()));
        task.setOnFailed(event -> showError(task.getException()));
        AppTaskExecutor.execute(task);
    }

    private void buildSubjectMenu(List<String> subjects) {
        VBox box = new VBox(4);
        for (String subject : subjects) {
            CheckBox check = new CheckBox(subject);
            check.setMaxWidth(Double.MAX_VALUE);
            check.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) selectedSubjects.add(subject); else selectedSubjects.remove(subject);
                updateCategoryText();
                reload();
            });
            subjectChecks.add(check);
            box.getChildren().add(check);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(260);
        scroll.setPrefViewportHeight(Math.min(360, Math.max(50, subjects.size() * 28)));
        categoryMenuButton.getItems().setAll(new CustomMenuItem(scroll, false));
        updateCategoryText();
    }

    private void reload() {
        if (loading) return;
        if (shop == null) {
            productTable.getItems().clear();
            updateEmpty();
            return;
        }
        loading = true;
        setLoading(true);
        SearchCriteria criteria = new SearchCriteria(shop.getId(), searchField.getText(),
                List.copyOf(selectedSubjects), statusFilter.getValue().code, PAGE_SIZE, 0);
        Task<List<Sku>> task = new Task<>() {
            @Override protected List<Sku> call() { return repository.search(criteria); }
        };
        task.setOnSucceeded(event -> {
            loading = false;
            setLoading(false);
            productTable.getItems().setAll(task.getValue());
            updateEmpty();
            resumePending(task.getValue());
        });
        task.setOnFailed(event -> {
            loading = false;
            setLoading(false);
            showError(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void createCard(Sku sku) {
        boolean retryWithoutGtin = sku.status() == Status.CHECKING
                && (sku.gtin() == null || sku.gtin().isBlank());
        if (shop == null || (isBusy(sku.status()) && !retryWithoutGtin)) return;
        List<ZnackCardRegistrationModels.WbCharacteristic> characteristics =
                repository.characteristics(shop.getId(), sku.nmId());
        String configuredTnved = repository.tnvedRule(shop.getId(), sku.subjectId());
        String productTnved = ZnackWbAttributeMapper.findTnved(characteristics);
        String tnved = first(configuredTnved, productTnved);
        if (tnved.isBlank()) {
            showWarning(java.text.MessageFormat.format(tr("znack.registration.missing_tnved"), sku.subjectName()));
            return;
        }
        ZnackModels.Settings current = settings();
        if (!current.hasDefaultGoodsDocument()) {
            showWarning(tr("znack.registration.missing_document_config"));
            return;
        }
        final String documentDate;
        try {
            documentDate = normalizedDocumentDate(current.documentDate());
        } catch (IllegalArgumentException error) {
            showWarning(error.getMessage());
            return;
        }
        setLoading(true);
        Task<AutomaticDraft> task = new Task<>() {
            @Override protected AutomaticDraft call() throws Exception {
                ZnackSignatureProvider signer = signer(current);
                ZnackApiClient api = new ZnackApiClient();
                ZnackAuthService auth = new ZnackAuthService(api, signer);
                ZnackNationalCatalogService service = new ZnackNationalCatalogService(api, auth, signer, current);
                ZnackNationalCatalogService.Preflight preflight = service.preflight(tnved);
                Category category = ZnackNationalCatalogService.selectLightIndustryCategory(
                        preflight.categories(), sku.subjectName());
                List<Attribute> required = service.requiredAttributes(category.id(), preflight.token());
                ZnackModels.GoodsDocument document = new ZnackModels.GoodsDocument(current.documentType(),
                        current.documentNumber(), documentDate);
                ZnackWbAttributeMapper.MappingResult mapped = new ZnackWbAttributeMapper().map(sku,
                        characteristics, required, preflight.tnved(), preflight.categoryTnved(), document);
                Draft draft = new Draft(preflight.tnved(), preflight.categoryTnved(), category.id(),
                        mapped.goodName(), mapped.brand(), mapped.attributes());
                return new AutomaticDraft(draft, mapped.missingFields());
            }
        };
        task.setOnSucceeded(event -> {
            setLoading(false);
            AutomaticDraft result = task.getValue();
            if (!result.missingFields().isEmpty()) {
                showWarning(java.text.MessageFormat.format(tr("znack.registration.missing_wb_fields"),
                        sku.vendorCode(), String.join("\n• ", result.missingFields())));
                return;
            }
            startWorkflow(sku, result.draft());
        });
        task.setOnFailed(event -> {
            setLoading(false);
            showError(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void startWorkflow(Sku sku, Draft draft) {
        boolean started = workflow.start(shop, sku, draft, (status, detail) -> Platform.runLater(() -> {
            reload();
            if (status == Status.ERROR) showWorkflowError(detail);
            else if (status == Status.PUBLISHED && !detail.isBlank()) {
                showInfo(tr("znack.registration.completed") + " " + detail);
            }
        }));
        if (!started) showInfo(tr("znack.registration.already_running"));
        reload();
    }

    private void resumePending(List<Sku> values) {
        if (shop == null) return;
        for (Sku sku : values) {
            if (!isBusy(sku.status()) || sku.gtin() == null || sku.gtin().isBlank()) continue;
            workflow.resume(shop, sku, (status, detail) -> Platform.runLater(() -> {
                if (status == Status.ERROR) showWorkflowError(detail);
                if (status == Status.PUBLISHED && !detail.isBlank()) {
                    showInfo(tr("znack.registration.completed") + " " + detail);
                }
                reload();
            }));
        }
    }

    private ZnackModels.Settings settings() {
        return new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
    }

    private static ZnackSignatureProvider signer(ZnackModels.Settings settings) {
        return new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                java.time.Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
    }

    private void configureColumns() {
        productTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        imageColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final ImageView image = new ImageView();
            private final StackPane pane = new StackPane(image);
            { image.setFitWidth(44); image.setFitHeight(58); image.setPreserveRatio(true); pane.setMinHeight(62); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.imageUrl().isBlank()) { image.setImage(null); setGraphic(null); }
                else { image.setImage(new Image(item.imageUrl(), 44, 58, true, true, true)); setGraphic(pane); }
            }
        });
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(first(cell.getValue().title(), cell.getValue().subjectName())));
        articleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().vendorCode()));
        colorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().color()));
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().size()));
        barcodeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(barcodes(cell.getValue())));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(statusText(cell.getValue().status())));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!empty) setStyle("-fx-font-weight: 700;");
            }
        });
        actionColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        actionColumn.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button();
            { button.getStyleClass().add("btn-primary"); button.setOnAction(event -> createCard(getItem())); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                button.setText(item.status() == Status.ERROR
                        || (item.status() == Status.CHECKING && item.gtin() == null)
                        ? tr("znack.registration.retry") : tr("znack.registration.create"));
                button.setDisable((isBusy(item.status()) && !(item.status() == Status.CHECKING && item.gtin() == null))
                        || item.status() == Status.PUBLISHED);
                setAlignment(Pos.CENTER);
                setGraphic(button);
            }
        });
        productTable.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                setTooltip(empty || item == null || item.errorMessage() == null || item.errorMessage().isBlank()
                        ? null : new Tooltip(item.errorMessage()));
            }
        });
    }

    private static String normalizedDocumentDate(String input) {
        String value = input == null ? "" : input.trim();
        try { return LocalDate.parse(value).toString(); }
        catch (DateTimeParseException ignored) {
            try { return LocalDate.parse(value, RU_DATE).toString(); }
            catch (DateTimeParseException error) {
                throw new IllegalArgumentException(tr("znack.registration.invalid_document_date"));
            }
        }
    }

    private void setLoading(boolean value) {
        loadingLabel.setVisible(value); loadingLabel.setManaged(value); refreshButton.setDisable(value);
    }
    private void updateEmpty() {
        boolean empty = productTable.getItems().isEmpty(); emptyLabel.setVisible(empty); emptyLabel.setManaged(empty);
    }
    private void updateCategoryText() {
        categoryMenuButton.setText(selectedSubjects.isEmpty() ? tr("znack.registration.categories")
                : tr("znack.registration.categories") + " (" + selectedSubjects.size() + ")");
    }
    private static boolean isBusy(Status status) {
        return status != Status.NOT_CREATED && status != Status.ERROR
                && status != Status.PUBLISHED;
    }
    private static String barcodes(Sku sku) {
        String source = String.join(", ", sku.barcodes());
        return sku.gtin() == null || sku.gtin().isBlank() ? source : source + (source.isBlank() ? "" : "\n") + "GTIN: " + sku.gtin();
    }
    private static String statusText(Status status) { return tr("znack.registration.status." + status.name().toLowerCase(Locale.ROOT)); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static String value(String value) { return value == null ? "" : value; }
    private static String tr(String key) { return I18nService.getInstance().tr(key); }
    private static void showError(Throwable error) {
        AlertService.showDetailedError(ZnackErrorDetails.summary(error), ZnackErrorDetails.format(error));
    }
    private static void showWorkflowError(String detail) {
        String full = ZnackErrorDetails.formatStored(detail);
        String summary = detail == null || detail.isBlank() ? "Unknown error"
                : detail.lines().filter(line -> !line.isBlank()).findFirst().orElse(detail).replaceFirst("^Summary:\\s*", "");
        AlertService.showDetailedError(summary, full);
    }
    private static void showWarning(String message) { Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK); alert.setHeaderText(null); alert.showAndWait(); }
    private static void showInfo(String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); alert.setHeaderText(null); alert.showAndWait(); }

    private enum DocumentType {
        DECLARATION("Декларация о соответствии", "CONFORMITY_DECLARATION"),
        CERTIFICATE("Сертификат соответствия", "CONFORMITY_CERTIFICATE");
        private final String label; private final String settingsCode;
        DocumentType(String label, String settingsCode) { this.label = label; this.settingsCode = settingsCode; }
        static DocumentType from(String value) {
            return value != null && value.toUpperCase(Locale.ROOT).contains("CERTIFICATE")
                    ? CERTIFICATE : DECLARATION;
        }
        @Override public String toString() { return label; }
    }

    private enum StatusFilter {
        ALL("ALL"), NOT_CREATED("NOT_CREATED"), IN_PROGRESS("IN_PROGRESS"), COMPLETED("COMPLETED"), ERROR("ERROR");
        private final String code;
        StatusFilter(String code) { this.code = code; }
        @Override public String toString() { return tr("znack.registration.filter." + name().toLowerCase(Locale.ROOT)); }
    }

    private record AutomaticDraft(Draft draft, List<String> missingFields) { }
}
