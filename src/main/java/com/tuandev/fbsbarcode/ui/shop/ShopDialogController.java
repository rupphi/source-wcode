package com.tuandev.fbsbarcode.ui.shop;

import com.tuandev.fbsbarcode.models.Shop;
import javafx.fxml.FXML;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class ShopDialogController {
    @FXML
    private TextField nameField;

    @FXML
    private PasswordField apiKeyField;

    public void setShop(Shop shop) {
        nameField.setText(shop.getName());
        apiKeyField.setText(shop.getApiKey());
    }

    public Shop toShop() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        return new Shop(name, apiKey);
    }
}
