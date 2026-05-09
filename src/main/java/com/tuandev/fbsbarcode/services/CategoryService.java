package com.tuandev.fbsbarcode.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.dto.CategoryResponse;
import com.tuandev.fbsbarcode.models.Category;
import com.tuandev.fbsbarcode.models.CategoryWB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class CategoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CategoryService.class);

    public static int createCategory(Category category) throws SQLException {
        String sql = "INSERT INTO categories (id, name) VALUES (?, ?)";
        try (Connection conn = Database.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, category.getId());
            ps.setString(2, category.getName());
            return ps.executeUpdate();
        }
    }

    public static void deleteCategory(int id) throws SQLException {
        String sql = "DELETE FROM categories WHERE id = ?";
        try (Connection conn = Database.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public static List<Category> getAllCategories(int shopId) {
        List<Category> categories = new ArrayList<>();
        String sql = """
                SELECT c.id, c.name, COUNT(k.code) AS kizs_count
                FROM categories c
                LEFT JOIN kizs k ON c.id = k.category_id AND k.shop_id = ?
                GROUP BY c.id, c.name
                ORDER BY c.id
                """;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                categories.add(new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("kizs_count")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return categories;
    }

    private static final Gson GSON = new GsonBuilder().create();

    public static List<CategoryWB> loadCategories() {
        try (Reader reader = new InputStreamReader(CategoryService.class.getResourceAsStream("categories.json"), StandardCharsets.UTF_8)) {
            CategoryResponse response = GSON.fromJson(reader, CategoryResponse.class);

            if (response == null || response.getCategories() == null) {
                return Collections.emptyList();
            }

            return response.getCategories();
        } catch (Exception e) {
            LOGGER.error("Không thể tải categories.json", e);
            return Collections.emptyList();
        }
    }

}
