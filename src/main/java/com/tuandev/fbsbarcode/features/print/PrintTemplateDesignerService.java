package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.ui.print.PrintTemplateDesignerController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class PrintTemplateDesignerService {
    public void showDialog() {
        FXMLLoader loader = FxmlViewLoader.loader(PrintTemplateDesignerController.class, "print-template-designer-view.fxml");
        Scene scene = new Scene(FxmlViewLoader.load(loader));
        scene.getStylesheets().add(java.util.Objects.requireNonNull(com.tuandev.fbsbarcode.MainApplication.class.getResource("css/theme.css")).toExternalForm());

        Stage stage = new Stage();
        stage.setTitle("Thiết kế tem 58x40");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(scene);
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.showAndWait();
    }
}
