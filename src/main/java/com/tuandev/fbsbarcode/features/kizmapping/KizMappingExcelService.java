package com.tuandev.fbsbarcode.features.kizmapping;

import com.tuandev.fbsbarcode.features.print.history.ImageCacheRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.features.fbo.FboProductImageService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class KizMappingExcelService {
    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final String[] HEADERS = {"nmId", "Ảnh", "Tên", "Danh mục", "Giới tính", "Vendor Code", "KIZ Category ID"};
    private static final int IMAGE_PRELOAD_TIMEOUT_SECONDS = 30;
    private final ImageCacheRepository imageCacheRepository = new ImageCacheRepository();
    private final FboProductImageService imageService = new FboProductImageService();

    public void exportProducts(File file, List<KizMappingProduct> products) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("KIZ Mapping");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            CellStyle editableStyle = workbook.createCellStyle();
            editableStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            editableStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(i == 6 ? editableStyle : headerStyle);
            }

            Drawing<?> drawing = sheet.createDrawingPatriarch();
            CreationHelper helper = workbook.getCreationHelper();
            List<KizMappingProduct> safeProducts = products == null ? List.of() : products;
            Map<String, byte[]> imagesByUrl = preloadImages(safeProducts);
            for (int i = 0; i < safeProducts.size(); i++) {
                KizMappingProduct product = safeProducts.get(i);
                int rowIndex = i + 1;
                Row row = sheet.createRow(rowIndex);
                row.setHeightInPoints(82);
                row.createCell(0).setCellValue(product.nmId());
                row.createCell(1);
                row.createCell(2).setCellValue(nullToEmpty(product.title()));
                row.createCell(3).setCellValue(nullToEmpty(product.subjectName()));
                row.createCell(4).setCellValue(nullToEmpty(product.gender()));
                row.createCell(5).setCellValue(nullToEmpty(product.vendorCode()));
                Cell categoryCell = row.createCell(6);
                if (product.kizCategoryId() != null) {
                    categoryCell.setCellValue(product.kizCategoryId());
                }
                categoryCell.setCellStyle(editableStyle);
                byte[] imageBytes = imagesByUrl.get(product.imageUrl());
                if (imageBytes != null && imageBytes.length > 0) {
                    addImage(workbook, drawing, helper, imageBytes, rowIndex);
                }
            }
            sheet.setColumnWidth(0, 14 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 42 * 256);
            sheet.setColumnWidth(3, 28 * 256);
            sheet.setColumnWidth(4, 18 * 256);
            sheet.setColumnWidth(5, 24 * 256);
            sheet.setColumnWidth(6, 18 * 256);
            sheet.createFreezePane(0, 1);

            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                workbook.write(outputStream);
            }
        }
    }

    public Map<Long, Integer> readMappings(File file) throws Exception {
        Map<Long, Integer> mappings = new LinkedHashMap<>();
        try (FileInputStream inputStream = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String nmIdText = read(row, 0);
                if (nmIdText.isBlank()) {
                    continue;
                }
                long nmId = parseLong(nmIdText, "nmId", i + 1);
                String categoryText = read(row, resolveCategoryColumn(sheet));
                Integer categoryId = categoryText.isBlank() ? null : Math.toIntExact(parseLong(categoryText, "KIZ Category ID", i + 1));
                mappings.put(nmId, categoryId);
            }
        }
        return mappings;
    }

    private void addImage(Workbook workbook, Drawing<?> drawing, CreationHelper helper, byte[] bytes, int rowIndex) {
        try {
            if (bytes == null || bytes.length == 0) {
                return;
            }
            int pictureIndex = workbook.addPicture(bytes, Workbook.PICTURE_TYPE_PNG);
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(1);
            anchor.setCol2(2);
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex + 1);
            drawing.createPicture(anchor, pictureIndex);
        } catch (Exception ignored) {
            // Export should still be usable if a thumbnail cannot be downloaded.
        }
    }

    private Map<String, byte[]> preloadImages(List<KizMappingProduct> products) {
        Map<String, byte[]> images = new LinkedHashMap<>();
        Map<String, CompletableFuture<byte[]>> pending = new LinkedHashMap<>();
        for (KizMappingProduct product : products) {
            String imageUrl = product.imageUrl();
            if (imageUrl == null || imageUrl.isBlank() || images.containsKey(imageUrl) || pending.containsKey(imageUrl)) {
                continue;
            }
            String cacheKey = PrintHistoryService.imageCacheKey(imageUrl);
            if (cacheKey == null) {
                continue;
            }
            byte[] cached = imageCacheRepository.findImage(cacheKey);
            if (cached != null && cached.length > 0) {
                images.put(imageUrl, cached);
            } else {
                pending.put(imageUrl, imageService.loadImage(imageUrl));
            }
        }
        if (pending.isEmpty()) {
            return images;
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new));
        try {
            all.get(IMAGE_PRELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Use whatever has finished within the export budget.
        }
        for (Map.Entry<String, CompletableFuture<byte[]>> entry : pending.entrySet()) {
            if (!entry.getValue().isDone() || entry.getValue().isCompletedExceptionally()) {
                continue;
            }
            try {
                byte[] image = entry.getValue().getNow(null);
                if (image != null && image.length > 0) {
                    images.put(entry.getKey(), image);
                }
            } catch (Exception ignored) {
            }
        }
        return images;
    }

    private String read(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
    }

    private int resolveCategoryColumn(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            return 6;
        }
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String value = read(header, i);
            if ("KIZ Category ID".equalsIgnoreCase(value) || "KIZ ID".equalsIgnoreCase(value)) {
                return i;
            }
        }
        return 6;
    }

    private long parseLong(String value, String column, int rowNumber) {
        String normalized = value == null ? "" : value.trim().replace(" ", "");
        if (normalized.endsWith(".0")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Dòng " + rowNumber + ": " + column + " không hợp lệ: " + value);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

}
