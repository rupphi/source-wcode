package com.tuandev.fbsbarcode.services;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.*;

public class ConfigService {
    public static int getPrintType() {
        String sql = "SELECT type FROM config";
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(sql);
            while (rs.next()) return rs.getInt("type");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 1;
    }

    public static void updatePrintType(int type) {
        String sql = "UPDATE config SET type = ? WHERE id = 1";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, type);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
