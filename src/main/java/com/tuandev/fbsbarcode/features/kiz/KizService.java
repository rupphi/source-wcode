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
                LOGGER.warn("WB attach KIZ failed for order {} with status {} and body {}", orderId, response.code(), responseBody);
            }
            return new AttachCodeResult(response.isSuccessful(), response.code(), responseBody);
        }
    }

    public record AttachCodeResult(boolean success, int statusCode, String responseBody) {
    }
}
