package com.tuandev.fbsbarcode.ui.shop;

import com.tuandev.fbsbarcode.models.Shop;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class ShopSidebarController {
    @FXML
    private VBox shopPane;

    private Consumer<Shop> onShopSelected;
    private Runnable onAddShop;
    private Runnable onOpenSettings;
    private HBox selectedBox;

    public void setOnShopSelected(Consumer<Shop> onShopSelected) {
        this.onShopSelected = onShopSelected;
    }

    public void setOnAddShop(Runnable onAddShop) {
        this.onAddShop = onAddShop;
    }

    public void setOnOpenSettings(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    @FXML
    private void onAddShop() {
        if (onAddShop != null) {
            onAddShop.run();
        }
    }

    @FXML
    private void onSettings() {
        if (onOpenSettings != null) {
            onOpenSettings.run();
        }
    }

    public void setShops(List<Shop> shops, Integer selectedShopId) {
        shopPane.getChildren().clear();
        selectedBox = null;

        Image image = new Image(
                Objects.requireNonNull(getClass().getResource("/com/tuandev/fbsbarcode/shopping-cart.png")).toExternalForm()
        );

        for (Shop shop : shops) {
            HBox row = createShopRow(shop, image);
            if (selectedShopId != null && selectedShopId == shop.getId()) {
                selectRow(row);
            }
            shopPane.getChildren().add(row);
        }
    }

    private HBox createShopRow(Shop shop, Image image) {
        HBox hBox = new HBox();
        hBox.setCursor(Cursor.HAND);
        hBox.setAlignment(Pos.CENTER_LEFT);
        hBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e9ecef; -fx-border-radius: 10;");
        hBox.setPadding(new Insets(10));
        hBox.setSpacing(6);

        ImageView shopIcon = new ImageView(image);
        Label shopName = new Label(shop.getName());
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        hBox.getChildren().addAll(shopIcon, shopName, spacer);
        hBox.setOnMouseClicked(e -> {
            selectRow(hBox);
            if (onShopSelected != null) {
                onShopSelected.accept(shop);
            }
        });
        hBox.setOnMouseEntered(e -> {
            if (hBox != selectedBox) {
                hBox.setStyle("-fx-background-color: #eef6ff; -fx-background-radius: 10; -fx-border-color: #cfe2ff; -fx-border-radius: 10;");
            }
        });
        hBox.setOnMouseExited(e -> {
            if (hBox != selectedBox) {
                hBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e9ecef; -fx-border-radius: 10;");
            }
        });
        return hBox;
    }

    private void selectRow(HBox row) {
        if (selectedBox != null) {
            selectedBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e9ecef; -fx-border-radius: 10;");
        }
        row.setStyle("-fx-background-color: #dceeff; -fx-background-radius: 10; -fx-border-color: #9ec5fe; -fx-border-radius: 10;");
        selectedBox = row;
    }
}
