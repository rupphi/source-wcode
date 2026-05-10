package com.tuandev.fbsbarcode.integration.update;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.AlertService;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.awt.Desktop;
import java.net.URI;

public class UpdateDialogService {

    public UpdateChoice showDialog(UpdateInfo info) {
        Dialog<UpdateChoice> dialog = new Dialog<>();
        dialog.setTitle("Co ban cap nhat moi");
        dialog.setHeaderText("Da co phien ban moi cua FBS Barcode!");

        VBox content = new VBox(10);
        content.setPrefWidth(420);

        Label versionLabel = new Label(String.format(
                "Hien tai: %s  -->  Moi: %s",
                BuildConfig.getAppVersion(), info.getVersion()
        ));
        versionLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label dateLabel = new Label("Ngay phat hanh: " + info.getReleaseDate());

        Label changelogHeader = new Label("Thay doi:");
        TextFlow changelogFlow = new TextFlow(new Text(info.getChangelog() != null ? info.getChangelog() : ""));
        changelogFlow.setMaxHeight(200);

        content.getChildren().addAll(versionLabel, dateLabel, changelogHeader, changelogFlow);
        dialog.getDialogPane().setContent(content);

        ButtonType downloadBtn = new ButtonType("Tai va cai dat", ButtonBar.ButtonData.OK_DONE);
        ButtonType skipBtn = new ButtonType("Bo qua phien ban nay", ButtonBar.ButtonData.OTHER);
        ButtonType laterBtn = new ButtonType("De sau", ButtonBar.ButtonData.CANCEL_CLOSE);

        if (info.isMandatory()) {
            dialog.getDialogPane().getButtonTypes().setAll(downloadBtn);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(downloadBtn, skipBtn, laterBtn);
        }

        dialog.setResultConverter(button -> {
            if (button == downloadBtn) return UpdateChoice.DOWNLOAD;
            if (button == skipBtn) return UpdateChoice.SKIP;
            return UpdateChoice.LATER;
        });

        return dialog.showAndWait().orElse(UpdateChoice.LATER);
    }

    public enum UpdateChoice {
        DOWNLOAD,
        SKIP,
        LATER
    }

    public static void openDownloadUrl(UpdateInfo info) {
        String url = info.getBestDownloadUrl();
        if (url != null && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception e) {
                AlertService.showError("Khong the mo trinh duyet. Vui long tai ve tai: " + url);
            }
        }
    }
}
