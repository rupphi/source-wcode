package com.tuandev.fbsbarcode.ui;

import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
import com.tuandev.fbsbarcode.ui.history.PrintHistoryController;
import com.tuandev.fbsbarcode.ui.kiz.CategoryDialogController;
import com.tuandev.fbsbarcode.ui.kiz.CategoryItemController;
import com.tuandev.fbsbarcode.ui.kiz.KizPanelController;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlSmokeTest {
    @BeforeAll
    static void initToolkit() throws Exception {
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

    @Test
    void shouldLoadAllPrimaryViews() throws Exception {
        assertLoads(HomeController.class, "home-view.fxml");
        assertLoads(ShopSidebarController.class, "shop-sidebar-view.fxml");
        assertLoads(WorkspaceHeaderController.class, "workspace-header-view.fxml");
        assertLoads(SupplyListController.class, "supply-list-view.fxml");
        assertLoads(SupplyDetailController.class, "supply-detail-view.fxml");
        assertLoads(PackingController.class, "packing-view.fxml");
        assertLoads(PrintHistoryController.class, "print-history-view.fxml");
        assertLoads(KizPanelController.class, "kiz-panel-view.fxml");
        assertLoads(PrintTemplateDesignerController.class, "print-template-designer-view.fxml");
        assertLoads(ShopDialogController.class, "shop-dialog.fxml");
        assertLoads(CategoryDialogController.class, "category-dialog.fxml");
        assertLoads(CategoryItemController.class, "category-item.fxml");
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
