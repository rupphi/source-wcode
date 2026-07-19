package com.tuandev.fbsbarcode.jdesk.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Re-verifies a staged MSI, its Authenticode publisher and local snapshot before launching it. */
public final class WindowsUpdateInstaller implements UpdateCommandService.InstallRunner {
    private static final String PUBLISHER_PATTERN = "[A-Za-z0-9][A-Za-z0-9 .,&()'=_-]{2,255}";

    private final Path temporaryBase;
    private final String expectedPublisher;
    private final PlatformSupport platform;
    private final AuthenticodeVerifier authenticode;
    private final SnapshotCreator snapshots;
    private final HelperLauncher helper;

    public WindowsUpdateInstaller(
            Path temporaryBase, String expectedPublisher, SnapshotCreator snapshots) {
        this(
                temporaryBase,
                expectedPublisher,
                WindowsUpdateInstaller::isWindowsX64,
                WindowsUpdateInstaller::verifyAuthenticodeWithPowerShell,
                snapshots,
                WindowsUpdateInstaller::launchHelper);
    }

    WindowsUpdateInstaller(
            Path temporaryBase,
            String expectedPublisher,
            PlatformSupport platform,
            AuthenticodeVerifier authenticode,
            SnapshotCreator snapshots,
            HelperLauncher helper) {
        this.temporaryBase = Objects.requireNonNull(temporaryBase, "temporaryBase")
                .toAbsolutePath().normalize();
        this.expectedPublisher = expectedPublisher == null ? "" : expectedPublisher;
        this.platform = Objects.requireNonNull(platform, "platform");
        this.authenticode = Objects.requireNonNull(authenticode, "authenticode");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.helper = Objects.requireNonNull(helper, "helper");
    }

    public boolean isSupported() {
        return expectedPublisher.matches(PUBLISHER_PATTERN) && platform.isSupported();
    }

    @Override
    public void install(Path path, SignedUpdateManifestVerifier.VerifiedAsset asset) throws Exception {
        if (!isSupported()) throw new IOException("Signed Windows installation is unavailable");
        Path installer = requireStagedInstaller(path);
        verifyFile(installer, asset);
        if (!authenticode.verify(installer, expectedPublisher)) {
            throw new IOException("The installer publisher could not be verified");
        }
        snapshots.create();
        verifyFile(installer, asset);
        if (!authenticode.verify(installer, expectedPublisher)) {
            throw new IOException("The installer publisher changed after the local snapshot");
        }
        helper.launch(installer);
    }

    private Path requireStagedInstaller(Path path) throws IOException {
        if (path == null) throw new IOException("The staged installer is unavailable");
        Path installer = path.toAbsolutePath().normalize();
        Path parent = installer.getParent();
        if (parent == null
                || parent.getParent() == null
                || !parent.getParent().equals(temporaryBase)
                || !parent.getFileName().toString().startsWith("wcode-update-")
                || !"WCode.msi".equals(installer.getFileName().toString())
                || Files.isSymbolicLink(parent)
                || Files.isSymbolicLink(installer)
                || !Files.isRegularFile(installer, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The staged installer path is unsafe");
        }
        return installer;
    }

    private static void verifyFile(
            Path installer, SignedUpdateManifestVerifier.VerifiedAsset asset) throws IOException {
        if (asset == null
                || !"WCode.msi".equals(asset.fileName())
                || asset.size() < 1024L * 1024L
                || asset.size() > 512L * 1024L * 1024L
                || asset.sha256() == null
                || !asset.sha256().matches("[0-9a-f]{64}")
                || Files.size(installer) != asset.size()) {
            throw new IOException("The staged installer did not match the signed manifest");
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(installer, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > asset.size()) {
                    throw new IOException("The staged installer changed during verification");
                }
                digest.update(buffer, 0, count);
            }
            if (total != asset.size()
                    || !MessageDigest.isEqual(
                            HexFormat.of().parseHex(asset.sha256()), digest.digest())) {
                throw new IOException("The staged installer did not match the signed digest");
            }
        }
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (architecture.equals("amd64") || architecture.equals("x86_64"));
    }

    private static boolean verifyAuthenticodeWithPowerShell(Path installer, String publisher)
            throws IOException, InterruptedException {
        String command = "$signature = Get-AuthenticodeSignature -LiteralPath $args[0]; "
                + "if ($signature.Status -eq 'Valid' -and $null -ne $signature.SignerCertificate "
                + "-and $signature.SignerCertificate.Subject -ceq $args[1]) { exit 0 }; exit 1";
        Process process = new ProcessBuilder(
                        "powershell.exe",
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        command,
                        installer.toString(),
                        publisher)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static void launchHelper(Path installer) throws IOException {
        String application = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("The current WCode executable is unavailable"));
        Path executable = Path.of(application).toAbsolutePath().normalize();
        if (!"WCode.exe".equalsIgnoreCase(executable.getFileName().toString())
                || Files.isSymbolicLink(executable)
                || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The current WCode executable is unsafe");
        }

        String command = helperScript();
        new ProcessBuilder(
                        "powershell.exe",
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-WindowStyle",
                        "Hidden",
                        "-Command",
                        command,
                        installer.toString(),
                        executable.toString(),
                        Long.toString(ProcessHandle.current().pid()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    static String helperScript() {
        return "$exitCode = 1; $msi = $args[0]; $app = $args[1]; "
                + "[long]$wcodeProcessId = 0; "
                + "if (-not [long]::TryParse([string]$args[2], [ref]$wcodeProcessId)) { exit 1 }; "
                + "$deadline = [DateTime]::UtcNow.AddMinutes(2); "
                + "while ($null -ne (Get-Process -Id $wcodeProcessId -ErrorAction SilentlyContinue)) { "
                + "if ([DateTime]::UtcNow -ge $deadline) { exit 1 }; "
                + "Start-Sleep -Milliseconds 250 }; try { "
                + "$quotedMsi = [char]34 + $msi + [char]34; "
                + "$process = Start-Process -FilePath 'msiexec.exe' "
                + "-ArgumentList @('/i', $quotedMsi) -Wait -PassThru; "
                + "$exitCode = $process.ExitCode } catch { $exitCode = 1 }; "
                + "if (Test-Path -LiteralPath $app -PathType Leaf) { "
                + "Start-Process -FilePath $app }; exit $exitCode";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    @FunctionalInterface
    interface PlatformSupport {
        boolean isSupported();
    }

    @FunctionalInterface
    interface AuthenticodeVerifier {
        boolean verify(Path path, String publisher) throws Exception;
    }

    @FunctionalInterface
    public interface SnapshotCreator {
        void create() throws Exception;
    }

    @FunctionalInterface
    interface HelperLauncher {
        void launch(Path path) throws Exception;
    }
}
