package com.tuandev.fbsbarcode.jdesk.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShopCredentialMirrorTest {
    @Test
    void writesVersionedEnvelopeAndVerifiesReadBackWithoutExposingTokenInMetadata() {
        FakeVault vault = new FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        String token = "secret-token";
        String fingerprint = ShopCredentialMirror.fingerprint(token);

        assertTrue(mirror.putVerified("shop-api-key-v1-fixture", 7, fingerprint, token));

        String stored = vault.values.get("shop-api-key-v1-fixture");
        assertFalse(stored.equals(token));
        assertTrue(stored.startsWith("wcode-shop-secret-v1\n7\n" + fingerprint + "\n"));
        assertEquals(64, fingerprint.length());
        assertFalse(mirror.toString().contains(token));
    }

    @Test
    void repairsMissingStaleAndCorruptEntriesButNeverAcknowledgesFailedReadBack() {
        FakeVault vault = new FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        String key = "shop-api-key-v1-fixture";
        String token = "new-secret";
        String fingerprint = ShopCredentialMirror.fingerprint(token);

        vault.values.put(key, "corrupt-old-value");
        vault.returnWrongValue = true;
        assertFalse(mirror.putVerified(key, 2, fingerprint, token));

        vault.returnWrongValue = false;
        assertTrue(mirror.putVerified(key, 2, fingerprint, token));
        assertEquals(1, vault.putCalls);
    }

    @Test
    void putAndDeleteRemainRetryableWhenVaultFailsBeforeOrAfterItsMutation() {
        FakeVault vault = new FakeVault();
        ShopCredentialMirror mirror = new ShopCredentialMirror(vault);
        String key = "shop-api-key-v1-fixture";
        String token = "secret";
        String fingerprint = ShopCredentialMirror.fingerprint(token);

        vault.failPutAfterMutation = true;
        assertFalse(mirror.putVerified(key, 1, fingerprint, token));
        assertTrue(vault.values.containsKey(key));
        vault.failPutAfterMutation = false;
        assertTrue(mirror.putVerified(key, 1, fingerprint, token));

        vault.failDeleteAfterMutation = true;
        assertFalse(mirror.deleteVerified(key));
        assertFalse(vault.values.containsKey(key));
        vault.failDeleteAfterMutation = false;
        assertTrue(mirror.deleteVerified(key));
    }

    static final class FakeVault implements ShopCredentialMirror.Vault {
        final Map<String, String> values = new HashMap<>();
        int putCalls;
        boolean returnWrongValue;
        boolean failPutBeforeMutation;
        boolean failPutAfterMutation;
        boolean failGet;
        boolean failDeleteBeforeMutation;
        boolean failDeleteAfterMutation;

        @Override
        public Optional<String> get(String key) {
            if (failGet) {
                throw new IllegalStateException("vault get failed with private material");
            }
            if (returnWrongValue) {
                return Optional.of("wrong-value");
            }
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void put(String key, String value) {
            putCalls++;
            if (failPutBeforeMutation) {
                throw new IllegalStateException("vault put failed");
            }
            values.put(key, value);
            if (failPutAfterMutation) {
                throw new IllegalStateException("vault put acknowledgement failed");
            }
        }

        @Override
        public void delete(String key) {
            if (failDeleteBeforeMutation) {
                throw new IllegalStateException("vault delete failed");
            }
            values.remove(key);
            if (failDeleteAfterMutation) {
                throw new IllegalStateException("vault delete acknowledgement failed");
            }
        }
    }
}
