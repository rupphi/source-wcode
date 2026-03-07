package com.tuandev.fbsbarcode.services;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Category;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CategoryService {
    public static int createCategory(Category category) throws SQLException {
        String sql = "INSERT INTO categories (id, name) VALUES (?, ?)";
        try (Connection conn = Database.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, category.getId());
            ps.setString(2, category.getName());
            return ps.executeUpdate();
        }
    }

    public static List<Category> getAllCategories(int shopId) {
        List<Category> categories = new ArrayList<>();
        String sql = "SELECT c.id, c.name, COUNT(k.code) as kizs_count FROM categories c LEFT JOIN kizs k ON c.id = k.category_id AND k.shop_id = " + shopId + " GROUP BY c.id, c.name ORDER BY c.id";

        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(sql);
            while (rs.next()) {
                categories.add(new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("kizs_count")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return categories;
    }
}
