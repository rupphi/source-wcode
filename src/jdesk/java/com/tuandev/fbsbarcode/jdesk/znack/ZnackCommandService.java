package com.tuandev.fbsbarcode.jdesk.znack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.features.znack.ZnackWorkspaceRepository;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Typed, bounded bridge for local Znack settings and reversible product lifecycle operations. */
public final class ZnackCommandService {
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_CATEGORY_COUNT = 30;
    private static final int MAX_AVAILABLE_CATEGORIES = 100;
    private static final int MAX_VISIBILITY_BATCH = 100;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OMS_ID_LENGTH = 100;
    private static final int MAX_OMS_CONNECTION_LENGTH = 120;
    private static final int MAX_DOCUMENT_LENGTH = 120;
    private static final int MAX_LABEL_LENGTH = 160;
    private static final int MAX_SHORT_LABEL_LENGTH = 80;

    private final Supplier<List<Shop>> shops;
    private final ZnackDataSource source;
    private final Clock clock;
    private final Object settingsLock = new Object();
    private final Object visibilityLock = new Object();

    public ZnackCommandService() {
        this(new ShopRepository()::findAll, new LegacyZnackDataSource(), Clock.systemUTC());
    }

    ZnackCommandService(Supplier<List<Shop>> shops, ZnackDataSource source, Clock clock) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @DesktopCommand("znack.settings")
    @RequiresCapability("znack:read")
    public CompletionStage<SettingsResponse> settings(SettingsRequest request, InvocationContext context) {
        int shopId = requirePositiveShopId(request == null ? 0 : request.shopId());
        return SafeCommandExecutor.execute(() -> {
            requireShop(shopId);
            return toSettingsResponse(shopId, requireSettings(shopId));
        });
    }

    @DesktopCommand("znack.saveSettings")
    @RequiresCapability("znack:configure")
    public CompletionStage<SettingsResponse> saveSettings(
            SaveSettingsRequest request, InvocationContext context) {
        ValidatedSettings validated = validateSettings(request);
        return SafeCommandExecutor.execute(() -> {
            requireShop(validated.shopId());
            synchronized (settingsLock) {
                Settings current = requireSettings(validated.shopId());
                if (!settingsVersion(current).equals(validated.version())) {
                    throw invalid("Znack settings changed. Reload them and try again.");
                }
                Settings merged = mergeSettings(current, validated);
                try {
                    merged.validateDefaultGoodsDocument();
                } catch (IllegalArgumentException error) {
                    throw invalid("The default goods document is invalid.");
                }
                source.saveSettings(validated.shopId(), merged);
                return toSettingsResponse(validated.shopId(), requireSettings(validated.shopId()));
            }
        });
    }

    @DesktopCommand("znack.products")
    @RequiresCapability("znack:read")
    public CompletionStage<ProductsResponse> products(ProductsRequest request, InvocationContext context) {
        ValidatedProducts validated = validateProducts(request);
        return SafeCommandExecutor.execute(() -> {
            requireShop(validated.shopId());
            List<String> categories = sanitizeLabels(
                    source.categories(validated.shopId(), validated.deleted()),
                    MAX_AVAILABLE_CATEGORIES,
                    "Znack category catalog");
            ProductQuery query = new ProductQuery(
                    validated.shopId(),
                    validated.query(),
                    validated.categories(),
                    validated.deleted(),
                    validated.pageSize() + 1,
                    Math.multiplyExact(validated.page() - 1, validated.pageSize()));
            List<ProductRow> loaded = List.copyOf(
                    Objects.requireNonNull(source.products(query), "Znack product page"));
            if (loaded.size() > query.limit() || loaded.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("Znack product page is invalid");
            }
            boolean hasMore = loaded.size() > validated.pageSize();
            List<ProductItem> items = loaded.subList(0, Math.min(loaded.size(), validated.pageSize()))
                    .stream().map(ZnackCommandService::toProductItem).toList();
            requireUniqueGtins(items);
            return new ProductsResponse(
                    validated.shopId(),
                    validated.query(),
                    validated.categories(),
                    validated.deleted(),
                    validated.page(),
                    validated.pageSize(),
                    hasMore,
                    categories,
                    items);
        });
    }

    @DesktopCommand("znack.setProductVisibility")
    @RequiresCapability("znack:products:write")
    public CompletionStage<VisibilityResponse> setProductVisibility(
            SetProductVisibilityRequest request, InvocationContext context) {
        ValidatedVisibility validated = validateVisibility(request);
        return SafeCommandExecutor.execute(() -> {
            Shop shop = requireShop(validated.shopId());
            synchronized (visibilityLock) {
                try {
                    source.setProductVisibility(
                            validated.shopId(), safeSourceText(shop.getName(), MAX_LABEL_LENGTH),
                            validated.gtins(), validated.deleted());
                } catch (VisibilityConflictException conflict) {
                    throw invalid("One or more GTINs changed. Reload the product list and try again.");
                }
            }
            return new VisibilityResponse(validated.shopId(), validated.deleted(), validated.gtins().size());
        });
    }

