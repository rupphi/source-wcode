package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.models.Category;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

public class CategoryItemController {
    @FXML
    private HBox root;

    @FXML
    private Label idLabel;

    @FXML
    private Label nameLabel;

    @FXML
    private TextField countKizsField;

    @FXML
    private Button addKizBtn;

    @FXML
    private Button deleteCategoryBtn;

    private Category category;

    public void setCategory(Category category) {
        this.category = category;
        idLabel.setText("ID: " + category.getId());
        nameLabel.setText(category.getName());
        countKizsField.setText(String.valueOf(category.getCountKiz()));
    }

    public void updateCount(int countKiz) {
        if (category != null) {
            category.setCountKiz(countKiz);
        }
        countKizsField.setText(String.valueOf(countKiz));
    }

    public void setOnAddKiz(Runnable action) {
        addKizBtn.setOnAction(event -> action.run());
    }

    public void setOnDeleteCategory(Runnable action) {
        deleteCategoryBtn.setOnAction(event -> action.run());
    }

    public HBox getRoot() {
        return root;
    }
}
