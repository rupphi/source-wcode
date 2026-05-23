package com.tuandev.fbsbarcode.features.dashboard;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DashboardRepository {
    public DashboardKpis loadKpis(int shopId) {
        try (Connection conn = Database.getConnection()) {
            return new DashboardKpis(
                    count(conn, "SELECT COUNT(*) FROM wb_product_cards WHERE shop_id = ?", shopId),
                    count(conn, """
                            SELECT COUNT(*)
                            FROM wb_orders
                            WHERE shop_id = ?
                              AND supplier_status = 'new'
                              AND (supply_id IS NULL OR TRIM(supply_id) = '')
                            """, shopId),
                    count(conn, "SELECT COUNT(*) FROM wb_supplies WHERE shop_id = ? AND COALESCE(done, 0) = 0", shopId)
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<Long, DashboardProductInfo> findProductInfo(int shopId, List<Long> nmIds) {
        if (nmIds == null || nmIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(nmIds.size(), "?"));
        String sql = """
                SELECT c.nm_id, c.vendor_code, c.title,
                       p.c246x328_url, p.square_url, p.big_url, p.hq_url, p.tm_url
                FROM wb_product_cards c
                LEFT JOIN wb_product_photos p ON p.shop_id = c.shop_id AND p.nm_id = c.nm_id AND p.photo_index = 0
                WHERE c.shop_id = ? AND c.nm_id IN (%s)
                """.formatted(placeholders);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            for (int i = 0; i < nmIds.size(); i++) {
                ps.setLong(i + 2, nmIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, DashboardProductInfo> result = new HashMap<>();
                while (rs.next()) {
                    long nmId = rs.getLong("nm_id");
                    result.put(nmId, new DashboardProductInfo(
                            nmId,
                            rs.getString("vendor_code"),
                            rs.getString("title"),
                            firstNonBlank(
                                    rs.getString("c246x328_url"),
                                    rs.getString("square_url"),
                                    rs.getString("big_url"),
                                    rs.getString("hq_url"),
                                    rs.getString("tm_url")
                            )
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long count(Connection conn, String sql, int shopId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
