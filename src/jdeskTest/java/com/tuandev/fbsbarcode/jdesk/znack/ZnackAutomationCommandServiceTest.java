package com.tuandev.fbsbarcode.jdesk.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProCertificateInfo;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ZnackAutomationCommandServiceTest {
    private static final String SECRET = "private-selector-path-token";
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void discoversBoundedSanitizedCertificatesBehindOpaqueSessionIds() {
        FakeSource source = new FakeSource();
        List<CryptoProCertificateInfo> discovered = new ArrayList<>();
        discovered.add(certificate(SECRET + "-usable", "ООО Маркировка", true, NOW.plusSeconds(86_400)));
        discovered.add(certificate(SECRET + "-expired", "Истёк\nсертификат", true, NOW.minusSeconds(1)));
        discovered.add(certificate(SECRET + "-keyless", "Без ключа", false, NOW.plusSeconds(86_400)));
        for (int index = 3; index < 140; index++) {
            discovered.add(certificate(SECRET + index, "Owner " + index, true, NOW.plusSeconds(86_400)));
        }
        ZnackAutomationCommandService service = service(source, settings -> discovered, (settings, cert) -> {},
                (shop, settings, progress) -> 0);

        ZnackAutomationCommandService.CertificateDiscoveryResponse response = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();

        assertEquals(7, response.shopId());
        assertTrue(response.sessionId().matches("[0-9a-f-]{36}"));
        assertEquals(NOW.plus(Duration.ofMinutes(10)).toString(), response.expiresAt());
        assertEquals(100, response.items().size());
        assertEquals("SELECTABLE", response.items().get(0).status());
        assertEquals("EXPIRED", response.items().get(1).status());
        assertEquals("NO_PRIVATE_KEY", response.items().get(2).status());
        assertEquals("ООО Маркировка", response.items().get(0).label());
        assertEquals("7700000000", response.items().get(0).inn());
        assertFalse(response.items().get(0).certificateId().contains(SECRET));
        String json = new JacksonJsonCodec().encode(response);
        assertFalse(json.contains(SECRET));
        assertFalse(json.contains("selector"));
        assertFalse(json.contains("thumbprint"));
        assertFalse(json.contains("provider"));
        assertFalse(json.contains("subject"));
    }

    @Test
    void testsOpaqueCertificateOnceAndAtomicallyPersistsVerifiedPrivateSelection() throws Exception {
        FakeSource source = new FakeSource();
        CryptoProCertificateInfo selected = certificate(
                SECRET + "-selector", "ООО Маркировка", true, NOW.plusSeconds(86_400));
        AtomicReference<String> selectorUsed = new AtomicReference<>();
        ZnackAutomationCommandService service = service(
                source,
                settings -> List.of(selected),
                (settings, certificate) -> {
                    assertEquals(SECRET + "/cryptcp", settings.cryptcpPath());
                    selectorUsed.set(certificate.selector());
                },
                (shop, settings, progress) -> 0);
        String version = ZnackCommandService.settingsVersion(source.settings);
        ZnackAutomationCommandService.CertificateDiscoveryResponse discovery = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();

        ZnackCommandService.SettingsResponse response = service.testCertificate(
                        new ZnackAutomationCommandService.CertificateTestRequest(
                                7, discovery.sessionId(), discovery.items().getFirst().certificateId(), version),
                        null)
                .toCompletableFuture().join();

        assertEquals(SECRET + "-selector", selectorUsed.get());
        assertEquals("VERIFIED", response.signatureStatus());
        assertEquals("ООО Маркировка", response.certificateLabel());
        assertEquals(NOW, source.saved.signerTestedAt());
        assertEquals(SECRET + "-selector", source.saved.signerCertificate());
        assertEquals(1, source.certificateSaves.get());
        assertFalse(new JacksonJsonCodec().encode(response).contains(SECRET));

        JDeskException replay = assertThrows(JDeskException.class, () -> service.testCertificate(
                new ZnackAutomationCommandService.CertificateTestRequest(
                        7, discovery.sessionId(), discovery.items().getFirst().certificateId(), response.version()),
                null));
        assertEquals(ErrorCode.INVALID_REQUEST, replay.code());
        assertEquals(1, source.certificateSaves.get());
    }

    @Test
    void rejectsStaleExpiredKeylessAndReplacedDiscoveryWithoutSigning() {
        FakeSource source = new FakeSource();
        AtomicInteger signs = new AtomicInteger();
        AtomicReference<List<CryptoProCertificateInfo>> certificates = new AtomicReference<>(List.of(
                certificate(SECRET + "-expired", "Expired", true, NOW.minusSeconds(1)),
                certificate(SECRET + "-keyless", "Keyless", false, NOW.plusSeconds(86_400))));
        ZnackAutomationCommandService service = service(source, settings -> certificates.get(),
                (settings, certificate) -> signs.incrementAndGet(), (shop, settings, progress) -> 0);
        String version = ZnackCommandService.settingsVersion(source.settings);
        ZnackAutomationCommandService.CertificateDiscoveryResponse first = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();
        ZnackAutomationCommandService.CertificateDiscoveryResponse replacement = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();

        assertInvalid(() -> service.testCertificate(new ZnackAutomationCommandService.CertificateTestRequest(
                7, first.sessionId(), first.items().getFirst().certificateId(), version), null));
        assertInvalid(() -> service.testCertificate(new ZnackAutomationCommandService.CertificateTestRequest(
                7, replacement.sessionId(), replacement.items().getFirst().certificateId(), version), null));

        ZnackAutomationCommandService.CertificateDiscoveryResponse keylessSession = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();
        assertInvalid(() -> service.testCertificate(new ZnackAutomationCommandService.CertificateTestRequest(
                7, keylessSession.sessionId(), keylessSession.items().get(1).certificateId(), version), null));

        certificates.set(List.of(certificate(SECRET, "Usable", true, NOW.plusSeconds(86_400))));
        ZnackAutomationCommandService.CertificateDiscoveryResponse staleSession = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();
        assertInvalid(() -> service.testCertificate(new ZnackAutomationCommandService.CertificateTestRequest(
                7, staleSession.sessionId(), staleSession.items().getFirst().certificateId(), "0".repeat(64)), null));
        assertEquals(0, signs.get());
        assertEquals(0, source.certificateSaves.get());
    }

    @Test
    void mapsCryptoProFailuresToAllowlistedErrorsWithoutPersisting() {
        FakeSource source = new FakeSource();
        ZnackAutomationCommandService service = service(
                source,
                settings -> List.of(certificate(SECRET, "Owner", true, NOW.plusSeconds(86_400))),
                (settings, certificate) -> {
                    throw new CryptoProException(CryptoProErrorCode.CRYPTCP_LICENSE_INVALID, SECRET);
                },
                (shop, settings, progress) -> 0);
        ZnackAutomationCommandService.CertificateDiscoveryResponse discovery = service.discoverCertificates(
                        new ZnackAutomationCommandService.CertificateDiscoveryRequest(7), null)
                .toCompletableFuture().join();

        JDeskException error = assertThrows(JDeskException.class, () -> service.testCertificate(
                new ZnackAutomationCommandService.CertificateTestRequest(
                        7, discovery.sessionId(), discovery.items().getFirst().certificateId(),
                        ZnackCommandService.settingsVersion(source.settings)), null));

        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        ZnackAutomationCommandService.AutomationError details =
                (ZnackAutomationCommandService.AutomationError) error.details();
        assertEquals("license_invalid", details.kind());
        assertFalse(error.publicMessage().contains(SECRET));
        assertEquals(0, source.certificateSaves.get());
    }

    @Test
    void runsOneParticipantProductSyncPerShopAndReturnsSafeStatus() throws Exception {
        FakeSource source = new FakeSource();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ZnackAutomationCommandService service = service(source, settings -> List.of(), (settings, cert) -> {},
                (shop, settings, progress) -> {
                    calls.incrementAndGet();
                    assertEquals(SECRET + "-selector", settings.signerCertificate());
                    progress.accept("downloading");
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    progress.accept("saving");
                    return 42;
                });
        String version = ZnackCommandService.settingsVersion(source.settings);

        ZnackAutomationCommandService.StartProductSyncResponse first = service.startProductSync(
                        new ZnackAutomationCommandService.StartProductSyncRequest(7, version), null)
                .toCompletableFuture().join();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        ZnackAutomationCommandService.StartProductSyncResponse duplicate = service.startProductSync(
                        new ZnackAutomationCommandService.StartProductSyncRequest(7, version), null)
                .toCompletableFuture().join();
        release.countDown();
        ZnackAutomationCommandService.ProductSyncStatusResponse status = awaitTerminal(service, first);

        assertTrue(first.accepted());
        assertFalse(duplicate.accepted());
        assertEquals(first.jobId(), duplicate.jobId());
        assertEquals("completed", status.state());
        assertEquals("completed", status.phase());
        assertEquals(42, status.products());
        assertEquals(1, calls.get());
        assertFalse(new JacksonJsonCodec().encode(status).contains(SECRET));
    }

    @Test
    void cancelsCooperativelyAndRedactsFailedProductSync() throws Exception {
        FakeSource source = new FakeSource();
        CountDownLatch started = new CountDownLatch(1);
        ZnackAutomationCommandService cancelling = service(source, settings -> List.of(), (settings, cert) -> {},
                (shop, settings, progress) -> {
                    started.countDown();
                    Thread.sleep(Duration.ofSeconds(30));
                    return 0;
                });
        String version = ZnackCommandService.settingsVersion(source.settings);
        ZnackAutomationCommandService.StartProductSyncResponse start = cancelling.startProductSync(
                        new ZnackAutomationCommandService.StartProductSyncRequest(7, version), null)
                .toCompletableFuture().join();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        ZnackAutomationCommandService.CancelProductSyncResponse cancel = cancelling.cancelProductSync(
                        new ZnackAutomationCommandService.CancelProductSyncRequest(7, start.jobId()), null)
                .toCompletableFuture().join();
        ZnackAutomationCommandService.ProductSyncStatusResponse cancelled = awaitTerminal(cancelling, start);
        assertTrue(cancel.cancelRequested());
        assertEquals("cancelled", cancelled.state());
        assertEquals("cancelled", cancelled.errorKind());

        ZnackAutomationCommandService failing = service(source, settings -> List.of(), (settings, cert) -> {},
                (shop, settings, progress) -> {
                    throw new IOException("token=" + SECRET);
                });
        ZnackAutomationCommandService.StartProductSyncResponse failedStart = failing.startProductSync(
                        new ZnackAutomationCommandService.StartProductSyncRequest(7, version), null)
                .toCompletableFuture().join();
        ZnackAutomationCommandService.ProductSyncStatusResponse failed = awaitTerminal(failing, failedStart);
        assertEquals("failed", failed.state());
        assertEquals("unavailable", failed.errorKind());
        assertTrue(failed.retryable());
        assertFalse(failed.toString().contains(SECRET));
    }

    @Test
    void rejectsUnverifiedStaleAndUnknownProductSyncRequests() {
        FakeSource source = new FakeSource();
        ZnackAutomationCommandService service = service(source, settings -> List.of(), (settings, cert) -> {},
                (shop, settings, progress) -> 0);
        assertInvalid(() -> service.startProductSync(
                new ZnackAutomationCommandService.StartProductSyncRequest(9, "0".repeat(64)), null));
        assertInvalid(() -> service.startProductSync(
                new ZnackAutomationCommandService.StartProductSyncRequest(7, "0".repeat(64)), null));

        source.settings = unverifiedSettings();
        assertInvalid(() -> service.startProductSync(
                new ZnackAutomationCommandService.StartProductSyncRequest(
                        7, ZnackCommandService.settingsVersion(source.settings)), null));
        assertInvalid(() -> service.productSyncStatus(
                new ZnackAutomationCommandService.ProductSyncStatusRequest(7, "00000000-0000-0000-0000-000000000000"), null));
    }

    @Test
    void reResolvesSettingsInTheWorkerBeforeCallingTheParticipantApi() throws Exception {
        FakeSource source = new FakeSource();
        source.blockSecondSettingsRead = true;
        AtomicInteger calls = new AtomicInteger();
        ZnackAutomationCommandService service = service(source, settings -> List.of(), (settings, cert) -> {},
                (shop, settings, progress) -> {
                    calls.incrementAndGet();
                    return 0;
                });
        String version = ZnackCommandService.settingsVersion(source.settings);

        ZnackAutomationCommandService.StartProductSyncResponse start = service.startProductSync(
                        new ZnackAutomationCommandService.StartProductSyncRequest(7, version), null)
                .toCompletableFuture().join();
        assertTrue(source.secondSettingsRead.await(5, TimeUnit.SECONDS));
        source.settings = unverifiedSettings();
        source.releaseSecondSettingsRead.countDown();
        ZnackAutomationCommandService.ProductSyncStatusResponse status = awaitTerminal(service, start);

        assertEquals("failed", status.state());
        assertEquals("settings_changed", status.errorKind());
        assertFalse(status.retryable());
        assertEquals(0, calls.get());
    }

    private static ZnackAutomationCommandService service(
            FakeSource source,
            ZnackAutomationCommandService.CertificateDiscoverer discoverer,
            ZnackAutomationCommandService.CertificateTester tester,
            ZnackAutomationCommandService.ProductSyncRunner syncRunner) {
        return new ZnackAutomationCommandService(
                () -> List.of(new Shop(7, "Main shop", SECRET)), source, discoverer, tester, syncRunner, CLOCK);
    }

    private static CryptoProCertificateInfo certificate(
            String selector, String owner, boolean privateKey, Instant validTo) {
        return new CryptoProCertificateInfo(
                selector,
                SECRET + "-thumbprint",
                "CN=" + owner + ", INN=7700000000",
                "CN=" + SECRET + "-issuer",
                "7700000000",
                NOW.minusSeconds(86_400),
                validTo,
                privateKey,
                SECRET + "-provider",
                SECRET + "-raw");
    }

    private static Settings verifiedSettings() {
        return new Settings(
                "https://private.example/" + SECRET, "https://private-suz.example/" + SECRET,
                "OMS-7", "CONNECTION-7", "7700000000", "7700000000", "7700000000",
                SECRET + "/signer", SECRET + "-selector", "[\"" + SECRET + "\"]", "", "",
                SECRET + "/pdf", false, SECRET + "/cert-list", "[\"" + SECRET + "\"]",
                "{\"subject\":\"ООО Маркировка\",\"validTo\":\"2027-07-18T00:00:00Z\"}",
                NOW.minusSeconds(60), SECRET + "/certmgr", SECRET + "/cryptcp", SECRET + "/csptest",
                60, "", Settings.DEFAULT_DOCUMENT_TYPE);
    }

    private static Settings unverifiedSettings() {
        Settings settings = verifiedSettings();
        return new Settings(
                settings.trueApiBaseUrl(), settings.suzBaseUrl(), settings.omsId(), settings.omsConnection(),
                settings.participantInn(), settings.producerInn(), settings.ownerInn(), settings.signerExecutable(),
                "", settings.signerArgumentsJson(), settings.documentNumber(), settings.documentDate(),
                settings.pdfFolder(), settings.autoIntroduction(), settings.certificateListExecutable(),
                settings.certificateListArgumentsJson(), "", null, settings.certmgrPath(), settings.cryptcpPath(),
                settings.csptestPath(), settings.cryptoProTimeoutSeconds(), settings.documentExpiryDate(),
                settings.documentType());
    }

    private static void assertInvalid(Runnable operation) {
        JDeskException error = assertThrows(JDeskException.class, operation::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
    }

    private static ZnackAutomationCommandService.ProductSyncStatusResponse awaitTerminal(
            ZnackAutomationCommandService service,
            ZnackAutomationCommandService.StartProductSyncResponse start) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            ZnackAutomationCommandService.ProductSyncStatusResponse status = service.productSyncStatus(
                            new ZnackAutomationCommandService.ProductSyncStatusRequest(start.shopId(), start.jobId()), null)
                    .toCompletableFuture().join();
            if (!"running".equals(status.state())) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("Znack sync job did not finish");
    }

    private static final class FakeSource implements ZnackAutomationCommandService.AutomationSource {
        private volatile Settings settings = verifiedSettings();
        private volatile Settings saved;
        private final AtomicInteger certificateSaves = new AtomicInteger();
        private final AtomicInteger settingsReads = new AtomicInteger();
        private final CountDownLatch secondSettingsRead = new CountDownLatch(1);
        private final CountDownLatch releaseSecondSettingsRead = new CountDownLatch(1);
        private volatile boolean blockSecondSettingsRead;

        @Override
        public Settings settings(int shopId) {
            if (blockSecondSettingsRead && settingsReads.incrementAndGet() == 2) {
                secondSettingsRead.countDown();
                try {
                    assertTrue(releaseSecondSettingsRead.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", error);
                }
            }
            return settings;
        }

        @Override
        public void saveVerifiedCertificate(
                int shopId, String shopName, Settings expected, Settings verified) {
            if (!settings.equals(expected)) throw new ZnackAutomationCommandService.SettingsConflictException();
            certificateSaves.incrementAndGet();
            saved = verified;
            settings = verified;
        }
    }
}
