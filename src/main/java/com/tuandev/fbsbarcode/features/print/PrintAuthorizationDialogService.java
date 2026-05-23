package com.tuandev.fbsbarcode.features.print;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;

import java.util.Optional;

public class PrintAuthorizationDialogService {
    private final PrintAuthorizationService authorizationService = new PrintAuthorizationService();

    public boolean ensureAuthorized() {
        if (authorizationService.isAuthorized()) {
            return true;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        I18nService i18n = I18nService.getInstance();
        dialog.setTitle(i18n.tr("print_auth.title"));
        dialog.setHeaderText(null);
        dialog.getDialogPane().setPadding(new Insets(18));
        dialog.getDialogPane().setMinWidth(420);

        ButtonType confirmButton = new ButtonType(i18n.tr("print_auth.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(i18n.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmButton, cancelButton);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(i18n.tr("print_auth.prompt"));

        CheckBox rememberCheckBox = new CheckBox(i18n.tr("print_auth.remember"));
        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        VBox content = new VBox(10,
                new Label(i18n.tr("print_auth.label")),
                passwordField,
                rememberCheckBox,
                errorLabel
        );
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(confirmButton);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!authorizationService.matches(passwordField.getText())) {
                errorLabel.setText(i18n.tr("print_auth.invalid"));
                errorLabel.setManaged(true);
                errorLabel.setVisible(true);
                passwordField.requestFocus();
                passwordField.selectAll();
                event.consume();
                return;
            }
            if (rememberCheckBox.isSelected()) {
                authorizationService.rememberAuthorized();
                if (!authorizationService.isAuthorized()) {
                    errorLabel.setText(i18n.tr("print_auth.remember_failed"));
                    errorLabel.setManaged(true);
                    errorLabel.setVisible(true);
                    event.consume();
                }
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == confirmButton;
    }
}
