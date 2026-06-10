package com.tuandev.fbsbarcode.ui.znack;

import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.signature.*;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class ZnackAutomationController {
    @FXML private Label titleLabel, authStatusLabel, signatureSummaryLabel;
    @FXML private Label settingsTitleLabel, omsIdLabel, omsConnectionLabel;
    @FXML private Label omsHelpTitleLabel, omsHelpDescriptionLabel, omsHelpStepsTitleLabel, omsHelpStepsLabel;
    @FXML private Label omsHelpRecognizeTitleLabel, omsHelpRecognizeLabel, omsHelpWarningLabel;
    @FXML private Label signatureTitleLabel, signatureHelpLabel, defaultGoodsDocumentLabel, defaultGoodsDocumentHelpLabel;
    @FXML private Label documentNumberLabel, documentIssueDateLabel, documentExpiryDateLabel;
    @FXML private Tab settingsTab, productsTab, ordersTab, logsTab;
    @FXML private TextField omsIdField, omsConnectionField, documentNumberField, documentIssueDateField, documentExpiryDateField;
    @FXML private ComboBox<CryptoProCertificateInfo> signatureCertificateCombo;
    @FXML private CheckBox autoIntroductionCheck;
    @FXML private Button saveButton, refreshCertificatesButton, testSignatureButton;
    @FXML private Button omsIdHelpButton, omsConnectionHelpButton, closeOmsHelpButton;
    @FXML private javafx.scene.layout.VBox omsHelpPane;
    @FXML private TableView<Product> productsTable;
    @FXML private TableColumn<Product,String> productGtinColumn, productNameColumn, productTnVedColumn;
    @FXML private TableView<KizOrder> ordersTable;
    @FXML private TableColumn<KizOrder,Number> orderIdColumn;
    @FXML private TableColumn<KizOrder,String> orderGtinColumn, orderStatusColumn;
    @FXML private TableView<OperationLog> logsTable;
    @FXML private TableColumn<OperationLog,String> logTimeColumn, logActionColumn, logEntityColumn, logSeverityColumn, logResultColumn;

    private ZnackRepository repository;
    private Settings loaded = Settings.empty();
    private String signerCertificate = "";
    private String certificateMetadata = "";
    private Instant signerTestedAt;
    private String testedConfigurationKey;
    private String savedFingerprint = "";
    private String currentHelpTopic;
    private boolean loading;

    @FXML
    private void initialize() {
        productGtinColumn.setCellValueFactory(v -> text(v.getValue().gtin()));
        productNameColumn.setCellValueFactory(v -> text(v.getValue().productName()));
        productTnVedColumn.setCellValueFactory(v -> text(v.getValue().tnVed()));
        orderIdColumn.setCellValueFactory(v -> new javafx.beans.property.SimpleLongProperty(v.getValue().id()));
        orderGtinColumn.setCellValueFactory(v -> text(v.getValue().gtin()));
        orderStatusColumn.setCellValueFactory(v -> text(localizeStatus(v.getValue().localStatus().name())));
        logTimeColumn.setCellValueFactory(v -> text(v.getValue().createdAt().toString()));
        logActionColumn.setCellValueFactory(v -> text(v.getValue().action()));
        logEntityColumn.setCellValueFactory(v -> text(v.getValue().entityReference()));
        logSeverityColumn.setCellValueFactory(v -> text(v.getValue().severity()));
        logResultColumn.setCellValueFactory(v -> text(v.getValue().message()));
        signatureCertificateCombo.setConverter(new StringConverter<>() {
            @Override public String toString(CryptoProCertificateInfo value) { return value == null ? "" : value.displayName(); }
            @Override public CryptoProCertificateInfo fromString(String value) { return null; }
        });
        for (TextField field : List.of(omsIdField, omsConnectionField, documentNumberField, documentIssueDateField, documentExpiryDateField)) {
            field.textProperty().addListener((o, old, value) -> updateSaveState());
        }
        autoIntroductionCheck.selectedProperty().addListener((o, old, value) -> updateSaveState());
        signatureCertificateCombo.valueProperty().addListener((o, old, value) -> {
            if (value == null) return;
            signerCertificate = value.selector();
            if (!loading) {
                certificateMetadata = metadata(value);
                if (!configurationKey().equals(testedConfigurationKey)) signerTestedAt = null;
            }
            updateSignatureSummary();
            updateSaveState();
        });
        applyTranslations();
        clear();
    }

    public void setShop(Shop shop) {
        repository = shop == null ? null : new ZnackRepository(new ShopContext(shop.getId(), shop.getName()));
        if (repository == null) clear(); else load();
    }

    public void refresh() {
        if (repository != null) load();
    }

    public void applyTranslations() {
        titleLabel.setText(tr("znack.title"));
        settingsTab.setText(tr("znack.tab.settings"));
        productsTab.setText(tr("znack.tab.products"));
        ordersTab.setText(tr("znack.tab.orders"));
        logsTab.setText(tr("znack.tab.logs"));
        settingsTitleLabel.setText(tr("znack.settings.basic"));
        omsIdLabel.setText(tr("znack.oms_id"));
        omsConnectionLabel.setText(tr("znack.oms_connection"));
        omsIdHelpButton.setText(tr("common.help"));
        omsConnectionHelpButton.setText(tr("common.help"));
        omsIdHelpButton.setAccessibleText(tr("znack.help.oms_id.button"));
        omsConnectionHelpButton.setAccessibleText(tr("znack.help.oms_connection.button"));
        closeOmsHelpButton.setText(tr("common.close"));
        omsHelpStepsTitleLabel.setText(tr("znack.help.steps_title"));
        omsHelpRecognizeTitleLabel.setText(tr("znack.help.recognize_title"));
        signatureTitleLabel.setText(tr("znack.digital_signature"));
        signatureHelpLabel.setText(tr("znack.signature.help"));
        refreshCertificatesButton.setText(tr("znack.signature.refresh_certificates"));
        testSignatureButton.setText(tr("znack.signature.test"));
        defaultGoodsDocumentLabel.setText(tr("znack.default_goods_document"));
        defaultGoodsDocumentHelpLabel.setText(tr("znack.default_goods_document_help"));
        documentNumberLabel.setText(tr("znack.document_number"));
        documentIssueDateLabel.setText(tr("znack.document_issue_date"));
        documentExpiryDateLabel.setText(tr("znack.document_expiry_date"));
        autoIntroductionCheck.setText(tr("znack.auto_introduction"));
        saveButton.setText(tr("znack.save"));
        productGtinColumn.setText(tr("znack.field.gtin"));
        productNameColumn.setText(tr("znack.field.name"));
        productTnVedColumn.setText(tr("znack.field.tn_ved"));
        orderIdColumn.setText(tr("znack.field.id"));
        orderGtinColumn.setText(tr("znack.field.gtin"));
        orderStatusColumn.setText(tr("znack.field.status"));
        logTimeColumn.setText(tr("znack.field.time"));
        logActionColumn.setText(tr("znack.field.action"));
        logEntityColumn.setText(tr("znack.field.entity"));
        logSeverityColumn.setText(tr("znack.field.severity"));
        logResultColumn.setText(tr("znack.field.result"));
        if (currentHelpTopic != null) showOmsHelp(currentHelpTopic);
        updateSignatureSummary();
    }

    @FXML
    private void showOmsIdHelp() {
        showOmsHelp("oms_id");
    }

    @FXML
    private void showOmsConnectionHelp() {
        showOmsHelp("oms_connection");
    }

    @FXML
    private void closeOmsHelp() {
        currentHelpTopic = null;
        omsHelpPane.setVisible(false);
        omsHelpPane.setManaged(false);
    }

    private void showOmsHelp(String topic) {
        currentHelpTopic = topic;
        omsHelpTitleLabel.setText(tr("znack.help." + topic + ".title"));
        omsHelpDescriptionLabel.setText(tr("znack.help." + topic + ".description"));
        omsHelpStepsLabel.setText(tr("znack.help." + topic + ".steps"));
        omsHelpRecognizeLabel.setText(tr("znack.help." + topic + ".recognize"));
        omsHelpWarningLabel.setText(tr("znack.help." + topic + ".warning"));
        omsHelpPane.setManaged(true);
        omsHelpPane.setVisible(true);
    }

    @FXML
    private void save() {
        Settings settings = settings();
        if (blank(settings.omsId()) || blank(settings.omsConnection())) {
            AlertService.showError(tr("znack.error.oms_required"));
            return;
        }
        try {
            settings.validateGoodsDocumentDates();
            repository.saveSettings(settings);
            repository.log("SETTINGS_SAVE", null, "INFO", "SAVED", null);
            loaded = settings;
            savedFingerprint = fingerprint();
            authStatusLabel.setText(tr("znack.status.saved"));
            updateSaveState();
            resumeEligibleIntroductions(settings);
        } catch (IllegalArgumentException e) {
            AlertService.showError(e.getMessage());
        }
    }

    @FXML
    private void refreshCertificates() {
        try {
            CryptoProCertificateDiscoveryService discovery = new CryptoProCertificateDiscoveryService();
            List<CryptoProCertificateInfo> certificates = discovery.usable(
                    discovery.discover(loaded.certmgrPath(), loaded.csptestPath(), timeout()), Instant.now());
            String previousSelector = selectedCertificate();
            boolean hadConfiguredCertificate = !blank(previousSelector);
            signatureCertificateCombo.getItems().setAll(certificates);
            CryptoProCertificateInfo previous = certificates.stream()
                    .filter(certificate -> previousSelector.equals(certificate.selector()))
                    .findFirst().orElse(null);
            if (previous != null) {
                signatureCertificateCombo.setValue(previous);
            } else if (!hadConfiguredCertificate && !certificates.isEmpty()) {
                signatureCertificateCombo.setValue(certificates.getFirst());
            } else {
                signatureCertificateCombo.setValue(null);
                signerCertificate = "";
                certificateMetadata = "";
                signerTestedAt = null;
                testedConfigurationKey = null;
                updateSignatureSummary();
                updateSaveState();
                if (certificates.isEmpty()) AlertService.showError(tr("znack.signature.not_found"));
            }
        } catch (CryptoProException e) {
            AlertService.showError(signatureError(e));
        }
    }

    @FXML
    private void testSignature() {
        try {
            new CryptoProSignatureProvider(loaded.cryptcpPath(), selectedCertificate(), timeout())
                    .sign(("WCode Znack signature test " + Instant.now()).getBytes(StandardCharsets.UTF_8),
                            ZnackSignatureContext.SIGNATURE_TEST);
            signerTestedAt = Instant.now();
            testedConfigurationKey = configurationKey();
            repository.log("SIGNATURE_TEST", null, "INFO", "VERIFIED", null);
            updateSignatureSummary();
            updateSaveState();
        } catch (CryptoProException e) {
            signerTestedAt = null;
            updateSignatureSummary();
            AlertService.showError(signatureError(e));
        }
    }

    private void load() {
        loading = true;
        loaded = repository.getSettings();
        signerCertificate = value(loaded.signerCertificate());
        certificateMetadata = value(loaded.certificateMetadataJson());
        signerTestedAt = loaded.signerTestedAt();
        omsIdField.setText(value(loaded.omsId()));
        omsConnectionField.setText(value(loaded.omsConnection()));
        documentNumberField.setText(value(loaded.documentNumber()));
        documentIssueDateField.setText(value(loaded.documentDate()));
        documentExpiryDateField.setText(value(loaded.documentExpiryDate()));
        autoIntroductionCheck.setSelected(loaded.autoIntroduction());
        signatureCertificateCombo.getItems().clear();
        if (!signerCertificate.isBlank()) {
            CryptoProCertificateInfo stored = new CryptoProCertificateInfo(signerCertificate, signerCertificate,
                    signerCertificate, "", "", null, null, false, "CryptoPro", "");
            signatureCertificateCombo.getItems().add(stored);
            signatureCertificateCombo.setValue(stored);
        }
        productsTable.getItems().setAll(repository.findProducts());
        ordersTable.getItems().setAll(repository.findOrders());
        logsTable.getItems().setAll(repository.findLogs());
        testedConfigurationKey = signerTestedAt == null ? null : configurationKey();
        loading = false;
        savedFingerprint = fingerprint();
        authStatusLabel.setText(tr("znack.status.audit_only"));
        updateSignatureSummary();
        updateSaveState();
    }

    private Settings settings() {
        return new Settings(loaded.trueApiBaseUrl(), loaded.suzBaseUrl(), omsIdField.getText(), omsConnectionField.getText(),
                loaded.participantInn(), loaded.producerInn(), loaded.ownerInn(), loaded.signerExecutable(),
                selectedCertificate(), loaded.signerArgumentsJson(), documentNumberField.getText(),
                documentIssueDateField.getText(), loaded.pdfFolder(), autoIntroductionCheck.isSelected(),
                loaded.certificateListExecutable(), loaded.certificateListArgumentsJson(), certificateMetadata,
                signerTestedAt, loaded.certmgrPath(), loaded.cryptcpPath(), loaded.csptestPath(),
                loaded.resolvedCryptoProTimeoutSeconds(), documentExpiryDateField.getText());
    }

    private void clear() {
        loading = true;
        loaded = Settings.empty();
        for (TextField field : List.of(omsIdField, omsConnectionField, documentNumberField, documentIssueDateField, documentExpiryDateField)) field.clear();
        signatureCertificateCombo.getItems().clear();
        productsTable.getItems().clear();
        ordersTable.getItems().clear();
        logsTable.getItems().clear();
        autoIntroductionCheck.setSelected(false);
        signerCertificate = "";
        certificateMetadata = "";
        signerTestedAt = null;
        testedConfigurationKey = null;
        loading = false;
        savedFingerprint = fingerprint();
        authStatusLabel.setText(tr("znack.error.select_shop"));
        updateSaveState();
    }

    private void updateSaveState() {
        saveButton.setDisable(repository == null || loading || Objects.equals(savedFingerprint, fingerprint()));
        refreshCertificatesButton.setDisable(repository == null);
        testSignatureButton.setDisable(repository == null);
    }

    private void updateSignatureSummary() {
        if (signatureSummaryLabel == null) return;
        signatureSummaryLabel.setText(tr(blank(signerCertificate) ? "znack.signature.not_configured"
                : signerTestedAt == null ? "znack.signature.not_verified" : "znack.signature.verified"));
    }

    private String fingerprint() {
        return String.join("\u001f", value(omsIdField.getText()), value(omsConnectionField.getText()), selectedCertificate(),
                value(documentNumberField.getText()), value(documentIssueDateField.getText()),
                value(documentExpiryDateField.getText()), String.valueOf(autoIntroductionCheck.isSelected()),
                value(certificateMetadata), signerTestedAt == null ? "" : signerTestedAt.toString());
    }

    private String selectedCertificate() {
        CryptoProCertificateInfo selected = signatureCertificateCombo.getValue();
        return selected == null ? signerCertificate : value(selected.selector());
    }

    private String configurationKey() {
        return String.join("\u001f", selectedCertificate(), value(loaded.cryptcpPath()), value(loaded.csptestPath()));
    }

    private String metadata(CryptoProCertificateInfo certificate) {
        JsonObject json = new JsonObject();
        json.addProperty("selector", certificate.selector());
        json.addProperty("thumbprint", certificate.thumbprint());
        json.addProperty("subject", certificate.subject());
        json.addProperty("inn", certificate.inn());
        return json.toString();
    }

    private Duration timeout() {
        return Duration.ofSeconds(loaded.resolvedCryptoProTimeoutSeconds());
    }

    private void resumeEligibleIntroductions(Settings settings) {
        ZnackRepository currentRepository = repository;
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                ZnackPurchaseCoordinator.create(currentRepository).resumeEligibleIntroductions(settings);
                return null;
            }
        };
        AppTaskExecutor.execute(task);
    }

    private String signatureError(CryptoProException error) {
        return tr("znack.signature.error." + switch (error.code()) {
            case CRYPTOPRO_MISSING -> "cryptopro_missing";
            case TOKEN_OR_CERTIFICATE_ABSENT -> "certificate_absent";
            case PRIVATE_KEY_UNAVAILABLE -> "private_key";
            case CERTIFICATE_EXPIRED -> "expired";
            case CANCELLED -> "cancelled";
            case TIMEOUT -> "timeout";
            case INVALID_SIGNATURE_OUTPUT -> "invalid_output";
            default -> "failed";
        });
    }

    private javafx.beans.property.SimpleStringProperty text(String value) {
        return new javafx.beans.property.SimpleStringProperty(value(value));
    }

    private String tr(String key) { return I18nService.getInstance().tr(key); }
    private String localizeStatus(String status) {
        return I18nService.getInstance().tr("znack.status_value." + status.toLowerCase(java.util.Locale.ROOT), status);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String value(String value) { return value == null ? "" : value; }
}
