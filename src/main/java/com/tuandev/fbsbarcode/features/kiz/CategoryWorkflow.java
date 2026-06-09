package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.Shop;

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

    public Optional<Category> requestCreateCategory(int shopId) {
        return categoryDialogService.showCreateDialog(categoryRepository.nextDisplayIdForShop(shopId));
    }

    public Optional<Category> requestEditCategory(Category category) {
        return categoryDialogService.showEditDialog(category);
    }

    public int createCategory(Shop shop, Category category) throws SQLException {
        return categoryRepository.insertForShop(shop.getId(), category);
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

    public int clearKizCount(Shop shop, Category category) {
        return categoryRepository.clearKizCountForShop(shop.getId(), category.getId());
    }

    public void deleteCategory(Shop shop, Category category) {
        categoryRepository.deleteFromShop(shop.getId(), category.getId());
    }
}
