package com.tuandev.fbsbarcode.jdesk.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SignedUpdateManifestSourceTest {
    @Test
    void fetchesOnlyTheFixedLatestManifestAndVerifiesIt() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String envelope = envelope(keys);
        AtomicReference<URI> requested = new AtomicReference<>();
        SignedUpdateManifestSource source = new SignedUpdateManifestSource(
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                uri -> {
                    requested.set(uri);
                    return new SignedUpdateManifestSource.EnvelopeResponse(
                            200, envelope.getBytes(StandardCharsets.UTF_8));
                });

        SignedUpdateManifestVerifier.VerifiedManifest manifest = source.load();

        assertEquals("1.2.3", manifest.version());
        assertEquals(
                URI.create("https://github.com/rupphi/relatest-wcode/releases/latest/download/update-manifest.json"),
                requested.get());
    }

    @Test
    void rejectsNonSuccessAndOversizedResponsesBeforeManifestParsing() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());

        assertThrows(Exception.class, () -> new SignedUpdateManifestSource(
                publicKey, ignored -> new SignedUpdateManifestSource.EnvelopeResponse(404, new byte[0]))
                .load());
        assertThrows(Exception.class, () -> new SignedUpdateManifestSource(
                publicKey,
                ignored -> new SignedUpdateManifestSource.EnvelopeResponse(200, new byte[128 * 1024 + 1]))
                .load());
    }

    private static String envelope(KeyPair keys) throws Exception {
        String payload = new Gson().toJson(Map.of(
                "schemaVersion", 1,
                "version", "1.2.3",
                "publishedAt", "2026-07-19T00:00:00Z",
                "mandatory", false,
                "notes", List.of("Signed update"),
                "assets", List.of(Map.of(
                        "platform", "windows-x64",
                        "kind", "msi",
                        "fileName", "WCode.msi",
                        "size", 12_345_678L,
                        "sha256", "a".repeat(64),
                        "url", "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi"))));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(bytes);
        return new Gson().toJson(Map.of(
                "format", "wcode-update-envelope-v1",
                "payload", Base64.getEncoder().encodeToString(bytes),
                "signature", Base64.getEncoder().encodeToString(signature.sign())));
    }
}
