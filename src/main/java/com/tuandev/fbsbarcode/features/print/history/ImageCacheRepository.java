package com.tuandev.fbsbarcode.features.print.history;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public class ImageCacheRepository {
    public byte[] findImage(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return null;
        }
        String sql = "SELECT image_blob FROM image_cache WHERE cache_key = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            touch(cacheKey);
            return rs.getBytes("image_blob");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveImage(String cacheKey, String imageUrl, byte[] imageBlob, String contentType) {
        if (cacheKey == null || cacheKey.isBlank() || imageBlob == null || imageBlob.length == 0) {
            return;
        }
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO image_cache(cache_key, image_url, image_blob, content_type, updated_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(cache_key) DO UPDATE SET
                    image_url = excluded.image_url,
                    image_blob = excluded.image_blob,
                    content_type = excluded.content_type,
                    updated_at = excluded.updated_at,
                    last_used_at = excluded.last_used_at
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ps.setString(2, imageUrl == null ? cacheKey : imageUrl);
            ps.setBytes(3, imageBlob);
            ps.setString(4, contentType);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void touch(String cacheKey) {
        String sql = "UPDATE image_cache SET last_used_at = ? WHERE cache_key = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, cacheKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
