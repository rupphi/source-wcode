package com.tuandev.fbsbarcode;

import com.tuandev.fbsbarcode.models.Category;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class CategoryDialogController {
    @FXML
    private TextField idField;

    @FXML
    private TextField nameField;

    public Category toCategory() {
        String idText = idField.getText() == null ? "" : idField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (idText.isBlank() || name.isBlank()) {
            return null;
        }

        return new Category(Integer.parseInt(idText), name);
    }
}
