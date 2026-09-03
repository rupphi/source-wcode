package com.tuandev.fbsbarcode.features.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.models.Kiz;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZnackKizExportServiceTest {
    private static final String GTIN = "04601234567890";
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", temporaryDirectory.resolve("data").toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'" + GTIN + "','Sports pants','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) "
                    + "VALUES(1,1,'" + GTIN + "',3,'INTRODUCED','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,subject_name,need_kiz,synced_at) "
                    + "VALUES(1,101,'Pants',1,'2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) "
                    + "VALUES(1,101,204557,'Gender','[\"Female\"]')");
            statement.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size,wb_size) "
                    + "VALUES(1,1001,101,'M','44')");
            statement.execute("INSERT INTO znack_gtin_mapping_rules(shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at) "
                    + "VALUES(1,'" + GTIN + "','Pants','Female',0,'2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            for (int id = 1; id <= 3; id++) {
                String raw = "01" + GTIN + "21SERIAL" + id + "\u001D91EE11\u001D92signature" + id;
                statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) "
                        + "VALUES(" + id + ",1,1,'" + raw + "','KIZ-" + id + "','" + GTIN
                        + "','AVAILABLE','IN_CIRCULATION','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            }
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void exportsOnePhysicalLabelPerKizAndConsumesOnlyExportedInventory() throws Exception {
        String preview = System.getProperty("wcode.kiz.preview");
        Path output = preview == null || preview.isBlank()
                ? temporaryDirectory.resolve("kiz-labels.pdf") : Path.of(preview);
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        ZnackKizExportService.ExportResult result = new ZnackKizExportService()
                .export(1, GTIN, 2, output.toFile());

        assertEquals(2, result.pageCount());
        assertEquals(2, count("status='CONSUMED'"));
        assertEquals(1, count("status='AVAILABLE'"));
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(2, document.getNumberOfPages());
            assertEquals((float) PrintTemplateService.PAGE_WIDTH,
                    document.getPage(0).getMediaBox().getWidth(), 0.1f);
            assertEquals((float) PrintTemplateService.PAGE_HEIGHT,
                    document.getPage(0).getMediaBox().getHeight(), 0.1f);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Sports pants"));
            assertTrue(text.contains("Female"));
            assertTrue(text.contains("M"));
        }
    }

    @Test
    void generationFailureReleasesReservationAndLeavesNoPublishedFile() {
        ZnackKizLabelPdfExporter failing = new ZnackKizLabelPdfExporter() {
            @Override public void write(List<Kiz> codes,
                                        com.tuandev.fbsbarcode.features.kizmapping.ZnackKizLabelMetadata metadata,
                                        File target) throws IOException {
                throw new IOException("simulated PDF failure");
            }
        };
        ZnackKizExportService service = new ZnackKizExportService(
                new ZnackGtinInventoryService(), new KizMappingRepository(), failing);
        Path output = temporaryDirectory.resolve("failed.pdf");

        assertThrows(IOException.class, () -> service.export(1, GTIN, 2, output.toFile()));

        assertEquals(3, count("status='AVAILABLE' AND reservation_token IS NULL"));
        assertFalse(Files.exists(output));
    }

    @Test
    void insufficientQuantityDoesNotMutateInventoryOrCreateAFile() {
        Path output = temporaryDirectory.resolve("too-many.pdf");

        assertThrows(IllegalStateException.class,
                () -> new ZnackKizExportService().export(1, GTIN, 4, output.toFile()));

        assertEquals(3, count("status='AVAILABLE' AND reservation_token IS NULL"));
        assertFalse(Files.exists(output));
    }

    private int count(String predicate) {
        try (Connection connection = Database.getConnection(); ResultSet result = connection.createStatement()
                .executeQuery("SELECT COUNT(*) FROM kiz_codes WHERE " + predicate)) {
            return result.next() ? result.getInt(1) : 0;
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
