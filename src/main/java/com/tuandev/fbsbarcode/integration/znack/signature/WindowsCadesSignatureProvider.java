package com.tuandev.fbsbarcode.integration.znack.signature;

import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

final class WindowsCadesSignatureProvider implements ZnackSignatureProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsCadesSignatureProvider.class);
    private static final String PROBE_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $signedData = New-Object -ComObject CAdESCOM.CadesSignedData
            if ($null -eq $signedData) { throw 'CAdESCOM.CadesSignedData is unavailable.' }
            """;
    private static final String SIGN_SCRIPT = """
            param(
              [string]$InputPath,
              [string]$Thumbprint,
              [string]$Detached,
              [string]$OutputPath
            )
            $ErrorActionPreference = 'Stop'
            $store = $null
            $stage = 'create certificate store'
            try {
              $store = New-Object -ComObject CAdESCOM.Store
              $stage = 'open current-user My certificate store'
              $store.Open(2, 'My', 2)
              $stage = 'find selected certificate'
              $normalized = ($Thumbprint -replace '\\s', '').ToUpperInvariant()
              $certificate = $null
              for ($i = 1; $i -le $store.Certificates.Count; $i++) {
                $candidate = $store.Certificates.Item($i)
                if ((($candidate.Thumbprint -replace '\\s', '').ToUpperInvariant()) -eq $normalized) {
                  $certificate = $candidate
                  break
                }
              }
              if ($null -eq $certificate) { throw 'Selected certificate was not found in the current-user My store.' }
              $stage = 'create signer'
              $signer = New-Object -ComObject CAdESCOM.CPSigner
              $stage = 'assign certificate to signer'
              $signer.Certificate = $certificate
              $stage = 'create signed-data object'
              $signedData = New-Object -ComObject CAdESCOM.CadesSignedData
              $signedData.ContentEncoding = 1
              $stage = 'load payload'
              $signedData.Content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($InputPath))
              $stage = 'sign payload'
              $signature = $signedData.SignCades($signer, 1, ($Detached -eq 'true'))
              $stage = 'write signature'
              [IO.File]::WriteAllText($OutputPath, $signature, [Text.Encoding]::ASCII)
            } catch {
              throw "CAdESCOM stage '$stage' failed: $($_.Exception.Message)"
            } finally {
              if ($null -ne $store) {
                try { $store.Close() } catch { }
              }
            }
            """;

    private final CryptoProCommandRunner runner;
    private final String certificateSelector;
    private final Duration timeout;

    WindowsCadesSignatureProvider(String certificateSelector, Duration timeout) {
        this(new CryptoProCommandRunner(), certificateSelector, timeout);
    }

    WindowsCadesSignatureProvider(CryptoProCommandRunner runner, String certificateSelector, Duration timeout) {
        this.runner = runner;
        this.certificateSelector = certificateSelector == null ? "" : certificateSelector.trim();
        this.timeout = timeout;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static void requireAvailable(Duration timeout) throws CryptoProException {
        CryptoProCommandRunner.Result result;
        try {
            result = new CryptoProCommandRunner().run(powerShell("-Command", PROBE_SCRIPT), timeout);
        } catch (CryptoProException error) {
            throw unavailable(error);
        }
        if (result.exitCode() != 0) throw unavailable(result);
    }

    @Override
    public CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException {
        if (certificateSelector.isBlank()) {
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT,
                    "Select a CryptoPro certificate before signing.");
        }
        Path workDir = null;
        Path input = null;
        Path output = null;
        Path script = null;
        try {
            workDir = Files.createTempDirectory("wcode-cades-");
            input = workDir.resolve("payload.bin");
            output = workDir.resolve("signature.p7s");
            script = workDir.resolve("sign.ps1");
            Files.write(input, payload);
            Files.writeString(script, SIGN_SCRIPT, StandardCharsets.UTF_8);
            CryptoProCommandRunner.Result result = runner.run(powerShell("-File", script.toString(),
                    input.toString(), certificateSelector, Boolean.toString(context.detached()), output.toString()), timeout);
            if (result.exitCode() != 0) throw failure(result);
            byte[] raw = Files.isRegularFile(output) ? Files.readAllBytes(output) : new byte[0];
            return new CryptoProSigningResult(CryptoProSignatureProvider.cms(raw), result.diagnostic());
        } catch (CryptoProException error) {
            if (error.code() == CryptoProErrorCode.CRYPTOPRO_MISSING) error = unavailable(error);
            LOGGER.error("CAdESCOM signing failed. code={}, details={}", error.code(), ZnackSanitizer.error(error));
            throw error;
        } catch (Exception error) {
            CryptoProException failure = new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "CAdESCOM signing failed.", error);
            LOGGER.error("CAdESCOM signing failed. code={}, details={}", failure.code(), ZnackSanitizer.error(failure));
            throw failure;
        } finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (Exception ignored) {}
            try { if (output != null) Files.deleteIfExists(output); } catch (Exception ignored) {}
            try { if (script != null) Files.deleteIfExists(script); } catch (Exception ignored) {}
            try { if (workDir != null) Files.deleteIfExists(workDir); } catch (Exception ignored) {}
        }
    }

    private CryptoProException failure(CryptoProCommandRunner.Result result) {
        String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
        CryptoProErrorCode code = diagnostic.contains("class not registered") || diagnostic.contains("класс не зарегистрирован")
                || diagnostic.contains("0x80040154") || diagnostic.contains("com class factory")
                || diagnostic.contains("cadescom") && diagnostic.contains("unavailable")
                ? CryptoProErrorCode.CADESCOM_MISSING
                : diagnostic.contains("cancel") || diagnostic.contains("отмен")
                ? CryptoProErrorCode.CANCELLED
                : diagnostic.contains("expired") || diagnostic.contains("истек")
                ? CryptoProErrorCode.CERTIFICATE_EXPIRED
                : diagnostic.contains("private key") || diagnostic.contains("закрыт")
                ? CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE
                : diagnostic.contains("certificate") || diagnostic.contains("сертифик")
                ? CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT : CryptoProErrorCode.SIGNING_FAILED;
        return new CryptoProException(code, "CAdESCOM signing failed (exit " + result.exitCode() + "): " + result.diagnostic());
    }

    private static CryptoProException unavailable(CryptoProCommandRunner.Result result) {
        return new CryptoProException(CryptoProErrorCode.CADESCOM_MISSING,
                "CryptoPro CAdESCOM signing component is unavailable: " + result.diagnostic());
    }

    private static CryptoProException unavailable(CryptoProException error) {
        return new CryptoProException(CryptoProErrorCode.CADESCOM_MISSING,
                "CryptoPro CAdESCOM signing component is unavailable.", error);
    }

    private static List<String> powerShell(String mode, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", mode));
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }
}
