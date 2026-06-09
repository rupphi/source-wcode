package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CategoryRepository {
    public int insertForShop(int shopId, Category category) throws SQLException {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (category.getId() <= 0) {
                    category.setId(nextCategoryId(conn));
                }
                int rows = insertCategory(conn, category);
                attachToShop(conn, shopId, category.getId());
                conn.commit();
                return rows;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public int updateName(Category category) throws SQLException {
        String sql = "UPDATE categories SET name = ? WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, category.getName());
            ps.setInt(2, category.getId());
            return ps.executeUpdate();
        }
    }

    public List<Category> findAllForShop(int shopId) {
        List<Category> categories = new ArrayList<>();
        String sql = """
                SELECT c.id, c.name, COUNT(k.code) AS kizs_count
                FROM shop_categories sc
                JOIN categories c ON c.id = sc.category_id
                LEFT JOIN kizs k ON c.id = k.category_id AND k.shop_id = ?
                WHERE sc.shop_id = ?
                GROUP BY c.id, c.name
                ORDER BY c.id
                """;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                categories.add(new Category(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getInt("kizs_count"),
                        categories.size() + 1
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return categories;
    }

    public int clearKizCountForShop(int shopId, int categoryId) {
        String sql = "DELETE FROM kizs WHERE shop_id = ? AND category_id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteFromShop(int shopId, int categoryId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                deleteKizs(conn, shopId, categoryId);
                detachFromShop(conn, shopId, categoryId);
                deleteUnreferencedCategory(conn, categoryId);
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void attachToShop(int shopId, int categoryId) throws SQLException {
        try (Connection conn = Database.getConnection()) {
            attachToShop(conn, shopId, categoryId);
        }
    }

    public int nextDisplayIdForShop(int shopId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) + 1 FROM shop_categories WHERE shop_id = ?")) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int insertCategory(Connection conn, Category category) throws SQLException {
        String sql = "INSERT INTO categories (id, name) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, category.getId());
            ps.setString(2, category.getName());
            return ps.executeUpdate();
        }
    }

    private int nextCategoryId(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM categories");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    private void attachToShop(Connection conn, int shopId, int categoryId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO shop_categories (shop_id, category_id, created_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private void deleteKizs(Connection conn, int shopId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM kizs WHERE shop_id = ? AND category_id = ?")) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.executeUpdate();
        }
    }

    private void detachFromShop(Connection conn, int shopId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM shop_categories WHERE shop_id = ? AND category_id = ?")) {
            ps.setInt(1, shopId);
            ps.setInt(2, categoryId);
            ps.executeUpdate();
        }
    }

    private void deleteUnreferencedCategory(Connection conn, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM categories
                WHERE id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_categories WHERE category_id = ?
                  )
                """)) {
            ps.setInt(1, categoryId);
            ps.setInt(2, categoryId);
            ps.executeUpdate();
        }
    }
}
