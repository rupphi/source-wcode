package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.KizOrder;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.OrderStatus;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZnackPurchaseCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackPurchaseCoordinator.class);
    private static final Duration ORDER_RECONCILIATION_GRACE = Duration.ofMinutes(5);
    private static final Pattern LEGACY_DOCUMENT_ID = Pattern.compile(
            "(?i)Not a JSON Object:\\s*[\"']?([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})[\"']?");
    private static final Object CREATE_LOCK = new Object();
    private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "znack-purchase-pipeline");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Set<java.util.concurrent.ScheduledFuture<?>> PENDING_POLLS = ConcurrentHashMap.newKeySet();

    private final ZnackRepository repository;
    private final ZnackKizOrderService orders;
    private final ZnackKizCodeService codes;
    private final ZnackIntroductionService introduction;
    private final ZnackIntroductionReadinessService readiness;

    public ZnackPurchaseCoordinator(ZnackRepository repository, ZnackKizOrderService orders,
                                    ZnackKizCodeService codes, ZnackIntroductionService introduction) {
        this(repository, orders, codes, introduction, null);
    }

    public ZnackPurchaseCoordinator(ZnackRepository repository, ZnackKizOrderService orders,
                                    ZnackKizCodeService codes, ZnackIntroductionService introduction,
                                    ZnackIntroductionReadinessService readiness) {
        this.repository = repository;
        this.orders = orders;
        this.codes = codes;
        this.introduction = introduction;
        this.readiness = readiness;
    }

    public static ZnackPurchaseCoordinator create(ZnackRepository repository) {
        Settings settings = repository.getSettings();
        return create(repository, settings);
    }

    private static ZnackPurchaseCoordinator create(ZnackRepository repository, Settings settings) {
        ZnackSignatureProvider signer = settings.signerCertificate() == null || settings.signerCertificate().isBlank()
                ? ZnackSignatureProvider.unconfigured()
                : new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        ZnackApiClient api = new ZnackApiClient();
        ZnackAuthService auth = new ZnackAuthService(api, signer);
        return new ZnackPurchaseCoordinator(repository, new ZnackKizOrderService(api, auth, signer, repository),
                new ZnackKizCodeService(api, auth, repository),
                new ZnackIntroductionService(api, auth, signer, repository),
                new ZnackIntroductionReadinessService(api, auth, repository));
    }

    public static void resumeAllPersisted() {
        for (Shop shop : new ShopRepository().findAll()) {
            try {
                ZnackRepository repository = new ZnackRepository(
                        new ZnackModels.ShopContext(shop.getId(), shop.getName()));
                ZnackPurchaseCoordinator coordinator = create(repository);
                Settings settings = repository.getSettings();
                coordinator.resume(settings);
                coordinator.resumeEligibleIntroductions(settings);
            } catch (RuntimeException e) {
                LOGGER.error("Could not resume Znack purchase pipelines for shop {}", shop.getId(), e);
            }
        }
    }

    /** True only while a persisted purchase pipeline is inside a local/remote mutation step. */
    public static boolean hasRunningMutation() {
        return !RUNNING.isEmpty();
    }

    public long start(Settings settings, String gtin, int quantity) throws Exception {
        return start(settings, gtin, quantity, null);
    }

    public long start(Settings settings, String gtin, int quantity, String requestKey) throws Exception {
        ZnackPurchasePipelineState replay = replay(requestKey, gtin, quantity);
        if (replay != null) return replay.id();
        validatePrerequisites(settings, gtin, quantity);
        long pipelineId;
        synchronized (CREATE_LOCK) {
            replay = replay(requestKey, gtin, quantity);
            if (replay != null) return replay.id();
            pipelineId = repository.enqueuePipeline(gtin, quantity, requestKey);
        }
        ZnackPurchasePipelineState persisted = repository.findPipeline(pipelineId).orElseThrow();
        if (persisted.stage() != PurchaseStage.QUEUED) advance(settings, pipelineId);
        schedule(pipelineId);
        return pipelineId;
    }

    /** Persists an idempotent purchase before any remote mutation, then advances it asynchronously. */
    public long enqueue(Settings settings, String gtin, int quantity, String requestKey) throws Exception {
        requireRequestKey(requestKey);
        ZnackPurchasePipelineState replay = replay(requestKey, gtin, quantity);
        if (replay != null) return replay.id();
        validatePrerequisites(settings, gtin, quantity);
        long pipelineId;
        synchronized (CREATE_LOCK) {
            replay = replay(requestKey, gtin, quantity);
            if (replay != null) return replay.id();
            pipelineId = repository.enqueuePipeline(gtin, quantity, requestKey);
        }
        Thread.ofVirtual().name("wcode-znack-purchase-" + repository.shop().shopId() + "-" + pipelineId)
                .start(() -> advanceEnqueued(pipelineId));
        return pipelineId;
    }

    private void advanceEnqueued(long pipelineId) {
        ZnackPurchaseCoordinator latestCoordinator = this;
        try {
            Settings latest = repository.getSettings();
            latestCoordinator = create(repository, latest);
            latestCoordinator.advance(latest, pipelineId);
        } catch (Exception error) {
            ZnackPurchasePipelineState current = repository.findPipeline(pipelineId).orElse(null);
            if (current != null && current.stage() == PurchaseStage.VALIDATING) {
                repository.updatePipeline(pipelineId, null, PurchaseStage.FAILED, error.getMessage());
                repository.log("PURCHASE_PIPELINE", current.gtin(), "ERROR", error.getMessage(), httpStatus(error));
            }
        } finally {
            latestCoordinator.schedule(pipelineId);
        }
    }

    private ZnackPurchasePipelineState replay(String requestKey, String gtin, int quantity) {
        if (requestKey == null || requestKey.isBlank()) return null;
        requireRequestKey(requestKey);
        ZnackPurchasePipelineState existing = repository.findPipelineByRequestKey(requestKey).orElse(null);
        if (existing == null) return null;
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        if (!existing.gtin().equals(normalized) || existing.quantity() != quantity) {
            throw new IllegalArgumentException("Purchase request does not match its persisted pipeline.");
        }
        return existing;
    }

    private void requireRequestKey(String requestKey) {
        try {
            if (requestKey == null || !UUID.fromString(requestKey).toString().equals(requestKey)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Purchase request key is invalid.");
        }
    }

    /**
     * Re-runs the introduction of an INTRODUCTION_FAILED pipeline (e.g. after the user fixed the
     * signature or updated the goods documents). The already-purchased codes are reused; a fresh
     * introduction document is submitted because the previous one was definitively rejected.
     */
    public void retryIntroduction(Settings settings, String gtin) throws Exception {
        ZnackPurchasePipelineState pipeline = repository.findLatestIntroductionFailedPipeline(gtin)
                .orElseThrow(() -> new IllegalStateException("No failed introduction to retry for GTIN " + gtin));
        retryIntroduction(settings, pipeline.id());
    }

    public void retryIntroduction(Settings settings, long pipelineId) throws Exception {
        ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId)
                .filter(candidate -> candidate.stage() == PurchaseStage.INTRODUCTION_FAILED)
                .orElseThrow(() -> new IllegalStateException("No failed introduction to retry for pipeline " + pipelineId));
        if (pipeline.orderId() == null || repository.findCodes(pipeline.orderId()).isEmpty()) {
            throw new IllegalStateException("The failed introduction has no downloaded codes to retry.");
        }
        synchronized (CREATE_LOCK) {
            repository.updatePipeline(pipeline.id(), pipeline.orderId(),
                    PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
        }
        repository.log("INTRODUCTION_RETRY", pipeline.gtin(), "INFO", "RETRY_REQUESTED", null);
        try {
            advance(settings, pipeline.id());
        } finally {
            schedule(pipeline.id());
        }
    }

    public void resume(Settings settings) {
        for (ZnackPurchasePipelineState pipeline : repository.findActivePipelines()) {
            try {
                advance(settings, pipeline.id());
            } catch (Exception e) {
                repository.log("PURCHASE_PIPELINE_RESUME", pipeline.gtin(), "ERROR", e.getMessage(), null);
            } finally {
                schedule(pipeline.id());
            }
        }
    }

    public void resumeEligibleIntroductions(Settings settings) {
        if (settings == null || !settings.autoIntroduction()) return;
        try {
            ZnackSafety.requireSigned(settings, true);
            CryptoProSignatureProvider.requireAvailable(settings.cryptcpPath(),
                    Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        } catch (Exception unavailable) {
            return;
        }
        List<ZnackPurchasePipelineState> candidates = new java.util.ArrayList<>(repository.findSkippedIntroductionPipelines());
        candidates.addAll(repository.findLegacyRejectedIntroductionPipelines());
        candidates.addAll(repository.findLegacyPrimitiveDocumentResponsePipelines());
        for (ZnackPurchasePipelineState pipeline : candidates) {
            Product product = repository.findProduct(pipeline.gtin()).orElse(null);
            if (product == null || pipeline.orderId() == null || repository.findCodes(pipeline.orderId()).isEmpty()
                    || product.tnVed() == null || product.tnVed().isBlank()) {
                continue;
            }
            synchronized (CREATE_LOCK) {
                repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
            }
            try {
                advance(settings, pipeline.id());
            } catch (Exception e) {
                PurchaseStage current = repository.findPipeline(pipeline.id())
                        .map(ZnackPurchasePipelineState::stage).orElse(pipeline.stage());
                if (current != PurchaseStage.WAITING_INTRODUCTION_READINESS
                        && repository.findLatestDocument(pipeline.orderId()).isEmpty()) {
                    repository.updatePipeline(pipeline.id(), pipeline.orderId(), pipeline.stage(), e.getMessage());
                }
                repository.log("INTRODUCTION_RESUME", pipeline.gtin(), "ERROR", e.getMessage(), null);
            } finally {
                schedule(pipeline.id());
            }
        }
    }

    public void advance(Settings settings, long pipelineId) throws Exception {
        String key = pipelineKey(pipelineId);
        if (!RUNNING.add(key)) return;
        String gtin = null;
        try {
            ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId).orElseThrow();
            gtin = pipeline.gtin();
            try {
                switch (pipeline.stage()) {
                    case QUEUED -> {
                        // Activation is handled in finally after checking that no purchase mutation
                        // is currently active for this GTIN.
                    }
                    case VALIDATING -> {
                        validatePrerequisites(settings, pipeline.gtin(), pipeline.quantity());
                        createOrder(settings, pipeline);
                    }
                    case POLLING_ORDER -> pollOrder(settings, pipeline);
                    case DOWNLOADING_CODES -> downloadCodes(settings, pipeline);
                    case WAITING_INTRODUCTION_READINESS -> checkIntroductionReadiness(settings, pipeline);
                    case SUBMITTING_INTRODUCTION -> submitIntroduction(settings, pipeline);
                    case POLLING_INTRODUCTION -> pollIntroduction(settings, pipeline);
                    case CREATING_ORDER -> {
                        Long localOrderId = pipeline.orderId() != null ? pipeline.orderId()
                                : repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                                        pipeline.updatedAt().minusSeconds(30)).map(KizOrder::id).orElse(null);
                        repository.updatePipeline(pipeline.id(), localOrderId,
                                PurchaseStage.RECONCILING_ORDER, pipeline.errorMessage());
                        reconcileOrder(settings, repository.findPipeline(pipeline.id()).orElseThrow());
                    }
                    case RECONCILING_ORDER -> reconcileOrder(settings, pipeline);
                    default -> {
                    }
                }
            } catch (Exception e) {
                PurchaseStage current = repository.findPipeline(pipelineId).map(ZnackPurchasePipelineState::stage)
                        .orElse(PurchaseStage.FAILED);
                if (current == PurchaseStage.RECONCILING_ORDER
                        || current == PurchaseStage.POLLING_ORDER || current == PurchaseStage.DOWNLOADING_CODES
                        || current == PurchaseStage.WAITING_INTRODUCTION_READINESS
                        || current == PurchaseStage.POLLING_INTRODUCTION) {
                    repository.updatePipeline(pipelineId, null, current, e.getMessage());
                } else if (current == PurchaseStage.SUBMITTING_INTRODUCTION) {
                    if (e instanceof ZnackIntroductionService.PermitDocumentsUnavailableException) {
                        Long orderId=repository.findPipeline(pipelineId).map(ZnackPurchasePipelineState::orderId).orElse(null);
                        if(orderId!=null)repository.updateOrder(orderId,null,null,
                                OrderStatus.WAITING_INTRODUCTION_READINESS,e.getMessage());
                        repository.updatePipeline(pipelineId, orderId,
                                PurchaseStage.WAITING_INTRODUCTION_READINESS, e.getMessage());
                    } else {
                        // Codes are already bought; keep a definitive local/signature failure available
                        // for an explicit retry without risking a duplicate introduction document.
                        repository.updatePipeline(pipelineId, null, PurchaseStage.INTRODUCTION_FAILED, e.getMessage());
                    }
                } else if (current == PurchaseStage.CREATING_ORDER
                        && !(e instanceof ZnackOrderCreationAmbiguousException)) {
                    Long failedOrderId = repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                                    repository.findPipeline(pipelineId).orElseThrow().updatedAt().minusSeconds(30))
                            .map(KizOrder::id).orElse(null);
                    repository.updatePipeline(pipelineId, failedOrderId, PurchaseStage.FAILED, e.getMessage());
                } else if (current != PurchaseStage.CREATING_ORDER && current != PurchaseStage.FAILED) {
                    repository.updatePipeline(pipelineId, null, PurchaseStage.FAILED, e.getMessage());
                }
                LOGGER.error("Znack purchase pipeline failed. shopId={}, pipelineId={}, gtin={}, stage={}, details={}",
                        repository.shop().shopId(), pipelineId, pipeline.gtin(), current, ZnackSanitizer.error(e), e);
                repository.log("PURCHASE_PIPELINE", pipeline.gtin(), "ERROR", e.getMessage(), httpStatus(e));
                throw e;
            }
        } finally {
            RUNNING.remove(key);
            if (gtin != null) activateNextQueued(gtin);
        }
    }

    public void validatePrerequisites(Settings settings, String gtin, int quantity) throws Exception {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive.");
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        if (repository.findProducts().stream().noneMatch(p -> normalized.equals(p.gtin()))) {
            throw new IllegalArgumentException("GTIN is not registered for the selected shop.");
        }
        ZnackSafety.requireSigned(settings, true);
        if (settings.omsId() == null || settings.omsId().isBlank()) {
            throw new IllegalStateException("omsId is required before buying KIZ.");
        }
        CryptoProSignatureProvider.requireAvailable(settings.cryptcpPath(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
    }

    private void createOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        Instant attemptStarted = Instant.now();
        repository.updatePipeline(pipeline.id(), null, PurchaseStage.CREATING_ORDER, null);
        try {
            KizOrder order = orders.buy(settings, pipeline.gtin(), pipeline.quantity());
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_ORDER, null);
        } catch (ZnackOrderCreationAmbiguousException ambiguous) {
            Long localOrderId = repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                            attemptStarted.minusSeconds(30))
                    .map(KizOrder::id).orElse(null);
            repository.updatePipeline(pipeline.id(), localOrderId,
                    PurchaseStage.RECONCILING_ORDER, ambiguous.getMessage());
            throw ambiguous;
        }
    }

    private void reconcileOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder local = pipeline.orderId() == null
                ? repository.findLatestUnlinkedOrder(pipeline.gtin(), pipeline.quantity(),
                        pipeline.updatedAt().minusSeconds(30)).orElse(null)
                : repository.findOrder(pipeline.orderId()).orElse(null);
        if (local == null) {
            String message = "The interrupted purchase did not start a remote order; the next queued request can continue.";
            repository.updatePipeline(pipeline.id(), null, PurchaseStage.FAILED, message);
            repository.log("PURCHASE_RECONCILIATION", pipeline.gtin(), "WARN", "NO_LOCAL_ATTEMPT", null);
            return;
        }
        if (pipeline.orderId() == null) {
            repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.RECONCILING_ORDER,
                    pipeline.errorMessage());
        }
        ZnackKizOrderService.OrderReconciliation result = orders.reconcile(settings, local);
        if (result.status() == ZnackKizOrderService.ReconciliationStatus.MATCHED) {
            ZnackKizOrderService.RemoteOrder remote = result.order();
            repository.updateOrder(local.id(), remote.externalOrderId(), remote.remoteStatus(),
                    OrderStatus.SUBMITTED, null);
            repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.POLLING_ORDER, null);
            repository.log("PURCHASE_RECONCILIATION", pipeline.gtin(), "INFO", "REMOTE_ORDER_RECOVERED", 200);
            pollOrder(settings, repository.findPipeline(pipeline.id()).orElseThrow());
            return;
        }
        if (result.status() == ZnackKizOrderService.ReconciliationStatus.NOT_FOUND
                && !Instant.now().isBefore(local.createdAt().plus(ORDER_RECONCILIATION_GRACE))) {
            String message = "Znack confirmed no matching order after the reconciliation window; the queue was released.";
            repository.updateOrder(local.id(), null, "NOT_FOUND", OrderStatus.FAILED, message);
            repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.FAILED, message);
            repository.log("PURCHASE_RECONCILIATION", pipeline.gtin(), "WARN", "REMOTE_ORDER_NOT_FOUND", 200);
            return;
        }
        String pending = result.status() == ZnackKizOrderService.ReconciliationStatus.CONFLICT
                ? "Several matching Znack orders were found; WCode will keep reconciling without buying again."
                : "Waiting for the interrupted order to become visible in Znack; WCode will retry automatically.";
        repository.updatePipeline(pipeline.id(), local.id(), PurchaseStage.RECONCILING_ORDER, pending);
    }

    private void pollOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = orders.refresh(settings, requiredOrderId(pipeline));
        if (order.localStatus() == OrderStatus.CODES_READY || order.localStatus() == OrderStatus.CODES_DOWNLOADED) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.DOWNLOADING_CODES, null);
            downloadCodes(settings, repository.findPipeline(pipeline.id()).orElseThrow());
        } else if (order.localStatus() == OrderStatus.FAILED || order.localStatus() == OrderStatus.CANCELLED) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.FAILED, order.errorMessage());
        } else {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_ORDER, null);
        }
    }

    private void downloadCodes(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        long orderId = requiredOrderId(pipeline);
        codes.download(settings, orderId);
        KizOrder order = repository.findOrder(orderId).orElseThrow();
        int downloaded = repository.findCodes(orderId).size();
        if (downloaded < order.quantity()) {
            throw new IllegalStateException("Downloaded " + downloaded + " of " + order.quantity()
                    + " KIZ codes; the pipeline will retry the safe download step.");
        }
        if (!settings.autoIntroduction()) {
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.COMPLETED, null);
            return;
        }
        Product product = product(pipeline.gtin());
        if (product.tnVed() == null || product.tnVed().isBlank()) {
            String error = "Missing TN VED.";
            repository.updateOrder(orderId, null, null, OrderStatus.INTRODUCTION_SKIPPED_MISSING_METADATA, error);
            repository.updatePipeline(pipeline.id(), orderId,
                    PurchaseStage.INTRODUCTION_SKIPPED_MISSING_METADATA, error);
            return;
        }
        repository.updateOrder(orderId, null, null, OrderStatus.WAITING_INTRODUCTION_READINESS, null);
        repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
        checkIntroductionReadiness(settings, repository.findPipeline(pipeline.id()).orElseThrow());
    }

    private void checkIntroductionReadiness(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        long orderId = requiredOrderId(pipeline);
        if (!settings.autoIntroduction()) {
            repository.updateOrder(orderId, null, null, OrderStatus.CODES_DOWNLOADED, null);
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.COMPLETED, null);
            return;
        }
        List<ZnackModels.KizCode> downloadedCodes = repository.findCodes(orderId);
        ZnackIntroductionReadinessService.Readiness result = readiness == null
                ? ZnackIntroductionReadinessService.Readiness.ready(null)
                : readiness.check(settings, product(pipeline.gtin()), downloadedCodes);
        if (result.allIntroduced()) {
            repository.markCodes(orderId, ZnackModels.KizLegalStatus.IN_CIRCULATION, null, null);
            repository.updateOrder(orderId, null, null, OrderStatus.INTRODUCED, null);
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.INTRODUCED, null);
            return;
        }
        if (!result.ready()) {
            repository.updateOrder(orderId, null, null, OrderStatus.WAITING_INTRODUCTION_READINESS, result.message());
            repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.WAITING_INTRODUCTION_READINESS, result.message());
            return;
        }
        if (result.message() != null && !result.message().isBlank()) {
            repository.log("INTRODUCTION_READINESS", pipeline.gtin(), "WARN", result.message(), null);
        }
        repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.SUBMITTING_INTRODUCTION, null);
        submitIntroduction(settings, repository.findPipeline(pipeline.id()).orElseThrow());
    }

    private void submitIntroduction(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = repository.findOrder(requiredOrderId(pipeline)).orElseThrow();
        ZnackModels.Document existing = repository.findLatestDocument(order.id()).orElse(null);
        if (existing != null
                && ("CHECKED_NOT_OK".equals(existing.status()) || "REJECTED".equals(existing.status()))) {
            // CHECKED_NOT_OK: Znack definitively processed the previous document with errors.
            // REJECTED: the True API answered the submission with an HTTP error, so no document
            // was created. Either way a fresh submission cannot double-introduce the codes.
            existing = null;
        }
        if (existing != null) {
            if (existing.externalDocumentId() != null && !existing.externalDocumentId().isBlank()) {
                repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
                return;
            }
            String recoveredDocumentId = legacyDocumentId(existing.errorMessage());
            if (!recoveredDocumentId.isBlank()) {
                repository.updateDocument(existing.id(), recoveredDocumentId, "SUBMITTED", null);
                repository.markCodes(order.id(), ZnackModels.KizLegalStatus.INTRO_SENT, null, existing.id());
                repository.updateOrder(order.id(), null, null, OrderStatus.INTRO_SENT, null);
                repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
                LOGGER.info("Recovered Znack document ID {} from a legacy primitive response for order {}.",
                        recoveredDocumentId, order.id());
                return;
            }
            if ("FAILED".equals(existing.status()) && repository.latestDocumentIsLegacyHttpRejection(order.id())) {
                existing = null;
            }
        }
        if (existing != null) {
            String error = "Introduction submission result is ambiguous; automatic retry is blocked.";
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.FAILED, error);
            throw new IllegalStateException(error);
        }
        introduction.submit(settings, order, product(pipeline.gtin()), repository.findCodes(order.id()));
        repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
    }

    private void pollIntroduction(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = repository.findOrder(requiredOrderId(pipeline)).orElseThrow();
        ZnackIntroductionService.ConfirmResult result =
                introduction.confirm(settings, order, repository.findCodes(order.id()));
        if (result.introduced()) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.INTRODUCED, null);
        } else if (result.failed()) {
            repository.updateOrder(order.id(), null, null, OrderStatus.INTRODUCTION_FAILED, result.message());
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.INTRODUCTION_FAILED, result.message());
            repository.log("INTRODUCTION", pipeline.gtin(), "ERROR", result.message(), null);
        } else {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
        }
    }

    private Product product(String gtin) {
        return repository.findProduct(gtin).orElseThrow();
    }

    private long requiredOrderId(ZnackPurchasePipelineState pipeline) {
        if (pipeline.orderId() == null) throw new IllegalStateException("Purchase pipeline has no order.");
        return pipeline.orderId();
    }

    void schedule(long pipelineId) {
        ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId).orElse(null);
        if (pipeline == null || !pipeline.active() || pipeline.stage() == PurchaseStage.CREATING_ORDER) return;
        String key = pipelineKey(pipelineId);
        if (!SCHEDULED.add(key)) return;
        long delaySeconds = pipeline.stage() == PurchaseStage.WAITING_INTRODUCTION_READINESS
                || pipeline.stage() == PurchaseStage.RECONCILING_ORDER
                ? 30 : pipeline.errorMessage() == null || pipeline.errorMessage().isBlank() ? 5 : 30;
        PENDING_POLLS.removeIf(java.util.concurrent.Future::isDone);
        PENDING_POLLS.add(POLLER.schedule(() -> {
            try {
                Settings latestSettings = repository.getSettings();
                advance(latestSettings, pipelineId);
            } catch (Exception ignored) {
                // The persisted error and stage are surfaced in the GTIN list.
            } finally {
                SCHEDULED.remove(key);
                schedule(pipelineId);
            }
        }, delaySeconds, TimeUnit.SECONDS));
    }

    private void activateNextQueued(String gtin) {
        try {
            repository.activateNextQueuedPipeline(gtin).ifPresent(next -> schedule(next.id()));
        } catch (RuntimeException error) {
            LOGGER.error("Could not activate the next queued KIZ purchase. shopId={}, gtin={}",
                    repository.shop().shopId(), gtin, error);
        }
    }

    /**
     * Test hook: cancels every not-yet-fired background poll. Unit tests point the app data dir at
     * a throwaway directory per test, and a poll firing later would reopen the SQLite database of a
     * different test — on Windows that file lock makes the JUnit temp-dir cleanup fail.
     */
    static void cancelPendingPolls() {
        for (java.util.concurrent.ScheduledFuture<?> poll : PENDING_POLLS) {
            poll.cancel(false);
        }
        PENDING_POLLS.clear();
        SCHEDULED.clear();
    }

    private String pipelineKey(long pipelineId) {
        return repository.shop().shopId() + ":" + pipelineId;
    }

    private Integer httpStatus(Exception error) {
        return error instanceof ZnackApiClient.ZnackApiException apiError ? apiError.statusCode() : null;
    }

    private String legacyDocumentId(String error) {
        if (error == null || error.isBlank()) return "";
        Matcher matcher = LEGACY_DOCUMENT_ID.matcher(error);
        return matcher.find() ? matcher.group(1) : "";
    }
}
