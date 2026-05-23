package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.shared.ConfigService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

public class PrintAuthorizationService {
    private static final String KEY_HASH = "c3d50561f76f4afcc90d0f312ebca4eee54a5a3bfd5c37e61622bb49d5d6dd13";

    public boolean isAuthorized() {
        String stored = ConfigService.getPrintAccessToken();
        return KEY_HASH.equals(stored);
    }

    public boolean matches(String candidate) {
        return KEY_HASH.equals(sha256(candidate == null ? "" : candidate.trim()));
    }

    public void rememberAuthorized() {
        ConfigService.setPrintAccessToken(KEY_HASH);
        if (ConfigService.getActivatedAt() == null || ConfigService.getActivatedAt().isBlank()) {
            ConfigService.setActivatedAt(Instant.now().toString());
        }
    }

    public void clearRememberedAuthorization() {
        ConfigService.clearPrintAccessToken();
        ConfigService.setActivatedAt("");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }
}
