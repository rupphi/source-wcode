package com.tuandev.fbsbarcode.features.shop;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ShopRepository {
    public int insert(Shop shop) {
        String sql = "INSERT INTO shops (name, api_key) VALUES (?, ?)";

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getApiKey());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int update(int id, Shop shop) {
        String sql = "UPDATE shops SET name = ?, api_key = ? WHERE id = ?";

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getApiKey());
            ps.setInt(3, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Shop> findAll() {
        String sql = "SELECT id, name, api_key FROM shops";
        List<Shop> shops = new ArrayList<>();

        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                shops.add(new Shop(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("api_key")
                ));
            }
            return shops;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int delete(int id) {
        String sql = "DELETE FROM shops WHERE id = ?";
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, id);
                int deleted = ps.executeUpdate();
                conn.commit();
                return deleted;
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
}
