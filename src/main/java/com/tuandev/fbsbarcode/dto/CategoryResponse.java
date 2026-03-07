package com.tuandev.fbsbarcode.dto;

import com.tuandev.fbsbarcode.models.CategoryWB;

import java.util.List;

public class CategoryResponse {
    private List<CategoryWB> categories;

    public CategoryResponse() {
    }

    public CategoryResponse(List<CategoryWB> categories) {
        this.categories = categories;
    }

    public List<CategoryWB> getCategories() {
        return categories;
    }

    public void setCategories(List<CategoryWB> categories) {
        this.categories = categories;
    }
}
