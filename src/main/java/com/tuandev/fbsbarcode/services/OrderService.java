package com.tuandev.fbsbarcode.services;

import com.google.gson.Gson;
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
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.tuandev.fbsbarcode.dto.StickerResponse;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Sticker;
import okhttp3.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class OrderService {
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static List<Order> getOrdersToExcel(File file) throws Exception {
        List<Order> orders = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheetAt(0);

            Map<Integer, byte[]> images = getImages(sheet);

            for (int i = 5; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);

                byte[] image = images.get(i);

                orders.add(new Order(
                        Long.parseLong(row.getCell(0).getStringCellValue()),
                        image,
                        row.getCell(2) != null ? row.getCell(2).getStringCellValue() : "",
                        row.getCell(3).getStringCellValue(),
                        row.getCell(4) != null ? row.getCell(4).getStringCellValue() : "",
                        row.getCell(5) != null ? row.getCell(5).getStringCellValue() : "",
                        row.getCell(6).getStringCellValue(),
                        row.getCell(7).getStringCellValue(),
                        row.getCell(8).getStringCellValue()
                ));
            }
        }
        return orders;
    }

    private static Map<Integer, byte[]> getImages(Sheet sheet) {
        Map<Integer, byte[]> images = new HashMap<>();

        XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
        if (drawing == null) return images;

        for (XSSFShape shape : drawing.getShapes()) {
            if (shape instanceof XSSFPicture picture) {
                XSSFPictureData pictureData = picture.getPictureData();
                byte[] data = pictureData.getData();

                XSSFClientAnchor anchor = picture.getPreferredSize();
                int row = anchor.getRow1();
                images.put(row, data);
            }
        }

        return images;
    }

    public static List<Sticker> getStickers(String apiKey, List<Long> orders) throws IOException {

        List<Sticker> allStickers = new ArrayList<>();

        int batchSize = 100;

        for (int i = 0; i < orders.size(); i += batchSize) {

            List<Long> batch = orders.subList(i, Math.min(i + batchSize, orders.size()));

            List<Sticker> batchStickers = requestStickerBatch(apiKey, batch);

            allStickers.addAll(batchStickers);
        }

        return allStickers;
    }

    private static List<Sticker> requestStickerBatch(String apiKey, List<Long> orders) throws IOException {

        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/stickers?type=png&width=58&height=40";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("orders", orders);

        String orderIdsJson = gson.toJson(bodyMap);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                orderIdsJson
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {

            if (response.isSuccessful()) {

                StickerResponse stickerResponse =
                        gson.fromJson(response.body().string(), StickerResponse.class);

                return stickerResponse.getStickers();

            } else {

                System.out.println(response.body().string());
                return Collections.emptyList();
            }
        }
    }

    public static void exportOrdersToPdf(File file, List<Order> orders) throws IOException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument =  new PdfDocument(pdfWriter);
        Document document = new Document(pdfDocument, PageSize.A4);
        document.setMargins(20, 20, 20, 20);
        document.setFont(GenerateBarcode.getArialFont());

        float[] widths = {90, 80, 90, 220, 60, 90, 120, 120};

        Table table = new Table(widths);
        table.setWidth(UnitValue.createPercentValue(100));

        addHeader(table, "№ задания");
        addHeader(table, "Фото");
        addHeader(table, "Бренд");
        addHeader(table, "Наименование");
        addHeader(table, "Размер");
        addHeader(table, "Цвет");
        addHeader(table, "Артикул продавца");
        addHeader(table, "Стикер");

        for (Order order : orders) {

            table.addCell(cell(order.getId() + ""));

            // image
            if (order.getImage() != null) {

                ImageData imageData = ImageDataFactory.create(order.getImage());
                Image img = new Image(imageData);

                img.scaleToFit(60, 60);

                table.addCell(new Cell()
                        .add(img)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setVerticalAlignment(VerticalAlignment.MIDDLE)
                        .setPadding(5));

            } else {
                table.addCell(cell(""));
            }

            table.addCell(cell(order.getBrand()));
            table.addCell(cell(order.getName()));
            table.addCell(cell(order.getSize()));
            table.addCell(cell(order.getColor()));
            table.addCell(cell(order.getArticle()));
            table.addCell(cell(order.getSticker()));
        }

        document.add(table);
        document.close();
    }

    private static Cell cell(String text) {

        return new Cell()
                .add(new Paragraph(text).setFontSize(9))
                .setTextAlignment(TextAlignment.LEFT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private static void addHeader(Table table, String text) {

        table.addHeaderCell(
                new Cell()
                        .add(new Paragraph(text).setBold().setFontSize(9))
                        .setVerticalAlignment(VerticalAlignment.MIDDLE)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setBackgroundColor(ColorConstants.LIGHT_GRAY)
        );
    }
}
