package com.tuandev.fbsbarcode.features.print.history;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.print.OrderExportWorkflow;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintHistoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldRecordAndReprintSnapshot() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        try {
            Database.initDatabase();
            ShopRepository shopRepository = new ShopRepository();
            Shop shop = new Shop("history-test-" + System.nanoTime(), "test-key");
            shopRepository.insert(shop);
            Shop persistedShop = shopRepository.findAll().stream()
                    .max(Comparator.comparingInt(Shop::getId))
                    .orElseThrow();

            Order order = new Order();
            order.setId(12345L);
            order.setBrand("Brand A");
            order.setName("Product A");
            order.setSubjectName("Category A");
            order.setSize("M");
            order.setColor("Black");
            order.setArticle("ART-001");
            order.setBarcode("2040000000001");
            order.setSticker("ABCD 12");
            order.setStickerCode("2040000000001");
            order.setKiz("KIZ-001");

            PrintHistoryService historyService = new PrintHistoryService();
            long jobId = historyService.recordSuccessfulJob(
                    persistedShop,
                    "SUP-1",
                    "Supply Test",
                    "2026-05-11T08:15:00Z",
                    new PrintTemplateService().getDefaultTemplate(),
                    List.of(order)
            );

            List<PrintHistoryJobSummary> jobs = historyService.getJobs(persistedShop.getId());
            assertFalse(jobs.isEmpty());
            assertEquals(jobId, jobs.getFirst().id());
            assertEquals(persistedShop.getName(), jobs.getFirst().shopName());
            assertEquals("SUP-1", jobs.getFirst().supplyId());

            List<PrintHistoryItem> items = historyService.getItems(jobId);
            assertEquals(1, items.size());
            assertEquals(12345L, items.getFirst().orderId());
            assertEquals("KIZ-001", items.getFirst().kiz());

            Path output = Files.createTempFile("print-history-", ".pdf");
            Path details = Files.createTempFile("print-history-details-", ".pdf");
            OrderExportWorkflow.ExportResult result = historyService.reprint(jobs.getFirst(), output.toFile(), details.toFile());

            assertEquals(1, result.exportedOrders().size());
            assertTrue(Files.size(output) > 0);
            assertTrue(Files.size(details) > 0);
            output.toFile().deleteOnExit();
            details.toFile().deleteOnExit();
        } finally {
            System.clearProperty("wcode.appdata.dir");
        }
    }
}