    private Settings requireSettings(int shopId) {
        return Objects.requireNonNull(source.settings(shopId), "Znack settings");
    }

    private Shop requireShop(int shopId) {
        List<Shop> available = List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
        return available.stream()
                .filter(shop -> shop != null && shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> invalid("The selected shop is not available."));
    }

    private SettingsResponse toSettingsResponse(int shopId, Settings settings) {
        return toSettingsResponse(shopId, settings, clock.instant());
    }

    static SettingsResponse toSettingsResponse(int shopId, Settings settings, Instant now) {
        CertificateSummary certificate = certificateSummary(settings, now);
        return new SettingsResponse(
                shopId,
                safeSourceText(settings.omsId(), MAX_OMS_ID_LENGTH),
                safeSourceText(settings.omsConnection(), MAX_OMS_CONNECTION_LENGTH),
                safeSourceText(settings.documentNumber(), MAX_DOCUMENT_LENGTH),
                safeSourceText(settings.documentDate(), 10),
                settings.autoIntroduction(),
                certificate.status(),
                certificate.label(),
                certificate.validTo(),
                settingsVersion(settings));
    }

    private static CertificateSummary certificateSummary(Settings settings, Instant now) {
        if (blank(settings.signerCertificate())) {
            return new CertificateSummary("NOT_CONFIGURED", "", "");
        }
        String label = "";
        String validTo = "";
        boolean expired = false;
        try {
            JsonObject metadata = JsonParser.parseString(value(settings.certificateMetadataJson())).getAsJsonObject();
            String displayLabel = jsonString(metadata, "label");
            label = safeSourceText(displayLabel.isBlank() ? jsonString(metadata, "subject") : displayLabel,
                    MAX_LABEL_LENGTH);
            String validToValue = jsonString(metadata, "validTo");
            if (!validToValue.isBlank()) {
                Instant expiry = Instant.parse(validToValue);
                expired = expiry.isBefore(now);
                validTo = LocalDate.ofInstant(expiry, ZoneOffset.UTC).toString();
            }
        } catch (RuntimeException ignored) {
            label = "";
            validTo = "";
        }
        String status = expired
                ? "EXPIRED"
                : settings.signerTestedAt() == null ? "NOT_VERIFIED" : "VERIFIED";
        return new CertificateSummary(status, label, validTo);
    }

    private static ProductItem toProductItem(ProductRow row) {
        String gtin;
        try {
            gtin = GtinNormalizer.requireProductionOrderable(row.gtin());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Znack product GTIN is invalid");
        }
        return new ProductItem(
                gtin,
                safeSourceText(row.productName(), MAX_LABEL_LENGTH),
                safeSourceText(row.category(), MAX_LABEL_LENGTH),
                safeSourceText(row.tnVed(), MAX_SHORT_LABEL_LENGTH),
                safeSourceText(row.cisType(), MAX_SHORT_LABEL_LENGTH),
                readinessStatus(row.goodMark()),
                readinessStatus(row.goodTurn()),
                row.readinessCheckedAt() == null ? "" : row.readinessCheckedAt().toString(),
                row.deleted());
    }

    private static void requireUniqueGtins(List<ProductItem> items) {
        Set<String> unique = new LinkedHashSet<>();
        if (items.stream().anyMatch(item -> !unique.add(item.gtin()))) {
            throw new IllegalStateException("Znack product page contains duplicate GTINs");
        }
    }

    private static String readinessStatus(Boolean value) {
        return value == null ? "UNKNOWN" : value ? "READY" : "NOT_READY";
    }

    private static ValidatedSettings validateSettings(SaveSettingsRequest request) {
        int shopId = requirePositiveShopId(request == null ? 0 : request.shopId());
        String omsId = writeText(request == null ? null : request.omsId(), MAX_OMS_ID_LENGTH, true,
                "A valid OMS id is required.");
        String omsConnection = writeText(request.omsConnection(), MAX_OMS_CONNECTION_LENGTH, true,
                "A valid OMS connection is required.");
        String documentNumber = writeText(request.documentNumber(), MAX_DOCUMENT_LENGTH, false,
                "The document number is invalid.");
        String documentDate = writeText(request.documentDate(), 10, false,
                "The document date is invalid.");
        if (documentNumber.isEmpty() != documentDate.isEmpty()) {
            throw invalid("The default goods document is incomplete.");
        }
        if (!documentDate.isEmpty()) {
            try {
                Settings.validateGoodsDocumentDate(documentDate, "Document issue date");
            } catch (IllegalArgumentException error) {
                throw invalid("The document date must use dd.MM.yyyy format.");
            }
        }
        String version = request.version();
        if (version == null || !version.matches("[0-9a-f]{64}")) {
            throw invalid("The Znack settings version is invalid.");
        }
        return new ValidatedSettings(shopId, omsId, omsConnection, documentNumber, documentDate,
                request.autoIntroduction(), version);
    }

