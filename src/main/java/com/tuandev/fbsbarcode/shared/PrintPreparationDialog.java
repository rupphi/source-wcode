package com.tuandev.fbsbarcode.shared;

import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;

/** Non-blocking, cancellable wait; cancellation stops printing, never reverses a purchased batch. */
public final class PrintPreparationDialog {
    private PrintPreparationDialog() { }
    public static void attach(Task<?> task, String shopName) {
        var i18n = I18nService.getInstance();
        ButtonType stop = new ButtonType(i18n.tr("wb.print.stop_waiting"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.INFORMATION, i18n.tr("wb.print.waiting"), stop);
        AlertService.applyTheme(dialog);
        dialog.setHeaderText(shopName);
        dialog.setTitle(i18n.tr("common.notice"));
        dialog.setOnCloseRequest(event -> { if (task.isRunning()) task.cancel(true); });
        PauseTransition delay = new PauseTransition(Duration.seconds(1));
        delay.setOnFinished(event -> { if (task.isRunning()) dialog.show(); });
        task.stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.RUNNING) delay.playFromStart();
            else if (state == Worker.State.SUCCEEDED || state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                delay.stop(); dialog.close();
            }
        });
    }
}
