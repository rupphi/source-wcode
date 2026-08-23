package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.print.history.ImageCacheRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPrintBundleServiceTest {
    private static final float LABEL_WIDTH = (float) (58 * 72d / 25.4d);
    private static final float LABEL_HEIGHT = (float) (40 * 72d / 25.4d);
    private static final String RAW_KIZ = "010464558878115421SERIAL-0001\u001d91ABCD\u001d92SIGNATURE";

    @TempDir
    Path temporaryDirectory;

    private Shop shop;
    private Path officialPdf;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", temporaryDirectory.resolve("appdata").toString());
        Database.initDatabase();
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,?,?,?)")) {
            statement.setString(1, shop.getName());
            statement.setString(2, Marketplace.OZON.name());
            statement.setString(3, shop.getClientId());
            statement.setString(4, shop.getApiKey());
            statement.executeUpdate();
        }
        officialPdf = temporaryDirectory.resolve("official.pdf");
        writeOfficialTwoPagePdf(officialPdf);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void acceptedSingleUnitProducesThreeLabelPagesAndSeparatePickingPdf() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        AtomicBoolean prepared = new AtomicBoolean(false);
        Path labels = temporaryDirectory.resolve("OZON-POST-1.pdf");
        Path picking = temporaryDirectory.resolve("OZON-POST-1-picking.pdf");

        OzonPrintBundleService.ExportResult result = service(prepared).export(
                shop, "POST-1", labels.toFile(), picking.toFile());

        assertFalse(prepared.get(), "An accepted durable job must not push KIZ again while reprinting");
        assertEquals(2, result.officialPages());
        assertEquals(1, result.kizPages());
        assertEquals(3, result.totalPages());
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            assertPageMillimeters(document, 0, 58, 40);
            assertPageMillimeters(document, 1, 58, 40);
            assertPageMillimeters(document, 2, 58, 40);
            Result matrix = decodeRenderedDataMatrixResult(document, 2, 300);
            assertEquals(KizService.scannerSafeCode(RAW_KIZ), KizService.scannerSafeCode(matrix.getText()));
            assertEquals("]d2", matrix.getResultMetadata().get(ResultMetadataType.SYMBOLOGY_IDENTIFIER));
            String kizText = pageText(document, 2);
            assertFalse(kizText.contains(shop.getName()), "The KIZ label must not print a shop/brand heading");
            assertFalse(kizText.contains("OZON - KIZ"));
            assertFalse(kizText.contains("KIZ accepted by Ozon"));
            assertFalse(kizText.contains("Item "), "The KIZ label must not print item counters");
            assertFalse(kizText.contains("Unit "), "The KIZ label must not print unit counters");
            assertFalse(pageOperators(document, 2).contains("S"), "The KIZ page must not stroke an outer border");
            assertEquals(0, imageCount(document, 2), "The DataMatrix must be vector content, not a bitmap image");
        }
        try (PDDocument document = Loader.loadPDF(picking.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("OZON FBS - PICKING LIST"));
            assertFalse(text.contains("POST-1"));
            assertFalse(text.contains("ORDER-1"));
            assertFalse(text.contains("Posting:"));
            assertFalse(text.contains("Shop:"));
            assertFalse(text.contains("Shipment:"));
            assertFalse(text.contains("Printed:"));
            assertFalse(text.contains("Raw KIZ values are intentionally omitted"));
            assertFalse(text.contains("KIZ"));
            assertTrue(text.indexOf("Image") < text.indexOf("Product"));
            assertTrue(text.contains("SKU-3583"));
            assertTrue(text.contains("offer-black-176"));
            assertTrue(text.contains("1"));
            assertFalse(text.contains(RAW_KIZ), "The picking list must not expose raw KIZ");
            assertTrue(imageCount(document, 0) >= 1, "The picking list must embed the catalog product image");
        }
    }

    @Test
    void appendsOneKizPageForEveryAcceptedExemplar() throws Exception {
        String secondKiz = "010464558878115421SERIAL-0002\u001d91ABCD\u001d92SIGNATURE";
        seedPosting(2);
        seedAcceptedExemplars(List.of(RAW_KIZ, secondKiz));
        Path labels = temporaryDirectory.resolve("labels-two.pdf");
        Path picking = temporaryDirectory.resolve("picking-two.pdf");

        OzonPrintBundleService.ExportResult result = service(new AtomicBoolean()).export(
                shop, "POST-1", labels.toFile(), picking.toFile());

        assertEquals(4, result.totalPages());
        assertEquals(2, result.kizPages());
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(KizService.scannerSafeCode(RAW_KIZ),
                    KizService.scannerSafeCode(decodeRenderedDataMatrixResult(document, 2, 300).getText()));
            assertEquals(KizService.scannerSafeCode(secondKiz),
                    KizService.scannerSafeCode(decodeRenderedDataMatrixResult(document, 3, 300).getText()));
        }
    }

    @Test
    void refusesToPublishWhenAutomaticPreparationDoesNotReachAccepted() throws Exception {
        seedPosting(1);
        Path labels = temporaryDirectory.resolve("blocked-labels.pdf");
        Path picking = temporaryDirectory.resolve("blocked-picking.pdf");
        AtomicBoolean labelDownloaded = new AtomicBoolean(false);
        OzonPrintBundleService service = new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) ->
                        new OzonPreparationResult(postingNumber, "VERIFYING", 1, false, false, ""),
                (selectedShop, postingNumber, target) -> {
                    labelDownloaded.set(true);
                    return target;
                });

        IOException failure = assertThrows(IOException.class, () ->
                service.export(shop, "POST-1", labels.toFile(), picking.toFile()));

        assertTrue(failure.getMessage().contains("not accepted"));
        assertFalse(labelDownloaded.get());
        assertFalse(Files.exists(labels));
        assertFalse(Files.exists(picking));
    }

    @Test
    void mandatoryMarkCanNeverBeDowngradedToNotRequiredByThePrintFlow() throws Exception {
        seedPosting(1, true);
        Path labels = temporaryDirectory.resolve("mandatory-labels.pdf");
        Path picking = temporaryDirectory.resolve("mandatory-picking.pdf");
        OzonPrintBundleService service = new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) ->
                        new OzonPreparationResult(postingNumber, "NOT_REQUIRED", 0, false, false, ""),
                (selectedShop, postingNumber, target) -> {
                    throw new AssertionError("mandatory KIZ must block before label download");
                });

        IOException failure = assertThrows(IOException.class, () ->
                service.export(shop, "POST-1", labels.toFile(), picking.toFile()));

        assertTrue(failure.getMessage().contains("mandatory"));
        assertFalse(Files.exists(labels));
        assertFalse(Files.exists(picking));
    }

    private OzonPrintBundleService service(AtomicBoolean prepared) {
        return new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) -> {
                    prepared.set(true);
                    throw new AssertionError("accepted jobs must not prepare again");
                },
                (selectedShop, postingNumber, target) -> {
                    Files.copy(officialPdf, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return target;
                });
    }

    private void seedPosting(int quantity) {
        seedPosting(quantity, false);
    }

    private void seedPosting(int quantity, boolean mandatory) {
        new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(
                "POST-1", "ORDER-1", "100001", "awaiting_deliver", "posting_transferring_to_delivery",
                "warehouse-1", "2026-08-19T10:00:00Z", "2026-08-19T09:00:00Z", "LOWER", "UPPER",
                new OzonRequirements(
                        mandatory ? List.of("1001") : List.of(),
                        mandatory ? List.of() : List.of("1001"),
                        List.of()),
                List.of(), false,
                List.of(new OzonPostingItemDto(
                        0, "1001", "SKU-3583", "offer-black-176", "Men's sports suit", quantity, "RUB", "1990"))));
        String imageUrl = "https://example.invalid/ozon-product.png";
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto(
                "1001", "offer-black-176", "SKU-3583", "Men's sports suit", imageUrl,
                false, "2026-08-19T00:00:00Z", List.of())), "");
        new ImageCacheRepository().saveImage(
                PrintHistoryService.imageCacheKey(imageUrl), imageUrl, sampleProductPng(), "image/png");
    }

    private void seedAcceptedExemplars(List<String> rawCodes) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'04645588781154','Suit','2026-08-19T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,external_order_id,gtin,quantity,remote_status,"
                    + "local_status,created_at,updated_at) VALUES(1,1,'KIZ-ORDER','04645588781154',"
                    + rawCodes.size() + ",'ready','COMPLETED','2026-08-19T00:00:00Z','2026-08-19T00:00:00Z')");
            statement.execute("INSERT INTO ozon_exemplar_jobs(id,shop_id,posting_number,stage,created_at,updated_at) "
                    + "VALUES(51,1,'POST-1','ACCEPTED','2026-08-19T00:00:00Z','2026-08-19T00:00:00Z')");
        }
        for (int index = 0; index < rawCodes.size(); index++) {
            long kizId = 100 + index;
            try (Connection connection = Database.getConnection();
                    PreparedStatement kiz = connection.prepareStatement("""
                            INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,
                                created_at,updated_at) VALUES(?,?,?,?,?,'04645588781154','CONSUMED',?,?)
                            """);
                    PreparedStatement exemplar = connection.prepareStatement("""
                            INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,
                                exemplar_id,exemplar_index,kiz_id,check_status,updated_at)
                            VALUES(51,1,'POST-1',0,'1001',?,?,?,'passed',?)
                            """)) {
                kiz.setLong(1, kizId);
                kiz.setInt(2, 1);
                kiz.setInt(3, 1);
                kiz.setString(4, rawCodes.get(index));
                kiz.setString(5, "KIZ-" + index);
                kiz.setString(6, "2026-08-19T00:00:00Z");
                kiz.setString(7, "2026-08-19T00:00:00Z");
                kiz.executeUpdate();
                exemplar.setString(1, "700" + index);
                exemplar.setInt(2, index);
                exemplar.setLong(3, kizId);
                exemplar.setString(4, "2026-08-19T00:00:00Z");
                exemplar.executeUpdate();
            }
        }
    }

    private static void writeOfficialTwoPagePdf(Path target) throws IOException {
        try (PdfDocument document = new PdfDocument(new PdfWriter(target.toFile()))) {
            document.addNewPage(new PageSize(LABEL_WIDTH, LABEL_HEIGHT));
            document.addNewPage(new PageSize(LABEL_WIDTH, LABEL_HEIGHT));
        }
    }

    private static void assertPageMillimeters(
            PDDocument document, int pageIndex, double expectedWidth, double expectedHeight) {
        double width = document.getPage(pageIndex).getMediaBox().getWidth() * 25.4 / 72;
        double height = document.getPage(pageIndex).getMediaBox().getHeight() * 25.4 / 72;
        assertEquals(expectedWidth, width, 0.2);
        assertEquals(expectedHeight, height, 0.2);
    }

    private static Result decodeRenderedDataMatrixResult(
            PDDocument document, int pageIndex, float dpi) throws Exception {
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        float scale = dpi / 72f;
        int margin = Math.round(3 * scale);
        int x = Math.max(0, Math.round(7 * scale) - margin);
        int y = Math.max(0, Math.round((LABEL_HEIGHT - 25 - 62) * scale) - margin);
        int side = Math.min(
                Math.round(62 * scale) + margin * 2,
                Math.min(page.getWidth() - x, page.getHeight() - y));
        BufferedImage barcode = page.getSubimage(x, y, side, side);
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(barcode)));
        return new DataMatrixReader().decode(
                bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
    }

    private static int imageCount(PDDocument document, int pageIndex) throws IOException {
        int count = 0;
        for (COSName name : document.getPage(pageIndex).getResources().getXObjectNames()) {
            if (document.getPage(pageIndex).getResources().getXObject(name) instanceof PDImageXObject) count++;
        }
        return count;
    }

    private static List<String> pageOperators(PDDocument document, int pageIndex) throws IOException {
        return new PDFStreamParser(document.getPage(pageIndex)).parse().stream()
                .filter(Operator.class::isInstance)
                .map(Operator.class::cast)
                .map(Operator::getName)
                .toList();
    }

    private static String pageText(PDDocument document, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(document);
    }

    private static byte[] sampleProductPng() {
        try {
            BufferedImage image = new BufferedImage(80, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 80, 100);
            graphics.setColor(new Color(25, 80, 160));
            graphics.fillRect(12, 10, 56, 80);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
