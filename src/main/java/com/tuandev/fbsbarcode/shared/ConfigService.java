package com.tuandev.fbsbarcode.shared;

import com.tuandev.fbsbarcode.config.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public class ConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);

    public static String getConfigValue(String key) {
        String sql = "SELECT value FROM app_config WHERE key = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) {
            LOGGER.error("Failed to read config key: " + key, e);
        }
        return null;
    }

    public static void setConfigValue(String key, String value) {
        String sql = "INSERT INTO app_config (key, value) VALUES (?, ?) " +
                     "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to write config key: " + key, e);
        }
    }

    public static String getSkippedVersion() {
        return getConfigValue("update_skipped_version");
    }

    public static void setSkippedVersion(String version) {
        setConfigValue("update_skipped_version", version);
    }

    public static String getLastUpdateCheck() {
        return getConfigValue("update_last_check");
    }

    public static void setLastUpdateCheck(String timestamp) {
        setConfigValue("update_last_check", timestamp);
    }

    public static int getPrintType() {
        String sql = "SELECT type FROM config";
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(sql);
            if (rs.next()) return rs.getInt("type");
        } catch (SQLException e) {
            LOGGER.error("Failed to read print type", e);
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
            LOGGER.error("Failed to update print type", e);
        }
    }
}
