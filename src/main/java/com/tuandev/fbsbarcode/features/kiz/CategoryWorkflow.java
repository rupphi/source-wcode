package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.features.kiz.CategoryRepository;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class CategoryWorkflow {
    private final CategoryDialogService categoryDialogService = new CategoryDialogService();
    private final KizInventoryWorkflow kizInventoryWorkflow = new KizInventoryWorkflow();
    private final CategoryRepository categoryRepository = new CategoryRepository();

    public Optional<Category> requestCreateCategory() {
        return categoryDialogService.showCreateDialog();
    }

    public Optional<Category> requestEditCategory(Category category) {
        return categoryDialogService.showEditDialog(category);
    }

    public int createCategory(Category category) throws SQLException {
        return categoryRepository.insert(category);
    }

    public int updateCategoryName(Category category) throws SQLException {
        return categoryRepository.updateName(category);
    }

    public List<Category> loadCategories(int shopId) {
        return categoryRepository.findAllForShop(shopId);
    }

    public int importKizFromPdf(File file, Shop shop, Category category) throws IOException, InterruptedException {
        return kizInventoryWorkflow.importKizFromPdf(file, shop, category);
    }

    public void deleteCategory(Shop shop, Category category) {
        KizService.deleteKizs(shop.getId(), category.getId());
    }
}
