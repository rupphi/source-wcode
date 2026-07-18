package com.tuandev.fbsbarcode.jdesk.znack;

import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackProductService;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackSafety;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProCertificateDiscoveryService;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProCertificateInfo;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Keeps CryptoPro identity and participant authentication behind opaque, shop-scoped commands. */
public final class ZnackAutomationCommandService {
    private static final Duration DISCOVERY_TTL = Duration.ofMinutes(10);
    private static final int MAX_CERTIFICATES = 100;
    private static final int MAX_LABEL_LENGTH = 160;
    private static final int MAX_PRODUCTS = 1_000_000;
    private static final String PROGRESS_EVENT = "znack.productSyncProgress";

    private final Supplier<List<Shop>> shops;
    private final AutomationSource source;
    private final CertificateDiscoverer discoverer;
    private final CertificateTester tester;
    private final ProductSyncRunner syncRunner;
    private final Clock clock;
    private final ConcurrentMap<Integer, CertificateSession> certificateSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, ProductSyncJob> syncJobs = new ConcurrentHashMap<>();

    public ZnackAutomationCommandService() {
        this(
                new ShopRepository()::findAll,
                new LegacyAutomationSource(),
                settings -> new CryptoProCertificateDiscoveryService().discover(
                        settings.certmgrPath(),
                        settings.csptestPath(),
                        Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())),
                (settings, certificate) -> new CryptoProSignatureProvider(
                                settings.cryptcpPath(),
                                certificate.selector(),
                                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()))
                        .sign(
                                ("WCode Znack signature test " + Instant.now())
                                        .getBytes(StandardCharsets.UTF_8),
                                ZnackSignatureContext.SIGNATURE_TEST),
                ZnackAutomationCommandService::syncProducts,
                Clock.systemUTC());
    }

    ZnackAutomationCommandService(
            Supplier<List<Shop>> shops,
            AutomationSource source,
            CertificateDiscoverer discoverer,
            CertificateTester tester,
            ProductSyncRunner syncRunner,
            Clock clock) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.source = Objects.requireNonNull(source, "source");
        this.discoverer = Objects.requireNonNull(discoverer, "discoverer");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.syncRunner = Objects.requireNonNull(syncRunner, "syncRunner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @DesktopCommand("znack.discoverCertificates")
    @RequiresCapability("znack:certificate")
    public CompletionStage<CertificateDiscoveryResponse> discoverCertificates(
            CertificateDiscoveryRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context, "Certificate discovery was cancelled.");
            requireShop(shopId);
            Settings settings = requireSettings(shopId);
            List<CryptoProCertificateInfo> discovered;
            try {
                discovered = List.copyOf(Objects.requireNonNull(discoverer.discover(settings), "certificates"));
            } catch (CryptoProException error) {
                throw certificateFailure(error);
            } catch (Exception error) {
                throw automationFailure(ErrorCode.INVALID_REQUEST, "Certificate discovery failed.", "discovery_failed", true);
            }
            requireNotCancelled(context, "Certificate discovery was cancelled.");
            if (discovered.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("Certificate discovery result is invalid");
            }
            Instant expiresAt = clock.instant().plus(DISCOVERY_TTL);
            String sessionId = UUID.randomUUID().toString();
            Map<String, CryptoProCertificateInfo> byId = new LinkedHashMap<>();
            List<CertificateItem> items = discovered.stream()
                    .limit(MAX_CERTIFICATES)
                    .map(certificate -> {
                        String certificateId = UUID.randomUUID().toString();
                        byId.put(certificateId, certificate);
                        return certificateItem(certificateId, certificate, clock.instant());
                    })
                    .toList();
            certificateSessions.put(shopId, new CertificateSession(sessionId, shopId, expiresAt, Map.copyOf(byId)));
            return new CertificateDiscoveryResponse(shopId, sessionId, expiresAt.toString(), items);
        });
    }

    @DesktopCommand("znack.testCertificate")
    @RequiresCapability("znack:certificate")
    public CompletionStage<ZnackCommandService.SettingsResponse> testCertificate(
            CertificateTestRequest request, InvocationContext context) {
        ValidatedCertificateTest validated = validateCertificateTest(request);
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context, "Certificate test was cancelled.");
            Shop shop = requireShop(validated.shopId());
            CertificateSession session = certificateSessions.get(validated.shopId());
            if (session == null
                    || !session.sessionId.equals(validated.sessionId())
                    || !session.expiresAt.isAfter(clock.instant())
                    || !certificateSessions.remove(validated.shopId(), session)) {
                throw invalid("Certificate discovery expired. Discover certificates again.");
            }
            Settings current = requireSettings(validated.shopId());
            if (!ZnackCommandService.settingsVersion(current).equals(validated.version())) {
                throw invalid("Znack settings changed. Reload them and discover certificates again.");
            }
            CryptoProCertificateInfo certificate = session.certificates.get(validated.certificateId());
            if (certificate == null) throw invalid("The selected certificate is not available.");
            if (certificate.expired(clock.instant())) throw invalid("The selected certificate is expired.");
            if (!certificate.hasPrivateKey()) throw invalid("The selected certificate has no private key.");
            try {
                tester.test(current, certificate);
            } catch (CryptoProException error) {
                throw certificateFailure(error);
            } catch (Exception error) {
                throw automationFailure(ErrorCode.INVALID_REQUEST, "Certificate test failed.", "signing_failed", true);
            }
            requireNotCancelled(context, "Certificate test was cancelled before settings were saved.");
            Instant verifiedAt = clock.instant();
            Settings verified = verifiedSettings(current, certificate, verifiedAt);
            try {
                source.saveVerifiedCertificate(
                        validated.shopId(), safeText(shop.getName(), MAX_LABEL_LENGTH), current, verified);
            } catch (SettingsConflictException conflict) {
                throw invalid("Znack settings changed. Reload them and discover certificates again.");
            }
            Settings persisted = requireSettings(validated.shopId());
            return ZnackCommandService.toSettingsResponse(validated.shopId(), persisted, clock.instant());
        });
    }

    @DesktopCommand("znack.startProductSync")
    @RequiresCapability("znack:sync")
    public CompletionStage<StartProductSyncResponse> startProductSync(
            StartProductSyncRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        String version = requireVersion(request == null ? null : request.version());
        requireNotCancelled(context, "Product sync was cancelled before launch.");
        Shop shop = requireShop(shopId);
        Settings settings = requireSettings(shopId);
        if (!ZnackCommandService.settingsVersion(settings).equals(version)) {
            throw invalid("Znack settings changed. Reload them before syncing products.");
        }
        try {
            ZnackSafety.requireSigned(settings, false);
        } catch (IllegalStateException error) {
            throw invalid("Verify a CryptoPro certificate before syncing products.");
        }

        AtomicBoolean accepted = new AtomicBoolean();
        ProductSyncJob job = syncJobs.compute(shopId, (ignored, existing) -> {
            if (existing != null && existing.isRunning()) return existing;
            accepted.set(true);
            return new ProductSyncJob(UUID.randomUUID().toString(), shopId);
        });
        if (accepted.get()) {
            EventEmitter emitter = emitter(context);
            job.worker = Thread.ofVirtual()
                    .name("wcode-znack-product-sync-" + shopId)
                    .start(() -> runSync(job, shop, version, emitter));
        }
        return CompletableFuture.completedFuture(new StartProductSyncResponse(accepted.get(), shopId, job.jobId));
    }

    @DesktopCommand("znack.productSyncStatus")
    @RequiresCapability("znack:sync")
    public CompletionStage<ProductSyncStatusResponse> productSyncStatus(
            ProductSyncStatusRequest request, InvocationContext context) {
        ProductSyncJob job = requireJob(request == null ? 0 : request.shopId(), request == null ? null : request.jobId());
        return CompletableFuture.completedFuture(job.snapshot());
    }

    @DesktopCommand("znack.cancelProductSync")
    @RequiresCapability("znack:sync")
    public CompletionStage<CancelProductSyncResponse> cancelProductSync(
            CancelProductSyncRequest request, InvocationContext context) {
        ProductSyncJob job = requireJob(request == null ? 0 : request.shopId(), request == null ? null : request.jobId());
        return CompletableFuture.completedFuture(
                new CancelProductSyncResponse(job.requestCancel(), job.shopId, job.jobId));
    }

    private void runSync(ProductSyncJob job, Shop shop, String expectedVersion, EventEmitter emitter) {
        if (!job.begin(clock.instant())) {
            emit(emitter, job.progress(true));
            return;
        }
        emit(emitter, job.progress(false));
        try {
            Settings settings = source.settings(shop.getId());
            if (settings == null || !ZnackCommandService.settingsVersion(settings).equals(expectedVersion)) {
                job.fail(new AutomationError("settings_changed", false), clock.instant());
                emit(emitter, job.progress(true));
                return;
            }
            ZnackSafety.requireSigned(settings, false);
            if (job.cancelRequested() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled before participant sync");
            }
            int products = syncRunner.sync(shop, settings, phase -> {
                job.phase(requirePhase(phase));
                emit(emitter, job.progress(false));
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
            });
            if (products < 0 || products > MAX_PRODUCTS) {
                throw new IllegalStateException("Znack product count is invalid");
            }
            job.complete(products, clock.instant());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            job.fail(new AutomationError(job.cancelRequested() ? "cancelled" : "unavailable", true), clock.instant());
        } catch (CryptoProException error) {
            job.fail(new AutomationError(certificateKind(error.code()), retryable(error.code())), clock.instant());
        } catch (ZnackApiClient.ZnackApiException error) {
            job.fail(apiError(error.statusCode()), clock.instant());
        } catch (IOException error) {
            job.fail(new AutomationError("unavailable", true), clock.instant());
        } catch (Exception error) {
            job.fail(new AutomationError("internal", true), clock.instant());
        }
        emit(emitter, job.progress(true));
    }

    private Shop requireShop(int shopId) {
        List<Shop> available;
        try {
            available = List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
        } catch (RuntimeException error) {
            throw automationFailure(ErrorCode.INTERNAL_ERROR, "Znack operation could not start.", "internal", true);
        }
        return available.stream()
                .filter(shop -> shop != null && shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> invalid("The selected shop is not available."));
    }

    private Settings requireSettings(int shopId) {
        try {
            return Objects.requireNonNull(source.settings(shopId), "Znack settings");
        } catch (JDeskException error) {
            throw error;
        } catch (RuntimeException error) {
            throw automationFailure(ErrorCode.INTERNAL_ERROR, "Znack settings are unavailable.", "internal", true);
        }
    }

    private ProductSyncJob requireJob(int shopId, String jobId) {
        requireShopId(shopId);
        if (!uuid(jobId)) throw invalid("The product sync identifier is invalid.");
        ProductSyncJob job = syncJobs.get(shopId);
        if (job == null || !job.jobId.equals(jobId)) throw invalid("The product sync is no longer available.");
        return job;
    }

    private static ValidatedCertificateTest validateCertificateTest(CertificateTestRequest request) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        if (request == null || !uuid(request.sessionId()) || !uuid(request.certificateId())) {
            throw invalid("The certificate selection is invalid.");
        }
        return new ValidatedCertificateTest(
                shopId, request.sessionId(), request.certificateId(), requireVersion(request.version()));
    }

    private static CertificateItem certificateItem(
            String certificateId, CryptoProCertificateInfo certificate, Instant now) {
        String owner = certificateLabel(certificate);
        String inn = certificate.inn() != null && certificate.inn().matches("\\d{10}|\\d{12}")
                ? certificate.inn() : "";
        String status = certificate.expired(now)
                ? "EXPIRED" : certificate.hasPrivateKey() ? "SELECTABLE" : "NO_PRIVATE_KEY";
        return new CertificateItem(
                certificateId,
                owner,
                inn,
                date(certificate.validFrom()),
                date(certificate.validTo()),
                certificate.hasPrivateKey(),
                status);
    }

    private static Settings verifiedSettings(
            Settings current, CryptoProCertificateInfo certificate, Instant verifiedAt) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("selector", certificate.selector());
        metadata.addProperty("thumbprint", value(certificate.thumbprint()));
        metadata.addProperty("label", certificateLabel(certificate));
        metadata.addProperty("subject", value(certificate.subject()));
        metadata.addProperty("issuer", value(certificate.issuer()));
        metadata.addProperty("inn", value(certificate.inn()));
        if (certificate.validFrom() != null) metadata.addProperty("validFrom", certificate.validFrom().toString());
        if (certificate.validTo() != null) metadata.addProperty("validTo", certificate.validTo().toString());
        metadata.addProperty("hasPrivateKey", certificate.hasPrivateKey());
        metadata.addProperty("provider", value(certificate.provider()));
        return new Settings(
                current.trueApiBaseUrl(), current.suzBaseUrl(), current.omsId(), current.omsConnection(),
                current.participantInn(), current.producerInn(), current.ownerInn(), current.signerExecutable(),
                certificate.selector(), current.signerArgumentsJson(), current.documentNumber(), current.documentDate(),
                current.pdfFolder(), current.autoIntroduction(), current.certificateListExecutable(),
                current.certificateListArgumentsJson(), metadata.toString(), verifiedAt, current.certmgrPath(),
                current.cryptcpPath(), current.csptestPath(), current.resolvedCryptoProTimeoutSeconds(),
                current.documentExpiryDate(), current.documentType());
    }

    private static int syncProducts(Shop shop, Settings settings, ProgressSink progress) throws Exception {
        progress.accept("connecting");
        ZnackRepository repository = new ZnackRepository(new ShopContext(shop.getId(), value(shop.getName())));
        ZnackApiClient api = new ZnackApiClient();
        CryptoProSignatureProvider signer = new CryptoProSignatureProvider(
                settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        try {
            int products = new ZnackProductService(api, new ZnackAuthService(api, signer), repository)
                    .sync(settings).size();
            progress.accept("saving");
            return products;
        } catch (Exception error) {
            repository.log("GTIN_SYNC", null, "ERROR", error.getMessage(), null);
            throw error;
        }
    }

    private static JDeskException certificateFailure(CryptoProException error) {
        String kind = certificateKind(error.code());
        return automationFailure(
                ErrorCode.INVALID_REQUEST,
                "CryptoPro operation failed. Check the certificate and CryptoPro installation.",
                kind,
                retryable(error.code()));
    }

    private static String certificateKind(CryptoProErrorCode code) {
        return switch (code) {
            case CRYPTOPRO_MISSING, CRYPTCP_MISSING, CERTMGR_MISSING, CADESCOM_MISSING -> "cryptopro_missing";
            case CRYPTCP_LICENSE_INVALID -> "license_invalid";
            case TOKEN_OR_CERTIFICATE_ABSENT -> "certificate_absent";
            case PRIVATE_KEY_UNAVAILABLE -> "private_key_unavailable";
            case CERTIFICATE_EXPIRED -> "certificate_expired";
            case CANCELLED -> "cancelled";
            case TIMEOUT -> "timeout";
            case INVALID_SIGNATURE_OUTPUT -> "invalid_signature";
            case DISCOVERY_FAILED -> "discovery_failed";
            case SIGNING_FAILED -> "signing_failed";
        };
    }

    private static boolean retryable(CryptoProErrorCode code) {
        return switch (code) {
            case CERTIFICATE_EXPIRED, CRYPTCP_LICENSE_INVALID, INVALID_SIGNATURE_OUTPUT -> false;
            default -> true;
        };
    }

    private static AutomationError apiError(int status) {
        if (status == 401 || status == 403) return new AutomationError("authentication_failed", false);
        if (status == 429) return new AutomationError("rate_limited", true);
        return new AutomationError("upstream", true);
    }

    private static JDeskException automationFailure(
            ErrorCode code, String message, String kind, boolean retryable) {
        return new JDeskException(code, message, new AutomationError(kind, retryable), null);
    }

    private static JDeskException invalid(String message) {
        return SafeCommandExecutor.invalidRequest(message);
    }

    private static void requireNotCancelled(InvocationContext context, String message) {
        if (context != null && context.isCancelled()) {
            throw automationFailure(ErrorCode.CANCELLED, message, "cancelled", true);
        }
    }

    private static int requireShopId(int shopId) {
        if (shopId <= 0) throw invalid("A positive shop id is required.");
        return shopId;
    }

    private static String requireVersion(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}")) {
            throw invalid("The Znack settings version is invalid.");
        }
        return version;
    }

    private static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String requirePhase(String phase) {
        if (phase == null || !phase.matches("[a-z][a-zA-Z0-9]{0,31}")) {
            throw new IllegalStateException("Znack sync phase is invalid");
        }
        return phase;
    }

    private static String safeText(String candidate, int maximum) {
        String normalized = value(candidate).replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).strip();
    }

    private static String date(Instant value) {
        return value == null ? "" : LocalDate.ofInstant(value, ZoneOffset.UTC).toString();
    }

    private static String certificateLabel(CryptoProCertificateInfo certificate) {
        String label = safeText(certificate.ownerName(), MAX_LABEL_LENGTH);
        return label.isBlank() || label.equals(certificate.selector()) ? "CryptoPro certificate" : label;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static EventEmitter emitter(InvocationContext context) {
        if (context == null) return null;
        try {
            return context.events();
        } catch (JDeskException error) {
            return null;
        }
    }

    private static void emit(EventEmitter emitter, ProductSyncProgress progress) {
        if (emitter == null) return;
        try {
            emitter.emit(PROGRESS_EVENT, progress);
        } catch (JDeskException ignored) {
            // Progress is advisory; status polling remains authoritative.
        }
    }

    @FunctionalInterface
    interface CertificateDiscoverer {
        List<CryptoProCertificateInfo> discover(Settings settings) throws Exception;
    }

    @FunctionalInterface
    interface CertificateTester {
        void test(Settings settings, CryptoProCertificateInfo certificate) throws Exception;
    }

    @FunctionalInterface
    interface ProductSyncRunner {
        int sync(Shop shop, Settings settings, ProgressSink progress) throws Exception;
    }

    @FunctionalInterface
    interface ProgressSink {
        void accept(String phase) throws InterruptedException;
    }

    interface AutomationSource {
        Settings settings(int shopId);

        void saveVerifiedCertificate(int shopId, String shopName, Settings expected, Settings verified);
    }

    public record CertificateDiscoveryRequest(int shopId) {}

    public record CertificateDiscoveryResponse(
            int shopId, String sessionId, String expiresAt, List<CertificateItem> items) {}

    public record CertificateItem(
            String certificateId,
            String label,
            String inn,
            String validFrom,
            String validTo,
            boolean hasPrivateKey,
            String status) {}

    public record CertificateTestRequest(
            int shopId, String sessionId, String certificateId, String version) {}

    public record StartProductSyncRequest(int shopId, String version) {}

    public record StartProductSyncResponse(boolean accepted, int shopId, String jobId) {}

    public record ProductSyncStatusRequest(int shopId, String jobId) {}

    public record CancelProductSyncRequest(int shopId, String jobId) {}

    public record CancelProductSyncResponse(boolean cancelRequested, int shopId, String jobId) {}

    public record ProductSyncStatusResponse(
            String jobId,
            int shopId,
            String state,
            String phase,
            int products,
            String completedAt,
            String errorKind,
            boolean retryable) {}

    public record ProductSyncProgress(
            int shopId, String jobId, String state, String phase, boolean done) {}

    public record AutomationError(String kind, boolean retryable) {}

    public static final class SettingsConflictException extends RuntimeException {
        public SettingsConflictException() {
            super("Znack settings changed concurrently.");
        }
    }

    private record ValidatedCertificateTest(
            int shopId, String sessionId, String certificateId, String version) {}

    private record CertificateSession(
            String sessionId,
            int shopId,
            Instant expiresAt,
            Map<String, CryptoProCertificateInfo> certificates) {}

    private static final class LegacyAutomationSource implements AutomationSource {
        @Override
        public Settings settings(int shopId) {
            return repository(shopId, "").getSettings();
        }

        @Override
        public void saveVerifiedCertificate(
                int shopId, String shopName, Settings expected, Settings verified) {
            try {
                repository(shopId, shopName).saveVerifiedCertificate(expected, verified);
            } catch (ZnackRepository.SettingsConflictException conflict) {
                throw new SettingsConflictException();
            }
        }

        private static ZnackRepository repository(int shopId, String shopName) {
            return new ZnackRepository(new ShopContext(shopId, shopName));
        }
    }

    private static final class ProductSyncJob {
        private final String jobId;
        private final int shopId;
        private String state = "running";
        private String phase = "queued";
        private int products;
        private String completedAt = "";
        private AutomationError error = new AutomationError("", false);
        private boolean cancelRequested;
        private volatile Thread worker;

        private ProductSyncJob(String jobId, int shopId) {
            this.jobId = jobId;
            this.shopId = shopId;
        }

        private synchronized boolean isRunning() {
            return "running".equals(state);
        }

        private synchronized boolean begin(Instant now) {
            if (cancelRequested) {
                cancel(now);
                return false;
            }
            phase = "starting";
            return true;
        }

        private synchronized void phase(String value) {
            if ("running".equals(state)) phase = value;
        }

        private synchronized void complete(int value, Instant now) {
            if (cancelRequested) {
                cancel(now);
                return;
            }
            products = value;
            phase = "completed";
            state = "completed";
            completedAt = now.toString();
        }

        private synchronized void fail(AutomationError failure, Instant now) {
            if (cancelRequested || "cancelled".equals(failure.kind())) {
                cancel(now);
                return;
            }
            error = failure;
            phase = "failed";
            state = "failed";
            completedAt = now.toString();
        }

        private synchronized boolean requestCancel() {
            if (!"running".equals(state)) return false;
            cancelRequested = true;
            Thread active = worker;
            if (active != null) active.interrupt();
            return true;
        }

        private synchronized boolean cancelRequested() {
            return cancelRequested;
        }

        private void cancel(Instant now) {
            error = new AutomationError("cancelled", true);
            phase = "cancelled";
            state = "cancelled";
            completedAt = now.toString();
        }

        private synchronized ProductSyncStatusResponse snapshot() {
            return new ProductSyncStatusResponse(
                    jobId, shopId, state, phase, products, completedAt, error.kind(), error.retryable());
        }

        private synchronized ProductSyncProgress progress(boolean done) {
            return new ProductSyncProgress(shopId, jobId, state, phase, done);
        }
    }
}
