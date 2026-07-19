package com.tuandev.fbsbarcode.jdesk.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsUpdateInstallerTest {
    private static final String PUBLISHER = "CN=WCode Release Signing, O=TuanDev";

    @TempDir Path temp;

    @Test
    void reverifiesAuthenticodeThenSnapshotsBeforeLaunchingTheFixedHelper() throws Exception {
        Path installer = installer();
        List<String> order = new ArrayList<>();
        WindowsUpdateInstaller service = new WindowsUpdateInstaller(
                temp,
                PUBLISHER,
                () -> true,
                (path, publisher) -> {
                    assertEquals(installer, path);
                    assertEquals(PUBLISHER, publisher);
                    order.add("authenticode");
                    return true;
                },
                () -> order.add("snapshot"),
                path -> {
                    assertEquals(installer, path);
                    order.add("helper");
                });

        service.install(installer, asset(installer));

        assertEquals(List.of("authenticode", "snapshot", "authenticode", "helper"), order);
    }

    @Test
    void rejectsWrongDigestBeforeAuthenticodeSnapshotOrLaunch() throws Exception {
        Path installer = installer();
        AtomicInteger effects = new AtomicInteger();
        WindowsUpdateInstaller service = new WindowsUpdateInstaller(
                temp,
                PUBLISHER,
                () -> true,
                (path, publisher) -> {
                    effects.incrementAndGet();
                    return true;
                },
                effects::incrementAndGet,
                path -> effects.incrementAndGet());
        SignedUpdateManifestVerifier.VerifiedAsset wrong = new SignedUpdateManifestVerifier.VerifiedAsset(
                "WCode.msi", Files.size(installer), "0".repeat(64), asset(installer).url());

        assertThrows(Exception.class, () -> service.install(installer, wrong));

        assertEquals(0, effects.get());
    }

    @Test
    void rejectsInvalidPublisherWithoutSnapshotOrLaunch() throws Exception {
        Path installer = installer();
        AtomicInteger effects = new AtomicInteger();
        WindowsUpdateInstaller service = new WindowsUpdateInstaller(
                temp,
                PUBLISHER,
                () -> true,
                (path, publisher) -> false,
                effects::incrementAndGet,
                path -> effects.incrementAndGet());

        assertThrows(Exception.class, () -> service.install(installer, asset(installer)));

        assertEquals(0, effects.get());
    }

    @Test
    void catchesInstallerMutationDuringTheSnapshotBeforeLaunching() throws Exception {
        Path installer = installer();
        SignedUpdateManifestVerifier.VerifiedAsset asset = asset(installer);
        AtomicInteger launches = new AtomicInteger();
        WindowsUpdateInstaller service = new WindowsUpdateInstaller(
                temp,
                PUBLISHER,
                () -> true,
                (path, publisher) -> true,
                () -> Files.write(installer, new byte[] {1, 2, 3}),
                path -> launches.incrementAndGet());

        assertThrows(Exception.class, () -> service.install(installer, asset));

        assertEquals(0, launches.get());
    }

    @Test
    void isFailClosedOffWindowsOrWithoutAnExactPublisher() {
        assertEquals(false, new WindowsUpdateInstaller(
                temp, PUBLISHER, () -> false, (path, publisher) -> true, () -> {}, path -> {})
                .isSupported());
        assertEquals(false, new WindowsUpdateInstaller(
                temp, "", () -> true, (path, publisher) -> true, () -> {}, path -> {})
                .isSupported());
    }

    @Test
    void helperWaitsForTheExactWCodeProcessToExitBeforeStartingMsi() {
        String script = WindowsUpdateInstaller.helperScript();

        assertTrue(script.contains("Get-Process -Id $wcodeProcessId"));
        assertTrue(script.contains("[DateTime]::UtcNow.AddMinutes(2)"));
        assertTrue(script.indexOf("Get-Process -Id $wcodeProcessId") < script.indexOf("msiexec.exe"));
        assertFalse(script.contains("Start-Sleep -Seconds 2"));
    }

    private Path installer() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("wcode-update-test"));
        Path installer = directory.resolve("WCode.msi");
        byte[] bytes = new byte[1024 * 1024];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (index * 17);
        Files.write(installer, bytes);
        return installer;
    }

    private static SignedUpdateManifestVerifier.VerifiedAsset asset(Path installer) throws Exception {
        return new SignedUpdateManifestVerifier.VerifiedAsset(
                "WCode.msi",
                Files.size(installer),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(installer))),
                URI.create("https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi"));
    }
}
