package com.tuandev.fbsbarcode.features.print;

import com.itextpdf.io.image.ImageData;
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
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.tuandev.fbsbarcode.models.Order;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class OrderDetailsPdfExporter {
    public void export(File file, List<Order> orders) throws IOException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument = new PdfDocument(pdfWriter);
        Document document = new Document(pdfDocument, PageSize.A4);
        document.setMargins(20, 20, 20, 20);
        document.setFont(GenerateBarcode.getArialFont());

        float[] widths = {32, 76, 58, 55, 78, 114, 140};
        Table table = new Table(widths);
        table.setWidth(UnitValue.createPercentValue(100));

        addHeader(table, "№");
        addHeader(table, "№ задания");
        addHeader(table, "Фото");
        addHeader(table, "Размер");
        addHeader(table, "Цвет");
        addHeader(table, "Артикул продавца");
        addHeader(table, "Стикер");

        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            table.addCell(cell(String.valueOf(index + 1)));
            table.addCell(cell(String.valueOf(order.getId())));
            table.addCell(imageCell(order));
            table.addCell(cell(order.getSize()));
            table.addCell(cell(order.getColor()));
            table.addCell(cell(order.getArticle()));
            table.addCell(stickerCell(order.getSticker()));
        }

        document.add(table);
        document.close();
    }

    private Cell imageCell(Order order) {
        if (order.getImage() == null) {
            return cell("");
        }

        ImageData imageData = ImageDataFactory.create(order.getImage());
        Image img = new Image(imageData);
        img.scaleToFit(42, 56);

        return new Cell()
                .add(img)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(5);
    }

    private Cell cell(String text) {
        return new Cell()
                .add(new Paragraph(text == null ? "" : text).setFontSize(10))
                .setTextAlignment(TextAlignment.LEFT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private Cell stickerCell(String sticker) {
        String safeSticker = normalizeStickerForPdf(StickerText.safe(sticker));
        Paragraph paragraph = new Paragraph().setFontSize(12).setBold();

        if (safeSticker.length() <= 4) {
            paragraph.add(new Text(safeSticker)
                    .setFontColor(ColorConstants.WHITE)
                    .setBackgroundColor(ColorConstants.BLACK));
        } else {
            int highlightStart = safeSticker.length() - 4;
            paragraph.add(new Text(safeSticker.substring(0, highlightStart)));
            paragraph.add(new Text(safeSticker.substring(highlightStart))
                    .setFontColor(ColorConstants.WHITE)
                    .setBackgroundColor(ColorConstants.BLACK));
        }

        return new Cell()
                .add(paragraph)
                .setTextAlignment(TextAlignment.LEFT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private void addHeader(Table table, String text) {
        table.addHeaderCell(
                new Cell()
                        .add(new Paragraph(text).setBold().setFontSize(10))
                        .setVerticalAlignment(VerticalAlignment.MIDDLE)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setBackgroundColor(ColorConstants.LIGHT_GRAY)
        );
    }

    private String normalizeStickerForPdf(String sticker) {
        if (sticker == null || sticker.isBlank()) {
            return "";
        }
        String[] parts = sticker.trim().split("\\s+");
        if (parts.length < 2) {
            return sticker;
        }
        String secondPart = parts[1];
        if (secondPart.matches("\\d{1,4}")) {
            secondPart = String.format("%04d", Integer.parseInt(secondPart));
        }
        return parts[0] + " " + secondPart;
    }
}
