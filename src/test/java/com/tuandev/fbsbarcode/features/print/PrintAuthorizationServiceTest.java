package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.shared.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintAuthorizationServiceTest {
    private final PrintAuthorizationService service = new PrintAuthorizationService();

    @BeforeEach
    void setup() {
        Database.initDatabase();
        ConfigService.clearPrintAccessToken();
    }

    @AfterEach
    void cleanup() {
        ConfigService.clearPrintAccessToken();
    }

    @Test
    void shouldRejectUnknownKeys() {
        assertFalse(service.matches("hongrancho"));
        assertFalse(service.matches("wrong-key"));
    }

    @Test
    void shouldRememberAuthorization() {
        assertFalse(service.isAuthorized());
        service.rememberAuthorized();
        assertTrue(service.isAuthorized());
    }
}
