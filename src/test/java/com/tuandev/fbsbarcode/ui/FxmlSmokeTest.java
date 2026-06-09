package com.tuandev.fbsbarcode.ui;

import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.ui.history.PrintHistoryController;
import com.tuandev.fbsbarcode.ui.dashboard.DashboardController;
import com.tuandev.fbsbarcode.ui.kiz.CategoryDialogController;
import com.tuandev.fbsbarcode.ui.kiz.CategoryItemController;
import com.tuandev.fbsbarcode.ui.kiz.KizPanelController;
import com.tuandev.fbsbarcode.ui.kizmapping.KizMappingController;
import com.tuandev.fbsbarcode.ui.packing.PackingController;
import com.tuandev.fbsbarcode.ui.print.PrintTemplateDesignerController;
import com.tuandev.fbsbarcode.ui.shop.ShopDialogController;
import com.tuandev.fbsbarcode.ui.shop.ShopSidebarController;
import com.tuandev.fbsbarcode.ui.supply.SupplyDetailController;
import com.tuandev.fbsbarcode.ui.supply.SupplyListController;
import com.tuandev.fbsbarcode.ui.workspace.HomeController;
import com.tuandev.fbsbarcode.ui.workspace.WorkspaceHeaderController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
        assertLoads(KizPanelController.class, "kiz-panel-view.fxml");
        assertLoads(KizMappingController.class, "kiz-mapping-view.fxml");
        assertLoads(PrintTemplateDesignerController.class, "print-template-designer-view.fxml");
        assertLoads(ShopDialogController.class, "shop-dialog.fxml");
        assertLoads(CategoryDialogController.class, "category-dialog.fxml");
        assertLoads(CategoryItemController.class, "category-item.fxml");
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
                        && root.lookup("#kizMappingButton") != null);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(found.get(), "Sidebar should contain the primary navigation buttons");
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
