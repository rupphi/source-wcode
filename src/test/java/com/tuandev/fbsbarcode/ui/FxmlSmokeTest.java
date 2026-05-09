package com.tuandev.fbsbarcode.ui;

import com.tuandev.fbsbarcode.shared.FxmlViewLoader;
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
        assertLoads("home-view.fxml");
        assertLoads("shop-sidebar-view.fxml");
        assertLoads("workspace-header-view.fxml");
        assertLoads("supply-list-view.fxml");
        assertLoads("supply-detail-view.fxml");
        assertLoads("kiz-panel-view.fxml");
        assertLoads("shop-dialog.fxml");
        assertLoads("category-dialog.fxml");
        assertLoads("category-item.fxml");
    }

    private void assertLoads(String resourceName) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean loaded = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = FxmlViewLoader.loader(FxmlSmokeTest.class, resourceName);
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
        assertTrue(loaded.get() && !failed.get(), "Failed to load " + resourceName);
    }
}
