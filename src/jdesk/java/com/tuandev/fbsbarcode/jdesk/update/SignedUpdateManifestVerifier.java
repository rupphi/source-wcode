package com.tuandev.fbsbarcode.jdesk.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies the separately signed, bounded update manifest before any payload field is trusted. */
public final class SignedUpdateManifestVerifier {
    private static final int MAX_ENVELOPE_BYTES = 128 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int ED25519_SIGNATURE_BYTES = 64;
    private static final int MAX_NOTES = 20;
    private static final int MAX_NOTE_CHARACTERS = 500;
    private static final long MIN_ASSET_BYTES = 1024L * 1024L;
    private static final long MAX_ASSET_BYTES = 512L * 1024L * 1024L;
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ENVELOPE_KEYS = Set.of("format", "payload", "signature");
    private static final Set<String> PAYLOAD_KEYS =
            Set.of("schemaVersion", "version", "publishedAt", "mandatory", "notes", "assets");
    private static final Set<String> ASSET_KEYS =
            Set.of("platform", "kind", "fileName", "size", "sha256", "url");

    private final PublicKey publicKey;

    public SignedUpdateManifestVerifier(String encodedPublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedPublicKey);
            publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (RuntimeException | java.security.GeneralSecurityException exception) {
            throw new IllegalArgumentException("Invalid update verification key");
        }
    }

    public VerifiedManifest verify(String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isBlank() || envelopeJson.length() > MAX_ENVELOPE_BYTES) {
            throw invalidManifest();
        }
        byte[] envelopeBytes = envelopeJson.getBytes(StandardCharsets.UTF_8);
        if (envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw invalidManifest();
        }

        JsonObject envelope = parseObject(envelopeJson);
        requireExactKeys(envelope, ENVELOPE_KEYS);
        if (!"wcode-update-envelope-v1".equals(requireString(envelope, "format"))) {
            throw invalidManifest();
        }

        byte[] payload = decodeBase64(requireString(envelope, "payload"));
        byte[] signature = decodeBase64(requireString(envelope, "signature"));
        if (payload.length == 0
                || payload.length > MAX_PAYLOAD_BYTES
                || signature.length != ED25519_SIGNATURE_BYTES) {
            throw invalidManifest();
        }
        verifySignature(payload, signature);
        return parseVerifiedPayload(payload);
    }

    private void verifySignature(byte[] payload, byte[] signedBytes) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(signedBytes)) {
                throw invalidSignature();
            }
        } catch (ManifestException exception) {
            throw exception;
        } catch (java.security.GeneralSecurityException exception) {
            throw invalidSignature();
        }
    }

    private static VerifiedManifest parseVerifiedPayload(byte[] payload) {
        JsonObject root = parseObject(decodeUtf8(payload));
        requireExactKeys(root, PAYLOAD_KEYS);
        if (requireInteger(root, "schemaVersion") != 1) {
            throw invalidManifest();
        }

        String version = requireString(root, "version");
        if (!VERSION.matcher(version).matches()) {
            throw invalidManifest();
        }
        String publishedAt = requireString(root, "publishedAt");
        try {
            if (!Instant.parse(publishedAt).toString().equals(publishedAt)) {
                throw invalidManifest();
            }
        } catch (DateTimeParseException exception) {
            throw invalidManifest();
        }

        boolean mandatory = requireBoolean(root, "mandatory");
        List<String> notes = parseNotes(requireArray(root, "notes"));
        JsonArray assets = requireArray(root, "assets");
        if (assets.size() != 1 || !assets.get(0).isJsonObject()) {
            throw invalidManifest();
        }
        VerifiedAsset asset = parseAsset(assets.get(0).getAsJsonObject(), version);
        return new VerifiedManifest(version, publishedAt, notes, mandatory, asset);
    }

    private static List<String> parseNotes(JsonArray source) {
        if (source.size() > MAX_NOTES) {
            throw invalidManifest();
        }
        List<String> notes = new ArrayList<>(source.size());
        for (JsonElement element : source) {
            if (!isString(element)) {
                throw invalidManifest();
            }
            String note = element.getAsString();
            if (note.isBlank()
                    || !note.equals(note.strip())
                    || note.length() > MAX_NOTE_CHARACTERS
                    || note.codePoints().anyMatch(Character::isISOControl)) {
                throw invalidManifest();
            }
            notes.add(note);
        }
        return List.copyOf(notes);
    }

    private static VerifiedAsset parseAsset(JsonObject source, String version) {
        requireExactKeys(source, ASSET_KEYS);
        if (!"windows-x64".equals(requireString(source, "platform"))
                || !"msi".equals(requireString(source, "kind"))
                || !"WCode.msi".equals(requireString(source, "fileName"))) {
            throw invalidManifest();
        }
        long size = requireLong(source, "size");
        if (size < MIN_ASSET_BYTES || size > MAX_ASSET_BYTES) {
            throw invalidManifest();
        }
        String sha256 = requireString(source, "sha256");
        if (!SHA_256.matcher(sha256).matches()) {
            throw invalidManifest();
        }

        String expectedUrl = "https://github.com/rupphi/relatest-wcode/releases/download/v"
                + version
                + "/WCode.msi";
        String urlText = requireString(source, "url");
        if (!expectedUrl.equals(urlText)) {
            throw invalidManifest();
        }
        URI url;
        try {
            url = URI.create(urlText);
        } catch (IllegalArgumentException exception) {
            throw invalidManifest();
        }
        if (!"https".equals(url.getScheme())
                || !"github.com".equals(url.getHost())
                || url.getPort() != -1
                || url.getUserInfo() != null
                || url.getQuery() != null
                || url.getFragment() != null) {
            throw invalidManifest();
        }
        return new VerifiedAsset("WCode.msi", size, sha256, url);
    }

    private static JsonObject parseObject(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw invalidManifest();
            }
            return element.getAsJsonObject();
        } catch (ManifestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidManifest();
        }
    }

    private static void requireExactKeys(JsonObject source, Set<String> expected) {
        if (!source.keySet().equals(expected)) {
            throw invalidManifest();
        }
    }

    private static String requireString(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (!isString(value)) {
            throw invalidManifest();
        }
        return value.getAsString();
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean requireBoolean(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalidManifest();
        }
        return value.getAsBoolean();
    }

    private static int requireInteger(JsonObject source, String name) {
        long value = requireLong(source, name);
        if (value > Integer.MAX_VALUE) {
            throw invalidManifest();
        }
        return (int) value;
    }

    private static long requireLong(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidManifest();
        }
        String raw = value.getAsString();
        if (!raw.matches("0|[1-9][0-9]*")) {
            throw invalidManifest();
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw invalidManifest();
        }
    }

    private static JsonArray requireArray(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonArray()) {
            throw invalidManifest();
        }
        return value.getAsJsonArray();
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalidManifest();
        }
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalidManifest();
        }
    }

    private static ManifestException invalidManifest() {
        return new ManifestException("invalid_manifest", "Update manifest is invalid");
    }

    private static ManifestException invalidSignature() {
        return new ManifestException("invalid_signature", "Update manifest signature is invalid");
    }

    public record VerifiedManifest(
            String version, String publishedAt, List<String> notes, boolean mandatory, VerifiedAsset asset) {}

    public record VerifiedAsset(String fileName, long size, String sha256, URI url) {}

    public static final class ManifestException extends RuntimeException {
        private final String kind;

        private ManifestException(String kind, String message) {
            super(message, null, false, false);
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }
    }
}
