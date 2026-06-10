package com.tuandev.fbsbarcode.ui.shop;

import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class ShopDialogController {
    @FXML
    private TextField nameField;

    @FXML
    private PasswordField apiKeyField;

    @FXML
    private Label errorLabel;

    @FXML
    public void initialize() {
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            clearErrorState(nameField);
        });
        apiKeyField.textProperty().addListener((obs, oldVal, newVal) -> {
            clearErrorState(apiKeyField);
        });
    }

    public void setShop(Shop shop) {
        nameField.setText(shop.getName());
        apiKeyField.setText(shop.getApiKey());
    }

    public Shop toShop() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        return new Shop(name, apiKey);
    }

    public boolean validate() {
        resetValidationState();

        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        I18nService i18n = I18nService.getInstance();

        if (name.isEmpty()) {
            showErrorState(nameField, i18n.tr("shop_dialog.validation.name_empty"));
            return false;
        }

        if (apiKey.isEmpty()) {
            showErrorState(apiKeyField, i18n.tr("shop_dialog.validation.api_key_empty"));
            return false;
        }

        return true;
    }

    private void resetValidationState() {
        clearErrorState(nameField);
        clearErrorState(apiKeyField);
        if (errorLabel != null) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            errorLabel.setText("");
        }
    }

    private void showErrorState(TextField field, String message) {
        if (!field.getStyleClass().contains("error")) {
            field.getStyleClass().add("error");
        }
        if (errorLabel != null) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
        field.requestFocus();
    }

    private void clearErrorState(TextField field) {
        field.getStyleClass().remove("error");
        if (errorLabel != null && !nameField.getStyleClass().contains("error") && !apiKeyField.getStyleClass().contains("error")) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            errorLabel.setText("");
        }
    }
}