    private static Settings mergeSettings(Settings current, ValidatedSettings editable) {
        return new Settings(
                current.trueApiBaseUrl(),
                current.suzBaseUrl(),
                editable.omsId(),
                editable.omsConnection(),
                current.participantInn(),
                current.producerInn(),
                current.ownerInn(),
                current.signerExecutable(),
                current.signerCertificate(),
                current.signerArgumentsJson(),
                editable.documentNumber(),
                editable.documentDate(),
                current.pdfFolder(),
                editable.autoIntroduction(),
                current.certificateListExecutable(),
                current.certificateListArgumentsJson(),
                current.certificateMetadataJson(),
                current.signerTestedAt(),
                current.certmgrPath(),
                current.cryptcpPath(),
                current.csptestPath(),
                current.resolvedCryptoProTimeoutSeconds(),
                current.documentExpiryDate(),
                current.documentType());
    }

    private static ValidatedProducts validateProducts(ProductsRequest request) {
        int shopId = requirePositiveShopId(request == null ? 0 : request.shopId());
        String query = validateQuery(request == null ? null : request.query());
        List<String> categories = sanitizeRequestLabels(request.categories(), MAX_CATEGORY_COUNT, "category filter");
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw invalid("The requested Znack product page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw invalid("The requested Znack product page size is invalid.");
        }
        return new ValidatedProducts(shopId, query, categories, request.deleted(), request.page(), request.pageSize());
    }

