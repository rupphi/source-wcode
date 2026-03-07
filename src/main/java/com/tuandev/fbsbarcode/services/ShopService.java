package com.tuandev.fbsbarcode.services;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Shop;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ShopService {
    public static int addShop(Shop shop) {
        String sql = "INSERT INTO shops (name, api_key) VALUES (?, ?)";

        try (PreparedStatement ps = Database.getConnection().prepareStatement(sql)) {
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getApiKey());

            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int updateShop(int id, Shop shop) {
        String sql = "UPDATE shops SET name = ?, api_key = ? WHERE id = ?";

        try (PreparedStatement ps = Database.getConnection().prepareStatement(sql)) {
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getApiKey());
            ps.setInt(3, id);

            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Shop> getAllShops() {
        String sql = "SELECT id, name, api_key FROM shops";
        List<Shop> shops = new ArrayList<>();

        try (Statement st = Database.getConnection().createStatement()) {
            ResultSet rs = st.executeQuery(sql);
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
}
