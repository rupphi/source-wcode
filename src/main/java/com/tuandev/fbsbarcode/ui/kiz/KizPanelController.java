package com.tuandev.fbsbarcode.ui.kiz;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class KizPanelController {
    @FXML
    private Label kizTitleLabel;

    @FXML
    private Button addCategoryButton;

    @FXML
    private HBox loadingBox;

    @FXML
    private ProgressIndicator loadingIndicator;

    @FXML
    private Label loadingLabel;

    @FXML
    private VBox categoryVBox;

    private Runnable onAddCategory;
    private final List<CategoryItemController> categoryItemControllers = new ArrayList<>();

    @FXML
    private void onAddCategory() {
        if (onAddCategory != null) {
            onAddCategory.run();
        }
    }

    public void setOnAddCategory(Runnable onAddCategory) {
        this.onAddCategory = onAddCategory;
    }

    public void clearCategories() {
        categoryVBox.getChildren().clear();
        categoryItemControllers.clear();
    }

    public void setCategories(List<Category> categories, Consumer<Category> onImportKiz, Consumer<Category> onDeleteCategory) {
        setCategories(categories, onImportKiz, null, null, onDeleteCategory);
    }

    public void setCategories(List<Category> categories, Consumer<Category> onImportKiz, Consumer<Category> onEditCategory, Consumer<Category> onDeleteCategory) {
        setCategories(categories, onImportKiz, onEditCategory, null, onDeleteCategory);
    }

    public void setCategories(List<Category> categories, Consumer<Category> onImportKiz, Consumer<Category> onEditCategory, Consumer<Category> onResetKiz, Consumer<Category> onDeleteCategory) {
        categoryVBox.getChildren().clear();
        categoryItemControllers.clear();
        for (Category category : categories) {
            categoryVBox.getChildren().add(createCategoryItem(category, onImportKiz, onEditCategory, onResetKiz, onDeleteCategory));
        }
        applyTranslations();
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        kizTitleLabel.setText(i18n.tr("kiz_panel.title"));
        addCategoryButton.setText(i18n.tr("kiz_panel.add_product"));
        loadingLabel.setText(i18n.tr("kiz_panel.importing"));
        categoryItemControllers.forEach(CategoryItemController::applyTranslations);
    }

    public void setLoading(boolean loading) {
        loadingBox.setVisible(loading);
        loadingBox.setManaged(loading);
        loadingIndicator.setVisible(loading);
        addCategoryButton.setDisable(loading);
        categoryVBox.setDisable(loading);
    }

    private Node createCategoryItem(Category category, Consumer<Category> onImportKiz, Consumer<Category> onEditCategory, Consumer<Category> onResetKiz, Consumer<Category> onDeleteCategory) {
        FXMLLoader loader = FxmlViewLoader.loader(CategoryItemController.class, "category-item.fxml");
        Node root = FxmlViewLoader.load(loader);
        CategoryItemController controller = loader.getController();
        controller.setCategory(category);
        categoryItemControllers.add(controller);
        controller.setOnAddKiz(() -> onImportKiz.accept(category));
        controller.setOnEditCategory(() -> {
            if (onEditCategory != null) {
                onEditCategory.accept(category);
            }
        });
        controller.setOnResetKiz(() -> {
            if (onResetKiz != null) {
                onResetKiz.accept(category);
            }
        });
        controller.setOnDeleteCategory(() -> onDeleteCategory.accept(category));
        return root;
    }
}
