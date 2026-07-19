package com.tuandev.fbsbarcode.jdesk.shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Persists rollback-safe credential metadata and reconciles it with the OS secret store. */
final class SqliteShopCredentialStore {
    private static final int MAX_SHOPS = 500;

    private final ConnectionFactory connections;
    private final Supplier<String> secretKeyIds;

    SqliteShopCredentialStore(ConnectionFactory connections, Supplier<String> secretKeyIds) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.secretKeyIds = Objects.requireNonNull(secretKeyIds, "secretKeyIds");
    }

    void insert(Connection connection, int shopId, String token) throws SQLException {
        insertMetadata(connection, shopId, token, 1);
    }

    void retain(Connection connection, int shopId) throws SQLException {
        ensureMetadata(connection, shopId, readToken(connection, shopId));
    }

    void replace(Connection connection, int shopId, String token) throws SQLException {
        CredentialRow current = readMetadata(connection, shopId);
        if (current == null) {
            insertMetadata(connection, shopId, token, 1);
            return;
        }
        String fingerprint = ShopCredentialMirror.fingerprint(token);
        if (fingerprint.equals(current.fingerprint())) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE shop_credential_mirrors SET credential_version=?,"
                        + "credential_fingerprint=? WHERE shop_id=?")) {
            statement.setLong(1, nextVersion(current.version()));
            statement.setString(2, fingerprint);
            statement.setInt(3, shopId);
            statement.executeUpdate();
        }
    }

    void tombstone(Connection connection, int shopId) throws SQLException {
        CredentialRow credential = ensureMetadata(connection, shopId, readToken(connection, shopId));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO shop_credential_tombstones("
                        + "secret_key,credential_version,credential_fingerprint) VALUES(?,?,?)")) {
            statement.setString(1, credential.secretKey());
            statement.setLong(2, credential.version());
            statement.setString(3, credential.fingerprint());
            statement.executeUpdate();
        }
    }

    void reconcile(ShopCredentialMirror mirror) {
        if (mirror == null) {
            return;
        }
        try {
            normalizeLegacyCredentials();
        } catch (RuntimeException ignored) {
            return;
        }
        for (PendingCredential pending : activeCredentials()) {
            boolean mirrored = pending.token().isBlank()
                    ? mirror.deleteVerified(pending.secretKey())
                    : mirror.putVerified(
                            pending.secretKey(), pending.version(), pending.fingerprint(), pending.token());
            if (mirrored) {
                acknowledge(pending);
            }
        }
        for (Tombstone tombstone : tombstones()) {
            if (mirror.deleteVerified(tombstone.secretKey())) {
                acknowledge(tombstone);
            }
        }
    }

    private void normalizeLegacyCredentials() {
        transaction(connection -> {
            List<LegacyCredential> credentials = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT id,api_key FROM shops ORDER BY id LIMIT ?")) {
                statement.setInt(1, MAX_SHOPS + 1);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        credentials.add(new LegacyCredential(result.getInt("id"), result.getString("api_key")));
                    }
                }
            }
            if (credentials.size() > MAX_SHOPS) {
                throw new ShopCommandService.ShopStoreException("shop_limit");
            }
            for (LegacyCredential credential : credentials) {
                ensureMetadata(connection, credential.shopId(), credential.token());
            }
        });
    }

    private CredentialRow ensureMetadata(Connection connection, int shopId, String token)
            throws SQLException {
        CredentialRow current = readMetadata(connection, shopId);
        String fingerprint = ShopCredentialMirror.fingerprint(token);
        if (current == null) {
            insertMetadata(connection, shopId, token, 1);
            return readMetadata(connection, shopId);
        }
        if (!fingerprint.equals(current.fingerprint())) {
            long version = nextVersion(current.version());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shop_credential_mirrors SET credential_version=?,credential_fingerprint=? "
                            + "WHERE shop_id=?")) {
                statement.setLong(1, version);
                statement.setString(2, fingerprint);
                statement.setInt(3, shopId);
                statement.executeUpdate();
            }
            return new CredentialRow(current.secretKey(), version, fingerprint);
        }
        return current;
    }

    private void insertMetadata(Connection connection, int shopId, String token, long version)
            throws SQLException {
        String secretKey = "shop-api-key-v1-" + secretKeyIds.get() + "-" + shopId;
        if (secretKey.length() > 128
                || secretKey.isBlank()
                || secretKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new SQLException("Generated credential key is invalid");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_credential_mirrors("
                        + "shop_id,secret_key,credential_version,credential_fingerprint) VALUES(?,?,?,?)")) {
            statement.setInt(1, shopId);
            statement.setString(2, secretKey);
            statement.setLong(3, version);
            statement.setString(4, ShopCredentialMirror.fingerprint(token));
            statement.executeUpdate();
        }
    }

    private static CredentialRow readMetadata(Connection connection, int shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT secret_key,credential_version,credential_fingerprint "
                        + "FROM shop_credential_mirrors WHERE shop_id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new CredentialRow(
                                result.getString("secret_key"),
                                result.getLong("credential_version"),
                                result.getString("credential_fingerprint"))
                        : null;
            }
        }
    }

    private static String readToken(Connection connection, int shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT api_key FROM shops WHERE id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ShopCommandService.ShopStoreException("shop_not_found");
                }
                return result.getString("api_key");
            }
        }
    }

    private List<PendingCredential> activeCredentials() {
        List<PendingCredential> active = new ArrayList<>();
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT m.shop_id,m.secret_key,m.credential_version,m.credential_fingerprint,s.api_key "
                        + "FROM shop_credential_mirrors m JOIN shops s ON s.id=m.shop_id "
                        + "ORDER BY m.shop_id LIMIT ?")) {
            statement.setInt(1, MAX_SHOPS + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    active.add(new PendingCredential(
                            result.getInt("shop_id"),
                            result.getString("secret_key"),
                            result.getLong("credential_version"),
                            result.getString("credential_fingerprint"),
                            result.getString("api_key")));
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return active.size() > MAX_SHOPS ? List.of() : List.copyOf(active);
    }

    private List<Tombstone> tombstones() {
        List<Tombstone> tombstones = new ArrayList<>();
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT secret_key,credential_version,credential_fingerprint "
                        + "FROM shop_credential_tombstones ORDER BY secret_key LIMIT ?")) {
            statement.setInt(1, MAX_SHOPS + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tombstones.add(new Tombstone(
                            result.getString("secret_key"),
                            result.getLong("credential_version"),
                            result.getString("credential_fingerprint")));
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return tombstones.size() > MAX_SHOPS ? List.of() : List.copyOf(tombstones);
    }

    private void acknowledge(PendingCredential pending) {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE shop_credential_mirrors SET mirrored_version=?,mirrored_fingerprint=? "
                        + "WHERE shop_id=? AND credential_version=? AND credential_fingerprint=?")) {
            statement.setLong(1, pending.version());
            statement.setString(2, pending.fingerprint());
            statement.setInt(3, pending.shopId());
            statement.setLong(4, pending.version());
            statement.setString(5, pending.fingerprint());
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // The verified OS value remains safe; a later reconcile retries this idempotent ack.
        }
    }

    private void acknowledge(Tombstone tombstone) {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shop_credential_tombstones WHERE secret_key=? "
                        + "AND credential_version=? AND credential_fingerprint=?")) {
            statement.setString(1, tombstone.secretKey());
            statement.setLong(2, tombstone.version());
            statement.setString(3, tombstone.fingerprint());
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // The tombstone intentionally survives for another verified delete attempt.
        }
    }

    private void transaction(Operation operation) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                operation.run(connection);
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

    private static long nextVersion(long version) throws SQLException {
        if (version <= 0 || version == Long.MAX_VALUE) {
            throw new SQLException("Credential version cannot advance");
        }
        return version + 1;
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    private interface Operation {
        void run(Connection connection) throws SQLException;
    }

    private record LegacyCredential(int shopId, String token) {
    }

    private record CredentialRow(String secretKey, long version, String fingerprint) {
    }

    private record PendingCredential(
            int shopId, String secretKey, long version, String fingerprint, String token) {
    }

    private record Tombstone(String secretKey, long version, String fingerprint) {
    }
}
