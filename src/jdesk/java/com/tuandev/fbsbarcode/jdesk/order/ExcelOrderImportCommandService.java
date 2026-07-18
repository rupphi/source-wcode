package com.tuandev.fbsbarcode.jdesk.order;

import com.tuandev.fbsbarcode.features.order.ExcelOrderImportService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.wb.WbStickerService;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.models.Sticker;
import com.tuandev.fbsbarcode.shared.NaturalOrderComparator;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileDialog;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class ExcelOrderImportCommandService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final String SESSION_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual()
            .name("wcode-excel-import")
            .start(command);

    private final Supplier<List<Shop>> shops;
    private final FilePicker picker;
    private final ExcelReader excel;
    private final StickerReader stickers;
    private final OrderImageAssetService imageAssets;
    private final Clock clock;
    private final Duration sessionTtl;
    private final int maxSessions;
    private final Executor executor;
    private final Map<String, ImportSession> sessions = new LinkedHashMap<>();

    public ExcelOrderImportCommandService(OrderImageAssetService imageAssets) {
        ShopRepository shopRepository = new ShopRepository();
        ExcelOrderImportService excelService = new ExcelOrderImportService();
        WbStickerService stickerService = new WbStickerService();
        this.shops = shopRepository::findAll;
        this.picker = ExcelOrderImportCommandService::showOpenDialog;
        this.excel = path -> excelService.getOrdersFromExcel(path.toFile());
        this.stickers = stickerService::getStickers;
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
        this.clock = Clock.systemUTC();
        this.sessionTtl = Duration.ofMinutes(30);
        this.maxSessions = 8;
        this.executor = VIRTUAL_EXECUTOR;
    }

    ExcelOrderImportCommandService(
            Supplier<List<Shop>> shops,
            FilePicker picker,
            ExcelReader excel,
            StickerReader stickers,
            OrderImageAssetService imageAssets,
            Clock clock,
            Duration sessionTtl,
            int maxSessions,
            Executor executor) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.excel = Objects.requireNonNull(excel, "excel");
        this.stickers = Objects.requireNonNull(stickers, "stickers");
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (sessionTtl.isZero() || sessionTtl.isNegative() || maxSessions <= 0) {
            throw new IllegalArgumentException("Import session limits are invalid.");
        }
        this.maxSessions = maxSessions;
    }

    @DesktopCommand("orders.importExcel")
    @RequiresCapability("orders:import")
    public CompletionStage<ImportedOrderPage> importExcel(
            ImportExcelRequest request, InvocationContext context) {
        ValidatedImport validated = validateImport(request);
        Shop shop = requireShop(validated.shopId());
        if (shop.getApiKey() == null || shop.getApiKey().isBlank()) {
            throw SafeCommandExecutor.invalidRequest("The selected shop does not have an API token.");
        }
        return picker.pick(context).thenCompose(selected -> {
            if (selected.isEmpty()) {
                return CompletableFuture.completedFuture(cancelledPage(validated.pageSize()));
            }
            return CompletableFuture.supplyAsync(
                    () -> importSelected(shop, selected.get(), validated.pageSize()), executor);
        });
    }

    @DesktopCommand("orders.importedPage")
    @RequiresCapability("orders:import")
    public CompletionStage<ImportedOrderPage> loadImported(
            LoadImportedOrdersRequest request, InvocationContext context) {
        ValidatedPage validated = validatePage(request);
        ImportSession session = requireSession(validated.shopId(), validated.sessionId());
        return SafeCommandExecutor.execute(() -> page(
                session,
                validated.sessionId(),
                validated.query(),
                validated.page(),
                validated.pageSize()));
    }

    private ImportedOrderPage importSelected(Shop shop, Path selected, int pageSize) {
        return SafeCommandExecutor.execute(() -> {
                    List<Order> imported;
                    try {
                        imported = List.copyOf(Objects.requireNonNull(excel.read(selected), "imported orders"));
                    } catch (ExcelOrderImportService.InvalidExcelFileException exception) {
                        throw SafeCommandExecutor.invalidRequest(
                                "The selected file is not a supported Wildberries XLSX workbook.");
                    } catch (IOException exception) {
                        throw new JDeskException(
                                ErrorCode.INTERNAL_ERROR,
                                "The selected XLSX workbook could not be read.");
                    }
                    if (imported.isEmpty()) {
                        throw SafeCommandExecutor.invalidRequest(
                                "The selected workbook does not contain any valid orders.");
                    }
                    enrichStickers(shop, imported);
                    List<Order> sorted = new ArrayList<>(imported);
                    Comparator<String> natural = Comparator.nullsLast(NaturalOrderComparator::compareIgnoreCase);
                    sorted.sort(Comparator.comparing(Order::getArticle, natural)
                            .thenComparing(Order::getId, Comparator.nullsLast(Long::compareTo)));
                    String sessionId = UUID.randomUUID().toString();
                    String fileName = selected.getFileName() == null
                            ? "orders.xlsx"
                            : sanitize(selected.getFileName().toString(), 180);
                    ImportSession session = new ImportSession(
                            shop.getId(), fileName, List.copyOf(sorted), clock.instant().plus(sessionTtl));
                    putSession(sessionId, session);
                    return page(session, sessionId, "", 1, pageSize);
                })
                .toCompletableFuture()
                .join();
    }

    private void enrichStickers(Shop shop, List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<Sticker> loaded;
        try {
            loaded = List.copyOf(Objects.requireNonNull(
                    stickers.read(shop.getApiKey(), orderIds), "stickers"));
        } catch (WbApiException exception) {
            String kind = exception.isContentPermissionError()
                    ? "token_invalid"
                    : exception.isRateLimited() ? "rate_limited" : "upstream";
            boolean retryable = exception.isRateLimited() || exception.getStatusCode() >= 500;
            throw new JDeskException(
                    ErrorCode.INTERNAL_ERROR,
                    "Wildberries stickers could not be loaded.",
                    new ExcelImportError(kind, exception.getStatusCode(), retryable),
                    null);
        } catch (IOException exception) {
            throw new JDeskException(
                    ErrorCode.INTERNAL_ERROR,
                    "Wildberries stickers could not be loaded. Retry the import.");
        }
        Map<Long, String> barcodeByOrder = new LinkedHashMap<>();
        for (Sticker sticker : loaded) {
            if (sticker != null && sticker.getOrderId() != null && sticker.getBarcode() != null) {
                barcodeByOrder.putIfAbsent(sticker.getOrderId(), sticker.getBarcode());
            }
        }
        for (Order order : orders) {
            String barcode = barcodeByOrder.get(order.getId());
            if (barcode != null && !barcode.isBlank()) {
                order.setStickerCode(barcode);
            }
        }
    }

    private ImportedOrderPage page(
            ImportSession session, String sessionId, String query, int requestedPage, int pageSize) {
        List<Order> matching = session.orders().stream()
                .filter(order -> matches(order, query))
                .toList();
        int totalItems = matching.size();
        int totalPages = totalItems == 0 ? 0 : (int) (((long) totalItems + pageSize - 1) / pageSize);
        int safePage = totalPages == 0 ? 1 : Math.min(requestedPage, totalPages);
        int offset = Math.multiplyExact(safePage - 1, pageSize);
        int from = Math.min(offset, totalItems);
        int to = Math.min(from + pageSize, totalItems);
        List<ImportedOrderItem> items = matching.subList(from, to).stream()
                .map(order -> toItem(sessionId, order))
                .toList();
        int stickerCount = (int) session.orders().stream()
                .filter(order -> order.getStickerCode() != null && !order.getStickerCode().isBlank())
                .count();
        return new ImportedOrderPage(
                false,
                sessionId,
                session.fileName(),
                query,
                safePage,
                pageSize,
                totalItems,
                totalPages,
                session.orders().size(),
                stickerCount,
                items);
    }

    private ImportedOrderItem toItem(String sessionId, Order order) {
        return new ImportedOrderItem(
                order.getId().toString(),
                sanitize(order.getName(), 160),
                sanitize(order.getBrand(), 120),
                sanitize(order.getArticle(), 120),
                sanitize(order.getColor(), 80),
                sanitize(order.getSize(), 80),
                sanitize(order.getBarcode(), 128),
                sanitize(order.getSticker(), 128),
                order.getStickerCode() != null && !order.getStickerCode().isBlank(),
                imageAssets.registerImported(sessionId, order));
    }

    private static boolean matches(Order order, String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(order.getId(), needle)
                || contains(order.getName(), needle)
                || contains(order.getBrand(), needle)
                || contains(order.getArticle(), needle)
                || contains(order.getBarcode(), needle)
                || contains(order.getSticker(), needle);
    }

    private static boolean contains(Object value, String needle) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private Shop requireShop(int shopId) {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops")).stream()
                .filter(shop -> shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> SafeCommandExecutor.invalidRequest(
                        "The selected shop is not available."));
    }

    private void putSession(String sessionId, ImportSession session) {
        synchronized (sessions) {
            purgeExpired();
            while (sessions.size() >= maxSessions) {
                sessions.remove(sessions.keySet().iterator().next());
            }
            sessions.put(sessionId, session);
        }
    }

    private ImportSession requireSession(int shopId, String sessionId) {
        synchronized (sessions) {
            purgeExpired();
            ImportSession session = sessions.get(sessionId);
            if (session == null || session.shopId() != shopId) {
                throw SafeCommandExecutor.invalidRequest(
                        "The Excel import session is unavailable. Import the workbook again.");
            }
            return session;
        }
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static ValidatedImport validateImport(ImportExcelRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        validatePageSize(request.pageSize());
        return new ValidatedImport(request.shopId(), request.pageSize());
    }

    private static ValidatedPage validatePage(LoadImportedOrdersRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.sessionId() == null || !request.sessionId().matches(SESSION_PATTERN)) {
            throw SafeCommandExecutor.invalidRequest("The Excel import session id is invalid.");
        }
        if (request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The imported-order search query is invalid.");
        }
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw SafeCommandExecutor.invalidRequest("The imported-order page is invalid.");
        }
        validatePageSize(request.pageSize());
        return new ValidatedPage(
                request.shopId(),
                request.sessionId(),
                request.query().strip(),
                request.page(),
                request.pageSize());
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw SafeCommandExecutor.invalidRequest("The imported-order page size is invalid.");
        }
    }

    private static CompletionStage<Optional<Path>> showOpenDialog(InvocationContext context) {
        if (context == null || context.application() == null) {
            throw SafeCommandExecutor.invalidRequest("The native file dialog is unavailable.");
        }
        FileDialog.OpenDialog dialog = FileDialog.OpenDialog.ofType(
                "Import Wildberries orders",
                new FileDialog.Filter("Excel workbook", List.of("xlsx")));
        return context.application().showOpenDialog(dialog)
                .thenApply(result -> result.path().map(Path::of));
    }

    private static ImportedOrderPage cancelledPage(int pageSize) {
        return new ImportedOrderPage(true, "", "", "", 1, pageSize, 0, 0, 0, 0, List.of());
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    @FunctionalInterface
    interface FilePicker {
        CompletionStage<Optional<Path>> pick(InvocationContext context);
    }

    @FunctionalInterface
    interface ExcelReader {
        List<Order> read(Path path) throws IOException;
    }

    @FunctionalInterface
    interface StickerReader {
        List<Sticker> read(String apiToken, List<Long> orderIds) throws IOException;
    }

    private record ValidatedImport(int shopId, int pageSize) {
    }

    private record ValidatedPage(int shopId, String sessionId, String query, int page, int pageSize) {
    }

    private record ImportSession(int shopId, String fileName, List<Order> orders, Instant expiresAt) {
    }

    public record ImportExcelRequest(int shopId, int pageSize) {
    }

    public record LoadImportedOrdersRequest(
            int shopId, String sessionId, String query, int page, int pageSize) {
    }

    public record ImportedOrderItem(
            String orderId,
            String name,
            String brand,
            String article,
            String color,
            String size,
            String barcode,
            String sticker,
            boolean stickerAvailable,
            String imagePath) {
    }

    public record ExcelImportError(String kind, int httpStatus, boolean retryable) {
    }

    public record ImportedOrderPage(
            boolean cancelled,
            String sessionId,
            String fileName,
            String query,
            int page,
            int pageSize,
            int totalItems,
            int totalPages,
            int importedItems,
            int stickerItems,
            List<ImportedOrderItem> items) {
        public ImportedOrderPage {
            items = List.copyOf(items);
        }
    }
}
