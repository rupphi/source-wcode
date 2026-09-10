package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.models.Shop;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPickingListPdfExporterTest {

    @Test
    void postingSuffixExtractsCorrectTrailingSegments() {
        assertEquals("0625-1", OzonPickingListPdfExporter.postingSuffix("45818967-0625-1"));
        assertEquals("0258-3", OzonPickingListPdfExporter.postingSuffix("72825046-0258-3"));
        assertEquals("0744-1", OzonPickingListPdfExporter.postingSuffix("34152185-0744-1"));
        assertEquals("sub-1", OzonPickingListPdfExporter.postingSuffix("main-sub-1"));
        assertEquals("123-45", OzonPickingListPdfExporter.postingSuffix("123-45"));
        assertEquals("123456", OzonPickingListPdfExporter.postingSuffix("123456"));
        assertEquals("456789", OzonPickingListPdfExporter.postingSuffix("0123456789"));
        assertEquals("", OzonPickingListPdfExporter.postingSuffix(""));
        assertEquals("", OzonPickingListPdfExporter.postingSuffix(null));
    }

    @Test
    void exportsPdfWithSingleAndMultiItemPlans(@TempDir File tempDir) throws Exception {
        var shop = new Shop(1, "Test Shop", com.tuandev.fbsbarcode.integration.marketplace.Marketplace.OZON, "client", "key");

        var item1 = new OzonPostingItemDto(0, "sku-1", "sku-1", "OFFER-1", "Product 1", 1, "RUB", "100");
        var product1 = new OzonProductDto("p-1", "OFFER-1", "sku-1", "Product 1", "", "ART-1", "Đen", "M", false, "", List.of("BARCODE-1"));

        var item2 = new OzonPostingItemDto(1, "sku-2", "sku-2", "OFFER-2", "Product 2", 2, "RUB", "200");
        var product2 = new OzonProductDto("p-2", "OFFER-2", "sku-2", "Product 2", "", "ART-2", "Trắng", "L", false, "", List.of("BARCODE-2"));

        // Single item posting in order 1
        var posting1 = new OzonPostingDto("10000001-0001-1", "ORDER-1", "ORDER-1", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, List.of(item1));
        var plan1 = OzonPackingPlan.create(posting1, List.of(product1), List.of());

        // Multi-item posting in order 2
        var posting2 = new OzonPostingDto("20000002-0002-1", "ORDER-2", "ORDER-2", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, List.of(item1, item2));
        var plan2 = OzonPackingPlan.create(posting2, List.of(product1, product2), List.of());

        // Another posting sharing order 2 (split shipment / multi-package)
        var posting3 = new OzonPostingDto("20000002-0002-2", "ORDER-2", "ORDER-2", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, List.of(item1));
        var plan3 = OzonPackingPlan.create(posting3, List.of(product1), List.of());

        File targetPdf = new File(tempDir, "test_picking_list.pdf");
        var exporter = new OzonPickingListPdfExporter();
        exporter.exportPlans(targetPdf, shop, List.of(plan1, plan2, plan3));

        assertTrue(targetPdf.exists());
        assertTrue(Files.size(targetPdf.toPath()) > 1000);
    }
}
