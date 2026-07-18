package com.tuandev.fbsbarcode.jdesk.shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite implementation that keeps each shop mutation and selection change in one transaction. */
final class SqliteShopStore implements ShopCommandService.ShopStore {
    private static final int MAX_SHOPS = 500;
    private static final String SELECTION_KEY = "last_selected_shop_id";
    private static final String[] TERMINAL_PURCHASE_STAGES = {
        "COMPLETED",
        "INTRODUCED",
        "FAILED",
        "INTRODUCTION_FAILED",
        "INTRODUCTION_SKIPPED_MISSING_DOCUMENTS",
        "INTRODUCTION_SKIPPED_MISSING_METADATA"
    };

    private final ConnectionFactory connections;

    SqliteShopStore(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public ShopCommandService.ShopState list() {
        try (Connection connection = connections.open()) {
            return readState(connection);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    @Override
    public ShopCommandService.ShopState create(String name, String apiKey) {
        return transaction(connection -> {
            if (shopCount(connection) >= MAX_SHOPS) {
                throw new ShopCommandService.ShopStoreException("shop_limit");
            }
            int shopId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shops(name,api_key) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, apiKey);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Shop insert did not affect one row");
                }
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Shop insert did not return an id");
                    }
                    shopId = keys.getInt(1);
                }
            }
            if (shopId <= 0) {
                throw new SQLException("Shop insert returned an invalid id");
            }
            writeSelection(connection, shopId);
            return readState(connection);
        });
    }

    @Override
    public ShopCommandService.ShopState update(int shopId, String name, String apiKey) {
        return transaction(connection -> {
            String sql = apiKey == null
                    ? "UPDATE shops SET name=? WHERE id=?"
                    : "UPDATE shops SET name=?,api_key=? WHERE id=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                if (apiKey == null) {
                    statement.setInt(2, shopId);
                } else {
                    statement.setString(2, apiKey);
                    statement.setInt(3, shopId);
                }
                if (statement.executeUpdate() != 1) {
                    throw new ShopCommandService.ShopStoreException("shop_not_found");
                }
            }
            return readState(connection);
        });
    }

    @Override
    public ShopCommandService.ShopState select(int shopId) {
        return transaction(connection -> {
            requireShop(connection, shopId);
            writeSelection(connection, shopId);
            return readState(connection);
        });
    }

    @Override
    public ShopCommandService.ShopState delete(int shopId) {
        return transaction(connection -> {
            requireShop(connection, shopId);
            if (hasActivePurchasePipeline(connection, shopId)) {
                throw new ShopCommandService.ShopStoreException("shop_busy");
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM shops WHERE id=?")) {
                statement.setInt(1, shopId);
                if (statement.executeUpdate() != 1) {
                    throw new ShopCommandService.ShopStoreException("shop_not_found");
                }
            }
            ShopCommandService.ShopState state = readState(connection);
            int selected = state.hasSelectedShop() ? state.selectedShopId() : firstShopId(state.shops());
            writeSelection(connection, selected == 0 ? null : selected);
            return readState(connection);
        });
    }

    private ShopCommandService.ShopState readState(Connection connection) throws SQLException {
        List<ShopCommandService.ManagedShopSummary> shops = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id,name,(length(trim(api_key))>0) token_configured FROM shops ORDER BY id LIMIT ?")) {
            statement.setInt(1, MAX_SHOPS + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    shops.add(new ShopCommandService.ManagedShopSummary(
                            result.getInt("id"),
                            result.getString("name"),
                            result.getBoolean("token_configured")));
                }
            }
        }
        if (shops.size() > MAX_SHOPS) {
            throw new ShopCommandService.ShopStoreException("shop_limit");
        }
        Integer configured = readSelection(connection);
        boolean selected = configured != null
                && shops.stream().anyMatch(shop -> shop.id() == configured);
        return new ShopCommandService.ShopState(
                List.copyOf(shops), selected, selected ? configured : 0);
    }

    private static int shopCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM shops");
                ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void requireShop(Connection connection, int shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM shops WHERE id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ShopCommandService.ShopStoreException("shop_not_found");
                }
            }
        }
    }

    private static Integer readSelection(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT value FROM app_config WHERE key=?")) {
            statement.setString(1, SELECTION_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                try {
                    String value = result.getString(1);
                    return value == null || value.isBlank() ? null : Integer.valueOf(value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
    }

    private static void writeSelection(Connection connection, Integer shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO app_config(key,value) VALUES(?,?) "
                                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, SELECTION_KEY);
            statement.setString(2, shopId == null ? "" : String.valueOf(shopId));
            statement.executeUpdate();
        }
    }

    private static boolean hasActivePurchasePipeline(Connection connection, int shopId)
            throws SQLException {
        if (!tableExists(connection, "znack_purchase_pipelines")) {
            return false;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
                TERMINAL_PURCHASE_STAGES.length, "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM znack_purchase_pipelines WHERE shop_id=? AND stage NOT IN ("
                        + placeholders + ") LIMIT 1")) {
            statement.setInt(1, shopId);
            for (int index = 0; index < TERMINAL_PURCHASE_STAGES.length; index++) {
                statement.setString(index + 2, TERMINAL_PURCHASE_STAGES[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static int firstShopId(List<ShopCommandService.ManagedShopSummary> shops) {
        return shops.isEmpty() ? 0 : shops.getFirst().id();
    }

    private ShopCommandService.ShopState transaction(SqlOperation operation) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                ShopCommandService.ShopState state = operation.run(connection);
                connection.commit();
                return state;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (ShopCommandService.ShopStoreException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlOperation {
        ShopCommandService.ShopState run(Connection connection) throws SQLException;
    }
}
