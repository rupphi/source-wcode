package com.tuandev.fbsbarcode.ui.znackregistration;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorDetails;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Category;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.SearchCriteria;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationRepository;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationWorkflow;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackNationalCatalogService;
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
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
        if (shop == null || isBusy(sku.status())) return;
        Map<String, List<String>> characteristics = repository.characteristics(shop.getId(), sku.nmId());
        TextInputDialog tnvedDialog = new TextInputDialog(findTnved(characteristics));
        tnvedDialog.setTitle(tr("znack.registration.create"));
        tnvedDialog.setHeaderText(sku.vendorCode() + " · " + sku.color() + " · " + sku.size());
        tnvedDialog.setContentText("TN VED:");
        Optional<String> tnved = tnvedDialog.showAndWait();
        if (tnved.isEmpty()) return;
        setLoading(true);
        Task<FormData> task = new Task<>() {
            @Override protected FormData call() throws Exception {
                ZnackModels.Settings settings = settings();
                ZnackSignatureProvider signer = signer(settings);
                ZnackApiClient api = new ZnackApiClient();
                ZnackAuthService auth = new ZnackAuthService(api, signer);
                ZnackNationalCatalogService service = new ZnackNationalCatalogService(api, auth, signer, settings);
                ZnackNationalCatalogService.Preflight preflight = service.preflight(tnved.get());
                return new FormData(settings, service, preflight, characteristics);
            }
        };
        task.setOnSucceeded(event -> {
            setLoading(false);
            chooseCategoryAndOpenForm(sku, task.getValue());
        });
        task.setOnFailed(event -> {
            setLoading(false);
            showError(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void chooseCategoryAndOpenForm(Sku sku, FormData data) {
        Category category;
        if (data.preflight.categories().size() == 1) category = data.preflight.categories().get(0);
        else {
            ChoiceDialog<Category> dialog = new ChoiceDialog<>(data.preflight.categories().get(0), data.preflight.categories());
            dialog.setTitle(tr("znack.registration.create"));
            dialog.setHeaderText(tr("znack.registration.choose_category"));
            Optional<Category> result = dialog.showAndWait();
            if (result.isEmpty()) return;
            category = result.get();
        }
        setLoading(true);
        Task<List<Attribute>> task = new Task<>() {
            @Override protected List<Attribute> call() throws Exception {
                return data.service.requiredAttributes(category.id(), data.preflight.token());
            }
        };
        task.setOnSucceeded(event -> {
            setLoading(false);
            showAttributeForm(sku, data, category, task.getValue()).ifPresent(draft -> startWorkflow(sku, draft));
        });
        task.setOnFailed(event -> {
            setLoading(false);
            showError(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private Optional<Draft> showAttributeForm(Sku sku, FormData data, Category category, List<Attribute> attributes) {
        Dialog<Draft> dialog = new Dialog<>();
        dialog.setTitle(tr("znack.registration.create"));
        dialog.setHeaderText(category.name() + "\n"
                + java.text.MessageFormat.format(tr("znack.registration.gs1_remaining"),
                data.preflight.gs1().quotaKnown()
                        ? data.preflight.gs1().remaining()
                        : tr("znack.registration.gs1_check_on_create")));
        ButtonType create = new ButtonType(tr("znack.registration.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(create, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        TextField goodName = new TextField(defaultName(sku));
        TextField brand = new TextField(value(sku.brand()));
        grid.addRow(0, new Label(tr("znack.registration.good_name") + " *"), goodName);
        grid.addRow(1, new Label(tr("znack.registration.brand") + " *"), brand);

        ComboBox<DocumentType> documentType = new ComboBox<>();
        documentType.getItems().setAll(DocumentType.values());
        documentType.getSelectionModel().select(data.settings.documentType().contains("CERTIFICATE")
                ? DocumentType.CERTIFICATE : DocumentType.DECLARATION);
        TextField documentNumber = new TextField(value(data.settings.documentNumber()));
        TextField documentDate = new TextField(value(data.settings.documentDate()));
        documentDate.setPromptText("dd.MM.yyyy / yyyy-MM-dd");
        grid.addRow(2, new Label(tr("znack.registration.document_type") + " *"), documentType);
        grid.addRow(3, new Label(tr("znack.registration.document_number") + " *"), documentNumber);
        grid.addRow(4, new Label(tr("znack.registration.document_date") + " *"), documentDate);

        Map<Attribute, Control> controls = new LinkedHashMap<>();
        int row = 5;
        for (Attribute attribute : attributes) {
            if (attribute.id() == 2478 || attribute.id() == 2504
                    || attribute.id() == ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID
                    || attribute.id() == ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID) continue;
            String automatic = autoValue(attribute, sku, data.characteristics);
            Control control;
            if (!attribute.presets().isEmpty()) {
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().setAll(attribute.presets());
                combo.setEditable(!attribute.presetOnly());
                selectValue(combo, automatic);
                control = combo;
            } else {
                TextField field = new TextField(automatic);
                if ("date".equalsIgnoreCase(attribute.fieldType())) field.setPromptText("yyyy-MM-dd");
                control = field;
            }
            control.setMaxWidth(Double.MAX_VALUE);
            controls.put(attribute, control);
            grid.addRow(row++, new Label(attribute.name() + " [" + attribute.id() + "] *"), control);
        }
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(250),
                new javafx.scene.layout.ColumnConstraints(380));
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(690);
        scroll.setPrefViewportHeight(620);
        dialog.getDialogPane().setContent(scroll);
        Node createButton = dialog.getDialogPane().lookupButton(create);
        createButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (goodName.getText().isBlank() || brand.getText().isBlank()
                    || documentNumber.getText().isBlank() || documentDate.getText().isBlank()
                    || controls.values().stream().anyMatch(control -> controlValue(control).isBlank())) {
                event.consume();
                showWarning(tr("znack.registration.required"));
                return;
            }
            try { normalizedDocumentDate(documentDate.getText()); }
            catch (IllegalArgumentException error) { event.consume(); showWarning(error.getMessage()); }
        });
        dialog.setResultConverter(button -> {
            if (button != create) return null;
            Map<Long, String> values = new LinkedHashMap<>();
            values.put(2478L, goodName.getText().trim());
            values.put(2504L, brand.getText().trim());
            controls.forEach((attribute, control) -> values.put(attribute.id(), controlValue(control)));
            values.put(documentType.getValue().attributeId,
                    documentNumber.getText().trim() + ":::" + normalizedDocumentDate(documentDate.getText()));
            return new Draft(data.preflight.tnved(), category.id(), goodName.getText().trim(),
                    brand.getText().trim(), values);
        });
        return dialog.showAndWait();
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

    private static String autoValue(Attribute attribute, Sku sku, Map<String, List<String>> characteristics) {
        String name = normalize(attribute.name());
        if (attribute.id() == 2478 || name.contains("полное наименование")) return defaultName(sku);
        if (attribute.id() == 2504 || name.contains("товарный знак") || name.equals("бренд")) return value(sku.brand());
        if (name.contains("цвет")) return value(sku.color());
        if (name.contains("размер")) return value(sku.size());
        if (name.contains("артикул") || name.contains("модель")) return value(sku.vendorCode());
        for (Map.Entry<String, List<String>> entry : characteristics.entrySet()) {
            String sourceName = normalize(entry.getKey());
            if (sourceName.equals(name) || sourceName.contains(name) || name.contains(sourceName)) {
                return String.join(", ", entry.getValue());
            }
        }
        return "";
    }

    private static String defaultName(Sku sku) {
        List<String> parts = new ArrayList<>();
        add(parts, first(sku.title(), sku.subjectName()));
        add(parts, sku.brand());
        add(parts, "арт. " + value(sku.vendorCode()));
        if (!value(sku.color()).isBlank()) add(parts, "цвет " + sku.color());
        if (!value(sku.size()).isBlank()) add(parts, "размер " + sku.size());
        return String.join(", ", parts).replaceAll("\\s+", " ").trim();
    }

    private static String findTnved(Map<String, List<String>> characteristics) {
        for (Map.Entry<String, List<String>> entry : characteristics.entrySet()) {
            String name = normalize(entry.getKey());
            if (name.contains("тн вэд") || name.contains("тнвэд")) {
                return entry.getValue().stream().findFirst().orElse("").replaceAll("\\D", "");
            }
        }
        return "";
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

    private static void selectValue(ComboBox<String> combo, String value) {
        if (value == null || value.isBlank()) return;
        combo.getItems().stream().filter(item -> item.equalsIgnoreCase(value)).findFirst()
                .ifPresentOrElse(combo::setValue, () -> { if (combo.isEditable()) combo.getEditor().setText(value); });
    }

    private static String controlValue(Control control) {
        if (control instanceof TextField field) return value(field.getText()).trim();
        if (control instanceof ComboBox<?> combo) {
            if (combo.isEditable()) return value(combo.getEditor().getText()).trim();
            return combo.getValue() == null ? "" : combo.getValue().toString().trim();
        }
        return "";
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
    private static String normalize(String value) { return value(value).toLowerCase(Locale.ROOT).replace('ё', 'е').trim(); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static String value(String value) { return value == null ? "" : value; }
    private static void add(List<String> values, String value) { if (value != null && !value.isBlank() && !"арт.".equals(value)) values.add(value.trim()); }
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
        DECLARATION("Декларация о соответствии", ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID),
        CERTIFICATE("Сертификат соответствия", ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID);
        private final String label; private final long attributeId;
        DocumentType(String label, long attributeId) { this.label = label; this.attributeId = attributeId; }
        @Override public String toString() { return label; }
    }

    private enum StatusFilter {
        ALL("ALL"), NOT_CREATED("NOT_CREATED"), IN_PROGRESS("IN_PROGRESS"), COMPLETED("COMPLETED"), ERROR("ERROR");
        private final String code;
        StatusFilter(String code) { this.code = code; }
        @Override public String toString() { return tr("znack.registration.filter." + name().toLowerCase(Locale.ROOT)); }
    }

    private record FormData(ZnackModels.Settings settings, ZnackNationalCatalogService service,
                            ZnackNationalCatalogService.Preflight preflight,
                            Map<String, List<String>> characteristics) { }
}
