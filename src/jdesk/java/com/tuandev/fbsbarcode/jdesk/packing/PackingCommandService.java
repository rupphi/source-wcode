package com.tuandev.fbsbarcode.jdesk.packing;

import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class PackingCommandService {
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_CATEGORY_COUNT = 50;
    private static final int MAX_CATEGORY_LENGTH = 120;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final Supplier<List<Shop>> shops;
    private final BoardReader boards;
    private final PageImageLoader pageImages;
    private final OrderImageAssetService imageAssets;

    public PackingCommandService(OrderImageAssetService imageAssets) {
        ShopRepository shopRepository = new ShopRepository();
        PackingWorkflow workflow = new PackingWorkflow();
        WbSupplyWorkflow supplyWorkflow = new WbSupplyWorkflow();
        this.shops = shopRepository::findAll;
        this.boards = workflow::loadBoardData;
        this.pageImages = supplyWorkflow::populateCachedOrderImages;
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
    }

    PackingCommandService(
            Supplier<List<Shop>> shops,
            BoardReader boards,
            PageImageLoader pageImages,
            OrderImageAssetService imageAssets) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.boards = Objects.requireNonNull(boards, "boards");
        this.pageImages = Objects.requireNonNull(pageImages, "pageImages");
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
    }

    @DesktopCommand("packing.board")
    @RequiresCapability("packing:read")
    public CompletionStage<PackingBoardResponse> load(
            PackingBoardRequest request, InvocationContext context) {
        ValidatedRequest validated = validate(request);
        return SafeCommandExecutor.execute(() -> {
            Shop shop = requireShops().stream()
                    .filter(candidate -> candidate.getId() == validated.shopId())
                    .findFirst()
                    .orElseThrow(() -> SafeCommandExecutor.invalidRequest(
                            "The selected shop is not available."));
            PackingWorkflow.PackingBoard board = requireBoard(boards.read(shop));
            List<String> availableCategories = availableCategories(board.newOrders());
            List<PackingOrderItem> orders = List.of();
            List<PackingSupplyItem> supplies = List.of();
            int totalItems;

            if (validated.tab().equals("new")) {
                List<Order> matching = board.newOrders().stream()
                        .filter(order -> matchesOrder(order, validated.query()))
                        .filter(order -> matchesCategory(order, validated.categories()))
                        .toList();
                totalItems = matching.size();
                List<Order> page = page(matching, validated.page(), validated.pageSize());
                if (!page.isEmpty()) {
                    pageImages.populate(page);
                }
                orders = page.stream().map(this::toOrderItem).toList();
            } else {
                List<WbSupplySummary> source = validated.tab().equals("preparation")
                        ? board.preparationSupplies()
                        : board.dispatchSupplies();
                List<WbSupplySummary> matching = source.stream()
                        .filter(supply -> matchesSupply(supply, validated.query()))
                        .toList();
                totalItems = matching.size();
                supplies = page(matching, validated.page(), validated.pageSize()).stream()
                        .map(PackingCommandService::toSupplyItem)
                        .toList();
            }

            int totalPages = totalItems == 0
                    ? 0
                    : (int) (((long) totalItems + validated.pageSize() - 1) / validated.pageSize());
            return new PackingBoardResponse(
                    validated.shopId(),
                    validated.tab(),
                    validated.query(),
                    validated.categories(),
                    validated.page(),
                    validated.pageSize(),
                    totalItems,
                    totalPages,
                    board.newOrders().size(),
                    board.preparationSupplies().size(),
                    board.dispatchSupplies().size(),
                    availableCategories,
                    orders,
                    supplies);
        });
    }

    private List<Shop> requireShops() {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
    }

    private static PackingWorkflow.PackingBoard requireBoard(PackingWorkflow.PackingBoard board) {
        Objects.requireNonNull(board, "packing board");
        return new PackingWorkflow.PackingBoard(
                List.copyOf(Objects.requireNonNull(board.newOrders(), "new orders")),
                List.copyOf(Objects.requireNonNull(board.preparationSupplies(), "preparation supplies")),
                List.copyOf(Objects.requireNonNull(board.dispatchSupplies(), "dispatch supplies")));
    }

    private static ValidatedRequest validate(PackingBoardRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.tab() == null
                || !(request.tab().equals("new")
                        || request.tab().equals("preparation")
                        || request.tab().equals("dispatch"))) {
            throw SafeCommandExecutor.invalidRequest("The packing tab is invalid.");
        }
        if (request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The packing search query is invalid.");
        }
        List<String> categories = validateCategories(request.categories());
        if (!request.tab().equals("new") && !categories.isEmpty()) {
            throw SafeCommandExecutor.invalidRequest("Categories are only supported for new orders.");
        }
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw SafeCommandExecutor.invalidRequest("The requested packing page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw SafeCommandExecutor.invalidRequest("The packing page size is invalid.");
        }
        return new ValidatedRequest(
                request.shopId(), request.tab(), request.query().strip(), categories, request.page(), request.pageSize());
    }

    private static List<String> validateCategories(List<String> categories) {
        if (categories == null || categories.size() > MAX_CATEGORY_COUNT) {
            throw SafeCommandExecutor.invalidRequest("The packing categories are invalid.");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String category : categories) {
            if (category == null
                    || category.isBlank()
                    || category.length() > MAX_CATEGORY_LENGTH
                    || category.chars().anyMatch(Character::isISOControl)) {
                throw SafeCommandExecutor.invalidRequest("The packing categories are invalid.");
            }
            String value = category.strip();
            normalized.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        return List.copyOf(normalized.values());
    }

    private static List<String> availableCategories(List<Order> orders) {
        Map<String, String> values = new LinkedHashMap<>();
        orders.stream()
                .map(Order::getSubjectName)
                .map(value -> text(value, MAX_CATEGORY_LENGTH))
                .filter(value -> !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(value -> values.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        return List.copyOf(values.values());
    }

    private static boolean matchesOrder(Order order, String query) {
        Objects.requireNonNull(order, "order");
        if (query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(order.getId(), needle)
                || contains(order.getNmId(), needle)
                || contains(order.getName(), needle)
                || contains(order.getBrand(), needle)
                || contains(order.getSubjectName(), needle)
                || contains(order.getArticle(), needle)
                || contains(order.getColor(), needle)
                || contains(order.getSize(), needle)
                || contains(order.getRuSize(), needle)
                || contains(order.getBarcode(), needle);
    }

    private static boolean matchesCategory(Order order, List<String> categories) {
        if (categories.isEmpty()) {
            return true;
        }
        String subject = text(order.getSubjectName(), MAX_CATEGORY_LENGTH);
        return categories.stream().anyMatch(value -> value.equalsIgnoreCase(subject));
    }

    private static boolean matchesSupply(WbSupplySummary supply, String query) {
        Objects.requireNonNull(supply, "supply");
        if (query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(supply.getSupplyId(), needle)
                || contains(supply.getName(), needle)
                || contains(supply.getCreatedAt(), needle);
    }

    private static boolean contains(Object value, String needle) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static <T> List<T> page(List<T> items, int page, int pageSize) {
        int offset = Math.multiplyExact(page - 1, pageSize);
        int fromIndex = Math.min(offset, items.size());
        int toIndex = Math.min(fromIndex + pageSize, items.size());
        return new ArrayList<>(items.subList(fromIndex, toIndex));
    }

    private PackingOrderItem toOrderItem(Order order) {
        if (order.getId() == null || order.getId() <= 0) {
            throw new IllegalStateException("Order id is invalid");
        }
        if (order.getNmId() != null && order.getNmId() <= 0) {
            throw new IllegalStateException("Order product id is invalid");
        }
        int price = order.getPrice() == null ? 0 : order.getPrice();
        if (price < 0) {
            throw new IllegalStateException("Order price is invalid");
        }
        String orderId = order.getId().toString();
        String article = text(order.getArticle(), 120);
        String name = text(order.getName(), 160);
        if (name.isBlank()) {
            name = article.isBlank() ? "Order " + orderId : article;
        }
        return new PackingOrderItem(
                orderId,
                order.getNmId() == null ? "" : order.getNmId().toString(),
                name,
                text(order.getBrand(), 120),
                text(order.getSubjectName(), 120),
                article,
                text(order.getColor(), 80),
                text(order.getSize(), 80),
                text(order.getRuSize(), 80),
                text(order.getBarcode(), 128),
                text(order.getCreatedAt(), 64),
                price,
                order.isRequiresKiz(),
                imageAssets.register(order));
    }

    private static PackingSupplyItem toSupplyItem(WbSupplySummary supply) {
        String id = requireSupplyId(supply.getSupplyId());
        String name = text(supply.getName(), 160);
        if (name.isBlank()) {
            name = id;
        }
        if (supply.getItemCount() < 0) {
            throw new IllegalStateException("Supply item count is invalid");
        }
        return new PackingSupplyItem(
                id,
                name,
                supply.isDone() ? "closed" : "open",
                supply.getB2b() == null ? "unknown" : supply.getB2b() ? "b2b" : "consumer",
                text(supply.getCreatedAt(), 64),
                supply.getItemCount());
    }

    private static String requireSupplyId(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Supply id is invalid");
        }
        return value.strip();
    }

    private static String text(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    @FunctionalInterface
    interface BoardReader {
        PackingWorkflow.PackingBoard read(Shop shop);
    }

    @FunctionalInterface
    interface PageImageLoader {
        void populate(List<Order> orders);
    }

    private record ValidatedRequest(
            int shopId, String tab, String query, List<String> categories, int page, int pageSize) {
    }

    public record PackingBoardRequest(
            int shopId, String tab, String query, List<String> categories, int page, int pageSize) {
    }

    public record PackingOrderItem(
            String orderId,
            String nmId,
            String name,
            String brand,
            String subject,
            String article,
            String color,
            String size,
            String russianSize,
            String barcode,
            String createdAt,
            int priceKopecks,
            boolean requiresKiz,
            String imagePath) {
    }

    public record PackingSupplyItem(
            String id, String name, String status, String mode, String createdAt, int itemCount) {
    }

    public record PackingBoardResponse(
            int shopId,
            String tab,
            String query,
            List<String> categories,
            int page,
            int pageSize,
            int totalItems,
            int totalPages,
            int newOrderCount,
            int preparationCount,
            int dispatchCount,
            List<String> availableCategories,
            List<PackingOrderItem> orders,
            List<PackingSupplyItem> supplies) {
        public PackingBoardResponse {
            categories = List.copyOf(categories);
            availableCategories = List.copyOf(availableCategories);
            orders = List.copyOf(orders);
            supplies = List.copyOf(supplies);
        }
    }
}
