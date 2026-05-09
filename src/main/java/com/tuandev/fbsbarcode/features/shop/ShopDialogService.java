package com.tuandev.fbsbarcode.features.shop;

import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.ui.shop.ShopDialogController;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.Optional;

public class ShopDialogService {
    public Optional<Shop> showCreateDialog() {
        return showDialog("Thêm cửa hàng", "Thêm", new Shop());
    }

    public Optional<Shop> showUpdateDialog(Shop shop) {
        return showDialog("Cập nhật cửa hàng", "Lưu", shop);
    }

    private Optional<Shop> showDialog(String title, String submitLabel, Shop initialValue) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);

        FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
        dialog.getDialogPane().setContent(FxmlViewLoader.load(loader));
        ShopDialogController controller = loader.getController();
        controller.setShop(initialValue);

        ButtonType submitBtn = new ButtonType(submitLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancelBtn, submitBtn);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != submitBtn) {
            return Optional.empty();
        }

        Shop shop = controller.toShop();
        if (shop.getName() == null || shop.getName().isBlank() || shop.getApiKey() == null || shop.getApiKey().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(shop);
    }
}
