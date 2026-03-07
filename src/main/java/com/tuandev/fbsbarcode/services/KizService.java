package com.tuandev.fbsbarcode.services;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Kiz;
import okhttp3.*;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class KizService {
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static List<Kiz> getKizs(int shopId, int categoryId, int count) {
        List<Kiz> kizList = new ArrayList<>();

        String sql = "SELECT id, code FROM kizs WHERE shop_id = ? AND category_id = ? LIMIT ?";
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

    public static int addDataMatrixCodeToOrder(String apiKey, Long orderId, String code) throws IOException {
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
            return response.code();
        }
    }
}
