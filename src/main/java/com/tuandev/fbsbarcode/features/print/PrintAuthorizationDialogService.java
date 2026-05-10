package com.tuandev.fbsbarcode.features.print;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

import java.util.Optional;

public class PrintAuthorizationDialogService {
    private final PrintAuthorizationService authorizationService = new PrintAuthorizationService();

    public boolean ensureAuthorized() {
        if (authorizationService.isAuthorized()) {
            return true;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Xác thực in");
        dialog.setHeaderText("Nhập mã xác thực để sử dụng chức năng Print");

        ButtonType confirmButton = new ButtonType("Xác thực", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmButton, cancelButton);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Nhập mã xác thực");

        CheckBox rememberCheckBox = new CheckBox("Lưu để lần sau không cần nhập lại");
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 11px;");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        VBox content = new VBox(10,
                new Label("Mã xác thực"),
                passwordField,
                rememberCheckBox,
                errorLabel
        );
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(confirmButton);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!authorizationService.matches(passwordField.getText())) {
                errorLabel.setText("Mã xác thực không đúng");
                errorLabel.setManaged(true);
                errorLabel.setVisible(true);
                passwordField.requestFocus();
                passwordField.selectAll();
                event.consume();
                return;
            }
            if (rememberCheckBox.isSelected()) {
                authorizationService.rememberAuthorized();
            } else {
                authorizationService.clearRememberedAuthorization();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == confirmButton;
    }
}
