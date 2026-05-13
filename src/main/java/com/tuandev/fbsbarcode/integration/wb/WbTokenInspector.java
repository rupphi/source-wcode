package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.models.Shop;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public final class WbTokenInspector {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private WbTokenInspector() {
    }

    public static TokenStatus inspect(Shop shop) {
        if (shop == null || shop.getApiKey() == null || shop.getApiKey().isBlank()) {
            return TokenStatus.invalidToken("Token WB không hợp lệ hoặc đang để trống");
        }
        try {
            String[] parts = shop.getApiKey().trim().split("\\.");
            if (parts.length < 2) {
                return TokenStatus.invalidToken("Token WB không đúng định dạng JWT");
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonObject payload = JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!payload.has("exp")) {
                return TokenStatus.ok();
            }

            long expEpochSeconds = payload.get("exp").getAsLong();
            Instant expiresAt = Instant.ofEpochSecond(expEpochSeconds);
            if (expiresAt.isAfter(Instant.now())) {
                return TokenStatus.ok();
            }

            String formatted = LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()).format(FORMATTER);
            return TokenStatus.expiredToken("Token WB của cửa hàng đã hết hạn lúc " + formatted + ". Vui lòng cập nhật lại token.");
        } catch (Exception ex) {
            return TokenStatus.invalidToken("Không thể đọc hạn token WB. Vui lòng kiểm tra lại token.");
        }
    }

    public record TokenStatus(boolean valid, String message) {
        public static TokenStatus ok() {
            return new TokenStatus(true, null);
        }

        public static TokenStatus expiredToken(String message) {
            return new TokenStatus(false, message);
        }

        public static TokenStatus invalidToken(String message) {
            return new TokenStatus(false, message);
        }
    }
}
