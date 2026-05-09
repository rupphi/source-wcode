package com.tuandev.fbsbarcode.services;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public class PdfDataMatrixReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(PdfDataMatrixReader.class);
    private static final int DECODE_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final ExecutorService DECODE_EXECUTOR = Executors.newFixedThreadPool(DECODE_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "fbsbarcode-datamatrix");
        thread.setDaemon(true);
        return thread;
    });

    public static List<String> readDataMatrixFromPdf(File pdfFile) throws IOException, InterruptedException {
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();

            List<Future<?>> futures = new ArrayList<>();

            for (int page =  0; page < totalPages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 200);

                futures.add(DECODE_EXECUTOR.submit(() -> {
                    String code = decodeDataMatrix(image);
                    if (code != null && code.length() > 20) {
                        results.add(code);
                    }
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException | InterruptedException e) {
                    LOGGER.warn("Không thể đọc DataMatrix từ một trang trong file {}", pdfFile.getAbsolutePath(), e);
                    if (e instanceof InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw interruptedException;
                    }
                }
            }
        }
        return results;
    }

    private static String decodeDataMatrix(BufferedImage image) {
        String result = decode(image);
        if (result == null) {
            BufferedImage lefHalf = image.getSubimage(0, 0, image.getWidth() / 2, image.getHeight());
            result = decode(lefHalf);
        }

        return result;
    }

    private static String decode(BufferedImage image) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            DataMatrixReader reader = new DataMatrixReader();
            Result result = reader.decode(bitmap);

            return result.getText();
        } catch (ChecksumException | NotFoundException | FormatException e) {
            return null;
        }
    }
}
