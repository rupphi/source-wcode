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
import com.tuandev.fbsbarcode.shared.I18nService;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Creates a separate A4 warehouse picking list without buyer PII or raw KIZ values. */
final class OzonPickingListPdfExporter {
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final FboProductImageService images = new FboProductImageService();

    void export(File target, Shop shop, OzonPostingDto posting) throws IOException {
        export(target, shop, List.of(posting));
    }

    void exportBatch(File target, Shop shop, List<OzonPostingDto> postings) throws IOException {
        export(target, shop, postings);
    }

    private void export(File target, Shop shop, List<OzonPostingDto> postings) throws IOException {
        List<OzonPostingDto> safePostings = postings == null ? List.of() : postings.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        var products = catalog.findAll(shop.getId());
        var plans = new java.util.ArrayList<OzonPackingPlan>();
        for (var posting : safePostings) plans.add(OzonPackingPlan.create(posting, products, List.of()));
        exportPlans(target, shop, plans);
    }

    void exportPlans(File target, Shop shop, List<OzonPackingPlan> plans) throws IOException {
        try (PdfWriter writer = new PdfWriter(target);
                PdfDocument pdf = new PdfDocument(writer);
                Document document = new Document(pdf, PageSize.A4)) {
            document.setMargins(24, 24, 24, 24);
            document.setFont(GenerateBarcode.getArialFont());
            document.add(new Paragraph(shop.getName() + " · " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    + " · " + tr("fbo.column.quantity") + ": " + plans.stream().mapToInt(OzonPackingPlan::units).sum())
                    .setFontSize(10).setBold());
            float[] widths = new float[]{30, 85, 46, 38, 55, 145, 90, 35};
            Table table = new Table(widths);
            table.setWidth(UnitValue.createPercentValue(100));
            header(table, tr("ozon.picking.column.index"));
            header(table, tr("ozon.dashboard.col.order"));
            header(table, tr("ozon.dashboard.col.image"));
            header(table, tr("fbo.column.size"));
            header(table, tr("fbo.column.color"));
            header(table, tr("ozon.dashboard.item.article"));
            header(table, tr("supply.col.sticker"));
            header(table, tr("fbo.column.quantity"));
            int rowNumber = 0;
            for (OzonPackingPlan plan : plans) {
                var posting = plan.posting();
                for (var line : plan.lines()) {
                    var item = line.item();
                    var product = line.product();
                    int firstUnit = rowNumber + 1;
                    rowNumber += item.quantity();
                    table.addCell(cell(firstUnit == rowNumber ? "" + firstUnit : firstUnit + "-" + rowNumber, TextAlignment.CENTER));
                    table.addCell(cell(posting.postingNumber(), TextAlignment.LEFT));
                    table.addCell(imageCell(imageBytes(product)));
                    table.addCell(cell(product.size(), TextAlignment.CENTER));
                    table.addCell(cell(product.color(), TextAlignment.LEFT));
                    table.addCell(cell(first(product.article(), item.offerId()) + "\n" + first(item.name(), product.name()), TextAlignment.LEFT));
                    table.addCell(cell(first(posting.upperBarcode(), posting.lowerBarcode()), TextAlignment.LEFT));
                    table.addCell(cell(String.valueOf(item.quantity()), TextAlignment.CENTER, true));
                }
            }
            document.add(table);
        }
    }

    private byte[] imageBytes(OzonProductDto product) {
        String imageUrl = product == null ? "" : product.primaryImageUrl();
        if (imageUrl.isBlank()) return null;
        try {
            return images.loadImage(imageUrl).join();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static OzonProductDto findProduct(List<OzonProductDto> products, OzonPostingItemDto item) {
        return products.stream().filter(product -> matches(product, item)).findFirst().orElse(null);
    }

    private static boolean matches(OzonProductDto product, OzonPostingItemDto item) {
        return (!item.productId().isBlank() && item.productId().equals(product.productId()))
                || (!item.sku().isBlank() && item.sku().equals(product.sku()))
                || (!item.offerId().isBlank() && item.offerId().equals(product.offerId()));
    }

    private static Cell imageCell(byte[] imageBytes) {
        Cell cell = new Cell().setHeight(46)
                .setKeepTogether(true)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(4);
        if (imageBytes == null || imageBytes.length == 0) {
            return cell.add(new Paragraph("-").setFontSize(9));
        }
        try {
            Image image = new Image(ImageDataFactory.create(imageBytes));
            image.scaleToFit(38, 38).setHorizontalAlignment(HorizontalAlignment.CENTER);
            return cell.add(image);
        } catch (RuntimeException exception) {
            return cell.add(new Paragraph("-").setFontSize(9));
        }
    }

    private static void header(Table table, String value) {
        table.addHeaderCell(new Cell().add(new Paragraph(value).setBold().setFontSize(9))
                .setKeepTogether(true)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setBackgroundColor(ColorConstants.LIGHT_GRAY));
    }

    private static Cell cell(String value, TextAlignment alignment) {
        return cell(value, alignment, false);
    }

    private static Cell cell(String value, TextAlignment alignment, boolean bold) {
        Paragraph paragraph = new Paragraph(safe(value)).setFontSize(bold ? 11 : 9);
        if (bold) paragraph.setBold();
        return new Cell().add(paragraph)
                .setKeepTogether(true)
                .setTextAlignment(alignment).setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
    }

    private static String first(String preferred, String fallback) {
        String safe = safe(preferred);
        return safe.isBlank() ? safe(fallback) : safe;
    }

    private static String tr(String key) {
        return I18nService.getInstance().tr(key);
    }
}
