package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OzonProductGtinMappingRepository {
    public void put(int shopId, String sku, String gtin) {
        String safeSku = OzonApiClient.requireExternalId(sku, "SKU");
        String normalizedGtin = GtinNormalizer.normalize(gtin);
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                        VALUES(?,?,?,?,?)
                        ON CONFLICT(shop_id,sku) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safeSku);
            statement.setString(3, normalizedGtin);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public String findGtin(int shopId, String sku) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT gtin FROM ozon_product_gtin_mappings WHERE shop_id=? AND sku=?")) {
            statement.setInt(1, shopId);
            statement.setString(2, OzonApiClient.requireExternalId(sku, "SKU"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public Map<String, String> findAll(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT sku,gtin FROM ozon_product_gtin_mappings WHERE shop_id=? ORDER BY sku")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                Map<String, String> mappings = new LinkedHashMap<>();
                while (result.next()) mappings.put(result.getString(1), result.getString(2));
                return Map.copyOf(mappings);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Atomically replaces every Ozon catalog SKU assigned to one Znack GTIN. */
    public void replaceForGtin(int shopId, String gtin, List<String> skus) {
        if (shopId <= 0 || skus == null || skus.size() > 10_000) {
            throw new IllegalArgumentException("Invalid Ozon catalog mapping.");
        }
        String normalizedGtin = GtinNormalizer.normalize(gtin);
        List<String> normalizedSkus = skus.stream()
                .map(sku -> OzonApiClient.requireExternalId(sku, "SKU"))
                .distinct()
                .toList();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireGtin(connection, shopId, normalizedGtin);
                for (String sku : normalizedSkus) requireCatalogSku(connection, shopId, sku);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM ozon_product_gtin_mappings WHERE shop_id=? AND gtin=?")) {
                    delete.setInt(1, shopId);
                    delete.setString(2, normalizedGtin);
                    delete.executeUpdate();
                }
                String now = Instant.now().toString();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                        VALUES(?,?,?,?,?)
                        ON CONFLICT(shop_id,sku) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at
                        """)) {
                    for (String sku : normalizedSkus) {
                        insert.setInt(1, shopId);
                        insert.setString(2, sku);
                        insert.setString(3, normalizedGtin);
                        insert.setString(4, now);
                        insert.setString(5, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public int delete(int shopId, String sku) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ozon_product_gtin_mappings WHERE shop_id=? AND sku=?")) {
            statement.setInt(1, shopId);
            statement.setString(2, OzonApiClient.requireExternalId(sku, "SKU"));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void requireGtin(Connection connection, int shopId, String gtin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM znack_products WHERE shop_id=? AND gtin=? AND deleted_at IS NULL")) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("GTIN is not registered for this shop.");
            }
        }
    }

    private static void requireCatalogSku(Connection connection, int shopId, String sku) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ozon_products WHERE shop_id=? AND sku=? AND archived=0")) {
            statement.setInt(1, shopId);
            statement.setString(2, sku);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("SKU is not in the current Ozon catalog.");
            }
        }
    }
}
