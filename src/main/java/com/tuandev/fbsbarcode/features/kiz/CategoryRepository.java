package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CategoryRepository {
    public int insert(Category category) throws SQLException {
        String sql = "INSERT INTO categories (id, name) VALUES (?, ?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, category.getId());
            ps.setString(2, category.getName());
            return ps.executeUpdate();
        }
    }

    public List<Category> findAllForShop(int shopId) {
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
}