    private static ValidatedVisibility validateVisibility(SetProductVisibilityRequest request) {
        int shopId = requirePositiveShopId(request == null ? 0 : request.shopId());
        if (request.gtins() == null || request.gtins().isEmpty()
                || request.gtins().size() > MAX_VISIBILITY_BATCH) {
            throw invalid("The GTIN visibility selection is invalid.");
        }
        List<String> gtins = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String candidate : request.gtins()) {
            String gtin;
            try {
                gtin = GtinNormalizer.requireProductionOrderable(candidate);
            } catch (IllegalArgumentException error) {
                throw invalid("Every visibility target must be a production GTIN.");
            }
            if (!unique.add(gtin)) throw invalid("The GTIN visibility selection contains duplicates.");
            gtins.add(gtin);
        }
        return new ValidatedVisibility(shopId, List.copyOf(gtins), request.deleted());
    }

    private static int requirePositiveShopId(int shopId) {
        if (shopId <= 0) throw invalid("A positive shop id is required.");
        return shopId;
    }

    private static String validateQuery(String candidate) {
        if (candidate == null || candidate.length() > MAX_QUERY_LENGTH || hasControls(candidate)) {
            throw invalid("The Znack product search query is invalid.");
        }
        return candidate.strip();
    }

    private static String writeText(String candidate, int maxLength, boolean required, String message) {
        if (candidate == null || candidate.length() > maxLength || hasControls(candidate)) throw invalid(message);
        String normalized = candidate.strip();
        if (required && normalized.isEmpty()) throw invalid(message);
        return normalized;
    }

    private static List<String> sanitizeRequestLabels(List<String> values, int maximum, String name) {
        if (values == null || values.size() > maximum) throw invalid("The " + name + " is invalid.");
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            String normalized = writeText(value, MAX_LABEL_LENGTH, true, "The " + name + " is invalid.");
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        return List.copyOf(unique.values());
    }

    private static List<String> sanitizeLabels(List<String> values, int maximum, String name) {
        if (values == null || values.size() > maximum) throw new IllegalStateException(name + " is invalid");
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            String normalized = safeSourceText(value, MAX_LABEL_LENGTH);
            if (normalized.isEmpty()) throw new IllegalStateException(name + " is invalid");
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        return List.copyOf(unique.values());
    }

    private static String safeSourceText(String candidate, int maxLength) {
        String normalized = value(candidate).replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength).strip();
    }

    private static boolean hasControls(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    static String settingsVersion(Settings settings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : List.of(
                    nullable(settings.trueApiBaseUrl()), nullable(settings.suzBaseUrl()), nullable(settings.omsId()),
                    nullable(settings.omsConnection()), nullable(settings.participantInn()), nullable(settings.producerInn()),
                    nullable(settings.ownerInn()), nullable(settings.signerExecutable()), nullable(settings.signerCertificate()),
                    nullable(settings.signerArgumentsJson()), nullable(settings.documentNumber()), nullable(settings.documentDate()),
                    nullable(settings.pdfFolder()), settings.autoIntroduction(), nullable(settings.certificateListExecutable()),
                    nullable(settings.certificateListArgumentsJson()), nullable(settings.certificateMetadataJson()),
                    settings.signerTestedAt() == null ? "" : settings.signerTestedAt().toString(),
                    nullable(settings.certmgrPath()), nullable(settings.cryptcpPath()), nullable(settings.csptestPath()),
                    settings.resolvedCryptoProTimeoutSeconds(), nullable(settings.documentExpiryDate()),
                    nullable(settings.documentType()))) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String jsonString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static dev.jdesk.api.JDeskException invalid(String message) {
        return SafeCommandExecutor.invalidRequest(message);
    }

    public record SettingsRequest(int shopId) {
    }

    public record SaveSettingsRequest(
            int shopId,
            String omsId,
            String omsConnection,
            String documentNumber,
            String documentDate,
            boolean autoIntroduction,
            String version) {
    }

    public record SettingsResponse(
            int shopId,
            String omsId,
            String omsConnection,
            String documentNumber,
            String documentDate,
            boolean autoIntroduction,
            String signatureStatus,
            String certificateLabel,
            String certificateValidTo,
            String version) {
    }

    public record ProductsRequest(
            int shopId, String query, List<String> categories, boolean deleted, int page, int pageSize) {
    }

    public record ProductsResponse(
            int shopId,
            String query,
            List<String> categories,
            boolean deleted,
            int page,
            int pageSize,
            boolean hasMore,
            List<String> availableCategories,
            List<ProductItem> items) {
    }

    public record ProductItem(
            String gtin,
            String productName,
            String category,
            String tnVed,
            String cisType,
            String goodMarkStatus,
            String goodTurnStatus,
            String readinessCheckedAt,
            boolean deleted) {
    }

    public record SetProductVisibilityRequest(int shopId, List<String> gtins, boolean deleted) {
    }

    public record VisibilityResponse(int shopId, boolean deleted, int changed) {
    }

    public record ProductQuery(
            int shopId, String query, List<String> categories, boolean deleted, int limit, int offset) {
    }

    public record ProductRow(
            String gtin,
            String productName,
            String category,
            String tnVed,
            String cisType,
            Boolean goodMark,
            Boolean goodTurn,
            Instant readinessCheckedAt,
            boolean deleted) {
    }

    interface ZnackDataSource {
        Settings settings(int shopId);

        void saveSettings(int shopId, Settings settings);

        List<String> categories(int shopId, boolean deleted);

        List<ProductRow> products(ProductQuery request);

        void setProductVisibility(int shopId, String shopName, List<String> gtins, boolean deleted);
    }

    public static final class VisibilityConflictException extends RuntimeException {
        public VisibilityConflictException() {
            super("Znack product visibility changed concurrently.");
        }
    }

    private record ValidatedSettings(
            int shopId,
            String omsId,
            String omsConnection,
            String documentNumber,
            String documentDate,
            boolean autoIntroduction,
            String version) {
    }

    private record ValidatedProducts(
            int shopId, String query, List<String> categories, boolean deleted, int page, int pageSize) {
    }

    private record ValidatedVisibility(int shopId, List<String> gtins, boolean deleted) {
    }

    private record CertificateSummary(String status, String label, String validTo) {
    }

    private static final class LegacyZnackDataSource implements ZnackDataSource {
        private final ZnackWorkspaceRepository workspace = new ZnackWorkspaceRepository();

        @Override
        public Settings settings(int shopId) {
            return repository(shopId, "").getSettings();
        }

        @Override
        public void saveSettings(int shopId, Settings settings) {
            repository(shopId, "").saveSettings(settings);
        }

        @Override
        public List<String> categories(int shopId, boolean deleted) {
            return workspace.findCategories(shopId, deleted);
        }

        @Override
        public List<ProductRow> products(ProductQuery request) {
            return workspace.findProductsPage(
                            request.shopId(), request.query(), request.categories(), request.deleted(),
                            request.limit(), request.offset())
                    .stream()
                    .map(product -> new ProductRow(
                            product.gtin(), product.productName(), product.category(), product.tnVed(),
                            product.cisType(), product.goodMark(), product.goodTurn(),
                            product.readinessCheckedAt(), product.deleted()))
                    .toList();
        }

        @Override
        public void setProductVisibility(
                int shopId, String shopName, List<String> gtins, boolean deleted) {
            try {
                workspace.setProductVisibility(shopId, shopName, gtins, deleted);
            } catch (ZnackWorkspaceRepository.VisibilityConflictException conflict) {
                throw new VisibilityConflictException();
            }
        }

        private static ZnackRepository repository(int shopId, String shopName) {
            return new ZnackRepository(new ShopContext(shopId, shopName));
        }
    }
}
