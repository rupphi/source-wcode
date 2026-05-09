package com.tuandev.fbsbarcode.features.order;

import com.tuandev.fbsbarcode.models.Order;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExcelOrderImportService {
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    public List<Order> getOrdersFromExcel(File file) throws Exception {
        List<Order> orders = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<Integer, byte[]> images = getImages(sheet);

            for (int i = 5; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String idValue = readCell(row, 0);
                if (idValue.isBlank()) {
                    continue;
                }

                orders.add(new Order(
                        Long.parseLong(idValue),
                        images.get(i),
                        readCell(row, 2),
                        readCell(row, 3),
                        readCell(row, 4),
                        readCell(row, 5),
                        readCell(row, 6),
                        readCell(row, 7),
                        readCell(row, 8)
                ));
            }
        }
        return orders;
    }

    private String readCell(Row row, int cellIndex) {
        if (row.getCell(cellIndex) == null) {
            return "";
        }
        return DATA_FORMATTER.formatCellValue(row.getCell(cellIndex)).trim();
    }

    private Map<Integer, byte[]> getImages(Sheet sheet) {
        Map<Integer, byte[]> images = new HashMap<>();
        XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
        if (drawing == null) {
            return images;
        }

        for (XSSFShape shape : drawing.getShapes()) {
            if (shape instanceof XSSFPicture picture) {
                XSSFPictureData pictureData = picture.getPictureData();
                XSSFClientAnchor anchor = picture.getPreferredSize();
                images.put(anchor.getRow1(), pictureData.getData());
            }
        }
        return images;
    }
}
