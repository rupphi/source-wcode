package com.tuandev.fbsbarcode.ui.kiz;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class CategoryItemController {
    @FXML
    private VBox root;

    @FXML
    private Label idLabel;

    @FXML
    private Label kizCountLabel;

    @FXML
    private Label nameLabel;

    @FXML
    private TextField countKizsField;

    @FXML
    private Button addKizBtn;

    @FXML
    private Button editCategoryBtn;

    @FXML
    private Button resetKizBtn;

    @FXML
    private Button deleteCategoryBtn;

    private Category category;

    public void setCategory(Category category) {
        this.category = category;
        applyTranslations();
        nameLabel.setText(category.getName());
        updateCount(category.getCountKiz());
    }

    public void applyTranslations() {
        if (category != null) {
            idLabel.setText(I18nService.getInstance().tr("category_item.id_prefix") + ": " + category.getDisplayId());
        }
        kizCountLabel.setText(I18nService.getInstance().tr("category_item.kiz_count"));
    }

    public void updateCount(int countKiz) {
        if (category != null) {
            category.setCountKiz(countKiz);
        }
        countKizsField.setText(String.valueOf(countKiz));
        resetKizBtn.setDisable(countKiz <= 0);
    }

    public void setOnAddKiz(Runnable action) {
        addKizBtn.setOnAction(event -> action.run());
    }

    public void setOnEditCategory(Runnable action) {
        editCategoryBtn.setOnAction(event -> action.run());
    }

    public void setOnResetKiz(Runnable action) {
        resetKizBtn.setOnAction(event -> action.run());
    }

    public void setOnDeleteCategory(Runnable action) {
        deleteCategoryBtn.setOnAction(event -> action.run());
    }

    public VBox getRoot() {
        return root;
    }
}
