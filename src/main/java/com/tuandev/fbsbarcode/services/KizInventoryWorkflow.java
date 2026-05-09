package com.tuandev.fbsbarcode.services;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class KizInventoryWorkflow {
    public int importKizFromPdf(File file, Shop shop, Category category) throws IOException, InterruptedException {
        List<String> kizCodes = PdfDataMatrixReader.readDataMatrixFromPdf(file);
        return KizService.addKizs(shop.getId(), category.getId(), kizCodes);
    }
}
