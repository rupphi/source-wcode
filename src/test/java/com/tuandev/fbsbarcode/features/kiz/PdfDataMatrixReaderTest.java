package com.tuandev.fbsbarcode.features.kiz;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfDataMatrixReaderTest {
    @Test
    void decodeDataMatrixFallsBackToRightHalfAfterFullPageAndLeftHalf() {
        BufferedImage image = new BufferedImage(101, 37, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, x < image.getWidth() / 2 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }

        List<String> attempts = new ArrayList<>();
        String decodedCode = "010465039888513821RIGHTHALFCODE";

        String result = PdfDataMatrixReader.decodeDataMatrix(image, candidate -> {
            String region = candidate.getWidth() + "x" + candidate.getHeight() + ":"
                    + (candidate.getRGB(0, 0) == Color.BLUE.getRGB() ? "right" : "left");
            attempts.add(region);
            return "51x37:right".equals(region) ? decodedCode : null;
        });

        assertEquals(decodedCode, result);
        assertEquals(List.of("101x37:left", "50x37:left", "51x37:right"), attempts);
    }
}
