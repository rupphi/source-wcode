package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingProduct;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

public class KizMappingRow {
    private final KizMappingProduct product;
    private final SimpleObjectProperty<Integer> categoryId = new SimpleObjectProperty<>();
    private final SimpleIntegerProperty saveState = new SimpleIntegerProperty(0);

    public KizMappingRow(KizMappingProduct product) {
        this.product = product;
        this.categoryId.set(product.kizCategoryId());
    }

    public KizMappingProduct product() {
        return product;
    }

    public Integer getCategoryId() {
        return categoryId.get();
    }

    public void setCategoryId(Integer value) {
        categoryId.set(value);
    }

    public SimpleObjectProperty<Integer> categoryIdProperty() {
        return categoryId;
    }

    public int getSaveState() {
        return saveState.get();
    }

    public void setSaveState(int value) {
        saveState.set(value);
    }

    public SimpleIntegerProperty saveStateProperty() {
        return saveState;
    }
}
