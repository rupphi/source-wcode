package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.models.Order;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderDetailsPdfExporterTest {
    @Test
    void shouldExportOrderDetailsPdf() throws Exception {
        Order order = new Order();
        order.setId(123456L);
        order.setSize("M");
        order.setColor("Black");
        order.setArticle("ART-001");
        order.setSticker("ABCD 12");

        Path file = Files.createTempFile("order-details-", ".pdf");
        new OrderDetailsPdfExporter().export(file.toFile(), List.of(order));
        assertTrue(Files.size(file) > 0);
        file.toFile().deleteOnExit();
    }
}
