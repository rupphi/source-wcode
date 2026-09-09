package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

/** One bounded worker drains durable identities, never one thread per selected row. */
public final class RegistrationRunner {
    private static final RegistrationQueueStore QUEUE = new RegistrationQueueStore();
    private static final ZnackCardRegistrationRepository REPOSITORY = new ZnackCardRegistrationRepository();
    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, BiConsumer<Status, String>> LISTENERS = new ConcurrentHashMap<>();
    private static boolean started;
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "znack-registration-queue"); t.setDaemon(true); return t;
    });
    record Session(String fingerprint, ZnackAuthService auth, ZnackNationalCatalogService catalog) { }

    public static synchronized void start() {
        if (started) return;
        QUEUE.recoverInterrupted();
        started = true;
        WORKER.scheduleWithFixedDelay(RegistrationRunner::tick, 1, 15, TimeUnit.SECONDS);
    }
    static boolean enqueue(Shop shop, Sku sku, Draft draft, BiConsumer<Status, String> listener) {
        start();
        var settings = settings(shop);
        boolean accepted = QUEUE.enqueue(shop.getId(), sku, draft, fingerprint(shop, settings), sku.status() == Status.ERROR);
        if (accepted && listener != null) LISTENERS.put(shop.getId() + ":" + sku.chrtId(), listener);
        return accepted;
    }
    /** Called only by the user's explicit resume action after correcting the account problem. */
    public static int resumePaused(Shop shop) {
        if (shop == null || shop.getMarketplace() != com.tuandev.fbsbarcode.integration.marketplace.Marketplace.WILDBERRIES)
            throw new IllegalArgumentException("A Wildberries shop is required.");
        int resumed = QUEUE.resumeAccount(shop.getId(), fingerprint(shop, settings(shop)));
        if (resumed > 0) {
            SESSIONS.remove(shop.getId());
            ZnackSigningSession.authorizeShop(shop.getId());
            start();
        }
        return resumed;
    }
    static ZnackModels.Settings settings(Shop shop) {
        return new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
    }
    static String fingerprint(Shop shop, ZnackModels.Settings settings) {
        try {
            String identity = shop.getApiKey() + "|" + settings.participantInn() + "|" + settings.signerCertificate()
                    + "|" + settings.resolvedTrueApiBaseUrl();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static Session session(Shop shop, ZnackModels.Settings settings) {
        String key = fingerprint(shop, settings);
        return SESSIONS.compute(shop.getId(), (id, old) -> {
            if (old != null && old.fingerprint().equals(key)) return old;
            var signer = ZnackSigningSession.guard(id, new CryptoProSignatureProvider(settings.cryptcpPath(),
                    settings.signerCertificate(), Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
            var api = new ZnackApiClient();
            var auth = new ZnackAuthService(api, signer);
            return new Session(key, auth, new ZnackNationalCatalogService(api, auth, signer, settings));
        });
    }
    private static void tick() {
        try {
            for (var job : QUEUE.pending(ZnackSigningSession.authorizedShopIds())) {
                if (!ZnackSigningSession.isShopAuthorized(job.shopId())) continue;
                Shop shop = new ShopRepository().findById(job.shopId());
                if (shop == null) continue;
                String key = shop.getId() + ":" + job.sku().chrtId();
                var listener = LISTENERS.remove(key);
                var flow = new ZnackCardRegistrationWorkflow(REPOSITORY);
                try {
                    if (!job.fingerprint().equals(fingerprint(shop, settings(shop))))
                        throw new IllegalStateException("Shop credentials changed; review registration before retrying.");
                    Sku current = REPOSITORY.find(job.shopId(), job.sku().chrtId());
                    if (current == null || current.nmId() != job.sku().nmId())
                        throw new IllegalStateException("WB product identity changed; refresh the catalog.");
                    if (current.status() == Status.PUBLISHED || current.status() == Status.WB_UPDATE_PENDING) {
                        QUEUE.phase(job.shopId(), current.chrtId(), "DONE"); continue;
                    }
                    QUEUE.phase(job.shopId(), current.chrtId(), "RUNNING");
                    flow.execute(shop, retrySnapshot(current, job.sku().status()), job.draft(), listener);
                    QUEUE.phase(job.shopId(), current.chrtId(), "DONE");
                } catch (Exception error) {
                    if (accountWideFailure(error)) {
                        QUEUE.pauseAccount(job.shopId());
                        Sku checkpoint = REPOSITORY.find(job.shopId(), job.sku().chrtId());
                        if (checkpoint != null && checkpoint.feedId() != null && !checkpoint.feedId().isBlank()
                                && checkpoint.status() != Status.ERROR) {
                            // A temporary account failure after submission must not convert resume into resubmit.
                            REPOSITORY.updateProgress(job.shopId(), checkpoint.chrtId(), checkpoint.status(), null,
                                    null, ZnackErrorDetails.summary(error), null);
                            if (listener != null) listener.accept(Status.ERROR, ZnackErrorDetails.format(error));
                        } else flow.fail(shop, job.sku(), error, listener);
                    } else {
                        QUEUE.phase(job.shopId(), job.sku().chrtId(), "FAILED");
                        flow.fail(shop, job.sku(), error, listener);
                    }
                    break; // Account pause is durable: later ticks cannot drain the remaining batch.
                }
                break; // Bound remote work to one card per tick.
            }
            new RegistrationPublicationMonitor(new com.tuandev.fbsbarcode.integration.wb.WbApiClient()).tick();
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(RegistrationRunner.class).warn("Registration queue tick failed: {}", error.getClass().getSimpleName());
        }
    }

    static boolean accountWideFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ZnackApiClient.ZnackApiException api) {
                int status = api.statusCode();
                if (status == 401 || status == 403 || status == 429 || status >= 500) return true;
                String message = java.util.Objects.toString(api.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
                if (message.contains("quota") || message.contains("gs1") || message.contains("1090")) return true;
                // An HTTP 400/404 for one card is not a network failure.
                return false;
            }
            if (cause instanceof com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException
                    || cause instanceof java.io.IOException) return true;
            String message = java.util.Objects.toString(cause.getMessage(), "");
            if (message.startsWith("GS1/GTIN quota") || message.startsWith("Shop credentials changed;")) return true;
        }
        return false;
    }

    static Sku retrySnapshot(Sku current, Status original) {
        boolean submittedCheckpoint = current.feedId() != null && !current.feedId().isBlank()
                && (current.status() == Status.FEED_SUBMITTED || current.status() == Status.PROCESSING
                    || current.status() == Status.READY_TO_SIGN || current.status() == Status.SIGNING);
        if (original != Status.ERROR || submittedCheckpoint) return current;
        return new Sku(current.nmId(), current.chrtId(), current.subjectId(), current.vendorCode(), current.subjectName(),
                current.brand(), current.title(), current.color(), current.size(), current.barcodes(), current.imageUrl(),
                current.needKiz(), current.gtin(), current.goodId(), current.feedId(), Status.ERROR, current.errorMessage(), current.wbUpdated(), current.wbSize());
    }
}
