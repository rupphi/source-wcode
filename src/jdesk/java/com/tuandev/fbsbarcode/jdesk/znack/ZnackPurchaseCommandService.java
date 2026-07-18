package com.tuandev.fbsbarcode.jdesk.znack;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackPurchaseCoordinator;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Safe jDesk boundary for persisted KIZ purchase progress, introduction retry, and audit history. */
public final class ZnackPurchaseCommandService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    private static final int MAX_QUANTITY = 10_000;
    private static final int MAX_PREVIEWS = 200;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_LABEL = 160;
    private static final String PROGRESS_EVENT = "znack.purchaseProgress";

    private final Supplier<List<Shop>> shops;
    private final PurchaseSource source;
    private final PurchaseRunner runner;
    private final Clock clock;
    private final ConcurrentMap<String, PurchasePreview> previews = new ConcurrentHashMap<>();

    public ZnackPurchaseCommandService() {
        this(new ShopRepository()::findAll, new LegacyPurchaseSource(), new LegacyPurchaseRunner(), Clock.systemUTC());
    }

    ZnackPurchaseCommandService(
            Supplier<List<Shop>> shops, PurchaseSource source, PurchaseRunner runner, Clock clock) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.source = Objects.requireNonNull(source, "source");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @DesktopCommand("znack.preparePurchase")
    @RequiresCapability("znack:purchase")
    public CompletionStage<PurchasePreview> preparePurchase(
            PreparePurchaseRequest request, InvocationContext context) {
        ValidatedPrepare validated = validatePrepare(request);
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            requireShop(validated.shopId());
            Settings settings = requireSettings(validated.shopId());
            requireVersion(settings, validated.version());
            requireVerified(settings);
            ProductRow product = requireProduct(source.product(validated.shopId(), validated.gtin()), validated.gtin());
            if (product.deleted()) {
                throw invalid("The GTIN is not available for purchase in this shop.");
            }
            if (source.active(validated.shopId(), validated.gtin()) != null) {
                throw invalid("A purchase is already active for this GTIN.");
            }
            String purchaseId = UUID.randomUUID().toString();
            PurchasePreview preview = new PurchasePreview(
                    validated.shopId(), purchaseId, validated.gtin(), safeText(product.productName(), MAX_LABEL),
                    validated.quantity(), settings.autoIntroduction(),
                    settings.autoIntroduction() ? List.of("automatic_introduction") : List.of(),
                    clock.instant().plus(PREVIEW_TTL).toString(), validated.version());
            storePreview(preview);
            return preview;
        });
    }

    @DesktopCommand("znack.startPurchase")
    @RequiresCapability("znack:purchase")
    public CompletionStage<StartPurchaseResponse> startPurchase(
            StartPurchaseRequest request, InvocationContext context) {
        ValidatedStart validated = validateStart(request);
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            Shop shop = requireShop(validated.shopId());
            PurchaseRow persisted = source.purchase(validated.shopId(), validated.purchaseId());
            if (persisted != null) {
                PurchaseItem item = toPurchaseItem(persisted);
                emit(context, item);
                return new StartPurchaseResponse(false, item);
            }
            Settings settings = requireSettings(validated.shopId());
            requireVersion(settings, validated.version());
            requireVerified(settings);
            PurchasePreview preview = previews.remove(validated.purchaseId());
            if (preview == null || preview.shopId() != validated.shopId()
                    || !Instant.parse(preview.expiresAt()).isAfter(clock.instant())
                    || !preview.version().equals(validated.version())) {
                throw invalid("The purchase preview expired. Prepare it again.");
            }
            ProductRow product = requireProduct(source.product(validated.shopId(), preview.gtin()), preview.gtin());
            if (product.deleted() || source.active(validated.shopId(), preview.gtin()) != null) {
                throw invalid("The purchase state changed. Refresh and prepare it again.");
            }
            try {
                runner.start(shop, settings, preview.gtin(), preview.quantity(), preview.purchaseId());
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw invalid("The purchase could not start. Refresh the Znack workspace and try again.");
            } catch (Exception error) {
                throw failure("Purchase launch failed.", "unavailable", true);
            }
            PurchaseRow created = source.purchase(validated.shopId(), preview.purchaseId());
            if (created == null) throw new IllegalStateException("Persisted purchase is unavailable");
            PurchaseItem item = toPurchaseItem(created);
            emit(context, item);
            return new StartPurchaseResponse(true, item);
        });
    }

    @DesktopCommand("znack.purchases")
    @RequiresCapability("znack:read")
    public CompletionStage<PurchasesResponse> purchases(PurchasesRequest request, InvocationContext context) {
        ValidatedPage page = validatePage(request == null ? 0 : request.shopId(),
                request == null ? 0 : request.page(), request == null ? 0 : request.pageSize());
        return SafeCommandExecutor.execute(() -> {
            requireShop(page.shopId());
            PageQuery query = page.query();
            List<PurchaseRow> rows = validRows(source.purchases(query), query.limit(), "purchase page");
            boolean hasMore = rows.size() > page.pageSize();
            List<PurchaseItem> items = rows.subList(0, Math.min(rows.size(), page.pageSize()))
                    .stream().map(ZnackPurchaseCommandService::toPurchaseItem).toList();
            requireUniquePurchases(items);
            return new PurchasesResponse(page.shopId(), page.page(), page.pageSize(), hasMore, items);
        });
    }

    @DesktopCommand("znack.purchaseStatus")
    @RequiresCapability("znack:read")
    public CompletionStage<PurchaseItem> purchaseStatus(
            PurchaseStatusRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        String purchaseId = requireUuid(request == null ? null : request.purchaseId(), "purchase identifier");
        return SafeCommandExecutor.execute(() -> {
            requireShop(shopId);
            PurchaseRow row = source.purchase(shopId, purchaseId);
            if (row == null) throw invalid("The purchase is no longer available.");
            PurchaseItem item = toPurchaseItem(row);
            emit(context, item);
            return item;
        });
    }

    @DesktopCommand("znack.retryIntroduction")
    @RequiresCapability("znack:introduction")
    public CompletionStage<PurchaseItem> retryIntroduction(
            RetryIntroductionRequest request, InvocationContext context) {
        ValidatedRetry validated = validateRetry(request);
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            Shop shop = requireShop(validated.shopId());
            Settings settings = requireSettings(validated.shopId());
            requireVersion(settings, validated.version());
            requireVerified(settings);
            PurchaseRow row = source.purchase(validated.shopId(), validated.purchaseId());
            if (row == null || row.stage() != PurchaseStage.INTRODUCTION_FAILED || row.downloadedCodes() <= 0) {
                throw invalid("This purchase does not have a retryable introduction.");
            }
            try {
                runner.retryIntroduction(shop, settings, row.gtin(), row.purchaseId());
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw invalid("Introduction could not restart. Refresh the purchase and try again.");
            } catch (Exception error) {
                throw failure("Introduction retry failed.", "unavailable", true);
            }
            PurchaseRow updated = source.purchase(validated.shopId(), validated.purchaseId());
            if (updated == null) throw new IllegalStateException("Retried purchase is unavailable");
            PurchaseItem item = toPurchaseItem(updated);
            emit(context, item);
            return item;
        });
    }

    @DesktopCommand("znack.operationLogs")
    @RequiresCapability("znack:read")
    public CompletionStage<LogsResponse> operationLogs(LogsRequest request, InvocationContext context) {
        ValidatedPage page = validatePage(request == null ? 0 : request.shopId(),
                request == null ? 0 : request.page(), request == null ? 0 : request.pageSize());
        return SafeCommandExecutor.execute(() -> {
            requireShop(page.shopId());
            PageQuery query = page.query();
            List<LogRow> rows = validRows(source.logs(query), query.limit(), "operation log page");
            boolean hasMore = rows.size() > page.pageSize();
            List<LogItem> items = rows.subList(0, Math.min(rows.size(), page.pageSize()))
                    .stream().map(ZnackPurchaseCommandService::toLogItem).toList();
            return new LogsResponse(page.shopId(), page.page(), page.pageSize(), hasMore, items);
        });
    }

    private Settings requireSettings(int shopId) {
        return Objects.requireNonNull(source.settings(shopId), "Znack settings");
    }

    private Shop requireShop(int shopId) {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops")).stream()
                .filter(shop -> shop != null && shop.getId() == shopId)
                .findFirst().orElseThrow(() -> invalid("The selected shop is not available."));
    }

    private synchronized void storePreview(PurchasePreview preview) {
        Instant now = clock.instant();
        previews.entrySet().removeIf(entry -> !Instant.parse(entry.getValue().expiresAt()).isAfter(now));
        if (previews.size() >= MAX_PREVIEWS) {
            previews.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .limit(previews.size() - MAX_PREVIEWS + 1L)
                    .map(java.util.Map.Entry::getKey).toList().forEach(previews::remove);
        }
        previews.put(preview.purchaseId(), preview);
    }

    private static void requireVersion(Settings settings, String version) {
        if (!ZnackCommandService.settingsVersion(settings).equals(version)) {
            throw invalid("Znack settings changed. Reload them and prepare the purchase again.");
        }
    }

    private static void requireVerified(Settings settings) {
        if (settings.signerCertificate() == null || settings.signerCertificate().isBlank()
                || settings.signerTestedAt() == null || settings.omsId() == null || settings.omsId().isBlank()) {
            throw invalid("Verify a CryptoPro certificate and OMS settings before buying KIZ.");
        }
    }

    private static ProductRow requireProduct(ProductRow product, String gtin) {
        if (product == null) throw invalid("The GTIN is not available for purchase in this shop.");
        if (!gtin.equals(product.gtin())) throw new IllegalStateException("Znack product ownership is invalid");
        return product;
    }

    private static PurchaseItem toPurchaseItem(PurchaseRow row) {
        requireUuid(row.purchaseId(), "persisted purchase identifier");
        String gtin;
        try {
            gtin = GtinNormalizer.requireProductionOrderable(row.gtin());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Persisted purchase GTIN is invalid");
        }
        if (row.quantity() <= 0 || row.quantity() > MAX_QUANTITY || row.downloadedCodes() < 0
                || row.downloadedCodes() > row.quantity() || row.stage() == null
                || row.createdAt() == null || row.updatedAt() == null) {
            throw new IllegalStateException("Persisted purchase is invalid");
        }
        String state = state(row.stage());
        String errorKind = errorKind(row.stage(), row.errorMessage());
        boolean canRetryIntroduction = row.stage() == PurchaseStage.INTRODUCTION_FAILED
                && row.downloadedCodes() > 0;
        boolean retryable = switch (row.stage()) {
            case POLLING_ORDER, DOWNLOADING_CODES, WAITING_INTRODUCTION_READINESS, POLLING_INTRODUCTION -> true;
            default -> canRetryIntroduction;
        };
        return new PurchaseItem(
                row.purchaseId(), gtin, safeText(row.productName(), MAX_LABEL), row.quantity(),
                row.stage().name().toLowerCase(Locale.ROOT), state, row.downloadedCodes(),
                progress(row.stage()), errorKind, retryable, canRetryIntroduction,
                row.createdAt().toString(), row.updatedAt().toString());
    }

    private static String state(PurchaseStage stage) {
        return switch (stage) {
            case COMPLETED, INTRODUCED -> "completed";
            case CREATING_ORDER -> "manual_review";
            case FAILED -> "failed";
            case INTRODUCTION_FAILED, INTRODUCTION_SKIPPED_MISSING_DOCUMENTS,
                    INTRODUCTION_SKIPPED_MISSING_METADATA -> "attention";
            default -> "running";
        };
    }

    private static int progress(PurchaseStage stage) {
        return switch (stage) {
            case VALIDATING -> 5;
            case CREATING_ORDER -> 15;
            case POLLING_ORDER -> 35;
            case DOWNLOADING_CODES -> 55;
            case WAITING_INTRODUCTION_READINESS -> 70;
            case SUBMITTING_INTRODUCTION -> 80;
            case POLLING_INTRODUCTION -> 90;
            case COMPLETED, INTRODUCED -> 100;
            case INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, INTRODUCTION_SKIPPED_MISSING_METADATA,
                    INTRODUCTION_FAILED, FAILED -> 100;
        };
    }

    private static String errorKind(PurchaseStage stage, String message) {
        if (stage == PurchaseStage.CREATING_ORDER) return "order_creation_ambiguous";
        if (stage == PurchaseStage.INTRODUCTION_FAILED) return "introduction_failed";
        if (stage == PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS) return "missing_documents";
        if (stage == PurchaseStage.INTRODUCTION_SKIPPED_MISSING_METADATA) return "missing_metadata";
        String normalized = value(message).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return "";
        if (normalized.contains("401") || normalized.contains("403") || normalized.contains("auth")) {
            return "authentication_failed";
        }
        if (normalized.contains("429") || normalized.contains("rate")) return "rate_limited";
        if (normalized.contains("timeout")) return "timeout";
        if (normalized.contains("certificate") || normalized.contains("cryptopro")) return "certificate_unavailable";
        return "upstream_error";
    }

    private static LogItem toLogItem(LogRow row) {
        if (row.createdAt() == null) throw new IllegalStateException("Operation log timestamp is invalid");
        String action = switch (value(row.action()).toUpperCase(Locale.ROOT)) {
            case "BUY_KIZ" -> "buy_kiz";
            case "DOWNLOAD_CODES" -> "download_codes";
            case "PURCHASE_PIPELINE", "PURCHASE_PIPELINE_RESUME" -> "purchase_pipeline";
            case "INTRODUCTION", "INTRODUCTION_RETRY", "INTRODUCTION_RESUME", "INTRODUCTION_READINESS" -> "introduction";
            case "GTIN_SYNC" -> "product_sync";
            default -> "operation";
        };
        String severity = switch (value(row.severity()).toUpperCase(Locale.ROOT)) {
            case "ERROR" -> "error";
            case "WARN", "WARNING" -> "warning";
            default -> "info";
        };
        String entity = "";
        try {
            entity = GtinNormalizer.requireProductionOrderable(row.entityReference());
        } catch (IllegalArgumentException ignored) {
            // Order/document IDs remain private; only a valid GTIN crosses the bridge.
        }
        return new LogItem(action, entity, severity, logMessageKind(row, severity),
                httpClass(row.httpStatus()), row.createdAt().toString());
    }

    private static String logMessageKind(LogRow row, String severity) {
        String message = value(row.message()).toLowerCase(Locale.ROOT);
        if (message.contains("missing") && message.contains("document")) return "missing_documents";
        if (message.contains("missing") && (message.contains("metadata") || message.contains("tn ved"))) {
            return "missing_metadata";
        }
        if (row.httpStatus() != null && (row.httpStatus() == 401 || row.httpStatus() == 403)) {
            return "authentication_failed";
        }
        if (row.httpStatus() != null && row.httpStatus() == 429) return "rate_limited";
        if (message.contains("timeout")) return "timeout";
        if ("error".equals(severity)) return "upstream_error";
        if ("warning".equals(severity)) return "attention";
        return "completed";
    }

    private static String httpClass(Integer status) {
        if (status == null || status < 100 || status > 599) return "";
        return status / 100 + "xx";
    }

    private static <T> List<T> validRows(List<T> rows, int limit, String label) {
        List<T> copy = List.copyOf(Objects.requireNonNull(rows, label));
        if (copy.size() > limit || copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(label + " is invalid");
        }
        return copy;
    }

    private static void requireUniquePurchases(List<PurchaseItem> items) {
        Set<String> ids = new LinkedHashSet<>();
        if (items.stream().anyMatch(item -> !ids.add(item.purchaseId()))) {
            throw new IllegalStateException("Purchase page contains duplicates");
        }
    }

    private static ValidatedPrepare validatePrepare(PreparePurchaseRequest request) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        String gtin;
        try {
            gtin = GtinNormalizer.requireProductionOrderable(request == null ? null : request.gtin());
        } catch (IllegalArgumentException error) {
            throw invalid("A production GTIN is required.");
        }
        int quantity = request == null ? 0 : request.quantity();
        if (quantity <= 0 || quantity > MAX_QUANTITY) throw invalid("Quantity must be between 1 and 10000.");
        return new ValidatedPrepare(shopId, gtin, quantity, requireSettingsVersion(request.version()));
    }

    private static ValidatedStart validateStart(StartPurchaseRequest request) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        if (request == null || !request.confirmed()) throw invalid("Explicit purchase confirmation is required.");
        return new ValidatedStart(shopId, requireUuid(request.purchaseId(), "purchase identifier"),
                requireSettingsVersion(request.version()));
    }

    private static ValidatedRetry validateRetry(RetryIntroductionRequest request) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        if (request == null || !request.confirmed()) throw invalid("Explicit introduction confirmation is required.");
        return new ValidatedRetry(shopId, requireUuid(request.purchaseId(), "purchase identifier"),
                requireSettingsVersion(request.version()));
    }

    private static ValidatedPage validatePage(int shopId, int page, int pageSize) {
        requireShopId(shopId);
        if (page <= 0 || page > MAX_PAGE || pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw invalid("The requested page is invalid.");
        }
        return new ValidatedPage(shopId, page, pageSize);
    }

    private static int requireShopId(int shopId) {
        if (shopId <= 0) throw invalid("A positive shop id is required.");
        return shopId;
    }

    private static String requireSettingsVersion(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}")) {
            throw invalid("The Znack settings version is invalid.");
        }
        return version;
    }

    private static String requireUuid(String candidate, String label) {
        try {
            if (candidate != null && UUID.fromString(candidate).toString().equals(candidate)) return candidate;
        } catch (IllegalArgumentException ignored) {
            // Converted to one public validation error below.
        }
        throw invalid("The " + label + " is invalid.");
    }

    private static String safeText(String candidate, int maximum) {
        String normalized = value(candidate).replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).strip();
    }

    private static String value(String candidate) {
        return candidate == null ? "" : candidate;
    }

    private static void requireNotCancelled(InvocationContext context) {
        if (context != null && context.isCancelled()) {
            throw new JDeskException(
                    ErrorCode.CANCELLED, "Operation cancelled.", new PurchaseError("cancelled", true), null);
        }
    }

    private static void emit(InvocationContext context, PurchaseItem item) {
        if (context == null) return;
        try {
            EventEmitter emitter = context.events();
            emitter.emit(PROGRESS_EVENT, new PurchaseProgress(
                    item.purchaseId(), item.gtin(), item.state(), item.stage(), item.progress(),
                    !"running".equals(item.state())));
        } catch (JDeskException ignored) {
            // Events are advisory; persisted status polling remains authoritative.
        }
    }

    private static JDeskException invalid(String message) {
        return SafeCommandExecutor.invalidRequest(message);
    }

    private static JDeskException failure(String message, String kind, boolean retryable) {
        return new JDeskException(ErrorCode.INVALID_REQUEST, message, new PurchaseError(kind, retryable), null);
    }

    interface PurchaseSource {
        Settings settings(int shopId);
        ProductRow product(int shopId, String gtin);
        PurchaseRow active(int shopId, String gtin);
        PurchaseRow purchase(int shopId, String purchaseId);
        List<PurchaseRow> purchases(PageQuery query);
        List<LogRow> logs(PageQuery query);
    }

    interface PurchaseRunner {
        void start(Shop shop, Settings settings, String gtin, int quantity, String purchaseId) throws Exception;
        void retryIntroduction(Shop shop, Settings settings, String gtin, String purchaseId) throws Exception;
    }

    public record PreparePurchaseRequest(int shopId, String gtin, int quantity, String version) {}
    public record PurchasePreview(
            int shopId, String purchaseId, String gtin, String productName, int quantity,
            boolean autoIntroduction, List<String> warnings, String expiresAt, String version) {}
    public record StartPurchaseRequest(int shopId, String purchaseId, String version, boolean confirmed) {}
    public record StartPurchaseResponse(boolean accepted, PurchaseItem purchase) {}
    public record PurchasesRequest(int shopId, int page, int pageSize) {}
    public record PurchasesResponse(int shopId, int page, int pageSize, boolean hasMore, List<PurchaseItem> items) {}
    public record PurchaseStatusRequest(int shopId, String purchaseId) {}
    public record RetryIntroductionRequest(int shopId, String purchaseId, String version, boolean confirmed) {}
    public record LogsRequest(int shopId, int page, int pageSize) {}
    public record LogsResponse(int shopId, int page, int pageSize, boolean hasMore, List<LogItem> items) {}
    public record PurchaseItem(
            String purchaseId, String gtin, String productName, int quantity, String stage, String state,
            int downloadedCodes, int progress, String errorKind, boolean retryable,
            boolean canRetryIntroduction, String createdAt, String updatedAt) {}
    public record LogItem(
            String action, String entityGtin, String severity, String messageKind,
            String httpClass, String createdAt) {}
    public record PurchaseProgress(
            String purchaseId, String gtin, String state, String stage, int progress, boolean done) {}
    public record PurchaseError(String kind, boolean retryable) {}
    public record ProductRow(String gtin, String productName, boolean deleted) {}
    public record PurchaseRow(
            String purchaseId, String gtin, String productName, int quantity, PurchaseStage stage,
            int downloadedCodes, String errorMessage, Instant createdAt, Instant updatedAt) {}
    public record LogRow(
            String action, String entityReference, String severity, String message,
            Integer httpStatus, Instant createdAt) {}
    public record PageQuery(int shopId, int limit, int offset) {}

    private record ValidatedPrepare(int shopId, String gtin, int quantity, String version) {}
    private record ValidatedStart(int shopId, String purchaseId, String version) {}
    private record ValidatedRetry(int shopId, String purchaseId, String version) {}
    private record ValidatedPage(int shopId, int page, int pageSize) {
        PageQuery query() { return new PageQuery(shopId, pageSize + 1, Math.multiplyExact(page - 1, pageSize)); }
    }

    private static final class LegacyPurchaseRunner implements PurchaseRunner {
        @Override
        public void start(Shop shop, Settings settings, String gtin, int quantity, String purchaseId) throws Exception {
            ZnackRepository repository = repository(shop);
            ZnackPurchaseCoordinator.create(repository).enqueue(settings, gtin, quantity, purchaseId);
        }

        @Override
        public void retryIntroduction(Shop shop, Settings settings, String gtin, String purchaseId) throws Exception {
            ZnackRepository repository = repository(shop);
            if (!settings.equals(repository.getSettings())) {
                throw new IllegalStateException("Znack settings changed before introduction retry.");
            }
            var pipeline = repository.findPipelineByRequestKey(purchaseId)
                    .filter(candidate -> candidate.gtin().equals(gtin))
                    .orElseThrow(() -> new IllegalStateException("The selected purchase is unavailable."));
            ZnackPurchaseCoordinator.create(repository).retryIntroduction(settings, pipeline.id());
        }

        private static ZnackRepository repository(Shop shop) {
            return new ZnackRepository(new ShopContext(shop.getId(), value(shop.getName())));
        }
    }

    private static final class LegacyPurchaseSource implements PurchaseSource {
        @Override
        public Settings settings(int shopId) {
            return new ZnackRepository(new ShopContext(shopId, "")).getSettings();
        }

        @Override
        public ProductRow product(int shopId, String gtin) {
            String sql = "SELECT gtin,product_name,deleted_at FROM znack_products WHERE shop_id=? AND gtin=?";
            try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, shopId);
                statement.setString(2, gtin);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? new ProductRow(rows.getString(1), rows.getString(2), rows.getString(3) != null) : null;
                }
            } catch (SQLException error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public PurchaseRow active(int shopId, String gtin) {
            String terminal = "('COMPLETED','INTRODUCED','FAILED','INTRODUCTION_FAILED',"
                    + "'INTRODUCTION_SKIPPED_MISSING_DOCUMENTS','INTRODUCTION_SKIPPED_MISSING_METADATA')";
            return one("WHERE p.shop_id=? AND p.gtin=? AND p.stage NOT IN " + terminal
                    + " ORDER BY p.updated_at DESC LIMIT 1", statement -> {
                statement.setInt(1, shopId);
                statement.setString(2, gtin);
            });
        }

        @Override
        public PurchaseRow purchase(int shopId, String purchaseId) {
            return one("WHERE p.shop_id=? AND p.request_key=? LIMIT 1", statement -> {
                statement.setInt(1, shopId);
                statement.setString(2, purchaseId);
            });
        }

        @Override
        public List<PurchaseRow> purchases(PageQuery query) {
            return many("WHERE p.shop_id=? ORDER BY p.updated_at DESC,p.id DESC LIMIT ? OFFSET ?", statement -> {
                statement.setInt(1, query.shopId());
                statement.setInt(2, query.limit());
                statement.setInt(3, query.offset());
            });
        }

        @Override
        public List<LogRow> logs(PageQuery query) {
            String sql = """
                    SELECT action,entity_reference,severity,message,http_status,created_at
                    FROM znack_operation_logs WHERE shop_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                    """;
            try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, query.shopId());
                statement.setInt(2, query.limit());
                statement.setInt(3, query.offset());
                try (ResultSet rows = statement.executeQuery()) {
                    List<LogRow> result = new ArrayList<>();
                    while (rows.next()) {
                        int status = rows.getInt("http_status");
                        boolean statusNull = rows.wasNull();
                        result.add(new LogRow(
                                rows.getString("action"), rows.getString("entity_reference"), rows.getString("severity"),
                                rows.getString("message"), statusNull ? null : status,
                                Instant.parse(rows.getString("created_at"))));
                    }
                    return result;
                }
            } catch (SQLException error) {
                throw new RuntimeException(error);
            }
        }

        private PurchaseRow one(String suffix, SqlBinder binder) {
            List<PurchaseRow> rows = query(suffix, binder);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        private List<PurchaseRow> many(String suffix, SqlBinder binder) {
            return query(suffix, binder);
        }

        private List<PurchaseRow> query(String suffix, SqlBinder binder) {
            String sql = """
                    SELECT p.request_key,p.gtin,z.product_name,p.quantity,p.stage,p.error_message,
                           p.created_at,p.updated_at,
                           (SELECT COUNT(*) FROM kiz_codes c
                            WHERE c.shop_id=p.shop_id AND c.order_id=p.order_id) downloaded
                    FROM znack_purchase_pipelines p
                    LEFT JOIN znack_products z ON z.shop_id=p.shop_id AND z.gtin=p.gtin
                    """ + suffix;
            try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    List<PurchaseRow> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new PurchaseRow(
                                rows.getString("request_key"), rows.getString("gtin"), rows.getString("product_name"),
                                rows.getInt("quantity"), PurchaseStage.valueOf(rows.getString("stage")),
                                rows.getInt("downloaded"), rows.getString("error_message"),
                                Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at"))));
                    }
                    return result;
                }
            } catch (SQLException error) {
                throw new RuntimeException(error);
            }
        }

        @FunctionalInterface
        private interface SqlBinder {
            void bind(PreparedStatement statement) throws SQLException;
        }
    }
}
