package com.tuandev.fbsbarcode.jdesk.supply;

import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.features.supply.SupplyLoadWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.jdesk.shop.ShopActivityGate;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
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

/** Refreshes one locally owned supply without exposing seller credentials to the WebView. */
public final class SupplyRefreshCommandService {
    private static final int MAX_SUPPLY_ID_LENGTH = 128;
    private static final int MAX_UPDATED_ORDERS = 1_000_000;

    private final Supplier<List<Shop>> shops;
    private final SupplyReader supplies;
    private final RefreshRunner refreshRunner;
    private final ShopActivityGate activityGate;
    private final ConcurrentMap<Integer, RefreshJob> jobsByShop = new ConcurrentHashMap<>();

    public SupplyRefreshCommandService() {
        this(new ShopActivityGate());
    }

    public SupplyRefreshCommandService(ShopActivityGate activityGate) {
        ShopRepository shopRepository = new ShopRepository();
        WbSupplyRepository supplyRepository = new WbSupplyRepository();
        SupplyLoadWorkflow workflow = new SupplyLoadWorkflow();
        this.shops = shopRepository::findAll;
        this.supplies = supplyRepository::findSupplySummary;
        this.refreshRunner = (shop, supplyId) -> workflow.refreshSupplyData(shop, supplyId).size();
        this.activityGate = Objects.requireNonNull(activityGate, "activityGate");
    }

    SupplyRefreshCommandService(
            Supplier<List<Shop>> shops,
            SupplyReader supplies,
            RefreshRunner refreshRunner) {
        this(shops, supplies, refreshRunner, new ShopActivityGate());
    }

