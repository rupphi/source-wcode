package com.tuandev.fbsbarcode.integration.ozon;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import com.tuandev.fbsbarcode.features.print.GenerateBarcode;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Creates a separate A4 warehouse picking list without buyer PII or raw KIZ values. */
final class OzonPickingListPdfExporter {
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final FboProductImageService images = new FboProductImageService();

    void export(File target, Shop shop, OzonPostingDto posting) throws IOException {
        try (PdfWriter writer = new PdfWriter(target);
                PdfDocument pdf = new PdfDocument(writer);
                Document document = new Document(pdf, PageSize.A4)) {
            document.setMargins(24, 24, 24, 24);
            document.setFont(GenerateBarcode.getArialFont());
            List<OzonProductDto> products = catalog.findAll(shop.getId());
            Table table = new Table(new float[]{28, 72, 180, 86, 100, 42});
            table.setWidth(UnitValue.createPercentValue(100));
            header(table, "#");
            header(table, "Image");
            header(table, "Product");
            header(table, "SKU");
            header(table, "Offer ID");
            header(table, "Qty");
            for (OzonPostingItemDto item : posting.items()) {
                table.addCell(cell(String.valueOf(item.itemIndex() + 1), TextAlignment.CENTER));
                table.addCell(imageCell(imageBytes(products, item)));
                table.addCell(cell(item.name(), TextAlignment.LEFT));
                table.addCell(cell(item.sku(), TextAlignment.LEFT));
                table.addCell(cell(item.offerId(), TextAlignment.LEFT));
                table.addCell(cell(String.valueOf(item.quantity()), TextAlignment.CENTER));
            }
            document.add(table);
        }
    }

    private byte[] imageBytes(List<OzonProductDto> products, OzonPostingItemDto item) {
        String imageUrl = products.stream()
                .filter(product -> matches(product, item))
                .map(OzonProductDto::primaryImageUrl)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        if (imageUrl.isBlank()) return null;
        try {
            return images.loadImage(imageUrl).join();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean matches(OzonProductDto product, OzonPostingItemDto item) {
        return (!item.productId().isBlank() && item.productId().equals(product.productId()))
                || (!item.sku().isBlank() && item.sku().equals(product.sku()))
                || (!item.offerId().isBlank() && item.offerId().equals(product.offerId()));
    }

    private static Cell imageCell(byte[] imageBytes) {
        Cell cell = new Cell().setHeight(68)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(4);
        if (imageBytes == null || imageBytes.length == 0) {
            return cell.add(new Paragraph("-").setFontSize(9));
        }
        try {
            Image image = new Image(ImageDataFactory.create(imageBytes));
            image.scaleToFit(58, 58).setHorizontalAlignment(HorizontalAlignment.CENTER);
            return cell.add(image);
        } catch (RuntimeException exception) {
            return cell.add(new Paragraph("-").setFontSize(9));
        }
    }

    private static void header(Table table, String value) {
        table.addHeaderCell(new Cell().add(new Paragraph(value).setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setBackgroundColor(ColorConstants.LIGHT_GRAY));
    }

    private static Cell cell(String value, TextAlignment alignment) {
        return new Cell().add(new Paragraph(safe(value)).setFontSize(9))
                .setTextAlignment(alignment).setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
    }
}
