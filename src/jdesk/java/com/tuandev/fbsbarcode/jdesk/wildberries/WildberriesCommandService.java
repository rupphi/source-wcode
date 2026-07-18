package com.tuandev.fbsbarcode.jdesk.wildberries;

import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.wb.WbSyncReport;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runs resumable seller-state reads without exposing shop credentials to the WebView. */
public final class WildberriesCommandService {
    private static final String PROGRESS_EVENT = "wildberries.syncProgress";

    private final Supplier<List<Shop>> shops;
    private final SyncRunner syncRunner;
    private final ConcurrentMap<Integer, SyncJob> jobsByShop = new ConcurrentHashMap<>();

    public WildberriesCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        WbSyncWorkflow workflow = new WbSyncWorkflow();
        this.shops = shopRepository::findAll;
        this.syncRunner = (shop, progress) -> {
            progress.accept("wildberries", 0, 1);
            WbSyncReport report = workflow.syncOverview(shop);
            progress.accept("wildberries", 1, 1);
            return report;
        };
    }

    WildberriesCommandService(Supplier<List<Shop>> shops, SyncRunner syncRunner) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.syncRunner = Objects.requireNonNull(syncRunner, "syncRunner");
    }

    @DesktopCommand("wildberries.syncOverview")
    @RequiresCapability("wildberries:sync")
    public CompletionStage<StartSyncResponse> startOverview(
            StartSyncRequest request, InvocationContext context) {
        if (request == null || request.shopId() <= 0) {
            throw invalidRequest("Выберите магазин для синхронизации.", "invalid_shop");
        }
        if (context != null && context.isCancelled()) {
            throw new JDeskException(
                    ErrorCode.CANCELLED,
                    "Синхронизация отменена до запуска.",
                    new SyncError("cancelled", 0, true),
                    null);
        }

        Shop shop = safeRequireShop(request.shopId());
        if (shop.getApiKey() == null || shop.getApiKey().isBlank()) {
            throw invalidRequest("Добавьте API-токен Wildberries для этого магазина.", "token_missing");
        }

        AtomicBoolean accepted = new AtomicBoolean();
        SyncJob job = jobsByShop.compute(shop.getId(), (shopId, existing) -> {
            if (existing != null && existing.isRunning()) {
                return existing;
            }
            accepted.set(true);
            return new SyncJob(UUID.randomUUID().toString(), shopId);
        });
        if (accepted.get()) {
            EventEmitter emitter = emitter(context);
            job.worker = Thread.ofVirtual()
                    .name("wcode-wb-sync-" + shop.getId())
                    .start(() -> runJob(job, shop, emitter));
        }
        return CompletableFuture.completedFuture(
                new StartSyncResponse(accepted.get(), job.shopId, job.jobId));
    }

    @DesktopCommand("wildberries.syncStatus")
    @RequiresCapability("wildberries:sync")
    public CompletionStage<SyncStatusResponse> syncStatus(
            SyncStatusRequest request, InvocationContext context) {
        SyncJob job = requireJob(request == null ? 0 : request.shopId(), request == null ? null : request.jobId());
        return CompletableFuture.completedFuture(job.snapshot());
    }

    @DesktopCommand("wildberries.cancelSync")
    @RequiresCapability("wildberries:sync")
    public CompletionStage<CancelSyncResponse> cancelOverview(
            CancelSyncRequest request, InvocationContext context) {
        SyncJob job = requireJob(request == null ? 0 : request.shopId(), request == null ? null : request.jobId());
        boolean requested = job.requestCancel();
        return CompletableFuture.completedFuture(
                new CancelSyncResponse(requested, job.shopId, job.jobId));
    }

    private void runJob(SyncJob job, Shop shop, EventEmitter emitter) {
        emit(emitter, new SyncProgress(shop.getId(), job.jobId, "starting", 0, 1, false));
        try {
            WbSyncReport report = Objects.requireNonNull(
                    syncRunner.sync(
                            shop,
                            (phase, completed, total) -> emit(
                                    emitter,
                                    new SyncProgress(
                                            shop.getId(),
                                            job.jobId,
                                            safePhase(phase),
                                            requireProgress(completed, total),
                                            requireTotal(total),
                                            false))),
                    "Wildberries sync report");
            requireCounts(report);
            job.complete(report);
        } catch (WbApiException exception) {
            job.fail(mapApiFailure(exception));
        } catch (IOException exception) {
            job.fail(new SyncError("unavailable", 0, true));
        } catch (RuntimeException exception) {
            job.fail(new SyncError("internal", 0, true));
        }

        SyncStatusResponse status = job.snapshot();
        emit(
                emitter,
                new SyncProgress(
                        shop.getId(),
                        job.jobId,
                        status.state(),
                        1,
                        1,
                        true));
    }

    private Shop safeRequireShop(int shopId) {
        try {
            List<Shop> availableShops = List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
            return availableShops.stream()
                    .filter(shop -> shop != null && shop.getId() == shopId)
                    .findFirst()
                    .orElseThrow(() -> invalidRequest(
                            "Выбранный магазин больше не доступен.", "shop_not_found"));
        } catch (JDeskException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JDeskException(
                    ErrorCode.INTERNAL_ERROR,
                    "Синхронизация не запущена. Локальные данные не изменены.",
                    new SyncError("internal", 0, true),
                    null);
        }
    }

    private SyncJob requireJob(int shopId, String jobId) {
        if (shopId <= 0 || jobId == null || !jobId.matches("[0-9a-f-]{36}")) {
            throw invalidRequest("Некорректный идентификатор синхронизации.", "invalid_job");
        }
        SyncJob job = jobsByShop.get(shopId);
        if (job == null || !job.jobId.equals(jobId)) {
            throw invalidRequest("Синхронизация больше не доступна.", "job_not_found");
        }
        return job;
    }

    private static void requireCounts(WbSyncReport report) {
        if (report.products() < 0
                || report.supplies() < 0
                || report.orders() < 0
                || report.statuses() < 0) {
            throw new IllegalStateException("Wildberries sync counts must not be negative");
        }
    }

    private static int requireProgress(int completed, int total) {
        if (completed < 0 || completed > total) {
            throw new IllegalStateException("Wildberries sync progress is invalid");
        }
        return completed;
    }

    private static int requireTotal(int total) {
        if (total <= 0 || total > 100) {
            throw new IllegalStateException("Wildberries sync progress total is invalid");
        }
        return total;
    }

    private static String safePhase(String phase) {
        if (phase == null || !phase.matches("[a-z][a-zA-Z0-9]{0,31}")) {
            throw new IllegalStateException("Wildberries sync phase is invalid");
        }
        return phase;
    }

    private static EventEmitter emitter(InvocationContext context) {
        if (context == null) {
            return null;
        }
        try {
            return context.events();
        } catch (JDeskException exception) {
            return null;
        }
    }

    private static void emit(EventEmitter emitter, SyncProgress progress) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emit(PROGRESS_EVENT, progress);
        } catch (JDeskException ignored) {
            // Progress is advisory; a full event queue must not corrupt a resumable local sync.
        }
    }

    private static SyncError mapApiFailure(WbApiException exception) {
        if (exception.getStatusCode() == 401 || exception.getStatusCode() == 403) {
            return new SyncError("token_invalid", safeHttpStatus(exception.getStatusCode()), false);
        }
        if (exception.getStatusCode() == 429) {
            return new SyncError("rate_limited", 429, true);
        }
        return new SyncError("upstream", safeHttpStatus(exception.getStatusCode()), true);
    }

    private static int safeHttpStatus(int statusCode) {
        return statusCode >= 400 && statusCode <= 599 ? statusCode : 0;
    }

    private static JDeskException invalidRequest(String message, String kind) {
        return new JDeskException(
                ErrorCode.INVALID_REQUEST,
                message,
                new SyncError(kind, 0, false),
                null);
    }

    @FunctionalInterface
    interface SyncRunner {
        WbSyncReport sync(Shop shop, ProgressSink progress) throws IOException;
    }

    @FunctionalInterface
    interface ProgressSink {
        void accept(String phase, int completed, int total);
    }

    public record StartSyncRequest(int shopId) {
    }

    public record StartSyncResponse(boolean accepted, int shopId, String jobId) {
    }

    public record SyncStatusRequest(int shopId, String jobId) {
    }

    public record CancelSyncRequest(int shopId, String jobId) {
    }

    public record CancelSyncResponse(boolean cancelRequested, int shopId, String jobId) {
    }

    public record SyncStatusResponse(
            String jobId,
            int shopId,
            String state,
            int products,
            int supplies,
            int orders,
            int statuses,
            String completedAt,
            String errorKind,
            int httpStatus,
            boolean retryable) {
    }

    public record SyncProgress(
            int shopId,
            String jobId,
            String phase,
            int completed,
            int total,
            boolean done) {
    }

    public record SyncError(String kind, int httpStatus, boolean retryable) {
    }

    private static final class SyncJob {
        private final String jobId;
        private final int shopId;
        private String state = "running";
        private WbSyncReport report = new WbSyncReport(0, 0, 0, 0);
        private String completedAt = "";
        private SyncError error = new SyncError("", 0, false);
        private boolean cancelRequested;
        private volatile Thread worker;

        private SyncJob(String jobId, int shopId) {
            this.jobId = jobId;
            this.shopId = shopId;
        }

        private synchronized boolean isRunning() {
            return "running".equals(state);
        }

        private synchronized void complete(WbSyncReport completedReport) {
            if (cancelRequested) {
                cancel();
                return;
            }
            report = completedReport;
            state = "completed";
            completedAt = Instant.now().toString();
        }

        private synchronized void fail(SyncError failure) {
            if (cancelRequested) {
                cancel();
                return;
            }
            error = failure;
            state = "failed";
            completedAt = Instant.now().toString();
        }

        private synchronized boolean requestCancel() {
            if (!"running".equals(state)) {
                return false;
            }
            cancelRequested = true;
            Thread activeWorker = worker;
            if (activeWorker != null) {
                activeWorker.interrupt();
            }
            return true;
        }

        private void cancel() {
            error = new SyncError("cancelled", 0, true);
            state = "cancelled";
            completedAt = Instant.now().toString();
        }

        private synchronized SyncStatusResponse snapshot() {
            return new SyncStatusResponse(
                    jobId,
                    shopId,
                    state,
                    report.products(),
                    report.supplies(),
                    report.orders(),
                    report.statuses(),
                    completedAt,
                    error.kind(),
                    error.httpStatus(),
                    error.retryable());
        }
    }
}
