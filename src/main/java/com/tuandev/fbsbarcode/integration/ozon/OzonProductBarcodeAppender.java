package com.tuandev.fbsbarcode.integration.ozon;

import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.tuandev.fbsbarcode.features.print.GenerateBarcode;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import java.io.IOException;

final class OzonProductBarcodeAppender {
    static void append(PdfDocument pdf, OzonPackingPlan.Line line) throws IOException {
        PageSize size = new PageSize((float) PrintTemplateService.PAGE_WIDTH, (float) PrintTemplateService.PAGE_HEIGHT);
        var page = pdf.addNewPage(size);
        Barcode128 barcode = new Barcode128(pdf);
        barcode.setCode(line.barcode());
        barcode.setFont(GenerateBarcode.getArialFont());
        barcode.setSize(8);
        barcode.setBarHeight(32);
        var form = barcode.createFormXObject(pdf);
        float scale = Math.min(1, (size.getWidth() - 16) / form.getWidth());
        new PdfCanvas(page).addXObjectWithTransformationMatrix(form, scale, 0, 0, scale,
                (size.getWidth() - form.getWidth() * scale) / 2, 12);
        try (Canvas canvas = new Canvas(page, size)) {
            canvas.setFont(GenerateBarcode.getArialFont());
            String name = line.product().name();
            if (name.length() > 65) name = name.substring(0, 62) + "...";
            canvas.add(new Paragraph(name).setFontSize(8).setBold().setMargin(0)
                    .setFixedPosition(8, size.getHeight() - 32, size.getWidth() - 16));
            canvas.add(new Paragraph(line.product().article() + "  " + line.product().color() + "  " + line.product().size())
                    .setFontSize(7).setMargin(0).setFixedPosition(8, 58, size.getWidth() - 16));
        }
    }
}
