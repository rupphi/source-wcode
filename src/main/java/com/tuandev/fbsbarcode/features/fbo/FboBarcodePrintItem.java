package com.tuandev.fbsbarcode.features.fbo;

public record FboBarcodePrintItem(FboProductSku product, int quantity) {
    public int pageCount() {
        return Math.max(0, quantity) * 2;
    }
}
