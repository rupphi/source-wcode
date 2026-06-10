package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.KizOrder;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.OrderStatus;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProCommandRunner;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ZnackPurchaseCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackPurchaseCoordinator.class);
    private static final Object CREATE_LOCK = new Object();
    private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "znack-purchase-pipeline");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    private final ZnackRepository repository;
    private final ZnackKizOrderService orders;
    private final ZnackKizCodeService codes;
    private final ZnackIntroductionService introduction;

    public ZnackPurchaseCoordinator(ZnackRepository repository, ZnackKizOrderService orders,
                                    ZnackKizCodeService codes, ZnackIntroductionService introduction) {
        this.repository = repository;
        this.orders = orders;
        this.codes = codes;
        this.introduction = introduction;
    }

    public static ZnackPurchaseCoordinator create(ZnackRepository repository) {
        Settings settings = repository.getSettings();
        ZnackSignatureProvider signer = settings.signerCertificate() == null || settings.signerCertificate().isBlank()
                ? ZnackSignatureProvider.unconfigured()
                : new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        ZnackApiClient api = new ZnackApiClient();
        ZnackAuthService auth = new ZnackAuthService(api, signer);
        return new ZnackPurchaseCoordinator(repository, new ZnackKizOrderService(api, auth, signer, repository),
                new ZnackKizCodeService(api, auth, repository),
                new ZnackIntroductionService(api, auth, signer, repository));
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

    public long start(Settings settings, String gtin, int quantity) throws Exception {
        validatePrerequisites(settings, gtin, quantity);
        long pipelineId;
        synchronized (CREATE_LOCK) {
            ZnackPurchasePipelineState active = repository.findActivePipeline(gtin).orElse(null);
            if (active != null) {
                throw new IllegalStateException("A KIZ purchase pipeline is already active for GTIN " + active.gtin());
            }
            pipelineId = repository.createPipeline(gtin, quantity);
        }
        advance(settings, pipelineId);
        schedule(pipelineId);
        return pipelineId;
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
            new CryptoProCommandRunner().resolve(settings.cryptcpPath(), "cryptcp");
        } catch (Exception unavailable) {
            return;
        }
        for (ZnackPurchasePipelineState pipeline : repository.findSkippedIntroductionPipelines()) {
            Product product = repository.findProducts().stream()
                    .filter(item -> item.gtin().equals(pipeline.gtin()))
                    .findFirst().orElse(null);
            if (product == null || pipeline.orderId() == null || repository.findCodes(pipeline.orderId()).isEmpty()
                    || !hasGoodsDocument(settings, product) || product.tnVed() == null || product.tnVed().isBlank()) {
                continue;
            }
            synchronized (CREATE_LOCK) {
                if (repository.findActivePipeline(pipeline.gtin()).isPresent()) continue;
                repository.updatePipeline(pipeline.id(), pipeline.orderId(), PurchaseStage.SUBMITTING_INTRODUCTION, null);
            }
            try {
                advance(settings, pipeline.id());
            } catch (Exception e) {
                if (repository.findLatestDocument(pipeline.orderId()).isEmpty()) {
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
        try {
            ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId).orElseThrow();
            try {
                switch (pipeline.stage()) {
                    case VALIDATING -> createOrder(settings, pipeline);
                    case POLLING_ORDER -> pollOrder(settings, pipeline);
                    case DOWNLOADING_CODES -> downloadCodes(settings, pipeline);
                    case SUBMITTING_INTRODUCTION -> submitIntroduction(settings, pipeline);
                    case POLLING_INTRODUCTION -> pollIntroduction(settings, pipeline);
                    case CREATING_ORDER -> throw new ZnackOrderCreationAmbiguousException(
                            "Order creation result is ambiguous; automatic retry is blocked to avoid duplicate charges.", null);
                    default -> {
                    }
                }
            } catch (Exception e) {
                PurchaseStage current = repository.findPipeline(pipelineId).map(ZnackPurchasePipelineState::stage)
                        .orElse(PurchaseStage.FAILED);
                if (current == PurchaseStage.POLLING_ORDER || current == PurchaseStage.DOWNLOADING_CODES
                        || current == PurchaseStage.POLLING_INTRODUCTION) {
                    repository.updatePipeline(pipelineId, null, current, e.getMessage());
                } else if (current == PurchaseStage.CREATING_ORDER
                        && !(e instanceof ZnackOrderCreationAmbiguousException)) {
                    repository.updatePipeline(pipelineId, null, PurchaseStage.FAILED, e.getMessage());
                } else if (current != PurchaseStage.CREATING_ORDER && current != PurchaseStage.FAILED) {
                    repository.updatePipeline(pipelineId, null, PurchaseStage.FAILED, e.getMessage());
                }
                repository.log("PURCHASE_PIPELINE", pipeline.gtin(), "ERROR", e.getMessage(), null);
                throw e;
            }
        } finally {
            RUNNING.remove(key);
        }
    }

    public void validatePrerequisites(Settings settings, String gtin, int quantity) throws Exception {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive.");
        String normalized = GtinNormalizer.normalize(gtin);
        if (repository.findProducts().stream().noneMatch(p -> normalized.equals(p.gtin()))) {
            throw new IllegalArgumentException("GTIN is not registered for the selected shop.");
        }
        ZnackSafety.requireSigned(settings, true);
        if (settings.omsId() == null || settings.omsId().isBlank()) {
            throw new IllegalStateException("omsId is required before buying KIZ.");
        }
        new CryptoProCommandRunner().resolve(settings.cryptcpPath(), "cryptcp");
    }

    private void createOrder(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        repository.updatePipeline(pipeline.id(), null, PurchaseStage.CREATING_ORDER, null);
        KizOrder order = orders.buy(settings, pipeline.gtin(), pipeline.quantity());
        repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_ORDER, null);
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
        if (!hasGoodsDocument(settings, product)) {
            repository.updateOrder(orderId, null, null, OrderStatus.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, null);
            repository.updatePipeline(pipeline.id(), orderId,
                    PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, null);
            return;
        }
        if (product.tnVed() == null || product.tnVed().isBlank()) {
            repository.updateOrder(orderId, null, null, OrderStatus.INTRODUCTION_SKIPPED_MISSING_METADATA, null);
            repository.updatePipeline(pipeline.id(), orderId,
                    PurchaseStage.INTRODUCTION_SKIPPED_MISSING_METADATA, null);
            return;
        }
        repository.updatePipeline(pipeline.id(), orderId, PurchaseStage.SUBMITTING_INTRODUCTION, null);
        submitIntroduction(settings, repository.findPipeline(pipeline.id()).orElseThrow());
    }

    private void submitIntroduction(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = repository.findOrder(requiredOrderId(pipeline)).orElseThrow();
        ZnackModels.Document existing = repository.findLatestDocument(order.id()).orElse(null);
        if (existing != null) {
            if (existing.externalDocumentId() != null && !existing.externalDocumentId().isBlank()) {
                repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
                return;
            }
            String error = "Introduction submission result is ambiguous; automatic retry is blocked.";
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.FAILED, error);
            throw new IllegalStateException(error);
        }
        introduction.submit(settings, order, product(pipeline.gtin()), repository.findCodes(order.id()));
        repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
    }

    private void pollIntroduction(Settings settings, ZnackPurchasePipelineState pipeline) throws Exception {
        KizOrder order = repository.findOrder(requiredOrderId(pipeline)).orElseThrow();
        if (introduction.confirm(settings, order, repository.findCodes(order.id()))) {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.INTRODUCED, null);
        } else {
            repository.updatePipeline(pipeline.id(), order.id(), PurchaseStage.POLLING_INTRODUCTION, null);
        }
    }

    private Product product(String gtin) {
        return repository.findProducts().stream().filter(p -> p.gtin().equals(gtin)).findFirst().orElseThrow();
    }

    private long requiredOrderId(ZnackPurchasePipelineState pipeline) {
        if (pipeline.orderId() == null) throw new IllegalStateException("Purchase pipeline has no order.");
        return pipeline.orderId();
    }

    private boolean hasGoodsDocument(Settings settings, Product product) {
        return settings.hasDefaultGoodsDocument()
                || (product.certificateNumber() != null && !product.certificateNumber().isBlank()
                && product.certificateDate() != null && !product.certificateDate().isBlank());
    }

    void schedule(long pipelineId) {
        ZnackPurchasePipelineState pipeline = repository.findPipeline(pipelineId).orElse(null);
        if (pipeline == null || !pipeline.active() || pipeline.stage() == PurchaseStage.CREATING_ORDER) return;
        String key = pipelineKey(pipelineId);
        if (!SCHEDULED.add(key)) return;
        long delaySeconds = pipeline.errorMessage() == null || pipeline.errorMessage().isBlank() ? 5 : 30;
        POLLER.schedule(() -> {
            try {
                Settings latestSettings = repository.getSettings();
                advance(latestSettings, pipelineId);
            } catch (Exception ignored) {
                // The persisted error and stage are surfaced in the GTIN list.
            } finally {
                SCHEDULED.remove(key);
                schedule(pipelineId);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private String pipelineKey(long pipelineId) {
        return repository.shop().shopId() + ":" + pipelineId;
    }
}
