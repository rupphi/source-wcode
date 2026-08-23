package com.tuandev.fbsbarcode.ui;

import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.ui.history.PrintHistoryController;
import com.tuandev.fbsbarcode.ui.dashboard.DashboardController;
import com.tuandev.fbsbarcode.ui.kizmapping.KizMappingController;
import com.tuandev.fbsbarcode.ui.ozon.OzonDashboardController;
import com.tuandev.fbsbarcode.ui.packing.PackingController;
import com.tuandev.fbsbarcode.ui.print.PrintTemplateDesignerController;
import com.tuandev.fbsbarcode.ui.shop.ShopDialogController;
import com.tuandev.fbsbarcode.ui.shop.ShopSidebarController;
import com.tuandev.fbsbarcode.ui.supply.SupplyDetailController;
import com.tuandev.fbsbarcode.ui.supply.SupplyListController;
import com.tuandev.fbsbarcode.ui.workspace.HomeController;
import com.tuandev.fbsbarcode.ui.workspace.WorkspaceHeaderController;
import com.tuandev.fbsbarcode.ui.znack.ZnackAutomationController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlSmokeTest {
    @TempDir
    static Path appDataDir;

    @BeforeAll
    static void initToolkit() throws Exception {
        System.setProperty("wcode.appdata.dir", appDataDir.toString());
        AtomicBoolean started = new AtomicBoolean(false);
        try {
            Platform.startup(() -> started.set(true));
        } catch (IllegalStateException alreadyStarted) {
            started.set(true);
        }
        if (!started.get()) {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT OR IGNORE INTO shops(id,name,api_key) VALUES(1,'Shop A','a')");
        }
    }

    @AfterAll
    static void clearAppDataOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldLoadAllPrimaryViews() throws Exception {
        assertLoads(HomeController.class, "home-view.fxml");
        assertLoads(DashboardController.class, "dashboard-view.fxml");
        assertLoads(ShopSidebarController.class, "shop-sidebar-view.fxml");
        assertLoads(WorkspaceHeaderController.class, "workspace-header-view.fxml");
        assertLoads(SupplyListController.class, "supply-list-view.fxml");
        assertLoads(SupplyDetailController.class, "supply-detail-view.fxml");
        assertLoads(PackingController.class, "packing-view.fxml");
        assertLoads(PrintHistoryController.class, "print-history-view.fxml");
        assertLoads(KizMappingController.class, "kiz-mapping-view.fxml");
        assertLoads(ZnackAutomationController.class, "znack-automation-view.fxml");
        assertLoads(PrintTemplateDesignerController.class, "print-template-designer-view.fxml");
        assertLoads(ShopDialogController.class, "shop-dialog.fxml");
        assertLoads(OzonDashboardController.class, "ozon-dashboard-view.fxml");
    }

    @Test
    void editingOzonShopKeepsMarketplaceImmutableAndSecretWriteOnly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
                FxmlViewLoader.load(loader);
                ShopDialogController controller = loader.getController();
                controller.setShop(new Shop(
                        7,
                        "Ozon shop",
                        com.tuandev.fbsbarcode.integration.marketplace.Marketplace.OZON,
                        "client-7",
                        "must-not-be-prefilled"));
                ComboBox<?> marketplace = (ComboBox<?>) loader.getNamespace().get("marketplaceField");
                TextField clientId = (TextField) loader.getNamespace().get("clientIdField");
                PasswordField apiKey = (PasswordField) loader.getNamespace().get("apiKeyField");
                valid.set(marketplace.isDisabled()
                        && clientId.isVisible()
                        && "client-7".equals(clientId.getText())
                        && apiKey.getText().isEmpty()
                        && controller.validate());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon edit must lock marketplace and never prefill its API key");
    }

    @Test
    void createShopDialogPlacesMarketplaceBeforeShopName() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
                VBox root = FxmlViewLoader.load(loader);
                ComboBox<?> marketplace = (ComboBox<?>) loader.getNamespace().get("marketplaceField");
                TextField name = (TextField) loader.getNamespace().get("nameField");
                valid.set(root.getChildren().indexOf(marketplace) < root.getChildren().indexOf(name));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Marketplace must be selected before the shop name is entered");
    }

    @Test
    void sidebarShouldExposeNavigationButtons() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean found = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                Parent root = FxmlViewLoader.load(loader);
                found.set(root.lookup("#dashboardButton") != null
                        && root.lookup("#packingButton") != null
                        && root.lookup("#kizMappingButton") != null
                        && root.lookup("#znackAutomationButton") != null);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(found.get(), "Sidebar should contain the primary navigation buttons");
    }

    @Test
    void ozonSidebarKeepsPackingAndCatalogMappingAvailable() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                FxmlViewLoader.load(loader);
                ShopSidebarController controller = loader.getController();
                controller.setMarketplace(com.tuandev.fbsbarcode.integration.marketplace.Marketplace.OZON);
                Button packing = (Button) loader.getNamespace().get("packingButton");
                Button mapping = (Button) loader.getNamespace().get("kizMappingButton");
                Button fbo = (Button) loader.getNamespace().get("fboPackingButton");
                valid.set(packing.isVisible() && packing.isManaged() && !packing.isDisabled()
                        && mapping.isVisible() && mapping.isManaged() && !mapping.isDisabled()
                        && !fbo.isVisible() && !fbo.isManaged() && fbo.isDisabled());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon must expose its packing queue and SKU-to-GTIN catalog mapping");
    }

    @Test
    void supplyDetailShouldExposeZnackGtinInventoryPane() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(SupplyDetailController.class, "supply-detail-view.fxml");
                FxmlViewLoader.load(loader);
                valid.set(loader.getNamespace().get("gtinInventoryPane") != null
                        && loader.getNamespace().get("gtinInventoryList") != null
                        && loader.getNamespace().get("gtinInventoryRefreshButton") != null
                        && loader.getNamespace().get("gtinInventoryLoading") != null
                        && loader.getNamespace().get("gtinInventoryEmptyLabel") != null);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Supply detail should expose the Znack GTIN inventory pane");
    }

    @Test
    void ozonDashboardShouldExposePostingActionsAndSkuGtinMapping() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
                FxmlViewLoader.load(loader);
                ComboBox<?> status = (ComboBox<?>) loader.getNamespace().get("statusFilter");
                valid.set(loader.getNamespace().get("postingList") != null
                        && loader.getNamespace().get("itemList") != null
                        && loader.getNamespace().get("exemplarList") != null
                        && loader.getNamespace().get("gtinField") != null
                        && loader.getNamespace().get("saveMappingButton") != null
                        && loader.getNamespace().get("prepareButton") != null
                        && loader.getNamespace().get("shipButton") != null
                        && loader.getNamespace().get("labelButton") != null
                        && "ACTIVE".equals(status.getValue()));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Ozon dashboard should expose mapping, prepare, ship and label controls");
    }

    @Test
    void reopeningSameOzonDashboardWhileBusyPreservesTheActiveRequestToken() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
                FxmlViewLoader.load(loader);
                OzonDashboardController controller = loader.getController();
                Shop first = new Shop(7, "Ozon", com.tuandev.fbsbarcode.integration.marketplace.Marketplace.OZON,
                        "client-7", "secret-7");
                Shop sameContext = new Shop(7, "Ozon renamed",
                        com.tuandev.fbsbarcode.integration.marketplace.Marketplace.OZON,
                        "client-7", "secret-7");
                controller.setShop(first, false);
                var busyMethod = OzonDashboardController.class.getDeclaredMethod("setBusy", boolean.class);
                busyMethod.setAccessible(true);
                var tokenField = OzonDashboardController.class.getDeclaredField("requestToken");
                tokenField.setAccessible(true);
                busyMethod.invoke(controller, true);
                long activeToken = tokenField.getLong(controller);
                controller.setShop(sameContext, false);
                ProgressIndicator indicator = (ProgressIndicator) loader.getNamespace().get("loadingIndicator");
                valid.set(activeToken == tokenField.getLong(controller) && indicator.isVisible());
                busyMethod.invoke(controller, false);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get(), "Reopening the same shop must not orphan a running sync and leave its spinner stuck");
    }

    @Test
    void znackSettingsShouldExposeOnlyBasicWorkflowAndEnableSaveAfterChange() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(ZnackAutomationController.class, "znack-automation-view.fxml");
                FxmlViewLoader.load(loader);
                ZnackAutomationController controller = loader.getController();
                controller.setShop(new Shop(1, "Shop A", "a"));
                Button save = (Button) loader.getNamespace().get("saveButton");
                Button omsIdHelp = (Button) loader.getNamespace().get("omsIdHelpButton");
                Button omsConnectionHelp = (Button) loader.getNamespace().get("omsConnectionHelpButton");
                Button closeOmsHelp = (Button) loader.getNamespace().get("closeOmsHelpButton");
                VBox omsHelpPane = (VBox) loader.getNamespace().get("omsHelpPane");
                Label omsHelpTitle = (Label) loader.getNamespace().get("omsHelpTitleLabel");
                TextField omsConnection = (TextField) loader.getNamespace().get("omsConnectionField");
                ComboBox<?> signatureCertificate = (ComboBox<?>) loader.getNamespace().get("signatureCertificateCombo");
                boolean initiallyDisabled = save.isDisabled();
                boolean helpInitiallyHidden = !omsHelpPane.isVisible() && !omsHelpPane.isManaged();
                omsIdHelp.fire();
                boolean omsIdHelpShown = omsHelpPane.isVisible() && omsHelpPane.isManaged()
                        && omsHelpTitle.getText().contains("omsId");
                omsConnectionHelp.fire();
                boolean omsConnectionHelpShown = omsHelpPane.isVisible()
                        && omsHelpTitle.getText().contains("omsConnection");
                closeOmsHelp.fire();
                boolean helpClosed = !omsHelpPane.isVisible() && !omsHelpPane.isManaged();
                omsConnection.setText("changed-connection");
                valid.set(loader.getNamespace().get("basicSettingsCard") != null
                        && loader.getNamespace().get("advancedSettingsPane") == null
                        && loader.getNamespace().get("omsConnectionField") != null
                        && signatureCertificate != null
                        && signatureCertificate.getOnShowing() != null
                        && loader.getNamespace().get("refreshCertificatesButton") == null
                        && loader.getNamespace().get("testSignatureButton") != null
                        && loader.getNamespace().get("documentNumberField") != null
                        && loader.getNamespace().get("trueApiUrlField") == null
                        && loader.getNamespace().get("omsIdField") != null
                        && helpInitiallyHidden && omsIdHelpShown && omsConnectionHelpShown && helpClosed
                        && loader.getNamespace().get("authenticateButton") == null
                        && initiallyDisabled && !save.isDisabled());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(valid.get());
    }

    private void assertLoads(Class<?> resourceOwner, String resourceName) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean loaded = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(resourceOwner, resourceName);
                Object root = FxmlViewLoader.load(loader);
                assertNotNull(root);
                loaded.set(true);
            } catch (Throwable ex) {
                failed.set(true);
                throw ex;
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(loaded.get() && !failed.get(), "Failed to load " + resourceOwner.getSimpleName() + "/" + resourceName);
    }
}
