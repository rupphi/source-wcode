package com.tuandev.fbsbarcode.ui.kiz;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.ui.kiz.CategoryItemController;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

public class KizPanelController {
    @FXML
    private VBox categoryVBox;

    private Runnable onAddCategory;

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
    }

    public void setCategories(List<Category> categories, Consumer<Category> onImportKiz, Consumer<Category> onDeleteCategory) {
        setCategories(categories, onImportKiz, null, onDeleteCategory);
    }

    public void setCategories(List<Category> categories, Consumer<Category> onImportKiz, Consumer<Category> onEditCategory, Consumer<Category> onDeleteCategory) {
        categoryVBox.getChildren().clear();
        for (Category category : categories) {
            categoryVBox.getChildren().add(createCategoryItem(category, onImportKiz, onEditCategory, onDeleteCategory));
        }
    }

    private Node createCategoryItem(Category category, Consumer<Category> onImportKiz, Consumer<Category> onEditCategory, Consumer<Category> onDeleteCategory) {
        FXMLLoader loader = FxmlViewLoader.loader(CategoryItemController.class, "category-item.fxml");
        Node root = FxmlViewLoader.load(loader);
        CategoryItemController controller = loader.getController();
        controller.setCategory(category);
        controller.setOnAddKiz(() -> onImportKiz.accept(category));
        controller.setOnEditCategory(() -> {
            if (onEditCategory != null) {
                onEditCategory.accept(category);
            }
        });
        controller.setOnDeleteCategory(() -> onDeleteCategory.accept(category));
        return root;
    }
}
