package com.tuandev.fbsbarcode.features.print;

public enum PrintPageOrder {
    BARCODE_THEN_STICKER("Сначала barcode, потом стикер WB"),
    STICKER_THEN_BARCODE("Сначала стикер WB, потом barcode");

    private final String label;

    PrintPageOrder(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
