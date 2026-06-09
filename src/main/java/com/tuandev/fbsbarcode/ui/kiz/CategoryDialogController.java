package com.tuandev.fbsbarcode.ui.kiz;

import com.tuandev.fbsbarcode.models.Category;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class CategoryDialogController {
    @FXML
    private TextField idField;

    @FXML
    private TextField nameField;

    private boolean generatedId;
    private Integer fixedCategoryId;

    public void setCreateDisplayId(int displayId) {
        generatedId = true;
        fixedCategoryId = null;
        idField.setText(String.valueOf(displayId));
        idField.setEditable(false);
        idField.setDisable(true);
        nameField.requestFocus();
    }

    public void setCategory(Category category, boolean editableId) {
        if (category == null) {
            return;
        }
        generatedId = false;
        fixedCategoryId = editableId ? null : category.getId();
        idField.setText(String.valueOf(editableId ? category.getId() : category.getDisplayId()));
        idField.setEditable(editableId);
        idField.setDisable(!editableId);
        nameField.setText(category.getName());
        nameField.requestFocus();
    }

    public Category toCategory() {
        String idText = idField.getText() == null ? "" : idField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (idText.isBlank() || name.isBlank()) {
            return null;
        }

        int id = generatedId ? 0 : fixedCategoryId != null ? fixedCategoryId : Integer.parseInt(idText);
        return new Category(id, name);
    }
}
