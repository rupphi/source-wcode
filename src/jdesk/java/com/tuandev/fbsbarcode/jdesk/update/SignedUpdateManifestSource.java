package com.tuandev.fbsbarcode.jdesk.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Loads the bounded signed envelope from the one compile-time WCode release endpoint. */
public final class SignedUpdateManifestSource implements UpdateCommandService.ManifestSource {
    private static final int MAX_ENVELOPE_BYTES = 128 * 1024;
    private static final URI ENDPOINT = URI.create(
            "https://github.com/rupphi/relatest-wcode/releases/latest/download/update-manifest.json");

    private final SignedUpdateManifestVerifier verifier;
    private final EnvelopeFetcher fetcher;

    public SignedUpdateManifestSource(String encodedPublicKey) {
        this(encodedPublicKey, new OkHttpEnvelopeFetcher());
    }

    SignedUpdateManifestSource(String encodedPublicKey, EnvelopeFetcher fetcher) {
        this.verifier = new SignedUpdateManifestVerifier(encodedPublicKey);
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    public SignedUpdateManifestVerifier.VerifiedManifest load() throws Exception {
        EnvelopeResponse response = Objects.requireNonNull(fetcher.fetch(ENDPOINT), "response");
        byte[] body = response.body();
        if (response.statusCode() != 200
                || body == null
                || body.length == 0
                || body.length > MAX_ENVELOPE_BYTES) {
            throw new IOException("Signed update manifest is unavailable");
        }
        return verifier.verify(decodeUtf8(body));
    }

    private static String decodeUtf8(byte[] body) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Signed update manifest encoding is invalid");
        }
    }

    @FunctionalInterface
    interface EnvelopeFetcher {
        EnvelopeResponse fetch(URI endpoint) throws Exception;
    }

    record EnvelopeResponse(int statusCode, byte[] body) {
        EnvelopeResponse {
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }
    }

    private static final class OkHttpEnvelopeFetcher implements EnvelopeFetcher {
        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();

        @Override
        public EnvelopeResponse fetch(URI endpoint) throws IOException {
            Request request = new Request.Builder()
                    .url(endpoint.toString())
                    .header("User-Agent", "WCode-Signed-Updater")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                byte[] body = responseBody == null ? null : readBounded(responseBody.byteStream());
                return new EnvelopeResponse(response.code(), body);
            }
        }

        private static byte[] readBounded(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_ENVELOPE_BYTES) {
                    throw new IOException("Signed update manifest exceeded its size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
