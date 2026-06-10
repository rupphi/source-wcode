package com.tuandev.fbsbarcode.ui.znack;

import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import com.tuandev.fbsbarcode.integration.znack.signature.*;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.util.StringConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class ZnackAutomationController {
    private static final DateTimeFormatter CERTIFICATE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
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
    @FXML private Button saveButton, testSignatureButton;
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
    private boolean certificateDiscoveryRunning;
    private boolean updatingCertificateSelection;
    private long certificateDiscoveryGeneration;

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
        configureLogCopy();
        signatureCertificateCombo.setConverter(new StringConverter<>() {
            @Override public String toString(CryptoProCertificateInfo value) { return certificateDisplay(value); }
            @Override public CryptoProCertificateInfo fromString(String value) { return null; }
        });
        signatureCertificateCombo.setCellFactory(ignored -> certificateCell(true));
        signatureCertificateCombo.setButtonCell(certificateCell(false));
        signatureCertificateCombo.setOnShowing(ignored -> loadCertificates());
        for (TextField field : List.of(omsIdField, omsConnectionField, documentNumberField, documentIssueDateField, documentExpiryDateField)) {
            field.textProperty().addListener((o, old, value) -> updateSaveState());
        }
        autoIntroductionCheck.selectedProperty().addListener((o, old, value) -> updateSaveState());
        signatureCertificateCombo.valueProperty().addListener((o, old, value) -> {
            if (updatingCertificateSelection) return;
            if (value == null) return;
            if (!loading && !certificateSelectable(value, Instant.now())) {
                updatingCertificateSelection = true;
                signatureCertificateCombo.setValue(old);
                updatingCertificateSelection = false;
                return;
            }
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
        invalidateCertificateDiscovery();
        repository = shop == null ? null : new ZnackRepository(new ShopContext(shop.getId(), shop.getName()));
        if (repository == null) clear(); else load();
    }

    public void refresh() {
        if (repository != null) {
            invalidateCertificateDiscovery();
            load();
        }
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
        signatureCertificateCombo.setButtonCell(certificateCell(false));
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

    private void loadCertificates() {
        if (repository == null || certificateDiscoveryRunning) return;
        long generation = certificateDiscoveryGeneration;
        Settings discoverySettings = loaded;
        certificateDiscoveryRunning = true;
        updateSignatureSummary();
        updateSaveState();
        Task<List<CryptoProCertificateInfo>> task = new Task<>() {
            @Override protected List<CryptoProCertificateInfo> call() throws CryptoProException {
                return new CryptoProCertificateDiscoveryService().discover(
                        discoverySettings.certmgrPath(), discoverySettings.csptestPath(),
                        Duration.ofSeconds(discoverySettings.resolvedCryptoProTimeoutSeconds()));
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != certificateDiscoveryGeneration) return;
            certificateDiscoveryRunning = false;
            applyDiscoveredCertificates(task.getValue(), Instant.now());
        });
        task.setOnFailed(event -> {
            if (generation != certificateDiscoveryGeneration) return;
            certificateDiscoveryRunning = false;
            updateSignatureSummary();
            updateSaveState();
            Throwable failure = task.getException();
            AlertService.showError(failure instanceof CryptoProException error
                    ? signatureError(error) : value(failure == null ? null : failure.getMessage()));
        });
        AppTaskExecutor.execute(task);
    }

    private void applyDiscoveredCertificates(List<CryptoProCertificateInfo> discovered, Instant now) {
        String previousSelector = selectedCertificate();
        CryptoProCertificateInfo current = signatureCertificateCombo.getValue();
        boolean hadConfiguredCertificate = !blank(previousSelector);
        List<CryptoProCertificateInfo> certificates = new ArrayList<>(orderedCertificates(discovered, now));
        CryptoProCertificateInfo previous = findCertificate(certificates, previousSelector);
        if (previous == null && current != null && previousSelector.equals(current.selector()) && current.expired(now)) {
            certificates.add(current);
            certificates = new ArrayList<>(orderedCertificates(certificates, now));
            previous = current;
        }
        CryptoProCertificateInfo selection = previous;
        if (selection == null && !hadConfiguredCertificate) {
            selection = certificates.stream().filter(certificate -> certificateSelectable(certificate, now))
                    .findFirst().orElse(null);
        }

        loading = true;
        updatingCertificateSelection = true;
        try {
            signatureCertificateCombo.getItems().setAll(certificates);
            signatureCertificateCombo.setValue(selection);
        } finally {
            updatingCertificateSelection = false;
            loading = false;
        }
        if (selection == null) {
            signerCertificate = "";
            certificateMetadata = "";
            signerTestedAt = null;
            testedConfigurationKey = null;
        } else {
            signerCertificate = selection.selector();
            certificateMetadata = metadata(selection);
            if (selection.expired(now) || !configurationKey().equals(testedConfigurationKey)) {
                signerTestedAt = null;
                testedConfigurationKey = null;
            }
        }
        updateSignatureSummary();
        updateSaveState();
        if (discovered.isEmpty()) AlertService.showError(tr("znack.signature.not_found"));
    }

    @FXML
    private void testSignature() {
        if (selectedCertificateExpired()) {
            AlertService.showError(tr("znack.signature.error.expired"));
            return;
        }
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
            repository.log("SIGNATURE_TEST", null, "ERROR", signatureDiagnostic(e), null);
            logsTable.getItems().setAll(repository.findLogs());
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
            CryptoProCertificateInfo stored = storedCertificate();
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
        signatureCertificateCombo.setDisable(repository == null);
        testSignatureButton.setDisable(repository == null || certificateDiscoveryRunning || selectedCertificateExpired());
    }

    private void updateSignatureSummary() {
        if (signatureSummaryLabel == null) return;
        signatureSummaryLabel.setText(tr(certificateDiscoveryRunning ? "znack.signature.loading"
                : selectedCertificateExpired() ? "znack.signature.error.expired"
                : blank(signerCertificate) ? "znack.signature.not_configured"
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
        json.addProperty("issuer", certificate.issuer());
        json.addProperty("inn", certificate.inn());
        if (certificate.validFrom() != null) json.addProperty("validFrom", certificate.validFrom().toString());
        if (certificate.validTo() != null) json.addProperty("validTo", certificate.validTo().toString());
        json.addProperty("hasPrivateKey", certificate.hasPrivateKey());
        json.addProperty("provider", certificate.provider());
        return json.toString();
    }

    private CryptoProCertificateInfo storedCertificate() {
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(certificateMetadata).getAsJsonObject();
            return new CryptoProCertificateInfo(
                    jsonString(json, "selector", signerCertificate),
                    jsonString(json, "thumbprint", signerCertificate),
                    jsonString(json, "subject", signerCertificate),
                    jsonString(json, "issuer", ""),
                    jsonString(json, "inn", ""),
                    jsonInstant(json, "validFrom"),
                    jsonInstant(json, "validTo"),
                    json.has("hasPrivateKey") && json.get("hasPrivateKey").getAsBoolean(),
                    jsonString(json, "provider", "CryptoPro"),
                    "");
        } catch (Exception ignored) {
            return new CryptoProCertificateInfo(signerCertificate, signerCertificate, signerCertificate,
                    "", "", null, null, false, "CryptoPro", "");
        }
    }

    private ListCell<CryptoProCertificateInfo> certificateCell(boolean disableExpired) {
        return new ListCell<>() {
            @Override protected void updateItem(CryptoProCertificateInfo certificate, boolean empty) {
                super.updateItem(certificate, empty);
                getStyleClass().removeAll("certificate-valid", "certificate-expired");
                setDisable(false);
                setText(empty || certificate == null ? null : certificateDisplay(certificate));
                setTooltip(empty || certificate == null ? null : new Tooltip(certificate.thumbprint()));
                if (!empty && certificate != null) {
                    boolean expired = certificate.expired(Instant.now());
                    getStyleClass().add(expired ? "certificate-expired" : "certificate-valid");
                    setDisable(disableExpired && expired);
                }
            }
        };
    }

    static List<CryptoProCertificateInfo> orderedCertificates(List<CryptoProCertificateInfo> certificates, Instant now) {
        return certificates.stream()
                .sorted(Comparator.comparing((CryptoProCertificateInfo certificate) -> certificate.expired(now))
                        .thenComparing(certificate -> value(certificate.ownerName()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(certificate -> value(certificate.selector()), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static boolean certificateSelectable(CryptoProCertificateInfo certificate, Instant now) {
        return certificate != null && !certificate.expired(now);
    }

    private static CryptoProCertificateInfo findCertificate(List<CryptoProCertificateInfo> certificates, String selector) {
        return certificates.stream().filter(certificate -> selector.equals(certificate.selector())).findFirst().orElse(null);
    }

    private boolean selectedCertificateExpired() {
        CryptoProCertificateInfo selected = signatureCertificateCombo.getValue();
        return selected != null && selected.expired(Instant.now());
    }

    private void invalidateCertificateDiscovery() {
        certificateDiscoveryGeneration++;
        certificateDiscoveryRunning = false;
    }

    private String certificateDisplay(CryptoProCertificateInfo certificate) {
        if (certificate == null) return "";
        StringBuilder display = new StringBuilder(value(certificate.ownerName()));
        if (!blank(certificate.inn())) display.append(" / INN ").append(certificate.inn());
        String expiry = certificate.validToDate() == null
                ? tr("znack.signature.expiry_unknown")
                : certificate.validToDate().format(CERTIFICATE_DATE);
        return display.append(" / ").append(tr("znack.signature.expires_on")).append(" ").append(expiry).toString();
    }

    private static String jsonString(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static Instant jsonInstant(JsonObject json, String key) {
        String value = jsonString(json, key, "");
        return value.isBlank() ? null : Instant.parse(value);
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
        String message = tr("znack.signature.error." + switch (error.code()) {
            case CRYPTOPRO_MISSING -> "cryptopro_missing";
            case CRYPTCP_MISSING -> "cryptcp_missing";
            case CRYPTCP_LICENSE_INVALID -> "cryptcp_license";
            case CERTMGR_MISSING -> "certmgr_missing";
            case CADESCOM_MISSING -> "cadescom_missing";
            case TOKEN_OR_CERTIFICATE_ABSENT -> "certificate_absent";
            case PRIVATE_KEY_UNAVAILABLE -> "private_key";
            case CERTIFICATE_EXPIRED -> "expired";
            case CANCELLED -> "cancelled";
            case TIMEOUT -> "timeout";
            case INVALID_SIGNATURE_OUTPUT -> "invalid_output";
            default -> "failed";
        });
        return error.code() == CryptoProErrorCode.SIGNING_FAILED
                ? withSignatureDetails(message, ZnackSanitizer.error(error)) : message;
    }

    private String withSignatureDetails(String message, String details) {
        String sanitized = ZnackSanitizer.message(details);
        return sanitized.isBlank() ? message : message + "\n\n" + tr("znack.signature.error.details") + ": " + sanitized;
    }

    private String signatureDiagnostic(CryptoProException error) {
        return "code=" + error.code() + "; " + ZnackSanitizer.error(error);
    }

    private void configureLogCopy() {
        MenuItem copy = new MenuItem(tr("common.copy"));
        copy.setOnAction(event -> copySelectedLog());
        ContextMenu menu = new ContextMenu(copy);
        menu.setOnShowing(event -> copy.setDisable(logsTable.getSelectionModel().getSelectedItem() == null));
        logsTable.setContextMenu(menu);
        logsTable.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) copySelectedLog();
        });
        logsTable.setRowFactory(ignored -> {
            TableRow<OperationLog> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) logsTable.getSelectionModel().select(row.getItem());
            });
            return row;
        });
    }

    private void copySelectedLog() {
        OperationLog selected = logsTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(logText(selected));
        Clipboard.getSystemClipboard().setContent(content);
    }

    static String logText(OperationLog log) {
        return "time=" + log.createdAt() + "\n"
                + "action=" + value(log.action()) + "\n"
                + "entity=" + value(log.entityReference()) + "\n"
                + "severity=" + value(log.severity()) + "\n"
                + "httpStatus=" + (log.httpStatus() == null ? "" : log.httpStatus()) + "\n"
                + "message=" + value(log.message());
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
