package com.tuandev.fbsbarcode.integration.znack.signature;

import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CryptoProCommandRunner {
    public record Result(int exitCode, byte[] stdout, byte[] stderr) {
        public String stdoutText() {
            return decode(stdout);
        }

        public String diagnostic() {
            return ZnackSanitizer.message(decode(stderr.length == 0 ? stdout : stderr));
        }
    }

    public Result run(List<String> command, Duration timeout) throws CryptoProException {
        try {
            Process process = new ProcessBuilder(new ArrayList<>(command)).redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new CryptoProException(CryptoProErrorCode.TIMEOUT,
                        "CryptoPro command timed out after " + timeout.toSeconds() + " seconds.");
            }
            return new Result(process.exitValue(), stdout.join(), stderr.join());
        } catch (IOException e) {
            throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "CryptoPro command could not be started.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "CryptoPro command was cancelled.", e);
        }
    }

    public String resolve(String override, String command) throws CryptoProException {
        if (override != null && !override.isBlank()) {
            Path configured = Path.of(override.trim());
            if (Files.isRegularFile(configured) && Files.isExecutable(configured)) return configured.toString();
            if (configured.getParent() == null) {
                for (Path candidate : candidates(configured.toString())) {
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
                }
            }
            throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING,
                    "Configured CryptoPro command is not executable: " + override.trim());
        }
        for (Path candidate : candidates(command)) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
        }
        throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "CryptoPro command not found: " + command);
    }

    List<Path> candidates(String command) {
        String executable = isWindows() && !command.toLowerCase(Locale.ROOT).endsWith(".exe") ? command + ".exe" : command;
        List<Path> result = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) for (String entry : path.split(java.io.File.pathSeparator)) result.add(Path.of(entry, executable));
        if (isWindows()) {
            for (String root : List.of("C:\\Program Files\\Crypto Pro\\CSP", "C:\\Program Files (x86)\\Crypto Pro\\CSP")) {
                result.add(Path.of(root, executable));
            }
        } else {
            for (String root : List.of("/opt/cprocsp/bin", "/opt/cprocsp/sbin",
                    "/opt/cprocsp/bin/amd64", "/opt/cprocsp/bin/aarch64", "/opt/cprocsp/bin/arm64",
                    "/opt/cprocsp/bin/ia32", "/opt/cprocsp/sbin/amd64", "/opt/cprocsp/sbin/aarch64",
                    "/opt/cprocsp/sbin/arm64", "/Applications/CryptoPro/CSP/bin")) {
                result.add(Path.of(root, executable));
            }
        }
        return result;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String decode(byte[] value) {
        String utf8 = new String(value, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        try { return new String(value, Charset.forName("windows-1251")); }
        catch (Exception ignored) { return utf8; }
    }

    private static byte[] read(java.io.InputStream stream) {
        try { return stream.readAllBytes(); }
        catch (IOException e) { return new byte[0]; }
    }
}
