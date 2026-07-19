package com.tuandev.fbsbarcode.jdesk.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedUpdateDownloaderTest {
    @TempDir Path temp;

    @Test
    void streamsToAnOwnerOnlyPartAndPublishesOnlyAfterExactSizeAndHashMatch() throws Exception {
        byte[] content = content();
        AtomicLong progress = new AtomicLong();
        VerifiedUpdateDownloader downloader = downloader(content, content.length);

        Path result = downloader.download(asset(content), progress::set, () -> false);

        assertEquals("WCode.msi", result.getFileName().toString());
        assertArrayEquals(content, Files.readAllBytes(result));
        assertEquals(content.length, progress.get());
        assertFalse(Files.exists(result.resolveSibling("WCode.msi.part")));
        if (Files.getFileStore(result).supportsFileAttributeView("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(result));
        }
    }

    @Test
    void rejectsDeclaredLengthByteCountAndDigestMismatchWithoutLeavingArtifacts() throws Exception {
        byte[] content = content();
        SignedUpdateManifestVerifier.VerifiedAsset valid = asset(content);
        SignedUpdateManifestVerifier.VerifiedAsset wrongHash = new SignedUpdateManifestVerifier.VerifiedAsset(
                "WCode.msi", content.length, "0".repeat(64), valid.url());
        List<DownloadCase> cases = List.of(
                new DownloadCase(downloader(content, content.length - 1L), valid),
                new DownloadCase(downloader(new byte[content.length - 1], content.length), valid),
                new DownloadCase(downloader(content, content.length), wrongHash));

        for (DownloadCase testCase : cases) {
            assertEmpty();
            assertThrows(Exception.class, () -> testCase.downloader().download(
                    testCase.asset(), ignored -> {}, () -> false));
            assertEmpty();
        }
    }

    @Test
    void cancellationDeletesThePartAndPrivateDirectory() throws Exception {
        byte[] content = content();
        AtomicBoolean cancelled = new AtomicBoolean();
        VerifiedUpdateDownloader downloader = new VerifiedUpdateDownloader(temp, ignored ->
                new VerifiedUpdateDownloader.AssetResponse(
                        200,
                        content.length,
                        new ByteArrayInputStream(content) {
                            @Override
                            public synchronized int read(byte[] bytes, int offset, int length) {
                                int count = super.read(bytes, offset, Math.min(length, 8192));
                                cancelled.set(true);
                                return count;
                            }
                        },
                        () -> {}));

        assertThrows(InterruptedException.class, () -> downloader.download(
                asset(content), ignored -> {}, cancelled::get));

        assertEmpty();
    }

    private VerifiedUpdateDownloader downloader(byte[] content, long declaredLength) {
        return new VerifiedUpdateDownloader(temp, ignored -> new VerifiedUpdateDownloader.AssetResponse(
                200, declaredLength, new ByteArrayInputStream(content), () -> {}));
    }

    private void assertEmpty() throws Exception {
        try (var files = Files.list(temp)) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    private static SignedUpdateManifestVerifier.VerifiedAsset asset(byte[] content) throws Exception {
        return new SignedUpdateManifestVerifier.VerifiedAsset(
                "WCode.msi",
                content.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                URI.create("https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi"));
    }

    private static byte[] content() {
        byte[] content = new byte[1024 * 1024];
        for (int index = 0; index < content.length; index++) content[index] = (byte) (index * 31);
        return content;
    }

    private record DownloadCase(
            VerifiedUpdateDownloader downloader,
            SignedUpdateManifestVerifier.VerifiedAsset asset) {}
}
