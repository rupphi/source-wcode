package com.tuandev.fbsbarcode.shared;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.util.Optional;

public final class AlertService {
    private AlertService() {
    }

    public static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18nService.getInstance().tr("alert.error.title"));
        alert.setHeaderText(I18nService.getInstance().tr("alert.error.header"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void showWarning(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        alert.showAndWait();
    }

    public static Optional<ButtonType> showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (content != null && !content.isBlank()) {
            alert.setContentText(content);
        }
        return alert.showAndWait();
    }
}
