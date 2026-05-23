package com.tuandev.fbsbarcode.shared;

import com.tuandev.fbsbarcode.MainApplication;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

import java.util.Objects;
import java.util.Optional;

public final class AlertService {
    private static final String THEME_CSS = "/com/tuandev/fbsbarcode/styles/theme.css";

    private AlertService() {
    }

    public static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        applyTheme(alert);
        alert.setTitle(I18nService.getInstance().tr("alert.error.title"));
        alert.setHeaderText(I18nService.getInstance().tr("alert.error.header"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void showWarning(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        alert.showAndWait();
    }

    public static void showInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        alert.showAndWait();
    }

    public static Optional<ButtonType> showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        return alert.showAndWait();
    }

    public static void applyTheme(Dialog<?> dialog) {
        if (dialog != null) {
            applyTheme(dialog.getDialogPane());
        }
    }

    public static void applyTheme(Alert alert) {
        if (alert != null) {
            applyTheme(alert.getDialogPane());
        }
    }

    public static void applyTheme(DialogPane pane) {
        if (pane == null) {
            return;
        }
        String stylesheet = Objects.requireNonNull(MainApplication.class.getResource(THEME_CSS)).toExternalForm();
        if (!pane.getStylesheets().contains(stylesheet)) {
            pane.getStylesheets().add(stylesheet);
        }
        if (!pane.getStyleClass().contains("app-dialog-pane")) {
            pane.getStyleClass().add("app-dialog-pane");
        }
    }
}
