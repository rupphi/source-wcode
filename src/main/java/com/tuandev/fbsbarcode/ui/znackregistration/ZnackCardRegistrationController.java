package com.tuandev.fbsbarcode.ui.znackregistration;

import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
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
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationRepository;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationWorkflow;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackNationalCatalogService;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackWbAttributeMapper;
import com.tuandev.fbsbarcode.integration.znack.registration.RegistrationDocuments;
import com.tuandev.fbsbarcode.integration.znack.registration.RegistrationSelection;
import com.tuandev.fbsbarcode.integration.znack.registration.RegistrationDraftPreparer;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Cursor;
import javafx.util.Duration;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.ui.report.ErrorReportDialog;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ZnackCardRegistrationController {
    private static final int PAGE_SIZE = 500;
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private final ZnackCardRegistrationRepository repository = new ZnackCardRegistrationRepository();
    private final ZnackCardRegistrationWorkflow workflow = new ZnackCardRegistrationWorkflow(repository);
    private final FboProductImageService imageService = new FboProductImageService();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private final List<String> selectedSubjects = new ArrayList<>();
    private final List<CheckBox> subjectChecks = new ArrayList<>();
    private Shop shop;
    private boolean loading;
    private boolean bulkBusy;
    private long loadGeneration;
    private int page;
    private List<Sku> matching = List.of();
    private final RegistrationSelection selection = new RegistrationSelection();
    @FXML private CheckBox selectAllCheck;
    @FXML private Button registerSelectedButton;
    @FXML private Button clearSelectionButton;
    @FXML private Button previousPageButton;
    @FXML private Button nextPageButton;
    @FXML private Label pageLabel;
    @FXML private Label progressLabel;
    @FXML private Button resumeQueueButton;
    @FXML private TableColumn<Sku, Sku> selectColumn;

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
        statusFilter.valueProperty().addListener((obs, old, value) -> { filtersChanged(); reload(); });
        searchField.textProperty().addListener((obs, old, value) -> {
            filtersChanged();
            debounce.setOnFinished(event -> reload());
            debounce.playFromStart();
        });
        applyTranslations();
        javafx.animation.Timeline refresh = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(15), event -> {
                    if (productTable.getScene() != null && productTable.isVisible() && !bulkBusy) reload();
                }));
        refresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        refresh.play();
    }

    public void setShop(Shop shop) {
        this.shop = shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null;
        selection.clear();
        page = 0;
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
        selectAllCheck.setText(tr("znack.registration.select_all"));
        clearSelectionButton.setText(tr("znack.registration.clear_selection"));
        previousPageButton.setText("‹"); nextPageButton.setText("›");
        previousPageButton.setAccessibleText(tr("common.previous"));
        nextPageButton.setAccessibleText(tr("common.next"));
        updateSelection();
        resumeQueueButton.setText(tr("znack.registration.resume_queue"));
        productTable.refresh();
    }

    @FXML private void onRefresh() { reload(); }

    @FXML private void onResumeQueue() {
        if (shop == null || bulkBusy) return;
        try {
            com.tuandev.fbsbarcode.integration.znack.registration.RegistrationRunner.resumePaused(shop);
            reload();
        } catch (Exception error) { showError(error); }
    }

    private void filtersChanged() {
        ++loadGeneration;
        page = 0;
        selection.clear();
        matching = List.of();
        productTable.getItems().clear();
        updateSelection();
    }

    @FXML private void onSelectAll() {
        if (selectAllCheck.isSelected()) selection.selectAll(matching); else selection.clear();
        updateSelection(); productTable.refresh();
    }
    @FXML private void onClearSelection() { selection.clear(); updateSelection(); productTable.refresh(); }
    @FXML private void onPreviousPage() { page--; showPage(); }
    @FXML private void onNextPage() { page++; showPage(); }
    private void showPage() {
        int pages = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        productTable.getItems().setAll(matching.subList(page * PAGE_SIZE, Math.min(matching.size(), (page + 1) * PAGE_SIZE)));
        pageLabel.setText((page + 1) + " / " + pages + " · " + matching.size());
        long queued = matching.stream().filter(s -> s.status() == Status.QUEUED).count();
        long submitted = matching.stream().filter(s -> s.status() == Status.FEED_SUBMITTED || s.status() == Status.PROCESSING).count();
        long waiting = matching.stream().filter(s -> s.status() == Status.READY_TO_SIGN || s.status() == Status.WB_UPDATE_PENDING).count();
        long failed = matching.stream().filter(s -> s.status() == Status.ERROR).count();
        progressLabel.setText(java.text.MessageFormat.format(tr("znack.registration.progress"), queued, submitted, waiting, failed));
        previousPageButton.setDisable(page == 0); nextPageButton.setDisable(page + 1 >= pages);
        updateSelection();
    }
    private void updateSelection() {
        int count = selection.snapshot().size();
        long eligible = matching.stream().filter(RegistrationSelection::eligible).count();
        registerSelectedButton.setText(java.text.MessageFormat.format(tr("znack.registration.register_selected"), count));
        registerSelectedButton.setDisable(bulkBusy || count == 0);
        selectAllCheck.setDisable(bulkBusy || eligible == 0);
        selectAllCheck.setIndeterminate(count > 0 && count < eligible);
        selectAllCheck.setSelected(count > 0 && count == eligible);
        clearSelectionButton.setDisable(bulkBusy || count == 0);
        configButton.setDisable(bulkBusy);
        resumeQueueButton.setDisable(bulkBusy || shop == null);
    }
    @FXML private void onRegisterSelected() {
        if (bulkBusy || shop == null || selection.snapshot().isEmpty()) return;
        Shop selectedShop = new Shop(shop.getId(), shop.getName(), shop.getMarketplace(), shop.getClientId(), shop.getApiKey());
        var selected = selection.snapshot();
        bulkBusy = true; updateSelection(); productTable.refresh(); setLoading(true);
        Task<List<RegistrationDraftPreparer.Prepared>> task = new Task<>() {
            @Override protected List<RegistrationDraftPreparer.Prepared> call() throws Exception {
                var preparer = new RegistrationDraftPreparer(selectedShop, repository);
                var result = new ArrayList<RegistrationDraftPreparer.Prepared>();
                for (Sku original : selected) {
                    Sku current = repository.find(selectedShop.getId(), original.chrtId());
                    if (current == null || current.nmId() != original.nmId() || !RegistrationSelection.eligible(current)) continue;
                    try { result.add(preparer.prepare(current)); }
                    catch (IllegalArgumentException error) {
                        result.add(new RegistrationDraftPreparer.Prepared(current, null, List.of(error.getMessage())));
                    }
                }
                return result;
            }
        };
        task.setOnSucceeded(event -> {
            var chosen = RegistrationGoodsKindDialog.choose(selectedShop.getName(), task.getValue());
            if (chosen.isEmpty()) {
                bulkBusy = false; setLoading(false); updateSelection(); productTable.refresh(); return;
            }
            var valid = chosen.get().stream().filter(p -> p.draft() != null && p.missing().isEmpty()).toList();
            var invalid = chosen.get().stream().filter(p -> p.draft() == null || !p.missing().isEmpty()).toList();
            String summary = java.text.MessageFormat.format(tr("znack.registration.batch_confirm"),
                    selectedShop.getName(), valid.size(), invalid.size(), selected.size() - chosen.get().size());
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, summary, ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(tr("znack.registration.title"));
            if (!invalid.isEmpty()) {
                TextArea details = new TextArea(String.join("\n", invalid.stream().map(p -> p.sku().vendorCode()
                        + " / " + p.sku().size() + ": " + String.join(", ", p.missing())).toList()));
                details.setEditable(false); details.setWrapText(true); confirm.getDialogPane().setExpandableContent(details);
            }
            if (valid.isEmpty() || confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                if (valid.isEmpty()) { confirm.setAlertType(Alert.AlertType.INFORMATION); confirm.showAndWait(); }
                bulkBusy = false; setLoading(false); updateSelection(); productTable.refresh(); return;
            }
            Task<Integer> enqueue = new Task<>() {
                @Override protected Integer call() {
                    int count = 0;
                    for (var prepared : valid) if (workflow.start(selectedShop, prepared.sku(), prepared.draft(), null)) count++;
                    return count;
                }
            };
            enqueue.setOnSucceeded(done -> {
                bulkBusy = false; setLoading(false); selection.clear(); reload();
                showInfo(java.text.MessageFormat.format(tr("znack.registration.batch_queued"), enqueue.getValue()));
            });
            enqueue.setOnFailed(done -> { bulkBusy = false; setLoading(false); updateSelection(); reload(); showError(enqueue.getException()); });
            AppTaskExecutor.execute(enqueue);
        });
        task.setOnFailed(event -> { bulkBusy = false; setLoading(false); updateSelection(); productTable.refresh(); showError(task.getException()); });
        AppTaskExecutor.execute(task);
    }

    @FXML
    private void onConfig() {
        if (shop == null) return;
        ZnackModels.Settings current = settings();
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(tr("znack.registration.config"));
        dialog.setHeaderText(tr("znack.registration.config_header"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        var documents = RegistrationDocuments.load(shop.getId(), current);
        var declaration = documents.stream().filter(d -> !d.type().contains("CERTIFICATE")).findFirst();
        var certificate = documents.stream().filter(d -> d.type().contains("CERTIFICATE")).findFirst();
        TextField documentNumber = new TextField(declaration.map(ZnackModels.GoodsDocument::number).orElse(""));
        TextField documentDate = new TextField(declaration.map(ZnackModels.GoodsDocument::date).orElse(""));
        TextField certificateNumber = new TextField(certificate.map(ZnackModels.GoodsDocument::number).orElse(""));
        TextField certificateDate = new TextField(certificate.map(ZnackModels.GoodsDocument::date).orElse(""));
        documentDate.setPromptText("dd.MM.yyyy");
        certificateDate.setPromptText("dd.MM.yyyy");

        grid.addRow(0, new Label("Декларация о соответствии"));
        grid.addRow(1, new Label(tr("znack.registration.document_number")), documentNumber);
        grid.addRow(2, new Label(tr("znack.registration.document_date")), documentDate);
        grid.addRow(3, new Label("Сертификат соответствия"));
        grid.addRow(4, new Label(tr("znack.registration.document_number")), certificateNumber);
        grid.addRow(5, new Label(tr("znack.registration.document_date")), certificateDate);
        Label note = new Label(tr("znack.registration.config_note"));
        note.setWrapText(true);
        note.setMaxWidth(560);
        grid.add(note, 0, 6, 2, 1);
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(220),
                new javafx.scene.layout.ColumnConstraints(360));
        dialog.getDialogPane().setContent(grid);

        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                RegistrationDocuments.parse(documentNumber.getText(), documentDate.getText(),
                        certificateNumber.getText(), certificateDate.getText());
            } catch (IllegalArgumentException error) {
                event.consume();
                showWarning(tr(error.getMessage()));
            }
        });
        dialog.setResultConverter(button -> button == save);
        if (dialog.showAndWait().orElse(false)) {
            RegistrationDocuments.save(shop.getId(), documentNumber.getText(), documentDate.getText(),
                    certificateNumber.getText(), certificateDate.getText());
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
        int requestedShop = shop.getId();
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return repository.findSubjects(requestedShop); }
        };
        task.setOnSucceeded(event -> { if (shop != null && shop.getId() == requestedShop) buildSubjectMenu(task.getValue()); });
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
                filtersChanged();
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
        long generation = ++loadGeneration;
        if (shop == null) {
            matching = List.of(); selection.clear(); showPage();
            loading = false; setLoading(false);
            updateEmpty();
            return;
        }
        loading = true;
        setLoading(true);
        SearchCriteria criteria = new SearchCriteria(shop.getId(), searchField.getText(),
                List.copyOf(selectedSubjects), statusFilter.getValue().code, PAGE_SIZE, 0);
        selection.reset(shop.getId(), criteria.query() + "|" + criteria.subjects() + "|" + criteria.status());
        Task<List<Sku>> task = new Task<>() {
            @Override protected List<Sku> call() { return repository.allMatching(criteria); }
        };
        task.setOnSucceeded(event -> {
            if (generation != loadGeneration) return;
            loading = false;
            setLoading(false);
            matching = task.getValue();
            selection.retainEligible(matching);
            showPage();
            updateEmpty();
            resumePending(task.getValue());
        });
        task.setOnFailed(event -> {
            if (generation != loadGeneration) return;
            loading = false;
            setLoading(false);
            showError(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void createCard(Sku sku) {
        if (bulkBusy || shop == null || sku == null) return;
        if (sku.status() == Status.GTIN_GENERATED) {
            workflow.resume(shop, sku, null);
            reload(); return;
        }
        if (sku.status() == Status.ERROR && sku.gtin() != null && !sku.gtin().isBlank()) {
            Shop selectedShop = new Shop(shop.getId(), shop.getName(), shop.getMarketplace(), shop.getClientId(), shop.getApiKey());
            String stored = repository.payload(selectedShop.getId(), sku.chrtId());
            if (!stored.isBlank()) {
                try {
                    JsonObject payload = JsonParser.parseString(stored).getAsJsonObject();
                    Draft draft = ZnackCardRegistrationWorkflow.draftFromPayload(payload);
                    startWorkflow(selectedShop, sku, draft);
                    return;
                } catch (Exception ignored) {
                    // Fall back to preparer if payload cannot be parsed
                }
            }
        }
        if (sku.status() != Status.NOT_CREATED && sku.status() != Status.ERROR) return;
        Shop selectedShop = new Shop(shop.getId(), shop.getName(), shop.getMarketplace(), shop.getClientId(), shop.getApiKey());
        bulkBusy = true; updateSelection(); productTable.refresh();
        setLoading(true);
        Task<RegistrationDraftPreparer.Prepared> task = new Task<>() {
            @Override protected RegistrationDraftPreparer.Prepared call() throws Exception {
                return new RegistrationDraftPreparer(selectedShop, repository).prepare(sku);
            }
        };
        task.setOnSucceeded(event -> {
            bulkBusy = false; updateSelection(); productTable.refresh();
            setLoading(false);
            var result = task.getValue();
            if (!result.missing().isEmpty()) {
                showWarning(java.text.MessageFormat.format(tr("znack.registration.missing_wb_fields"),
                        sku.vendorCode(), String.join("\n• ", result.missing())));
                return;
            }
            var chosen = RegistrationGoodsKindDialog.choose(selectedShop.getName(), List.of(result));
            if (chosen.isPresent() && !chosen.get().isEmpty()) startWorkflow(selectedShop, sku, chosen.get().getFirst().draft());
        });
        task.setOnFailed(event -> {
            bulkBusy = false; updateSelection(); productTable.refresh();
            setLoading(false);
            showError(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void startWorkflow(Shop selectedShop, Sku sku, Draft draft) {
        boolean started = workflow.start(selectedShop, sku, draft, (status, detail) -> Platform.runLater(() -> {
            reload();
            if (status == Status.ERROR) showWorkflowError(sku, detail);
            else if (status == Status.PUBLISHED && !detail.isBlank()) {
                showInfo(tr("znack.registration.completed") + " " + detail);
            }
        }));
        if (!started) showInfo(tr("znack.registration.already_running"));
        reload();
    }

    private void resumePending(List<Sku> values) {
        if (shop == null) return;
        com.tuandev.fbsbarcode.integration.znack.registration.RegistrationRunner.start();
    }

    private ZnackModels.Settings settings() {
        return new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
    }

    private static ZnackSignatureProvider signer(ZnackModels.Settings settings) {
        return new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                java.time.Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
    }

    private void configureColumns() {
        selectColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        selectColumn.setCellFactory(column -> new TableCell<>() {
            private final CheckBox check = new CheckBox();
            { check.setOnAction(event -> { if (getItem() != null) { selection.set(getItem(), check.isSelected()); updateSelection(); } }); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                check.setSelected(selection.contains(item));
                check.setDisable(bulkBusy || !RegistrationSelection.eligible(item));
                check.setAccessibleText(item.vendorCode() + " / " + item.size());
                setAlignment(Pos.CENTER); setGraphic(check);
            }
        });
        productTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        imageColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private final Region placeholder = new Region();
            private final StackPane pane = new StackPane(placeholder, imageView);
            private String currentUrl;
            {
                imageView.setFitWidth(44);
                imageView.setFitHeight(58);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                placeholder.setMinSize(44, 58);
                placeholder.setPrefSize(44, 58);
                placeholder.setMaxSize(44, 58);
                placeholder.getStyleClass().add("fbo-image-placeholder");
                pane.setMinHeight(62);
            }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    currentUrl = null;
                    imageView.setImage(null);
                    setGraphic(null);
                    return;
                }
                currentUrl = item.imageUrl() == null ? "" : item.imageUrl().strip();
                imageView.setImage(null);
                imageView.setVisible(false);
                placeholder.setVisible(true);
                setGraphic(pane);
                if (currentUrl.isBlank()) return;
                String requestedUrl = currentUrl;
                imageService.loadImage(requestedUrl).whenComplete((bytes, error) -> Platform.runLater(() -> {
                    if (!Objects.equals(currentUrl, requestedUrl) || bytes == null || bytes.length == 0) return;
                    imageView.setImage(new Image(new ByteArrayInputStream(bytes)));
                    imageView.setVisible(true);
                    placeholder.setVisible(false);
                }));
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
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setCursor(Cursor.DEFAULT);
                    setUnderline(false);
                    setOnMouseClicked(null);
                    return;
                }
                setText(item);
                Sku sku = getTableRow() == null ? null : getTableRow().getItem();
                boolean isError = sku != null && (sku.status() == Status.ERROR
                        || (sku.errorMessage() != null && !sku.errorMessage().isBlank()));
                if (isError) {
                    setStyle("-fx-font-weight: 700; -fx-text-fill: #e53935;");
                    setUnderline(true);
                    setCursor(Cursor.HAND);
                    setTooltip(new Tooltip(tr("report.dialog.title") + " - " + tr("report.button")));
                    setOnMouseClicked(event -> {
                        if (shop != null && sku.errorMessage() != null && !sku.errorMessage().isBlank()) {
                            ErrorReportDialog.show(shop.getName(), sku.vendorCode() + " / " + sku.size(),
                                    "CARD_REGISTRATION", sku.errorMessage());
                        }
                    });
                } else {
                    setStyle("-fx-font-weight: 700;");
                    setUnderline(false);
                    setCursor(Cursor.DEFAULT);
                    setTooltip(null);
                    setOnMouseClicked(null);
                }
            }
        });
        actionColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        actionColumn.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button();
            { button.getStyleClass().add("btn-primary"); button.setOnAction(event -> createCard(getItem())); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                button.setText(item.status() == Status.ERROR || item.status() == Status.GTIN_GENERATED
                        ? tr("znack.registration.retry") : tr("znack.registration.create"));
                button.setDisable(bulkBusy || (isBusy(item.status()) && item.status() != Status.GTIN_GENERATED)
                        || item.status() == Status.PUBLISHED);
                setAlignment(Pos.CENTER);
                setGraphic(button);
            }
        });
        productTable.setRowFactory(table -> {
            TableRow<Sku> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    Sku sku = row.getItem();
                    if (sku != null && sku.errorMessage() != null && !sku.errorMessage().isBlank() && shop != null) {
                        ErrorReportDialog.show(shop.getName(), sku.vendorCode() + " / " + sku.size(),
                                "CARD_REGISTRATION", sku.errorMessage());
                    }
                }
            });
            row.itemProperty().addListener((obs, old, sku) -> {
                row.setTooltip(sku == null || sku.errorMessage() == null || sku.errorMessage().isBlank()
                        ? null : new Tooltip(sku.errorMessage()));
            });
            return row;
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
    private void showError(Throwable error) {
        if (shop != null) {
            ErrorReportDialog.show(shop.getName(), "Znack Registration", "CARD_REGISTRATION", ZnackErrorDetails.format(error));
        } else {
            AlertService.showDetailedError(ZnackErrorDetails.summary(error), ZnackErrorDetails.format(error));
        }
    }
    private void showWorkflowError(Sku sku, String detail) {
        String shopName = shop != null ? shop.getName() : "Shop";
        String entity = sku != null ? (sku.vendorCode() + " / " + sku.size()) : "Znack Card";
        ErrorReportDialog.show(shopName, entity, "CARD_REGISTRATION", detail);
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