    private SupplyRefreshCommandService(
            Supplier<List<Shop>> shops,
            SupplyReader supplies,
            RefreshRunner refreshRunner,
            ShopActivityGate activityGate) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.supplies = Objects.requireNonNull(supplies, "supplies");
        this.refreshRunner = Objects.requireNonNull(refreshRunner, "refreshRunner");
        this.activityGate = Objects.requireNonNull(activityGate, "activityGate");
    }

    @DesktopCommand("supplies.refresh")
    @RequiresCapability("wildberries:sync")
    public CompletionStage<StartSupplyRefreshResponse> start(
            StartSupplyRefreshRequest request, InvocationContext context) {
        ValidatedSupply validated = validateSupply(request == null ? 0 : request.shopId(),
                request == null ? null : request.supplyId());
        if (context != null && context.isCancelled()) {
            throw new JDeskException(
                    ErrorCode.CANCELLED,
                    "Обновление поставки отменено до запуска.",
                    new SupplyRefreshError("cancelled", 0, true),
                    null);
        }

        ShopActivityGate.Lease activity = beginActivity(validated.shopId());
        boolean transferred = false;
        try {
            Shop shop = safeRequireOwnedSupply(validated);
            AtomicBoolean accepted = new AtomicBoolean();
            RefreshJob job = jobsByShop.compute(shop.getId(), (shopId, existing) -> {
                if (existing != null && existing.isRunning()) {
                    if (existing.supplyId.equals(validated.supplyId())) {
                        return existing;
                    }
                    throw invalidRequest(
                            "Для этого магазина уже обновляется другая поставка.", "shop_busy", true);
                }
                accepted.set(true);
                return new RefreshJob(UUID.randomUUID().toString(), shopId, validated.supplyId());
            });
            if (accepted.get()) {
                job.worker = Thread.ofVirtual()
                        .name("wcode-supply-refresh-" + shop.getId())
                        .start(() -> {
                            try {
                                runJob(job, shop);
                            } finally {
                                activity.close();
                            }
                        });
                transferred = true;
            }
            return CompletableFuture.completedFuture(new StartSupplyRefreshResponse(
                    accepted.get(), job.shopId, job.supplyId, job.jobId));
        } finally {
            if (!transferred) activity.close();
        }
    }

    private ShopActivityGate.Lease beginActivity(int shopId) {
        try {
            return activityGate.begin(shopId);
        } catch (ShopActivityGate.ShopBusyException exception) {
            throw invalidRequest("Магазин удаляется. Повторите после завершения операции.", "shop_busy", true);
        }
    }

    @DesktopCommand("supplies.refreshStatus")
    @RequiresCapability("wildberries:sync")
    public CompletionStage<SupplyRefreshStatusResponse> status(
            SupplyRefreshStatusRequest request, InvocationContext context) {
        RefreshJob job = requireJob(
                request == null ? 0 : request.shopId(),
                request == null ? null : request.supplyId(),
                request == null ? null : request.jobId());
        return CompletableFuture.completedFuture(job.snapshot());
    }

    @DesktopCommand("supplies.cancelRefresh")
    @RequiresCapability("wildberries:sync")
    public CompletionStage<CancelSupplyRefreshResponse> cancel(
            CancelSupplyRefreshRequest request, InvocationContext context) {
        RefreshJob job = requireJob(
                request == null ? 0 : request.shopId(),
                request == null ? null : request.supplyId(),
                request == null ? null : request.jobId());
        return CompletableFuture.completedFuture(new CancelSupplyRefreshResponse(
                job.requestCancel(), job.shopId, job.supplyId, job.jobId));
    }

    private void runJob(RefreshJob job, Shop shop) {
        try {
            int localOrders = refreshRunner.refresh(shop, job.supplyId);
            if (localOrders < 0 || localOrders > MAX_UPDATED_ORDERS) {
                throw new IllegalStateException("Supply refresh count is invalid");
            }
            job.complete(localOrders);
        } catch (WbApiException exception) {
            job.fail(mapApiFailure(exception));
        } catch (IOException exception) {
            job.fail(new SupplyRefreshError("unavailable", 0, true));
        } catch (RuntimeException exception) {
            job.fail(new SupplyRefreshError("internal", 0, true));
        }
    }

    private Shop safeRequireOwnedSupply(ValidatedSupply request) {
        try {
            Shop shop = List.copyOf(Objects.requireNonNull(shops.get(), "shops")).stream()
                    .filter(candidate -> candidate != null && candidate.getId() == request.shopId())
                    .findFirst()
                    .orElseThrow(() -> invalidRequest(
                            "Выбранный магазин больше не доступен.", "shop_not_found", false));
            if (shop.getApiKey() == null || shop.getApiKey().isBlank()) {
                throw invalidRequest(
                        "Добавьте API-токен Wildberries для этого магазина.", "token_missing", false);
            }
            WbSupplySummary supply = supplies.read(request.shopId(), request.supplyId());
            if (supply == null || !request.supplyId().equals(supply.getSupplyId())) {
                throw invalidRequest(
                        "Выбранная поставка больше не доступна.", "supply_not_found", false);
            }
            return shop;
        } catch (JDeskException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JDeskException(
                    ErrorCode.INTERNAL_ERROR,
                    "Обновление не запущено. Локальные данные доступны без изменений.",
                    new SupplyRefreshError("internal", 0, true),
                    null);
        }
    }

    private RefreshJob requireJob(int shopId, String supplyId, String jobId) {
        ValidatedSupply validated = validateSupply(shopId, supplyId);
        if (jobId == null || !jobId.matches("[0-9a-f-]{36}")) {
            throw invalidRequest("Некорректный идентификатор обновления.", "invalid_job", false);
        }
        RefreshJob job = jobsByShop.get(validated.shopId());
        if (job == null || !job.jobId.equals(jobId) || !job.supplyId.equals(validated.supplyId())) {
            throw invalidRequest("Обновление больше не доступно.", "job_not_found", false);
        }
        return job;
    }

    private static ValidatedSupply validateSupply(int shopId, String supplyId) {
        if (shopId <= 0) {
            throw invalidRequest("Выберите магазин для обновления.", "invalid_shop", false);
        }
        if (supplyId == null
                || supplyId.isBlank()
                || supplyId.length() > MAX_SUPPLY_ID_LENGTH
                || supplyId.chars().anyMatch(Character::isISOControl)) {
            throw invalidRequest("Некорректный идентификатор поставки.", "invalid_supply", false);
        }
        return new ValidatedSupply(shopId, supplyId.strip());
    }

    private static SupplyRefreshError mapApiFailure(WbApiException exception) {
        if (exception.getStatusCode() == 401 || exception.getStatusCode() == 403) {
            return new SupplyRefreshError("token_invalid", safeHttpStatus(exception.getStatusCode()), false);
        }
        if (exception.getStatusCode() == 429) {
            return new SupplyRefreshError("rate_limited", 429, true);
        }
        return new SupplyRefreshError("upstream", safeHttpStatus(exception.getStatusCode()), true);
    }

    private static int safeHttpStatus(int statusCode) {
        return statusCode >= 400 && statusCode <= 599 ? statusCode : 0;
    }

    private static JDeskException invalidRequest(String message, String kind, boolean retryable) {
        return new JDeskException(
                ErrorCode.INVALID_REQUEST,
                message,
                new SupplyRefreshError(kind, 0, retryable),
                null);
    }

    @FunctionalInterface
    interface SupplyReader {
        WbSupplySummary read(int shopId, String supplyId);
    }

    @FunctionalInterface
    interface RefreshRunner {
        int refresh(Shop shop, String supplyId) throws IOException;
    }

    private record ValidatedSupply(int shopId, String supplyId) {
    }

    public record StartSupplyRefreshRequest(int shopId, String supplyId) {
    }

    public record StartSupplyRefreshResponse(boolean accepted, int shopId, String supplyId, String jobId) {
    }

    public record SupplyRefreshStatusRequest(int shopId, String supplyId, String jobId) {
    }

    public record CancelSupplyRefreshRequest(int shopId, String supplyId, String jobId) {
    }

    public record CancelSupplyRefreshResponse(
            boolean cancelRequested, int shopId, String supplyId, String jobId) {
    }

    public record SupplyRefreshStatusResponse(
            String jobId,
            int shopId,
            String supplyId,
            String state,
            int localOrders,
            String completedAt,
            String errorKind,
            int httpStatus,
            boolean retryable) {
    }

    public record SupplyRefreshError(String kind, int httpStatus, boolean retryable) {
    }

    private static final class RefreshJob {
        private final String jobId;
        private final int shopId;
        private final String supplyId;
        private String state = "running";
        private int localOrders;
        private String completedAt = "";
        private SupplyRefreshError error = new SupplyRefreshError("", 0, false);
        private boolean cancelRequested;
        private volatile Thread worker;

        private RefreshJob(String jobId, int shopId, String supplyId) {
            this.jobId = jobId;
            this.shopId = shopId;
            this.supplyId = supplyId;
        }

        private synchronized boolean isRunning() {
            return "running".equals(state);
        }

        private synchronized void complete(int count) {
            if (cancelRequested) {
                cancel();
                return;
            }
            localOrders = count;
            state = "completed";
            completedAt = Instant.now().toString();
        }

        private synchronized void fail(SupplyRefreshError failure) {
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
            error = new SupplyRefreshError("cancelled", 0, true);
            state = "cancelled";
            completedAt = Instant.now().toString();
        }

        private synchronized SupplyRefreshStatusResponse snapshot() {
            return new SupplyRefreshStatusResponse(
                    jobId,
                    shopId,
                    supplyId,
                    state,
                    localOrders,
                    completedAt,
                    error.kind(),
                    error.httpStatus(),
                    error.retryable());
        }
    }
}
