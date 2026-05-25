package com.tuandev.fbsbarcode.features.kizmapping;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KizMappingExcelServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldReadMappingWorkbookWithManyInternalZipEntries() throws Exception {
        Path baseWorkbook = tempDir.resolve("mapping-base.xlsx");
        Path largeWorkbook = tempDir.resolve("mapping-large.xlsx");
        writeBaseWorkbook(baseWorkbook);
        copyWithExtraZipEntries(baseWorkbook, largeWorkbook, 1_100);

        Map<Long, Integer> mappings = new KizMappingExcelService().readMappings(largeWorkbook.toFile());

        assertEquals(Map.of(1001L, 10), mappings);
    }

    private void writeBaseWorkbook(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("KIZ Mapping");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("nmId");
            header.createCell(6).setCellValue("KIZ Category ID");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1001L);
            row.createCell(6).setCellValue(10);
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }
    }

    private void copyWithExtraZipEntries(Path source, Path target, int extraEntries) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(source.toFile()));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target.toFile()))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                out.closeEntry();
                in.closeEntry();
            }
            byte[] payload = "<unused/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < extraEntries; i++) {
                out.putNextEntry(new ZipEntry("xl/unused/unused-" + i + ".xml"));
                out.write(payload);
                out.closeEntry();
            }
        }
        assertTrue(Files.exists(target));
    }
}
