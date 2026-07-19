package com.tuandev.fbsbarcode.jdesk.shop;

import dev.jdesk.api.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Writes a versioned shop credential envelope and treats read-back as the commit acknowledgement. */
final class ShopCredentialMirror {
    private static final String ENVELOPE = "wcode-shop-secret-v1";

    private final Vault vault;

    ShopCredentialMirror(Vault vault) {
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    static ShopCredentialMirror from(SecretStore secrets) {
        Objects.requireNonNull(secrets, "secrets");
        return new ShopCredentialMirror(new Vault() {
            @Override
            public Optional<String> get(String key) {
                return secrets.get(key);
            }

            @Override
            public void put(String key, String value) {
                secrets.put(key, value);
            }

            @Override
            public void delete(String key) {
                secrets.delete(key);
            }
        });
    }

    boolean putVerified(String key, long version, String fingerprint, String token) {
        String expected = envelope(version, fingerprint, token);
        try {
            if (vault.get(key).filter(expected::equals).isPresent()) {
                return true;
            }
            vault.put(key, expected);
            return vault.get(key).filter(expected::equals).isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean deleteVerified(String key) {
        try {
            vault.delete(key);
            return vault.get(key).isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static String fingerprint(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String envelope(long version, String fingerprint, String token) {
        if (version <= 0
                || fingerprint == null
                || !fingerprint.matches("[0-9a-f]{64}")
                || !fingerprint.equals(fingerprint(token))) {
            throw new IllegalArgumentException("Credential metadata is invalid");
        }
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return ENVELOPE + "\n" + version + "\n" + fingerprint + "\n" + encoded;
    }

    interface Vault {
        Optional<String> get(String key);

        void put(String key, String value);

        void delete(String key);
    }
}
