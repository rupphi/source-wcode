package com.tuandev.fbsbarcode.services;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.AreaBreakType;
import com.itextpdf.layout.properties.TextAlignment;
import com.tuandev.fbsbarcode.models.Order;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GenerateBarcode {
    private static final float WIDTH = 164f, HEIGHT = 113f; // 58mmx40mm

    public static PdfFont getArialFont() {
        try {
            InputStream is = GenerateBarcode.class.getResourceAsStream("ARIAL.TTF");
            if (is == null) {
                throw new RuntimeException("Không tìm thấy file font trong resources!");
            }
            byte[] fontBytes = is.readAllBytes();
            return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void type1(List<Order> orders, File file) throws IOException, WriterException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument = new PdfDocument(pdfWriter);

        PageSize pageSize = new PageSize(WIDTH, HEIGHT);
        Document document = new Document(pdfDocument, pageSize);
        document.setMargins(5,5,5,5);
        document.setFont(getArialFont());

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            if (order.getKiz() != null) {
                addPageKiz(order, document);
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }

            addPageSticker(order, document, pdfDocument);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            addPageProduct(order, document);
            if (i != orders.size() - 1) {
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }
        }

        document.close();
    }

    public static void type2(List<Order> orders, File file) throws IOException, WriterException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument = new PdfDocument(pdfWriter);

        PageSize pageSize = new PageSize(WIDTH, HEIGHT);
        Document document = new Document(pdfDocument, pageSize);
        document.setMargins(5,5,5,5);
        document.setFont(getArialFont());

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            addPageProduct(order, document);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            addPageSticker(order, document, pdfDocument);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            if (order.getKiz() != null) {
                addPageKiz(order, document);
                if (i != orders.size() - 1) {
                    document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
                }
            }
        }

        document.close();
    }

    public static void type3(List<Order> orders, File file) throws IOException, WriterException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument = new PdfDocument(pdfWriter);

        PageSize pageSize = new PageSize(WIDTH, HEIGHT);
        Document document = new Document(pdfDocument, pageSize);
        document.setMargins(5,5,5,5);
        document.setFont(getArialFont());

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            addPageProductAndKiz(order, document, pdfDocument);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            addPageSticker(order, document, pdfDocument);
            if (i != orders.size() - 1) {
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }

        }

        document.close();
    }

    private static void addPageProductAndKiz(Order order, Document document, PdfDocument pdfDocument) throws IOException {
        if (order.getKiz() != null) {
            Image kiz = new Image(ImageDataFactory.create(generateDataMatrix(order.getKiz(), 325)));
            kiz.scaleToFit(54, 54);
            kiz.setFixedPosition(10, HEIGHT - 64);
            document.add(kiz);
        }

        if (order.getBrand() != null) {
            Paragraph brand = new Paragraph(order.getBrand())
                    .setFontSize(7)
                    .setBold()
                    .setFixedPosition(75, HEIGHT - 20, 80)
                    .setTextAlignment(TextAlignment.CENTER);
            document.add(brand);
        }

        String[] names = order.getName().split(" ");
        Paragraph name = new Paragraph((names.length > 1) ? names[0] + " " + names[1] : names[0])
                .setFontSize(7)
                .setTextAlignment(TextAlignment.LEFT)
                .setFixedPosition(75, HEIGHT - 28, 80);
        document.add(name);

        Paragraph article = new Paragraph("Арт: " + order.getArticle())
                .setFontSize(7)
                .setTextAlignment(TextAlignment.LEFT)
                .setFixedPosition(75, HEIGHT - 36, 80);
        document.add(article);

        if (order.getColor() != null) {
            Paragraph color = new Paragraph("Цвет: " + order.getColor())
                    .setFontSize(7)
                    .setTextAlignment(TextAlignment.LEFT)
                    .setFixedPosition(75, HEIGHT - 44, 80);
            document.add(color);
        }

        if (order.getSize() != null) {
            Paragraph size = new Paragraph("Размер: " + order.getSize())
                    .setFontSize(7)
                    .setTextAlignment(TextAlignment.LEFT)
                    .setFixedPosition(75, HEIGHT - 52, 80);
            document.add(size);
        }

        Image chestniyZnack = new Image(ImageDataFactory.create(Objects.requireNonNull(GenerateBarcode.class.getResource("chestniy-znak.png"))));
        chestniyZnack.scaleToFit(30, 15);
        chestniyZnack.setFixedPosition(75, HEIGHT - 63);
        document.add(chestniyZnack);

        PdfPage currentPage = pdfDocument.getPage(pdfDocument.getNumberOfPages());
        PdfCanvas canvas = new PdfCanvas(currentPage);
        canvas.setLineWidth(1f);
        canvas.moveTo(10, HEIGHT - 67);
        canvas.lineTo(WIDTH - 10, HEIGHT - 67);
        canvas.stroke();

        Image barcodeImage = new Image(ImageDataFactory.create(generateCode128(order.getBarcode(), 420, 75)));
        barcodeImage.scaleToFit(140, 25);
        barcodeImage.setFixedPosition(WIDTH - 152, 16);
        document.add(barcodeImage);

        Paragraph barcode = new Paragraph(order.getBarcode())
                .setFontSize(8)
                .setFixedPosition(8, 4, WIDTH - 20)
                .setTextAlignment(TextAlignment.CENTER);
        document.add(barcode);

        String[] stickers =  order.getSticker().split(" ");
        Paragraph stickerPathB = new Paragraph((stickers.length > 1) ? stickers[1] : stickers[0])
                .setFontSize(8)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFixedPosition(WIDTH - 30, 4, 20);
        document.add(stickerPathB);
    }

    private static void addPageKiz(Order order, Document document) throws IOException {
        Image dataMatrix = new Image(ImageDataFactory.create(generateDataMatrix(order.getKiz(), 600)));
        dataMatrix.scaleToFit(70, 70);
        dataMatrix.setFixedPosition(10, HEIGHT - 80);
        document.add(dataMatrix);

        String kiz = order.getKiz().substring(0, 32);
        Paragraph kizPr = new Paragraph(kiz)
                .setFontSize(6)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(10, HEIGHT - 100, 70);
        document.add(kizPr);

        Image chestniyZnack = new Image(ImageDataFactory.create(Objects.requireNonNull(GenerateBarcode.class.getResource("chestniy-znak.png"))));
        chestniyZnack.scaleToFit(60, 30);
        chestniyZnack.setFixedPosition(WIDTH - 80, 80);
        document.add(chestniyZnack);

        String[] names = order.getName().split(" ");
        Paragraph name = new Paragraph((names.length > 1) ? names[0] + " " + names[1] : names[0])
                .setFontSize(8)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(WIDTH - 80, 60, 70);
        document.add(name);

        Paragraph article = new Paragraph(order.getArticle())
                .setFontSize(8)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(WIDTH - 80, 40, 70);
        document.add(article);

        String[] stickers = order.getSticker().split(" ");
        Paragraph stickerPathB = new Paragraph((stickers.length > 1) ? stickers[1] : stickers[0])
                .setFontSize(8)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFixedPosition(WIDTH - 30, 10, 20);
        document.add(stickerPathB);
    }

    private static void addPageSticker(Order order, Document document, PdfDocument pdfDoc) throws IOException, WriterException {
        Image qrCenter = new Image(ImageDataFactory.create(generateQR(order.getStickerCode(), 1000)));
        qrCenter.scaleToFit(80, 80);
        qrCenter.setFixedPosition(WIDTH / 2 - 40, HEIGHT / 2 - 40);
        document.add(qrCenter);

        Image qrTL = new Image(ImageDataFactory.create(generateQR(order.getStickerCode(), 30)));
        qrTL.scaleToFit(30, 30);
        qrTL.setFixedPosition(8, HEIGHT - 38);
        document.add(qrTL);

        Image qrTR = new Image(ImageDataFactory.create(generateQR(order.getStickerCode(), 30)));
        qrTR.scaleToFit(30, 30);
        qrTR.setFixedPosition(WIDTH - 38, HEIGHT - 38);
        document.add(qrTR);

        Image qrBL = new Image(ImageDataFactory.create(generateQR(order.getStickerCode(), 30)));
        qrBL.scaleToFit(30, 30);
        qrBL.setFixedPosition(8, 8);
        document.add(qrBL);

        Image qrBR = new Image(ImageDataFactory.create(generateQR(order.getStickerCode(), 30)));
        qrBR.scaleToFit(30, 30);
        qrBR.setFixedPosition(WIDTH - 38, 8);
        document.add(qrBR);

        Paragraph wb = new Paragraph("WB")
                .setFontSize(18)
                .setBold()
                .setFontColor(ColorConstants.MAGENTA)
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        wb.setFixedPosition(40, 41, 40);
        document.add(wb);

        String[] stickers = order.getSticker().split(" ");
        Paragraph stickerPathA = new Paragraph(stickers[0])
                .setFontSize(10)
                .setBold()
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        stickerPathA.setFixedPosition(WIDTH - 16, 36, 51);
        document.add(stickerPathA);

        Paragraph stickerPathB = new Paragraph(stickers[1])
                .setFontSize(14)
                .setBold()
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        stickerPathB.setFixedPosition(WIDTH - 2, 36, 51);
        document.add(stickerPathB);
    }

    private static void addPageProduct(Order order, Document document) throws IOException {

        Paragraph name = new  Paragraph(order.getName())
                .setFontSize(9)
                .setFixedPosition(8, HEIGHT - 30, WIDTH - 16)
                .setTextAlignment(TextAlignment.CENTER);
        document.add(name);

        Paragraph brand = new Paragraph("Бренд: " + order.getBrand())
                .setFontSize(8)
                .setFixedPosition(8, HEIGHT - 40, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(brand);

        Paragraph article = new  Paragraph("Арт: " + order.getArticle())
                .setFontSize(8)
                .setFixedPosition(8, HEIGHT - 50, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(article);

        Paragraph size = new  Paragraph("Размер: " + order.getSize())
                .setFontSize(8)
                .setFixedPosition(8, HEIGHT - 60, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(size);

        Paragraph color = new  Paragraph("Цвет: " + order.getColor())
                .setFontSize(8)
                .setFixedPosition(8, HEIGHT - 70, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(color);

        Image barcodeImage = new Image(ImageDataFactory.create(generateCode128(order.getBarcode(), 420, 75)));
        barcodeImage.scaleToFit(140, 25);
        barcodeImage.setFixedPosition(WIDTH - 152, 16);
        document.add(barcodeImage);

        Paragraph barcode = new Paragraph(order.getBarcode())
                .setFontSize(8)
                .setFixedPosition(8, 5, WIDTH - 16)
                .setTextAlignment(TextAlignment.CENTER);
        document.add(barcode);

        Image eac = new Image(ImageDataFactory.create(Objects.requireNonNull(GenerateBarcode.class.getResource("eac.png"))));
        eac.scaleToFit(16, 12);
        eac.setFixedPosition(WIDTH - 35, HEIGHT / 2 - 12, WIDTH - 16);
        document.add(eac);

        String[] stickers =  order.getSticker().split(" ");
        Paragraph stickerPathB = new Paragraph((stickers.length > 1) ? stickers[1] : stickers[0])
                .setFontSize(8)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFixedPosition(WIDTH - 30, 5, 20);
        document.add(stickerPathB);
    }

    private static byte[] generateQR(String text, int size) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix bitMatrix = qrCodeWriter.encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints
        );

        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteArrayOutputStream);

        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] generateDataMatrix(String text, int size) throws IOException {
        DataMatrixWriter dataMatrixWriter = new DataMatrixWriter();

        BitMatrix bitMatrix = dataMatrixWriter.encode(text, BarcodeFormat.DATA_MATRIX, size, size);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteArrayOutputStream);

        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] generateCode128(String text, int width, int height) throws IOException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        Code128Writer code128Writer = new Code128Writer();

        BitMatrix bitMatrix = code128Writer.encode(text, BarcodeFormat.CODE_128, width, height, hints);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteArrayOutputStream);

        return byteArrayOutputStream.toByteArray();
    }

}
