package com.tuandev.fbsbarcode.features.print;

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
import java.util.concurrent.ConcurrentHashMap;

public class GenerateBarcode {
    private static final float WIDTH = 164f, HEIGHT = 113f; // 58mmx40mm
    private static volatile byte[] arialFontBytes;
    private static volatile byte[] eacImageBytes;
    private static volatile byte[] chestniyZnakImageBytes;

    public static PdfFont getArialFont() {
        if (arialFontBytes == null) {
            synchronized (GenerateBarcode.class) {
                if (arialFontBytes == null) {
                    try {
                        try (InputStream is = GenerateBarcode.class.getResourceAsStream("/com/tuandev/fbsbarcode/services/ARIAL.TTF")) {
                            if (is == null) {
                                throw new RuntimeException("Không tìm thấy file font trong resources!");
                            }
                            arialFontBytes = is.readAllBytes();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        try {
            return PdfFontFactory.createFont(arialFontBytes, PdfEncodings.IDENTITY_H);
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
        RenderContext renderContext = new RenderContext();

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            if (order.getKiz() != null) {
                addPageKiz(order, document, renderContext);
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }

            addPageSticker(order, document, pdfDocument, renderContext);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            addPageProduct(order, document, renderContext);
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
        RenderContext renderContext = new RenderContext();

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            addPageProduct(order, document, renderContext);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            addPageSticker(order, document, pdfDocument, renderContext);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            if (order.getKiz() != null) {
                addPageKiz(order, document, renderContext);
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
        RenderContext renderContext = new RenderContext();

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            addPageProductAndKiz(order, document, pdfDocument, renderContext);
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            addPageSticker(order, document, pdfDocument, renderContext);
            if (i != orders.size() - 1) {
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }

        }

        document.close();
    }

    private static void addPageProductAndKiz(Order order, Document document, PdfDocument pdfDocument, RenderContext renderContext) throws IOException {
        if (order.getKiz() != null) {
            Image kiz = new Image(ImageDataFactory.create(renderContext.getDataMatrix(order.getKiz(), 325)));
            kiz.scaleToFit(52, 52);
            kiz.setFixedPosition(10, HEIGHT - 62);
            document.add(kiz);
        }

        if (order.getBrand() != null) {
            Paragraph brand = new Paragraph(safeValue(order.getBrand()))
                    .setFontSize(9)
                    .setBold()
                    .setFixedPosition(70, HEIGHT - 20, 85)
                    .setTextAlignment(TextAlignment.CENTER);
            document.add(brand);
        }

        Paragraph name = new Paragraph(compactProductName(order.getName()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.LEFT)
                .setFixedPosition(70, HEIGHT - 28, 85);
        document.add(name);

        if (order.getColor() != null) {
            Paragraph color = new Paragraph("Цвет: " + safeValue(order.getColor()))
                    .setFontSize(8)
                    .setTextAlignment(TextAlignment.LEFT)
                    .setMultipliedLeading(0.9f)
                    .setFixedPosition(70, HEIGHT - 42, 85);
            document.add(color);
        }

        Paragraph article = new Paragraph("Арт: " + safeValue(order.getArticle()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.LEFT)
                .setMultipliedLeading(0.9f)
                .setFixedPosition(70, HEIGHT - 54, 85);
        document.add(article);

        if (order.getSize() != null) {
            Paragraph size = new Paragraph("Разм: " + safeValue(order.getSize()))
                    .setFontSize(9)
                    .setTextAlignment(TextAlignment.LEFT)
                    .setFixedPosition(70, HEIGHT - 66, 85);
            document.add(size);
        }

        PdfPage currentPage = pdfDocument.getPage(pdfDocument.getNumberOfPages());
        PdfCanvas canvas = new PdfCanvas(currentPage);
        canvas.setLineWidth(1f);
        canvas.moveTo(10, HEIGHT - 67);
        canvas.lineTo(WIDTH - 10, HEIGHT - 67);
        canvas.stroke();

        Image barcodeImage = new Image(ImageDataFactory.create(renderContext.getCode128(requiredValue(order.getBarcode(), "Barcode", order), 420, 75)));
        barcodeImage.scaleToFit(140, 25);
        barcodeImage.setFixedPosition(WIDTH - 152, 16);
        document.add(barcodeImage);

        Paragraph barcode = new Paragraph(safeValue(order.getBarcode()))
                .setFontSize(8)
                .setFixedPosition(8, 4, WIDTH - 20)
                .setTextAlignment(TextAlignment.CENTER);
        document.add(barcode);

        Paragraph stickerPathB = new Paragraph(StickerText.secondPartOrFirst(order.getSticker()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFixedPosition(WIDTH - 30, 4, 20);
        document.add(stickerPathB);
    }

    private static void addPageKiz(Order order, Document document, RenderContext renderContext) throws IOException {
        Image dataMatrix = new Image(ImageDataFactory.create(renderContext.getDataMatrix(order.getKiz(), 600)));
        dataMatrix.scaleToFit(70, 70);
        dataMatrix.setFixedPosition(10, HEIGHT - 80);
        document.add(dataMatrix);

        String kiz = safeValue(order.getKiz());
        if (kiz.length() > 32) {
            kiz = kiz.substring(0, 32);
        }
        Paragraph kizPr = new Paragraph(kiz)
                .setFontSize(6)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(10, HEIGHT - 100, 70);
        document.add(kizPr);

        Image chestniyZnack = new Image(ImageDataFactory.create(getChestniyZnakImageBytes()));
        chestniyZnack.scaleToFit(60, 30);
        chestniyZnack.setFixedPosition(WIDTH - 80, 80);
        document.add(chestniyZnack);

        Paragraph name = new Paragraph(compactProductName(order.getName()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(WIDTH - 80, 60, 70);
        document.add(name);

        Paragraph article = new Paragraph(safeValue(order.getArticle()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(WIDTH - 80, 40, 70);
        document.add(article);

        Paragraph stickerPathB = new Paragraph(StickerText.secondPartOrFirst(order.getSticker()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFixedPosition(WIDTH - 30, 10, 20);
        document.add(stickerPathB);
    }

    private static void addPageSticker(Order order, Document document, PdfDocument pdfDoc, RenderContext renderContext) throws IOException, WriterException {
        String stickerCode = requiredValue(order.getStickerCode(), "Sticker code", order);

        Image qrCenter = new Image(ImageDataFactory.create(renderContext.getQr(stickerCode, 1000)));
        qrCenter.scaleToFit(80, 80);
        qrCenter.setFixedPosition(WIDTH / 2 - 40, HEIGHT / 2 - 40);
        document.add(qrCenter);

        byte[] smallQr = renderContext.getQr(stickerCode, 30);

        Image qrTL = new Image(ImageDataFactory.create(smallQr));
        qrTL.scaleToFit(30, 30);
        qrTL.setFixedPosition(8, HEIGHT - 38);
        document.add(qrTL);

        Image qrTR = new Image(ImageDataFactory.create(smallQr));
        qrTR.scaleToFit(30, 30);
        qrTR.setFixedPosition(WIDTH - 38, HEIGHT - 38);
        document.add(qrTR);

        Image qrBL = new Image(ImageDataFactory.create(smallQr));
        qrBL.scaleToFit(30, 30);
        qrBL.setFixedPosition(8, 8);
        document.add(qrBL);

        Image qrBR = new Image(ImageDataFactory.create(smallQr));
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

        Paragraph stickerPathA = new Paragraph(StickerText.firstPart(order.getSticker()))
                .setFontSize(10)
                .setBold()
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        stickerPathA.setFixedPosition(WIDTH - 16, 36, 51);
        document.add(stickerPathA);

        Paragraph stickerPathB = new Paragraph(StickerText.secondPartOrFirst(order.getSticker()))
                .setFontSize(14)
                .setBold()
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        stickerPathB.setFixedPosition(WIDTH - 2, 36, 51);
        document.add(stickerPathB);
    }

    private static void addPageProduct(Order order, Document document, RenderContext renderContext) throws IOException {

        Paragraph name = new  Paragraph(safeValue(order.getName()))
                .setFontSize(9)
                .setFixedPosition(8, HEIGHT - 30, WIDTH - 16)
                .setTextAlignment(TextAlignment.CENTER);
        document.add(name);

        Paragraph brand = new Paragraph("Бренд: " + safeValue(order.getBrand()))
                .setFontSize(8)
                .setBold()
                .setFixedPosition(8, HEIGHT - 40, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(brand);

        Paragraph article = new  Paragraph("Арт: " + safeValue(order.getArticle()))
                .setFontSize(8)
                .setBold()
                .setFixedPosition(8, HEIGHT - 50, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(article);

        Paragraph size = new  Paragraph("Размер: " + safeValue(order.getSize()))
                .setFontSize(8)
                .setBold()
                .setFixedPosition(8, HEIGHT - 60, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(size);

        Paragraph color = new  Paragraph("Цвет: " + safeValue(order.getColor()))
                .setFontSize(8)
                .setBold()
                .setFixedPosition(8, HEIGHT - 70, WIDTH - 16)
                .setTextAlignment(TextAlignment.LEFT);
        document.add(color);

        Image barcodeImage = new Image(ImageDataFactory.create(renderContext.getCode128(requiredValue(order.getBarcode(), "Barcode", order), 420, 75)));
        barcodeImage.scaleToFit(140, 25);
        barcodeImage.setFixedPosition(WIDTH - 152, 16);
        document.add(barcodeImage);

        Paragraph barcode = new Paragraph(safeValue(order.getBarcode()))
                .setFontSize(8)
                .setFixedPosition(8, 5, WIDTH - 16)
                .setTextAlignment(TextAlignment.CENTER);
        document.add(barcode);

        Image eac = new Image(ImageDataFactory.create(getEacImageBytes()));
        eac.scaleToFit(16, 12);
        eac.setFixedPosition(WIDTH - 35, HEIGHT / 2 - 12, WIDTH - 16);
        document.add(eac);

        Paragraph stickerPathB = new Paragraph(StickerText.secondPartOrFirst(order.getSticker()))
                .setFontSize(8)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFixedPosition(WIDTH - 30, 5, 20);
        document.add(stickerPathB);
    }

    private static String compactProductName(String name) {
        String safeName = safeValue(name);
        if (safeName.isBlank()) {
            return "";
        }

        String[] names = safeName.split("\\s+");
        return names.length > 1 ? names[0] + " " + names[1] : names[0];
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requiredValue(String value, String fieldName, Order order) {
        String safeValue = safeValue(value);
        if (safeValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " bị thiếu cho order " + order.getId());
        }
        return safeValue;
    }

    private static byte[] getEacImageBytes() throws IOException {
        if (eacImageBytes == null) {
            synchronized (GenerateBarcode.class) {
                if (eacImageBytes == null) {
                    eacImageBytes = loadResourceBytes("/com/tuandev/fbsbarcode/services/eac.png");
                }
            }
        }
        return eacImageBytes;
    }

    private static byte[] getChestniyZnakImageBytes() throws IOException {
        if (chestniyZnakImageBytes == null) {
            synchronized (GenerateBarcode.class) {
                if (chestniyZnakImageBytes == null) {
                    chestniyZnakImageBytes = loadResourceBytes("/com/tuandev/fbsbarcode/services/chestniy-znak.png");
                }
            }
        }
        return chestniyZnakImageBytes;
    }

    private static byte[] loadResourceBytes(String resourceName) throws IOException {
        try (InputStream inputStream = GenerateBarcode.class.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("Không tìm thấy resource: " + resourceName);
            }
            return inputStream.readAllBytes();
        }
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

    private static final class RenderContext {
        private final Map<String, byte[]> qrCache = new ConcurrentHashMap<>();
        private final Map<String, byte[]> dataMatrixCache = new ConcurrentHashMap<>();
        private final Map<String, byte[]> code128Cache = new ConcurrentHashMap<>();

        byte[] getQr(String text, int size) {
            return qrCache.computeIfAbsent(text + "|" + size, key -> {
                try {
                    return generateQR(text, size);
                } catch (WriterException | IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        byte[] getDataMatrix(String text, int size) {
            return dataMatrixCache.computeIfAbsent(text + "|" + size, key -> {
                try {
                    return generateDataMatrix(text, size);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        byte[] getCode128(String text, int width, int height) {
            return code128Cache.computeIfAbsent(text + "|" + width + "|" + height, key -> {
                try {
                    return generateCode128(text, width, height);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

}
