package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.oned.Code128Reader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FboBarcodePdfExporterTest {
    @TempDir Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void wbCombinedLabelsKeepScannableBarcodeAndUniqueKizOnEveryPage() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        FboProductSku product = new FboProductSku(
                101, "Q 6306", "Джинсы", "The Royal", "Джинсы", "синий", "28-171", "28-171",
                "2049505796566", "", true);
        List<String> codes = List.of(
                "010460123456789021SERIAL-0001\u001d91ABCD\u001d92SIGNATURE",
                "010460123456789021SERIAL-0002\u001d91ABCD\u001d92SIGNATURE");
        FboPrintPlan plan = new FboPrintPlan(List.of(
                FboPrintPage.combined(product, codes.get(0), 1),
                FboPrintPage.combined(product, codes.get(1), 2)), List.of());
        Path output = tempDir.resolve("wb-fbo-combined.pdf");

        new FboBarcodePdfExporter().exportPlan(plan, output.toFile());

        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(2, document.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                assertEquals(PrintTemplateService.PAGE_WIDTH, document.getPage(page).getMediaBox().getWidth(), 0.2d);
                assertEquals(PrintTemplateService.PAGE_HEIGHT, document.getPage(page).getMediaBox().getHeight(), 0.2d);
                assertTrue(pageText(document, page + 1).contains(product.sku()));
                assertTrue(pageText(document, page + 1).contains(product.vendorCode()));
                BufferedImage rendered = renderer.renderImageWithDPI(page, 300, ImageType.GRAY);
                int barcodeTop = (int) (rendered.getHeight() * 0.68);
                BufferedImage barcode = rendered.getSubimage(0, barcodeTop,
                        rendered.getWidth(), rendered.getHeight() - barcodeTop);
                BufferedImage kiz = rendered.getSubimage(0, 0,
                        (int) (rendered.getWidth() * 0.38), (int) (rendered.getHeight() * 0.62));
                assertEquals(product.sku(), new Code128Reader().decode(bitmap(barcode)).getText());
                assertEquals(codes.get(page),
                        KizService.scannerSafeCode(new DataMatrixReader().decode(bitmap(kiz)).getText()));
            }
        }
    }

    private static BinaryBitmap bitmap(BufferedImage image) {
        return new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
    }

    @Test
    void exportsTwoBarcodeLabelsThenOneSeparateKizLabelAt58x40() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        FboProductSku product = new FboProductSku(
                101, "ART-42", "Jacket", "Brand", "Jacket black", "Black", "42", "42",
                "4601234567890", "", true, "OZON-SKU-42");
        FboPrintPlan plan = new FboPrintPlan(List.of(
                FboPrintPage.barcode(product, 1),
                FboPrintPage.barcode(product, 1),
                FboPrintPage.kiz(product, "010460123456789021SERIAL-42", 1)), List.of());
        Path output = tempDir.resolve("ozon-fbo-58x40.pdf");

        new FboBarcodePdfExporter().exportPlan(plan, output.toFile());

        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            document.getPages().forEach(page -> {
                assertEquals(PrintTemplateService.PAGE_WIDTH, page.getMediaBox().getWidth(), 0.2d);
                assertEquals(PrintTemplateService.PAGE_HEIGHT, page.getMediaBox().getHeight(), 0.2d);
            });
            String first = pageText(document, 1);
            String second = pageText(document, 2);
            String third = pageText(document, 3);
            assertTrue(first.contains("4601234567890"));
            assertTrue(second.contains("4601234567890"));
            assertFalse(third.contains("4601234567890"));
            assertTrue(third.contains("ART-42"));
            assertTrue(third.contains("Jacket"));
        }
    }

    private static String pageText(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document).replaceAll("\\s+", " ").trim();
    }
}
