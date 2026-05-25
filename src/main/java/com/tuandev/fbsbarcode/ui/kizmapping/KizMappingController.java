package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.features.kiz.CategoryWorkflow;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingExcelService;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingImportResult;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingProduct;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingSearchCriteria;
import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import com.tuandev.fbsbarcode.ui.kiz.KizPanelController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KizMappingController {
    private static final Logger LOGGER = LoggerFactory.getLogger(KizMappingController.class);
    private static final int IMAGE_WIDTH = 48;
    private static final int IMAGE_HEIGHT = 64;
    private static final int SUBJECT_VISIBLE_ROWS = 10;
    private static final int SUBJECT_ROW_HEIGHT = 30;
    private static final int PAGE_SIZE = 50;

    @FXML private Label titleLabel;
    @FXML private TextField searchField;
    @FXML private MenuButton subjectMenuButton;
    @FXML private Button clearFiltersButton;
    @FXML private Button exportButton;
    @FXML private Button importButton;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadingLabel;
    @FXML private Label emptyStateLabel;
    @FXML private VBox kizPanelContainer;
    @FXML private TableView<KizMappingRow> productTable;
    @FXML private TableColumn<KizMappingRow, KizMappingRow> imageColumn;
    @FXML private TableColumn<KizMappingRow, String> nameColumn;
    @FXML private TableColumn<KizMappingRow, String> subjectColumn;
    @FXML private TableColumn<KizMappingRow, String> genderColumn;
    @FXML private TableColumn<KizMappingRow, String> vendorCodeColumn;
    @FXML private TableColumn<KizMappingRow, KizMappingRow> categoryIdColumn;

    private final KizMappingRepository repository = new KizMappingRepository();
    private final KizMappingExcelService excelService = new KizMappingExcelService();
    private final FboProductImageService imageService = new FboProductImageService();
    private final CategoryWorkflow categoryWorkflow = new CategoryWorkflow();
    private final ObservableList<KizMappingRow> rows = FXCollections.observableArrayList();
    private final List<String> selectedSubjects = new ArrayList<>();
    private final List<CheckBox> subjectCheckBoxes = new ArrayList<>();
    private final FileChooser fileChooser = new FileChooser();
    private final FileChooser pdfFileChooser = new FileChooser();
    private KizPanelController kizPanelController;
    private Shop shop;
    private boolean loading;
    private boolean hasMore;
    private boolean suppressSearchEvents;
    private Runnable onKizInventoryChanged;

    @FXML
    private void initialize() {
        productTable.setItems(rows);
        productTable.setEditable(true);
        configureColumns();
        initializeKizPanel();
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            updateClearFiltersVisibility();
            if (!suppressSearchEvents) {
                reload();
            }
        });
        productTable.skinProperty().addListener((obs, oldValue, newValue) ->
                productTable.lookupAll(".scroll-bar").forEach(node -> {
                    if (node instanceof javafx.scene.control.ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                        bar.valueProperty().addListener((valueObs, previousScroll, currentScroll) -> {
                            if (hasMore && !loading && currentScroll.doubleValue() >= 0.70) {
                                loadProducts(true);
                            }
                        });
                    }
                })
        );
        fileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        applyTranslations();
        updateButtons();
    }

    public void setShop(Shop shop) {
        this.shop = shop;
        rows.clear();
        if (shop == null && kizPanelController != null) {
            kizPanelController.clearCategories();
        }
        setSubjects(shop == null ? List.of() : repository.findSubjects(shop.getId()));
        updateButtons();
        if (shop != null) {
            loadCategories();
            reload();
        }
    }

    public void setOnKizInventoryChanged(Runnable onKizInventoryChanged) {
        this.onKizInventoryChanged = onKizInventoryChanged;
    }

    public void refresh() {
        if (shop != null) {
            setSubjects(repository.findSubjects(shop.getId()));
            loadCategories();
            reload();
        }
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("kiz_mapping.title"));
        searchField.setPromptText(i18n.tr("kiz_mapping.search"));
        exportButton.setText(i18n.tr("kiz_mapping.export"));
        importButton.setText(i18n.tr("kiz_mapping.import"));
        loadingLabel.setText(i18n.tr("kiz_mapping.loading"));
        emptyStateLabel.setText(i18n.tr("kiz_mapping.empty"));
        imageColumn.setText(i18n.tr("kiz_mapping.column.image"));
        nameColumn.setText(i18n.tr("kiz_mapping.column.name"));
        subjectColumn.setText(i18n.tr("kiz_mapping.column.subject"));
        genderColumn.setText(i18n.tr("kiz_mapping.column.gender"));
        vendorCodeColumn.setText(i18n.tr("kiz_mapping.column.vendor_code"));
        categoryIdColumn.setText(i18n.tr("kiz_mapping.column.kiz_category"));
        if (kizPanelController != null) {
            kizPanelController.applyTranslations();
        }
        updateSubjectText();
    }

    @FXML
    private void onClearFilters() {
        suppressSearchEvents = true;
        searchField.clear();
        selectedSubjects.clear();
        subjectCheckBoxes.forEach(item -> item.setSelected(false));
        suppressSearchEvents = false;
        updateSubjectText();
        updateClearFiltersVisibility();
        reload();
    }

    @FXML
    private void onExport() {
        if (shop == null) {
            return;
        }
        fileChooser.setTitle(I18nService.getInstance().tr("kiz_mapping.export"));
        fileChooser.setInitialFileName("kiz-mapping-" + shop.getId() + ".xlsx");
        File file = fileChooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                excelService.exportProducts(file, repository.findAllForExport(shop.getId()));
                return null;
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            setLoading(false);
            openExportedFile(file);
        });
        task.setOnFailed(event -> {
            setLoading(false);
            LOGGER.error("Không thể export KIZ mapping", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    @FXML
    private void onImport() {
        if (shop == null) {
            return;
        }
        fileChooser.setTitle(I18nService.getInstance().tr("kiz_mapping.import"));
        File file = fileChooser.showOpenDialog(null);
        if (file == null) {
            return;
        }
        Task<KizMappingImportResult> task = new Task<>() {
            @Override
            protected KizMappingImportResult call() throws Exception {
                return repository.replaceMappingsFromImport(shop.getId(), excelService.readMappings(file));
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            setLoading(false);
            KizMappingImportResult result = task.getValue();
            if (!result.success()) {
                AlertService.showError(String.join("\n", result.errors()));
                return;
            }
            I18nService i18n = I18nService.getInstance();
            AlertService.showInfo(i18n.tr("kiz_mapping.title"), i18n.tr("kiz_mapping.import_done"),
                    MessageFormat.format(i18n.tr("kiz_mapping.import_result"), result.updatedCount(), result.clearedCount()));
            refresh();
        });
        task.setOnFailed(event -> {
            setLoading(false);
            LOGGER.error("Không thể import KIZ mapping", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void reload() {
        loadProducts(false);
    }

    private void initializeKizPanel() {
        if (kizPanelContainer == null) {
            return;
        }
        FXMLLoader loader = FxmlViewLoader.loader(KizPanelController.class, "kiz-panel-view.fxml");
        VBox root = FxmlViewLoader.load(loader);
        kizPanelController = loader.getController();
        kizPanelController.setOnAddCategory(this::onAddCategory);
        kizPanelController.applyTranslations();
        kizPanelContainer.getChildren().setAll(root);
    }

    private void loadCategories() {
        if (kizPanelController == null) {
            return;
        }
        Shop currentShop = shop;
        if (currentShop == null) {
            kizPanelController.clearCategories();
            return;
        }
        Task<List<Category>> task = new Task<>() {
            @Override
            protected List<Category> call() {
                return categoryWorkflow.loadCategories(currentShop.getId());
            }
        };
        task.setOnSucceeded(event -> {
            if (shop == null || shop.getId() != currentShop.getId()) {
                return;
            }
            List<Category> categories = task.getValue() == null ? List.of() : task.getValue();
            kizPanelController.setCategories(categories, this::importKizForCategory, this::editCategory, this::confirmDeleteCategory);
        });
        task.setOnFailed(event -> AlertService.showError(task.getException().getMessage()));
        AppTaskExecutor.execute(task);
    }

    private void onAddCategory() {
        if (shop == null) {
            return;
        }
        try {
            Optional<Category> categoryResult = categoryWorkflow.requestCreateCategory();
            if (categoryResult.isPresent() && categoryWorkflow.createCategory(categoryResult.get()) > 0) {
                loadCategories();
                notifyKizInventoryChanged();
            }
        } catch (NumberFormatException ex) {
            AlertService.showError(I18nService.getInstance().tr("category.error.id_number"));
        } catch (SQLException ex) {
            LOGGER.error("Không thể thêm KIZ category", ex);
            AlertService.showError(I18nService.getInstance().tr("category.error.id_exists"));
        }
    }

    private void editCategory(Category category) {
        if (shop == null || category == null) {
            return;
        }
        try {
            Optional<Category> categoryResult = categoryWorkflow.requestEditCategory(category);
            if (categoryResult.isPresent() && categoryWorkflow.updateCategoryName(categoryResult.get()) > 0) {
                loadCategories();
                notifyKizInventoryChanged();
            }
        } catch (NumberFormatException ex) {
            AlertService.showError(I18nService.getInstance().tr("category.error.id_number"));
        } catch (SQLException ex) {
            LOGGER.error("Không thể sửa KIZ category", ex);
            AlertService.showError(ex.getMessage());
        }
    }

    private void importKizForCategory(Category category) {
        Shop currentShop = shop;
        if (currentShop == null || category == null) {
            return;
        }
        I18nService i18n = I18nService.getInstance();
        pdfFileChooser.setTitle(i18n.tr("filechooser.open_pdf"));
        pdfFileChooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(i18n.tr("filechooser.pdf"), "*.pdf"));
        File file = pdfFileChooser.showOpenDialog(null);
        if (file == null) {
            return;
        }

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return categoryWorkflow.importKizFromPdf(file, currentShop, category);
            }
        };
        setLoading(true);
        setKizPanelLoading(true);
        task.setOnSucceeded(event -> {
            setLoading(false);
            setKizPanelLoading(false);
            if (shop != null && shop.getId() == currentShop.getId()) {
                loadCategories();
                notifyKizInventoryChanged();
            }
        });
        task.setOnFailed(event -> {
            setLoading(false);
            setKizPanelLoading(false);
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void confirmDeleteCategory(Category category) {
        if (shop == null || category == null) {
            return;
        }
        I18nService i18n = I18nService.getInstance();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        AlertService.applyTheme(alert);
        alert.setTitle(i18n.tr("category.delete.title"));
        alert.setHeaderText(java.text.MessageFormat.format(i18n.tr("category.delete.header"), category.getName()));
        ButtonType confirm = new ButtonType(i18n.tr("common.delete"), ButtonBar.ButtonData.YES);
        ButtonType cancel = new ButtonType(i18n.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(confirm, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == confirm) {
            categoryWorkflow.deleteCategory(shop, category);
            loadCategories();
            notifyKizInventoryChanged();
        }
    }

    private void notifyKizInventoryChanged() {
        if (onKizInventoryChanged != null) {
            onKizInventoryChanged.run();
        }
    }

    private void setKizPanelLoading(boolean loading) {
        if (kizPanelController != null) {
            kizPanelController.setLoading(loading);
        }
    }

    private void loadProducts(boolean append) {
        if (shop == null || loading) {
            return;
        }
        int offset = append ? rows.size() : 0;
        Task<List<KizMappingProduct>> task = new Task<>() {
            @Override
            protected List<KizMappingProduct> call() {
                return repository.search(new KizMappingSearchCriteria(shop.getId(), searchField.getText(), List.copyOf(selectedSubjects), PAGE_SIZE + 1, offset));
            }
        };
        setLoading(true);
        task.setOnSucceeded(event -> {
            setLoading(false);
            List<KizMappingProduct> loaded = task.getValue() == null ? List.of() : task.getValue();
            hasMore = loaded.size() > PAGE_SIZE;
            List<KizMappingProduct> page = hasMore ? loaded.subList(0, PAGE_SIZE) : loaded;
            if (!append) {
                rows.clear();
            }
            page.stream().map(KizMappingRow::new).forEach(rows::add);
            updateEmptyState();
        });
        task.setOnFailed(event -> {
            setLoading(false);
            LOGGER.error("Không thể tải KIZ mapping", task.getException());
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void setSubjects(List<String> subjects) {
        subjectMenuButton.getItems().clear();
        subjectCheckBoxes.clear();
        selectedSubjects.clear();
        VBox menuContent = new VBox(2);
        if (subjects != null) {
            for (String subject : subjects) {
                CheckBox item = new CheckBox(subject);
                item.getStyleClass().add("fbo-category-check");
                item.selectedProperty().addListener((obs, wasSelected, selected) -> {
                    if (selected) {
                        selectedSubjects.add(subject);
                    } else {
                        selectedSubjects.remove(subject);
                    }
                    updateSubjectText();
                    updateClearFiltersVisibility();
                    if (!suppressSearchEvents) {
                        reload();
                    }
                });
                subjectCheckBoxes.add(item);
                menuContent.getChildren().add(item);
            }
        }
        ScrollPane scrollPane = new ScrollPane(menuContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPrefViewportWidth(220);
        scrollPane.setPrefViewportHeight(Math.min(SUBJECT_VISIBLE_ROWS, Math.max(1, subjectCheckBoxes.size())) * SUBJECT_ROW_HEIGHT);
        CustomMenuItem menuItem = new CustomMenuItem(scrollPane, false);
        subjectMenuButton.getItems().setAll(menuItem);
        updateSubjectText();
        updateClearFiltersVisibility();
    }

    private void configureColumns() {
        imageColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        imageColumn.setCellFactory(column -> new ImageCell());
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(cell.getValue().product().title())));
        subjectColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(cell.getValue().product().subjectName())));
        genderColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(cell.getValue().product().gender())));
        vendorCodeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(cell.getValue().product().vendorCode())));
        categoryIdColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        categoryIdColumn.setCellFactory(column -> new CategoryCell());
    }

    private void saveRow(KizMappingRow row, Integer categoryId) {
        if (shop == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                repository.saveMapping(shop.getId(), row.product().nmId(), categoryId);
                return null;
            }
        };
        row.setSaveState(1);
        task.setOnSucceeded(event -> {
            row.setCategoryId(categoryId);
            row.setSaveState(2);
            productTable.refresh();
        });
        task.setOnFailed(event -> {
            row.setSaveState(-1);
            productTable.refresh();
            AlertService.showError(task.getException().getMessage());
        });
        AppTaskExecutor.execute(task);
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        loadingLabel.setVisible(loading);
        loadingLabel.setManaged(loading);
        updateButtons();
    }

    private void updateButtons() {
        boolean enabled = shop != null && !loading;
        exportButton.setDisable(!enabled);
        importButton.setDisable(!enabled);
    }

    private void updateSubjectText() {
        I18nService i18n = I18nService.getInstance();
        subjectMenuButton.setText(selectedSubjects.isEmpty()
                ? i18n.tr("kiz_mapping.subjects")
                : i18n.tr("kiz_mapping.subjects") + " (" + selectedSubjects.size() + ")");
    }

    private void updateClearFiltersVisibility() {
        boolean hasFilters = !selectedSubjects.isEmpty() || (searchField.getText() != null && !searchField.getText().isBlank());
        clearFiltersButton.setVisible(hasFilters);
        clearFiltersButton.setManaged(hasFilters);
    }

    private void updateEmptyState() {
        boolean empty = rows.isEmpty() && !loading;
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void openExportedFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException ex) {
            LOGGER.warn("Không thể mở file Excel sau khi export: {}", file.getAbsolutePath(), ex);
        }
    }

    private final class ImageCell extends TableCell<KizMappingRow, KizMappingRow> {
        private final ImageView imageView = new ImageView();
        private final Region placeholder = new Region();
        private final StackPane container = new StackPane(placeholder, imageView);
        private String currentUrl;

        ImageCell() {
            placeholder.getStyleClass().add("fbo-image-placeholder");
            placeholder.setMinSize(IMAGE_WIDTH, IMAGE_HEIGHT);
            placeholder.setPrefSize(IMAGE_WIDTH, IMAGE_HEIGHT);
            imageView.setFitWidth(IMAGE_WIDTH);
            imageView.setFitHeight(IMAGE_HEIGHT);
            imageView.setPreserveRatio(true);
            container.setAlignment(Pos.CENTER);
        }

        @Override
        protected void updateItem(KizMappingRow row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                currentUrl = null;
                imageView.setImage(null);
                setGraphic(null);
                return;
            }
            currentUrl = row.product().imageUrl();
            imageView.setImage(null);
            imageView.setVisible(false);
            placeholder.setVisible(true);
            setGraphic(container);
            if (currentUrl == null || currentUrl.isBlank()) {
                return;
            }
            String requestedUrl = currentUrl;
            imageService.loadImage(requestedUrl).whenComplete((bytes, error) ->
                    Platform.runLater(() -> {
                        if (bytes == null || bytes.length == 0 || currentUrl == null || !currentUrl.equals(requestedUrl)) {
                            return;
                        }
                        imageView.setImage(new Image(new ByteArrayInputStream(bytes)));
                        imageView.setVisible(true);
                        placeholder.setVisible(false);
                    }));
        }
    }

    private final class CategoryCell extends TableCell<KizMappingRow, KizMappingRow> {
        private final TextField field = new TextField();
        private KizMappingRow currentRow;
        private boolean updating;

        CategoryCell() {
            field.getStyleClass().add("fbo-quantity-field");
            field.setPrefWidth(58);
            field.setAlignment(Pos.CENTER);
            field.setOnAction(event -> commit());
            field.focusedProperty().addListener((obs, wasFocused, focused) -> {
                if (!focused) {
                    commit();
                }
            });
            field.textProperty().addListener((obs, oldValue, newValue) -> {
                if (updating) {
                    return;
                }
                String digits = newValue == null ? "" : newValue.replaceAll("\\D", "");
                if (!digits.equals(newValue)) {
                    field.setText(digits);
                }
            });
        }

        @Override
        protected void updateItem(KizMappingRow row, boolean empty) {
            super.updateItem(row, empty);
            currentRow = row;
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            updating = true;
            field.setText(row.getCategoryId() == null ? "" : String.valueOf(row.getCategoryId()));
            updating = false;
            setGraphic(field);
        }

        private void commit() {
            if (currentRow == null || updating) {
                return;
            }
            Integer value = parse(field.getText());
            if (java.util.Objects.equals(value, currentRow.getCategoryId())) {
                return;
            }
            saveRow(currentRow, value);
        }

        private Integer parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return Integer.parseInt(value);
        }
    }
}
