package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.shared.ConfigService;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.effect.InnerShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

import java.util.Objects;

public class PrintTypeDialogService {
    public void showDialog() {
        Dialog<Integer> dialog = new Dialog<>();
        dialog.setTitle("Chọn kiểu in");

        int currentType = ConfigService.getPrintType();
        HBox root = new HBox(20);
        root.setPadding(new Insets(20));

        root.getChildren().addAll(
                createPrintTypeView("type1.png", 1, currentType, dialog),
                createPrintTypeView("type2.png", 2, currentType, dialog),
                createPrintTypeView("type3.png", 3, currentType, dialog)
        );

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK);
        dialog.showAndWait();
    }

    private ImageView createPrintTypeView(String imagePath, int type, int currentType, Dialog<Integer> dialog) {
        Image image = new Image(
                Objects.requireNonNull(getClass().getResource("/com/tuandev/fbsbarcode/" + imagePath)).toExternalForm()
        );

        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(200);
        imageView.setFitHeight(400);
        imageView.setCursor(Cursor.HAND);

        if (type == currentType) {
            imageView.setEffect(createInnerShadow());
        }

        imageView.setOnMouseEntered(e -> {
            imageView.setScaleX(1.01);
            imageView.setScaleY(1.01);
        });

        imageView.setOnMouseExited(e -> {
            imageView.setScaleX(1);
            imageView.setScaleY(1);
        });

        imageView.setOnMouseClicked(e -> {
            ConfigService.updatePrintType(type);
            dialog.setResult(type);
        });

        return imageView;
    }

    private InnerShadow createInnerShadow() {
        InnerShadow innerShadow = new InnerShadow();
        innerShadow.setRadius(15.0);
        innerShadow.setColor(Color.MAGENTA);
        innerShadow.setChoke(0.5);
        return innerShadow;
    }
}
