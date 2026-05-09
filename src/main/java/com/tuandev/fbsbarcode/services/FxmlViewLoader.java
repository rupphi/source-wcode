package com.tuandev.fbsbarcode.services;

import javafx.fxml.FXMLLoader;

import java.io.IOException;
import java.net.URL;

public final class FxmlViewLoader {
    private FxmlViewLoader() {
    }

    public static FXMLLoader loader(Class<?> resourceOwner, String resourceName) {
        URL resource = resourceOwner.getResource(resourceName);
        if (resource == null) {
            throw new IllegalStateException("Không tìm thấy FXML: " + resourceName);
        }
        return new FXMLLoader(resource);
    }

    public static <T> T load(FXMLLoader loader) {
        try {
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Không thể load FXML: " + loader.getLocation(), e);
        }
    }
}
