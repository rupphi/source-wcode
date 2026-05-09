package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.ui.kiz.CategoryDialogController;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.Optional;

public class CategoryDialogService {
    public Optional<Category> showCreateDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Thêm danh mục sản phẩm");

        ButtonType okBtnType = new ButtonType("Thêm", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtnType = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtnType, cancelBtnType);

        FXMLLoader loader = FxmlViewLoader.loader(CategoryDialogController.class, "category-dialog.fxml");
        dialog.getDialogPane().setContent(FxmlViewLoader.load(loader));
        CategoryDialogController controller = loader.getController();

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != okBtnType) {
            return Optional.empty();
        }

        return Optional.ofNullable(controller.toCategory());
    }
}
