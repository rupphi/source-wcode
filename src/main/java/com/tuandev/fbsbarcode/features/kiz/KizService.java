package com.tuandev.fbsbarcode.features.kiz;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Kiz;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class KizService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KizService.class);
    private static final int MAX_ATTACH_ATTEMPTS = 4;
    private static final long ATTACH_BASE_RETRY_DELAY_MS = 400L;
    private static final long ATTACH_REQUEST_SPACING_MS = 150L;
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static List<Kiz> getKizs(int shopId, int categoryId, int count) {
        List<Kiz> kizList = new ArrayList<>();

        String sql = "SELECT id, code FROM kizs WHERE shop_id = ? AND category_id = ? ORDER BY id LIMIT ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.setInt(3, count);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                kizList.add(new Kiz(rs.getInt("id"), rs.getString("code")));
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return kizList;
    }

    public static int addKizs(int shopId, int categoryId, List<String> codes) {
        String sql = "INSERT INTO kizs (shop_id, category_id, code) VALUES (?, ?, ?)";
         try (Connection conn = Database.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {

             for(String code : codes) {
                 ps.setInt(1, shopId);
                 ps.setInt(2, categoryId);
                 ps.setString(3, code);

                 ps.addBatch();
             }

             int[] result = ps.executeBatch();
             return result.length;
         } catch (SQLException e) {
             throw new RuntimeException(e);
         }
    }

    public static void deleteKizs(int shopId, int categoryId) {
        String sql = "DELETE FROM kizs WHERE shop_id = ? AND category_id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void deleteKizs(List<Kiz> kizList) {
        String sql = "DELETE FROM kizs WHERE id = ?";

        try (Connection conn = Database.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Kiz kiz : kizList) {
                ps.setInt(1, kiz.getId());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static AttachCodeResult addDataMatrixCodeToOrder(String apiKey, Long orderId, String code) throws IOException {
        AttachCodeResult lastResult = null;
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTACH_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepQuietly(retryDelayForAttempt(attempt));
            }

            try {
                AttachCodeResult result = sendAttachCodeRequest(apiKey, orderId, code);
                lastResult = result;
                if (result.success()) {
                    sleepQuietly(ATTACH_REQUEST_SPACING_MS);
                    return result;
                }
                if (!isRetryableStatus(result.statusCode())) {
                    sleepQuietly(ATTACH_REQUEST_SPACING_MS);
                    return result;
                }
                LOGGER.warn("WB attach KIZ retrying for order {} after status {} on attempt {}/{}",
                        orderId, result.statusCode(), attempt, MAX_ATTACH_ATTEMPTS);
            } catch (IOException ex) {
                lastException = ex;
                if (attempt >= MAX_ATTACH_ATTEMPTS) {
                    throw ex;
                }
                LOGGER.warn("WB attach KIZ retrying for order {} after IO error on attempt {}/{}",
                        orderId, attempt, MAX_ATTACH_ATTEMPTS, ex);
            }
        }

        if (lastResult != null) {
            sleepQuietly(ATTACH_REQUEST_SPACING_MS);
            return lastResult;
        }
        if (lastException != null) {
            throw lastException;
        }
        return new AttachCodeResult(false, 0, "WB attach KIZ failed");
    }

    private static AttachCodeResult sendAttachCodeRequest(String apiKey, Long orderId, String code) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/" + orderId + "/meta/sgtin";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("sgtins", List.of(code));

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), gson.toJson(bodyMap));

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .put(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                if (response.code() == 409 && responseBody.contains("FailedToUpdateMeta")) {
                    LOGGER.info("WB skipped KIZ attach for order {} because it is not in Processing status: {}", orderId, responseBody);
                } else {
                    LOGGER.warn("WB attach KIZ failed for order {} with status {} and body {}", orderId, response.code(), responseBody);
                }
            }
            return new AttachCodeResult(response.isSuccessful(), response.code(), responseBody);
        }
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static long retryDelayForAttempt(int attempt) {
        long multiplier = 1L << Math.max(0, attempt - 2);
        return ATTACH_BASE_RETRY_DELAY_MS * multiplier;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record AttachCodeResult(boolean success, int statusCode, String responseBody) {
    }
}
