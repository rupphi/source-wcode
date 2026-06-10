package com.tuandev.fbsbarcode.integration.znack.signature;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CryptoProSignatureTest {
    @Test void parsesRussianAndEnglishCertmgrAndFiltersUnusable() {
        String output = """
                SHA1 Hash: AABB
                Subject: CN=Example LLC, INN=7701234567
                Issuer: CN=CA
                Not valid after: 01.01.2099 00:00:00
                Private key: present
                Provider: Crypto-Pro GOST

                Отпечаток: CCDD
                Субъект: CN=ООО Истек, ИНН=7812345678
                Издатель: CN=УЦ
                Действителен до: 01.01.2020 00:00:00
                Закрытый ключ: есть
                Провайдер: CryptoPro

                Отпечаток: EEFF
                Субъект: CN=ООО Без ключа, ИНН=7812345679
                Действителен до: 01.01.2099 00:00:00
                Закрытый ключ: нет
                """;
        CryptoProCertificateDiscoveryService service = new CryptoProCertificateDiscoveryService();
        List<CryptoProCertificateInfo> certificates = service.parse(output);
        assertEquals(3, certificates.size());
        assertEquals("7701234567", certificates.getFirst().inn());
        assertEquals(List.of("AABB", "EEFF"), service.usable(certificates, Instant.now()).stream().map(CryptoProCertificateInfo::selector).toList());
    }

    @Test void parsesActualCertmgrListBlockWhereThumbprintFollowsSubject() {
        String output = """
                Certmgr 1.1 (c) "Crypto-Pro", 2007-2021.
                =============================================================================
                1-------
                Issuer              : CN=CryptoPro Test CA
                Subject             : INN=007814508921, CN=Example Owner
                Serial              : 0x1234
                SHA1 Thumbprint     : cd321b87fdabb503829f88db68d893b59a7c5dd3
                Not valid before    : 01/01/2025 00:00:00
                Not valid after     : 01/01/2029 00:00:00
                PrivateKey Link     : Yes
                Container           : HDIMAGE\\\\container
                """;
        CryptoProCertificateInfo certificate = new CryptoProCertificateDiscoveryService().parse(output).getFirst();
        assertEquals("cd321b87fdabb503829f88db68d893b59a7c5dd3", certificate.thumbprint());
        assertEquals("007814508921", certificate.inn());
        assertTrue(certificate.hasPrivateKey());
    }

    @Test void treatsCertmgrEmptyStoreExitAsNoCertificatesInsteadOfDiscoveryFailure() throws Exception {
        CryptoProCommandRunner runner = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) {
                return "certmgr";
            }

            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(44, new byte[0], "Empty certificate list".getBytes());
            }
        };

        assertTrue(new CryptoProCertificateDiscoveryService(runner)
                .discover("", "", Duration.ofSeconds(1)).isEmpty());
    }

    @Test void signsWithOverrideAndValidatesCms() throws Exception {
        OutputRunner runner = new OutputRunner(cmsFixture());
        CryptoProSigningResult result = new CryptoProSignatureProvider(runner, "cryptcp", "AABB", Duration.ofSeconds(2))
                .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST);
        assertArrayEquals(cmsFixture(), result.cms());
        List<String> args = runner.command;
        assertEquals(List.of("-sign", "-uMy", "-thumbprint", "AABB", "-der", "-detached"), args.subList(0, 6));
        assertFalse(args.contains("-pin"));
        assertFalse(args.contains("-askpin"));
    }

    @Test void rejectsInvalidOutputAndMapsTimeout() throws Exception {
        OutputRunner invalid = new OutputRunner("invalid".getBytes());
        CryptoProException invalidError = assertThrows(CryptoProException.class,
                () -> new CryptoProSignatureProvider(invalid, "cryptcp", "AABB", Duration.ofSeconds(2))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));
        assertEquals(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, invalidError.code());

        CryptoProCommandRunner slow = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) {
                return "cryptcp";
            }

            @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
                throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "fixture timeout");
            }
        };
        CryptoProException timeout = assertThrows(CryptoProException.class,
                () -> new CryptoProSignatureProvider(slow, "cryptcp", "AABB", Duration.ofMillis(50))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));
        assertEquals(CryptoProErrorCode.TIMEOUT, timeout.code());
    }

    @Test void certificateSelectionUsesExpiryAndTestSigningValidatesPrivateKey() {
        Instant now = Instant.now();
        CryptoProCertificateInfo expired = new CryptoProCertificateInfo("a", "a", "", "", "", null,
                now.minus(1, ChronoUnit.DAYS), true, "", "");
        CryptoProCertificateInfo noKey = new CryptoProCertificateInfo("b", "b", "", "", "", null,
                now.plus(1, ChronoUnit.DAYS), false, "", "");
        assertFalse(expired.usable(now));
        assertTrue(noKey.usable(now));
    }

    private static final class OutputRunner extends CryptoProCommandRunner {
        private final byte[] output;
        private List<String> command = List.of();

        private OutputRunner(byte[] output) {
            this.output = output;
        }

        @Override public String resolve(String override, String command) {
            return "cryptcp";
        }

        @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
            this.command = List.copyOf(command.subList(1, command.size() - 2));
            try {
                Files.write(Path.of(command.getLast()), output);
                return new Result(0, new byte[0], new byte[0]);
            } catch (Exception e) {
                throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "Could not write fixture signature.", e);
            }
        }
    }

    private byte[] cmsFixture() throws Exception {
        AlgorithmIdentifier digest = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
        SignerInfo signer = new SignerInfo(new ASN1Integer(1),
                new IssuerAndSerialNumber(new X500Name("CN=Fixture"), BigInteger.ONE), digest, null,
                new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption), new DEROctetString(new byte[]{1}), null);
        SignedData signedData = new SignedData(new ASN1Integer(1), new DERSet(digest),
                new ContentInfo(PKCSObjectIdentifiers.data, null), null, null, new DERSet(signer));
        return new ContentInfo(PKCSObjectIdentifiers.signedData, signedData).getEncoded(ASN1Encoding.DER);
    }
}
