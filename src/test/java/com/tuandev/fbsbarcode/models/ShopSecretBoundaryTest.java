package com.tuandev.fbsbarcode.models;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShopSecretBoundaryTest {
    @Test
    void toStringReportsConfigurationWithoutRenderingApiKey() {
        String secret = "wb-secret-that-must-never-enter-logs";

        String rendered = new Shop(7, "Main", secret).toString();

        assertFalse(rendered.contains(secret));
        assertTrue(rendered.contains("apiKeyConfigured=true"));
    }
}
