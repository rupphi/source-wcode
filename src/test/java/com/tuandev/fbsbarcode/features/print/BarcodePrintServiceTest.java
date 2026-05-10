package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.models.Order;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarcodePrintServiceTest {
    @Test
    void shouldExportTemplateAndStickerPdf() throws Exception {
        Order order = new Order();
        order.setId(123456L);
        order.setBrand("Brand");
        order.setName("Ao thun the thao");
        order.setSubjectName("Ao");
        order.setColor("Den");
        order.setArticle("ART-001");
        order.setSize("L");
        order.setBarcode("8938501432101");
        order.setSticker("ABCD 12");
        order.setStickerCode("WB-STICKER-CODE");
        order.setKiz("0104607061657218215sQ>GQj5L8qP91Q");

        Path file = Files.createTempFile("barcode-template-", ".pdf");
        new BarcodePrintService().export(new PrintTemplateService().createSystemDefaultTemplate("Test"), List.of(order), file.toFile());

        assertTrue(Files.size(file) > 0);
        try (PdfDocument pdf = new PdfDocument(new PdfReader(file.toFile()))) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(PrintTemplateService.PAGE_WIDTH, pdf.getPage(1).getPageSize().getWidth(), 0.2d);
            assertEquals(PrintTemplateService.PAGE_HEIGHT, pdf.getPage(1).getPageSize().getHeight(), 0.2d);
        }
        file.toFile().deleteOnExit();
    }
}
