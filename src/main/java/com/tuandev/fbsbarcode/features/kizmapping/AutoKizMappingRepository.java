package com.tuandev.fbsbarcode.features.kizmapping;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class AutoKizMappingRepository {
    public AutoKizMappingResult autoCreateAndMap(int shopId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                AutoKizMappingResult result = autoCreateAndMap(conn, shopId);
                conn.commit();
                return result;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private AutoKizMappingResult autoCreateAndMap(Connection conn, int shopId) throws SQLException {
        Map<String, Integer> categoriesByName = loadCategoriesByNormalizedName(conn);
        int nextCategoryId = nextCategoryId(conn);
        int categoriesCreated = 0;
        int mappingsCreated = 0;

        for (ProductSubject product : findUnmappedKizProducts(conn, shopId).values()) {
            String normalizedName = normalize(product.subjectName());
            Integer categoryId = categoriesByName.get(normalizedName);
            if (categoryId == null) {
                categoryId = nextCategoryId++;
                insertCategory(conn, categoryId, product.subjectName().trim());
                categoriesByName.put(normalizedName, categoryId);
                categoriesCreated++;
            }
            mappingsCreated += upsertMissingMapping(conn, shopId, product.nmId(), categoryId);
        }

        return new AutoKizMappingResult(categoriesCreated, mappingsCreated);
    }

    private Map<String, Integer> loadCategoriesByNormalizedName(Connection conn) throws SQLException {
        Map<String, Integer> categories = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM categories ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String normalizedName = normalize(rs.getString("name"));
                if (!normalizedName.isBlank()) {
                    categories.putIfAbsent(normalizedName, rs.getInt("id"));
                }
            }
        }
        return categories;
    }

    private int nextCategoryId(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM categories");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    private Map<Long, ProductSubject> findUnmappedKizProducts(Connection conn, int shopId) throws SQLException {
        Map<Long, ProductSubject> products = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.nm_id, c.subject_name
                FROM wb_product_cards c
                LEFT JOIN wb_product_kiz_mappings m ON m.shop_id = c.shop_id AND m.nm_id = c.nm_id
                WHERE c.shop_id = ?
                  AND (COALESCE(c.need_kiz, 0) = 1 OR COALESCE(c.kiz_marked, 0) = 1)
                  AND c.subject_name IS NOT NULL
                  AND TRIM(c.subject_name) <> ''
                  AND m.kiz_category_id IS NULL
                ORDER BY c.subject_name COLLATE NOCASE, c.nm_id
                """)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    products.put(rs.getLong("nm_id"), new ProductSubject(
                            rs.getLong("nm_id"),
                            rs.getString("subject_name")
                    ));
                }
            }
        }
        return products;
    }

    private void insertCategory(Connection conn, int categoryId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO categories (id, name) VALUES (?, ?)")) {
            ps.setInt(1, categoryId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private int upsertMissingMapping(Connection conn, int shopId, long nmId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO wb_product_kiz_mappings (shop_id, nm_id, kiz_category_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(shop_id, nm_id) DO UPDATE SET
                    kiz_category_id = excluded.kiz_category_id,
                    updated_at = excluded.updated_at
                WHERE wb_product_kiz_mappings.kiz_category_id IS NULL
                """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setInt(3, categoryId);
            ps.setString(4, Instant.now().toString());
            return ps.executeUpdate();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ProductSubject(long nmId, String subjectName) {
    }
}
