package com.tuandev.fbsbarcode.features.print;

import com.google.zxing.WriterException;
import com.tuandev.fbsbarcode.models.Order;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class BarcodePrintService {
    public void export(int printType, List<Order> orders, File outputFile) throws IOException, WriterException {
        if (printType == 1) {
            GenerateBarcode.type1(orders, outputFile);
            return;
        }
        if (printType == 2) {
            GenerateBarcode.type2(orders, outputFile);
            return;
        }
        GenerateBarcode.type3(orders, outputFile);
    }
}
