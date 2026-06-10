package com.tuandev.fbsbarcode.integration.znack.signature;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.pkcs.SignerInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class CryptoProSignatureProvider implements ZnackSignatureProvider {
    private final CryptoProCommandRunner runner;
    private final String cryptcpOverride;
    private final String certificateSelector;
    private final Duration timeout;
    private final ZnackSignatureProvider windowsFallback;

    public CryptoProSignatureProvider(String cryptcpOverride, String certificateSelector, Duration timeout) {
        this(new CryptoProCommandRunner(), cryptcpOverride, certificateSelector, timeout,
                WindowsCadesSignatureProvider.isWindows()
                        ? new WindowsCadesSignatureProvider(certificateSelector, timeout) : null);
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector, Duration timeout) {
        this(runner, cryptcpOverride, certificateSelector, timeout, null);
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector,
                               Duration timeout, ZnackSignatureProvider windowsFallback) {
        this.runner = runner;
        this.cryptcpOverride = cryptcpOverride;
        this.certificateSelector = certificateSelector == null ? "" : certificateSelector.trim();
        this.timeout = timeout;
        this.windowsFallback = windowsFallback;
    }

    @Override
    public CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException {
        if (certificateSelector.isBlank()) {
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT, "Select a CryptoPro certificate before signing.");
        }
        Path workDir = null;
        Path input = null;
        Path output = null;
        try {
            workDir = Files.createTempDirectory("wcode-znack-");
            input = workDir.resolve("payload.bin");
            output = workDir.resolve("signature.p7s");
            Files.write(input, payload);
            List<String> command = new ArrayList<>(List.of(
                    runner.resolve(cryptcpOverride, "cryptcp"),
                    "-sign",
                    "-uMy",
                    "-thumbprint", certificateSelector,
                    "-der",
                    context.detached() ? "-detached" : "-attached",
                    input.toString(),
                    output.toString()));
            CryptoProCommandRunner.Result result = runner.run(command, timeout);
            if (result.exitCode() != 0) throw failure(result);
            byte[] raw = Files.isRegularFile(output) && Files.size(output) > 0 ? Files.readAllBytes(output) : result.stdout();
            byte[] cms = cms(raw);
            return new CryptoProSigningResult(cms, result.diagnostic());
        } catch (CryptoProException e) {
            if ((e.code() == CryptoProErrorCode.CRYPTCP_MISSING || e.code() == CryptoProErrorCode.CRYPTOPRO_MISSING)
                    && windowsFallback != null) {
                return windowsFallback.sign(payload, context);
            }
            throw e;
        } catch (Exception e) {
            throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "CryptoPro signing failed.", e);
        } finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (Exception ignored) {}
            try { if (output != null) Files.deleteIfExists(output); } catch (Exception ignored) {}
            try { if (workDir != null) Files.deleteIfExists(workDir); } catch (Exception ignored) {}
        }
    }

    public static void requireAvailable(String cryptcpOverride, Duration timeout) throws CryptoProException {
        try {
            new CryptoProCommandRunner().resolve(cryptcpOverride, "cryptcp");
        } catch (CryptoProException error) {
            if (error.code() != CryptoProErrorCode.CRYPTCP_MISSING || !WindowsCadesSignatureProvider.isWindows()) {
                throw error;
            }
            WindowsCadesSignatureProvider.requireAvailable(timeout);
        }
    }

    static byte[] cms(byte[] raw) throws CryptoProException {
        if (raw == null || raw.length == 0) {
            throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, "CryptoPro returned an empty signature.");
        }
        byte[] value = raw;
        String text = new String(raw, StandardCharsets.US_ASCII).replaceAll("\\s+", "");
        if (text.matches("[A-Za-z0-9+/]+={0,2}")) {
            try { value = Base64.getDecoder().decode(text); }
            catch (IllegalArgumentException e) {
                throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, "CryptoPro returned invalid Base64 signature data.", e);
            }
        }
        if (!isCmsSignedData(value)) {
            throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, "CryptoPro returned invalid CMS/CAdES signature data.");
        }
        return value;
    }

    private static boolean isCmsSignedData(byte[] value) {
        try (ASN1InputStream input = new ASN1InputStream(value)) {
            ContentInfo contentInfo = ContentInfo.getInstance(input.readObject());
            if (input.readObject() != null || !PKCSObjectIdentifiers.signedData.equals(contentInfo.getContentType())) return false;
            SignedData signedData = SignedData.getInstance(contentInfo.getContent());
            if (signedData.getSignerInfos() == null || signedData.getSignerInfos().size() == 0) return false;
            for (int i = 0; i < signedData.getSignerInfos().size(); i++) {
                SignerInfo.getInstance(signedData.getSignerInfos().getObjectAt(i));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private CryptoProException failure(CryptoProCommandRunner.Result result) {
        String diagnostic = result.diagnostic().toLowerCase();
        CryptoProErrorCode code = diagnostic.contains("cancel") || diagnostic.contains("отмен")
                ? CryptoProErrorCode.CANCELLED
                : diagnostic.contains("expired") || diagnostic.contains("истек")
                ? CryptoProErrorCode.CERTIFICATE_EXPIRED
                : diagnostic.contains("private key") || diagnostic.contains("закрыт")
                ? CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE
                : diagnostic.contains("certificate") || diagnostic.contains("сертифик")
                ? CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT : CryptoProErrorCode.SIGNING_FAILED;
        return new CryptoProException(code, "CryptoPro signing failed (exit " + result.exitCode() + "): " + result.diagnostic());
    }
}
