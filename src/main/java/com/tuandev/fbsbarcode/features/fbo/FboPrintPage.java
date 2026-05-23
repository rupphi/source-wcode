package com.tuandev.fbsbarcode.features.fbo;

public record FboPrintPage(FboProductSku product, String kizCode, int pairNumber) {
    public static FboPrintPage barcode(FboProductSku product, int pairNumber) {
        return new FboPrintPage(product, null, pairNumber);
    }

    public static FboPrintPage barcodeWithKiz(FboProductSku product, String kizCode, int pairNumber) {
        return new FboPrintPage(product, kizCode, pairNumber);
    }
}
