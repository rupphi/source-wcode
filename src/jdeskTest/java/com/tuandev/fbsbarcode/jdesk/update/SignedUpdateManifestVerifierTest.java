package com.tuandev.fbsbarcode.jdesk.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignedUpdateManifestVerifierTest {
    private static final String SECRET = "manifest-secret-must-not-escape";
    private KeyPair keys;
    private SignedUpdateManifestVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = new SignedUpdateManifestVerifier(
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    }

    @Test
    void verifiesBytesBeforeParsingAndReturnsOneBoundedWindowsAsset() throws Exception {
        String payload = payload("1.2.3", "WCode.msi", 12_345_678L, "a".repeat(64),
                "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi");

        SignedUpdateManifestVerifier.VerifiedManifest manifest = verifier.verify(envelope(payload));

        assertEquals("1.2.3", manifest.version());
        assertEquals("2026-07-19T00:00:00Z", manifest.publishedAt());
        assertEquals(List.of("Safer updater", "Rollback snapshot"), manifest.notes());
        assertFalse(manifest.mandatory());
        assertEquals("WCode.msi", manifest.asset().fileName());
        assertEquals(12_345_678L, manifest.asset().size());
        assertEquals("a".repeat(64), manifest.asset().sha256());
        assertFalse(manifest.toString().contains(SECRET));
    }

    @Test
    void rejectsTamperedPayloadWithoutReturningRawPayloadOrCause() throws Exception {
        String signed = envelope(payload("1.2.3", "WCode.msi", 12_345_678L, "a".repeat(64),
                "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi"));
        @SuppressWarnings("unchecked")
        Map<String, String> envelope = new Gson().fromJson(signed, Map.class);
        byte[] tamperedBytes = Base64.getDecoder().decode(envelope.get("payload"));
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        String tampered = new Gson().toJson(Map.of(
                "format", envelope.get("format"),
                "payload", Base64.getEncoder().encodeToString(tamperedBytes),
                "signature", envelope.get("signature")));

        SignedUpdateManifestVerifier.ManifestException error = assertThrows(
                SignedUpdateManifestVerifier.ManifestException.class,
                () -> verifier.verify(tampered));

        assertEquals("invalid_signature", error.kind());
        assertFalse(error.getMessage().contains(SECRET));
        assertNull(error.getCause());
    }

    @Test
    void rejectsWrongRepoTagFileKindHashSizeAndUnboundedNotes() throws Exception {
        List<String> payloads = List.of(
                payload("1.2.3", "WCode.msi", 12_345_678L, "a".repeat(64),
                        "https://example.com/WCode.msi"),
                payload("1.2.3", "WCode.msi", 12_345_678L, "a".repeat(64),
                        "https://github.com/rupphi/relatest-wcode/releases/download/v9.9.9/WCode.msi"),
                payload("1.2.3", "evil.msi", 12_345_678L, "a".repeat(64),
                        "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/evil.msi"),
                payload("1.2.3", "WCode.msi", 0L, "a".repeat(64),
                        "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi"),
                payload("1.2.3", "WCode.msi", 12_345_678L, "xyz",
                        "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi"),
                new Gson().toJson(Map.of(
                        "schemaVersion", 1,
                        "version", "1.2.3",
                        "publishedAt", "2026-07-19T00:00:00Z",
                        "mandatory", false,
                        "notes", List.of("x".repeat(501)),
                        "assets", List.of(asset("WCode.msi", 12_345_678L, "a".repeat(64),
                                "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi")))));

        for (String payload : payloads) {
            SignedUpdateManifestVerifier.ManifestException error = assertThrows(
                    SignedUpdateManifestVerifier.ManifestException.class,
                    () -> verifier.verify(envelope(payload)));
            assertEquals("invalid_manifest", error.kind());
        }
    }

    @Test
    void rejectsOversizedEnvelopeBeforeDecode() {
        SignedUpdateManifestVerifier.ManifestException error = assertThrows(
                SignedUpdateManifestVerifier.ManifestException.class,
                () -> verifier.verify("x".repeat(128 * 1024 + 1)));
        assertEquals("invalid_manifest", error.kind());
    }

    @Test
    void rejectsSignedPayloadThatIsNotStrictUtf8() throws Exception {
        byte[] bytes = payload("1.2.3", "WCode.msi", 12_345_678L, "a".repeat(64),
                        "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi")
                .getBytes(StandardCharsets.UTF_8);
        byte[] marker = "Safer updater".getBytes(StandardCharsets.UTF_8);
        int offset = indexOf(bytes, marker);
        bytes[offset] = (byte) 0xc3;
        bytes[offset + 1] = 0x28;

        SignedUpdateManifestVerifier.ManifestException error = assertThrows(
                SignedUpdateManifestVerifier.ManifestException.class,
                () -> verifier.verify(envelope(bytes)));

        assertEquals("invalid_manifest", error.kind());
    }

    private String envelope(String payload) throws Exception {
        return envelope(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String envelope(byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(bytes);
        return new Gson().toJson(Map.of(
                "format", "wcode-update-envelope-v1",
                "payload", Base64.getEncoder().encodeToString(bytes),
                "signature", Base64.getEncoder().encodeToString(signer.sign())));
    }

    private static int indexOf(byte[] source, byte[] target) {
        for (int offset = 0; offset <= source.length - target.length; offset++) {
            boolean match = true;
            for (int index = 0; index < target.length; index++) {
                if (source[offset + index] != target[index]) {
                    match = false;
                    break;
                }
            }
            if (match) return offset;
        }
        throw new AssertionError("Payload marker not found");
    }

    private static String payload(String version, String fileName, long size, String sha256, String url) {
        return new Gson().toJson(Map.of(
                "schemaVersion", 1,
                "version", version,
                "publishedAt", "2026-07-19T00:00:00Z",
                "mandatory", false,
                "notes", List.of("Safer updater", "Rollback snapshot"),
                "assets", List.of(asset(fileName, size, sha256, url))));
    }

    private static Map<String, Object> asset(String fileName, long size, String sha256, String url) {
        return Map.of(
                "platform", "windows-x64",
                "kind", "msi",
                "fileName", fileName,
                "size", size,
                "sha256", sha256,
                "url", url);
    }
}
