package com.tuandev.fbsbarcode.jdesk.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Streams one verified MSI into a private temporary directory and publishes it atomically. */
public final class VerifiedUpdateDownloader implements UpdateCommandService.DownloadRunner {
    private static final long MAX_ASSET_BYTES = 512L * 1024L * 1024L;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path temporaryBase;
    private final AssetStreamSource source;

    public VerifiedUpdateDownloader(Path temporaryBase) {
        this(temporaryBase, new OkHttpAssetStreamSource());
    }

    VerifiedUpdateDownloader(Path temporaryBase, AssetStreamSource source) {
        this.temporaryBase = Objects.requireNonNull(temporaryBase, "temporaryBase")
                .toAbsolutePath().normalize();
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Path download(
            SignedUpdateManifestVerifier.VerifiedAsset asset,
            UpdateCommandService.ProgressSink progress,
            BooleanSupplier cancelled) throws Exception {
        validate(asset);
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancelled, "cancelled");
        prepareBase();

        Path privateDirectory = Files.createTempDirectory(temporaryBase, "wcode-update-");
        Path part = privateDirectory.resolve("WCode.msi.part");
        Path target = privateDirectory.resolve("WCode.msi");
        boolean complete = false;
        try {
            setPermissions(privateDirectory, OWNER_DIRECTORY);
            downloadPart(asset, progress, cancelled, part);
            publish(part, target);
            setPermissions(target, OWNER_FILE);
            complete = true;
            return target;
        } finally {
            if (!complete) cleanup(privateDirectory, part, target);
        }
    }

    private void downloadPart(
            SignedUpdateManifestVerifier.VerifiedAsset asset,
            UpdateCommandService.ProgressSink progress,
            BooleanSupplier cancelled,
            Path part) throws Exception {
        if (cancelled.getAsBoolean()) throw new InterruptedException("Update download cancelled");
        try (AssetResponse response = source.open(asset.url())) {
            if (response.statusCode() != 200
                    || response.contentLength() != asset.size()
                    || response.body() == null) {
                throw new IOException("Update response metadata did not match the signed manifest");
            }
            MessageDigest digest = sha256();
            progress.update(0);
            try (FileChannel output = FileChannel.open(
                    part,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                setPermissions(part, OWNER_FILE);
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int count;
                while ((count = response.body().read(buffer)) != -1) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Update download cancelled");
                    }
                    received += count;
                    if (received > asset.size()) {
                        throw new IOException("Update response exceeded the signed size");
                    }
                    digest.update(buffer, 0, count);
                    writeFully(output, buffer, count);
                    progress.update(received);
                }
                if (received != asset.size()) {
                    throw new IOException("Update response did not match the signed size");
                }
                byte[] expected = HexFormat.of().parseHex(asset.sha256());
                if (!MessageDigest.isEqual(expected, digest.digest())) {
                    throw new IOException("Update response did not match the signed digest");
                }
                output.force(true);
            }
        }
    }

    private void prepareBase() throws IOException {
        Files.createDirectories(temporaryBase);
        if (!Files.isDirectory(temporaryBase, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(temporaryBase)) {
            throw new IOException("Update temporary directory is unsafe");
        }
    }

    private static void validate(SignedUpdateManifestVerifier.VerifiedAsset asset) {
        if (asset == null
                || !"WCode.msi".equals(asset.fileName())
                || asset.size() < 1024L * 1024L
                || asset.size() > MAX_ASSET_BYTES
                || asset.sha256() == null
                || !asset.sha256().matches("[0-9a-f]{64}")
                || asset.url() == null
                || !"https".equals(asset.url().getScheme())) {
            throw new IllegalArgumentException("Verified update asset is invalid");
        }
    }

    private static void writeFully(FileChannel output, byte[] bytes, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
        while (buffer.hasRemaining()) output.write(buffer);
    }

    private static void publish(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(part, target);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
        }
    }

    private static void cleanup(Path privateDirectory, Path part, Path target) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException ignored) {
            // A later startup cleanup may remove a locked partial download.
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // A later startup cleanup may remove a locked partial download.
        }
        try {
            Files.deleteIfExists(privateDirectory);
        } catch (IOException ignored) {
            // The bounded directory contains no other application data.
        }
    }

    @FunctionalInterface
    interface AssetStreamSource {
        AssetResponse open(URI url) throws Exception;
    }

    static final class AssetResponse implements AutoCloseable {
        private final int statusCode;
        private final long contentLength;
        private final InputStream body;
        private final AutoCloseable closeAction;

        AssetResponse(int statusCode, long contentLength, InputStream body, AutoCloseable closeAction) {
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.body = body;
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        }

        int statusCode() {
            return statusCode;
        }

        long contentLength() {
            return contentLength;
        }

        InputStream body() {
            return body;
        }

        @Override
        public void close() throws Exception {
            try {
                if (body != null) body.close();
            } finally {
                closeAction.close();
            }
        }
    }

    private static final class OkHttpAssetStreamSource implements AssetStreamSource {
        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.MINUTES)
                .build();

        @Override
        public AssetResponse open(URI url) throws IOException {
            Request request = new Request.Builder()
                    .url(url.toString())
                    .header("User-Agent", "WCode-Signed-Updater")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            ResponseBody body = response.body();
            return new AssetResponse(
                    response.code(),
                    body == null ? -1 : body.contentLength(),
                    body == null ? null : body.byteStream(),
                    response::close);
        }
    }
}
