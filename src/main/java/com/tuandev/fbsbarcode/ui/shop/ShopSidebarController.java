package com.tuandev.fbsbarcode.ui.shop;

import com.tuandev.fbsbarcode.shared.AppLanguage;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.util.function.Consumer;

public class ShopSidebarController {
    @FXML
    private Button btnPrintHistory;
    @FXML
    private Button addShopButton;
    @FXML
    private Button packingButton;
    @FXML
    private Button templateButton;
    @FXML
    private Label languageLabel;
    @FXML
    private ComboBox<AppLanguage> languageComboBox;

    private Runnable onPacking;
    private Runnable onPrintHistory;
    private Runnable onAddShop;
    private Runnable onOpenSettings;
    private Consumer<AppLanguage> onLanguageChanged;

    @FXML
    private void initialize() {
        languageComboBox.getItems().setAll(AppLanguage.values());
        languageComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && newValue != oldValue && onLanguageChanged != null) {
                onLanguageChanged.accept(newValue);
            }
        });
        applyTranslations();
    }

    public void setOnPrintHistory(Runnable onPrintHistory) {
        this.onPrintHistory = onPrintHistory;
    }

    public void setOnPacking(Runnable onPacking) {
        this.onPacking = onPacking;
    }

    public void setOnAddShop(Runnable onAddShop) {
        this.onAddShop = onAddShop;
    }

    public void setOnOpenSettings(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    public void setOnLanguageChanged(Consumer<AppLanguage> onLanguageChanged) {
        this.onLanguageChanged = onLanguageChanged;
    }

    public void setSelectedLanguage(AppLanguage language) {
        if (language != null) {
            languageComboBox.getSelectionModel().select(language);
        }
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        templateButton.setText(" " + i18n.tr("sidebar.template"));
        addShopButton.setText(" " + i18n.tr("sidebar.add_shop"));
        packingButton.setText(" " + i18n.tr("sidebar.packing"));
        btnPrintHistory.setText(" " + i18n.tr("sidebar.print_history"));
        languageLabel.setText(i18n.tr("sidebar.language"));
    }

    @FXML
    private void onPacking() {
        if (onPacking != null) {
            onPacking.run();
        }
    }

    @FXML
    private void onPrintHistory() {
        if (onPrintHistory != null) {
            onPrintHistory.run();
        }
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
}
