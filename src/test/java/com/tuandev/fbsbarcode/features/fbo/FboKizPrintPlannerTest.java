package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FboKizPrintPlannerTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldCreateTwoBarcodePagesWithSameKizPerQuantity() throws Exception {
        initializeFixture();
        FboKizPrintPlanner planner = new FboKizPrintPlanner();
        FboProductSku product = product(true);

        FboPrintPlan plan = planner.plan(1, List.of(new FboBarcodePrintItem(product, 2)));

        assertEquals(4, plan.pages().size());
        assertEquals(List.of("KIZ-1", "KIZ-1", "KIZ-2", "KIZ-2"),
                plan.pages().stream().map(FboPrintPage::kizCode).toList());
        assertEquals(List.of(1, 1, 2, 2), plan.pages().stream().map(FboPrintPage::pairNumber).toList());
        assertEquals(List.of("KIZ-1", "KIZ-2"), plan.usedKizs().stream().map(com.tuandev.fbsbarcode.models.Kiz::getCode).toList());
    }

    @Test
    void shouldSkipKizForProductThatDoesNotRequireIt() throws Exception {
        initializeFixture();
        FboKizPrintPlanner planner = new FboKizPrintPlanner();

        FboPrintPlan plan = planner.plan(1, List.of(new FboBarcodePrintItem(product(false), 2)));

        assertEquals(4, plan.pages().size());
        assertTrue(plan.pages().stream().allMatch(page -> page.kizCode() == null));
        assertEquals(List.of(1, 1, 2, 2), plan.pages().stream().map(FboPrintPage::pairNumber).toList());
        assertEquals(0, plan.usedKizs().size());
    }

    @Test
    void shouldExportOnlyTwoPagesForOneKizPair() throws Exception {
        initializeFixture();
        FboProductSku product = product(true);
        FboPrintPlan plan = new FboPrintPlan(List.of(
                FboPrintPage.barcodeWithKiz(product, "KIZ-1", 1),
                FboPrintPage.barcodeWithKiz(product, "KIZ-1", 1)
        ), List.of());
        Path file = Files.createTempFile(tempDir, "fbo-kiz-pair-", ".pdf");

        new FboBarcodePdfExporter().exportPlan(plan, file.toFile());

        try (PdfDocument pdf = new PdfDocument(new PdfReader(file.toFile()))) {
            assertEquals(2, pdf.getNumberOfPages());
        }
    }

    @Test
    void shouldKeepFboTemplatesSeparateFromFbsTemplates() throws Exception {
        initializeFixture();

        com.tuandev.fbsbarcode.features.print.PrintTemplate fbsTemplate =
                new com.tuandev.fbsbarcode.features.print.PrintTemplateService().getDefaultTemplate();
        com.tuandev.fbsbarcode.features.print.PrintTemplate fboTemplate =
                new FboPrintTemplateService().getDefaultTemplate();

        assertEquals(1, new com.tuandev.fbsbarcode.features.print.PrintTemplateRepository().count());
        assertEquals(1, new FboPrintTemplateRepository().count());
        org.junit.jupiter.api.Assertions.assertFalse(fbsTemplate.getElements().isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(fboTemplate.getElements().isEmpty());
    }

    @Test
    void shouldRejectMissingMapping() throws Exception {
        initializeFixture(false, 2);
        FboKizPrintPlanner planner = new FboKizPrintPlanner();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> planner.plan(1, List.of(new FboBarcodePrintItem(product(true), 1))));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("chưa map"));
    }

    @Test
    void shouldRejectKizShortageWithCategoryName() throws Exception {
        initializeFixture(true, 1);
        FboKizPrintPlanner planner = new FboKizPrintPlanner();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> planner.plan(1, List.of(new FboBarcodePrintItem(product(true), 2))));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("10 - Shoes: cần 2, còn 1"));
    }

    private void initializeFixture() throws Exception {
        initializeFixture(true, 3);
    }

    private void initializeFixture(boolean withMapping, int kizCount) throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO categories(id, name) VALUES (10, 'Shoes')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, need_kiz, synced_at)
                    VALUES (1, 1001, 'ART-1', 'Shoes WB', 'Brand', 'Product', 1, '2026-05-23T00:00:00Z')
                    """);
            if (withMapping) {
                st.execute("INSERT INTO wb_product_kiz_mappings(shop_id, nm_id, kiz_category_id, updated_at) VALUES (1, 1001, 10, 'now')");
            }
        }
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO kizs(code, shop_id, category_id) VALUES (?, 1, 10)")) {
            for (int i = 1; i <= kizCount; i++) {
                ps.setString(1, "KIZ-" + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private FboProductSku product(boolean requiresKiz) {
        return new FboProductSku(1001, "ART-1", "Shoes WB", "Brand", "Product", "Black", "42", "SKU-1", "", requiresKiz);
    }
}
