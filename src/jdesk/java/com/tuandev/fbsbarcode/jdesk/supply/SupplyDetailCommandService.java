package com.tuandev.fbsbarcode.jdesk.supply;

import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.features.supply.OrderSortingService;
import com.tuandev.fbsbarcode.integration.wb.WbOrderRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.ui.supply.OrderSortOptions;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class SupplyDetailCommandService {
    private static final int MAX_SUPPLY_ID_LENGTH = 128;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final Supplier<List<Shop>> shops;
    private final SupplyReader supplies;
    private final OrderReader orders;
    private final OrderSortingService sorting;
    private final OrderImageAssetService imageAssets;

    public SupplyDetailCommandService() {
        this(new OrderImageAssetService());
    }

    public SupplyDetailCommandService(OrderImageAssetService imageAssets) {
        ShopRepository shopRepository = new ShopRepository();
        WbSupplyRepository supplyRepository = new WbSupplyRepository();
        WbOrderRepository orderRepository = new WbOrderRepository();
        WbSupplyWorkflow supplyWorkflow = new WbSupplyWorkflow();
        this.shops = shopRepository::findAll;
        this.supplies = supplyRepository::findSupplySummary;
        this.orders = (shopId, supplyId) -> supplyWorkflow.populateCachedOrderImages(
                orderRepository.getOrdersForSupply(shopId, supplyId));
        this.sorting = new OrderSortingService();
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
    }

    SupplyDetailCommandService(
            Supplier<List<Shop>> shops,
            SupplyReader supplies,
            OrderReader orders,
            OrderSortingService sorting) {
        this(shops, supplies, orders, sorting, new OrderImageAssetService());
    }

    SupplyDetailCommandService(
            Supplier<List<Shop>> shops,
            SupplyReader supplies,
            OrderReader orders,
            OrderSortingService sorting,
            OrderImageAssetService imageAssets) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.supplies = Objects.requireNonNull(supplies, "supplies");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.sorting = Objects.requireNonNull(sorting, "sorting");
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
    }

    @DesktopCommand("supplies.detail")
    @RequiresCapability("supplies:read")
    public CompletionStage<SupplyDetailResponse> load(
            LoadSupplyDetailRequest request, InvocationContext context) {
        ValidatedRequest validated = validate(request);
        return SafeCommandExecutor.execute(() -> {
            if (requireShops().stream().noneMatch(shop -> shop.getId() == validated.shopId())) {
                throw SafeCommandExecutor.invalidRequest("The selected shop is not available.");
            }
            WbSupplySummary supply = supplies.read(validated.shopId(), validated.supplyId());
            if (supply == null) {
                throw SafeCommandExecutor.invalidRequest("The selected supply is not available.");
            }
            List<Order> matching = List.copyOf(Objects.requireNonNull(
                            orders.read(validated.shopId(), validated.supplyId()), "supply orders"))
                    .stream()
                    .filter(order -> matches(order, validated.query()))
                    .toList();
            OrderSortOptions options = new OrderSortOptions(
                    validated.sort().bySubject(),
                    validated.sort().byArticle(),
                    validated.sort().byColor(),
                    validated.sort().bySize());
            List<Order> sorted = List.copyOf(Objects.requireNonNull(sorting.sort(matching, options), "sorted orders"));
            int totalItems = sorted.size();
            int totalPages = totalItems == 0
                    ? 0
                    : (int) (((long) totalItems + validated.pageSize() - 1) / validated.pageSize());
            int offset = Math.multiplyExact(validated.page() - 1, validated.pageSize());
            int fromIndex = Math.min(offset, totalItems);
            int toIndex = Math.min(fromIndex + validated.pageSize(), totalItems);
            List<OrderItem> items = sorted.subList(fromIndex, toIndex).stream()
                    .map(this::toItem)
                    .toList();
            return new SupplyDetailResponse(
                    SupplyCommandService.toItem(supply),
                    validated.query(),
                    validated.page(),
                    validated.pageSize(),
                    totalItems,
                    totalPages,
                    validated.sort(),
                    items);
        });
    }

    private List<Shop> requireShops() {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
    }

    private static ValidatedRequest validate(LoadSupplyDetailRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.supplyId() == null
                || request.supplyId().isBlank()
                || request.supplyId().length() > MAX_SUPPLY_ID_LENGTH
                || request.supplyId().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The supply id is invalid.");
        }
        if (request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The order search query is invalid.");
        }
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw SafeCommandExecutor.invalidRequest("The requested order page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw SafeCommandExecutor.invalidRequest("The order page size is invalid.");
        }
        if (request.sort() == null) {
            throw SafeCommandExecutor.invalidRequest("Order sorting options are required.");
        }
        return new ValidatedRequest(
                request.shopId(),
                request.supplyId().strip(),
                request.query().strip(),
                request.page(),
                request.pageSize(),
                request.sort());
    }

    private static boolean matches(Order order, String query) {
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
                || contains(order.getBarcode(), needle)
                || contains(order.getSupplierStatus(), needle)
                || contains(order.getWbStatus(), needle);
    }

    private static boolean contains(Object value, String needle) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private OrderItem toItem(Order order) {
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
        return new OrderItem(
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
                text(order.getSupplierStatus(), 64),
                text(order.getWbStatus(), 64),
                order.isRequiresKiz(),
                imageAssets.register(order));
    }

    private static String text(String value, int maxLength) {
        return SupplyCommandService.sanitizeDisplayText(value, maxLength);
    }

    @FunctionalInterface
    interface SupplyReader {
        WbSupplySummary read(int shopId, String supplyId);
    }

    @FunctionalInterface
    interface OrderReader {
        List<Order> read(int shopId, String supplyId);
    }

    private record ValidatedRequest(
            int shopId,
            String supplyId,
            String query,
            int page,
            int pageSize,
            OrderSortRequest sort) {
    }

    public record LoadSupplyDetailRequest(
            int shopId,
            String supplyId,
            String query,
            int page,
            int pageSize,
            OrderSortRequest sort) {
    }

    public record OrderSortRequest(
            boolean bySubject,
            boolean byArticle,
            boolean byColor,
            boolean bySize) {
    }

    public record OrderItem(
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
            String supplierStatus,
            String wbStatus,
            boolean requiresKiz,
            String imagePath) {
    }

    public record SupplyDetailResponse(
            SupplyCommandService.SupplyItem supply,
            String query,
            int page,
            int pageSize,
            int totalItems,
            int totalPages,
            OrderSortRequest sort,
            List<OrderItem> items) {
        public SupplyDetailResponse {
            items = List.copyOf(items);
        }
    }
}
