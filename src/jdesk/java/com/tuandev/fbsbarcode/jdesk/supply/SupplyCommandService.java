package com.tuandev.fbsbarcode.jdesk.supply;

import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class SupplyCommandService {
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_CREATED_AT_LENGTH = 64;

    private final Supplier<List<Shop>> shops;
    private final SupplyPageReader pages;

    public SupplyCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        WbSupplyRepository supplyRepository = new WbSupplyRepository();
        this.shops = shopRepository::findAll;
        this.pages = query -> supplyRepository.findSupplyPage(
                query.shopId(), query.query(), query.done(), query.limit(), query.offset());
    }

    SupplyCommandService(Supplier<List<Shop>> shops, SupplyPageReader pages) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    @DesktopCommand("supplies.list")
    @RequiresCapability("supplies:read")
    public CompletionStage<ListSuppliesResponse> list(
            ListSuppliesRequest request, InvocationContext context) {
        ValidatedRequest validated = validate(request);
        return SafeCommandExecutor.execute(() -> {
            if (requireShops().stream().noneMatch(shop -> shop.getId() == validated.shopId())) {
                throw SafeCommandExecutor.invalidRequest("The selected shop is not available.");
            }

            SupplyQuery query = new SupplyQuery(
                    validated.shopId(),
                    validated.query(),
                    validated.done(),
                    validated.pageSize(),
                    Math.multiplyExact(validated.page() - 1, validated.pageSize()));
            WbSupplyRepository.SupplyPage result = Objects.requireNonNull(pages.read(query), "supply page");
            List<SupplyItem> items = requirePageItems(result, validated.pageSize()).stream()
                    .map(SupplyCommandService::toItem)
                    .toList();
            requireCounts(result, items.size());
            int totalPages = result.totalItems() == 0
                    ? 0
                    : (int) (((long) result.totalItems() + validated.pageSize() - 1) / validated.pageSize());
            return new ListSuppliesResponse(
                    validated.shopId(),
                    validated.query(),
                    validated.status(),
                    validated.page(),
                    validated.pageSize(),
                    result.totalItems(),
                    totalPages,
                    result.openItems(),
                    result.closedItems(),
                    items);
        });
    }

    private List<Shop> requireShops() {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
    }

    private static List<WbSupplySummary> requirePageItems(
            WbSupplyRepository.SupplyPage page, int pageSize) {
        List<WbSupplySummary> items = List.copyOf(Objects.requireNonNull(page.items(), "supply items"));
        if (items.size() > pageSize) {
            throw new IllegalStateException("Supply page exceeds the requested page size");
        }
        return items;
    }

    private static void requireCounts(WbSupplyRepository.SupplyPage page, int returnedItems) {
        if (page.totalItems() < returnedItems || page.openItems() < 0 || page.closedItems() < 0) {
            throw new IllegalStateException("Supply counts are invalid");
        }
    }

    private static ValidatedRequest validate(ListSuppliesRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The supply search query is invalid.");
        }
        if (request.status() == null) {
            throw SafeCommandExecutor.invalidRequest("The supply status is invalid.");
        }
        Boolean done = switch (request.status()) {
            case "all" -> null;
            case "open" -> false;
            case "closed" -> true;
            default -> throw SafeCommandExecutor.invalidRequest("The supply status is invalid.");
        };
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw SafeCommandExecutor.invalidRequest("The requested supply page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw SafeCommandExecutor.invalidRequest("The supply page size is invalid.");
        }
        return new ValidatedRequest(
                request.shopId(), request.query().strip(), request.status(), done, request.page(), request.pageSize());
    }

    static SupplyItem toItem(WbSupplySummary supply) {
        Objects.requireNonNull(supply, "supply");
        String id = requireId(supply.getSupplyId());
        String name = sanitizeDisplayText(supply.getName(), MAX_NAME_LENGTH);
        if (name.isBlank()) {
            name = id;
        }
        String createdAt = sanitizeDisplayText(supply.getCreatedAt(), MAX_CREATED_AT_LENGTH);
        if (supply.getItemCount() < 0) {
            throw new IllegalStateException("Supply item count is invalid");
        }
        String mode = supply.getB2b() == null ? "unknown" : supply.getB2b() ? "b2b" : "consumer";
        return new SupplyItem(
                id,
                name,
                supply.isDone() ? "closed" : "open",
                mode,
                createdAt,
                supply.getItemCount());
    }

    private static String requireId(String value) {
        if (value == null
                || value.length() > MAX_ID_LENGTH
                || value.chars().anyMatch(Character::isISOControl)
                || value.isBlank()) {
            throw new IllegalStateException("Supply id is invalid");
        }
        return value.strip();
    }

    static String sanitizeDisplayText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    @FunctionalInterface
    interface SupplyPageReader {
        WbSupplyRepository.SupplyPage read(SupplyQuery query);
    }

    public record SupplyQuery(int shopId, String query, Boolean done, int limit, int offset) {
    }

    private record ValidatedRequest(
            int shopId, String query, String status, Boolean done, int page, int pageSize) {
    }

    public record ListSuppliesRequest(int shopId, String query, String status, int page, int pageSize) {
    }

    public record SupplyItem(
            String id, String name, String status, String mode, String createdAt, int itemCount) {
    }

    public record ListSuppliesResponse(
            int shopId,
            String query,
            String status,
            int page,
            int pageSize,
            int totalItems,
            int totalPages,
            int openItems,
            int closedItems,
            List<SupplyItem> items) {
        public ListSuppliesResponse {
            items = List.copyOf(items);
        }
    }
}
