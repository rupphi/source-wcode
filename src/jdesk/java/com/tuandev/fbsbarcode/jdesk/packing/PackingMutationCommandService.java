package com.tuandev.fbsbarcode.jdesk.packing;

import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.jdesk.shop.ShopActivityGate;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** One-use confirmation boundary for seller-state mutations in the FBS packing workflow. */
public final class PackingMutationCommandService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    private static final int MAX_PREVIEWS = 200;
    private static final int MAX_SELECTION = 1_000;
    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_SUPPLY_ID_LENGTH = 128;
    private static final Set<String> ALLOWED_BLOCKERS =
            Set.of("supply_not_ready", "labels_missing", "kiz_missing");

    private final Supplier<List<Shop>> shops;
    private final BoardReader boards;
    private final MutationRunner mutations;
    private final Clock clock;
    private final ShopActivityGate activityGate;
    private final ConcurrentMap<String, PendingPreview> previews = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();

    public PackingMutationCommandService(ShopActivityGate activityGate) {
        ShopRepository shopRepository = new ShopRepository();
        PackingWorkflow workflow = new PackingWorkflow();
        this.shops = shopRepository::findAll;
        this.boards = workflow::loadBoardData;
        this.mutations = new MutationRunner() {
            @Override
            public DeliveryReadiness inspect(Shop shop, WbSupplySummary supply) {
                PackingWorkflow.DeliveryPreflight preflight =
                        workflow.inspectDelivery(shop.getId(), supply);
                return new DeliveryReadiness(
                        preflight.ready(),
                        preflight.labelsPrinted(),
                        preflight.kizComplete(),
                        preflight.blockers());
            }

            @Override
            public String create(Shop shop, String name, List<Long> orderIds) throws IOException {
                return workflow.createShipment(shop, name, orderIds);
            }

            @Override
            public void add(Shop shop, String supplyId, List<Long> orderIds) throws IOException {
                workflow.addOrdersToSupply(shop, supplyId, orderIds);
            }

            @Override
            public void deliver(Shop shop, WbSupplySummary supply) throws IOException {
                workflow.deliverSupply(shop, supply);
            }
        };
        this.clock = Clock.systemUTC();
        this.activityGate = Objects.requireNonNull(activityGate, "activityGate");
    }

    PackingMutationCommandService(
            Supplier<List<Shop>> shops,
            BoardReader boards,
            MutationRunner mutations,
            Clock clock,
            ShopActivityGate activityGate) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.boards = Objects.requireNonNull(boards, "boards");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activityGate = Objects.requireNonNull(activityGate, "activityGate");
    }

    @DesktopCommand("packing.prepareCreate")
    @RequiresCapability("packing:write")
    public CompletionStage<MutationPreview> prepareCreate(
            PrepareCreateRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        String name = requireText(
                request == null ? null : request.name(), MAX_NAME_LENGTH, "invalid_name");
        List<Long> orderIds = requireOrderIds(request == null ? null : request.orderIds());
        return prepare(context, shopId, "create", "", name, orderIds);
    }

    @DesktopCommand("packing.prepareAdd")
    @RequiresCapability("packing:write")
    public CompletionStage<MutationPreview> prepareAdd(
            PrepareAddRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        String supplyId = requireText(
                request == null ? null : request.supplyId(), MAX_SUPPLY_ID_LENGTH, "invalid_supply");
        List<Long> orderIds = requireOrderIds(request == null ? null : request.orderIds());
        return prepare(context, shopId, "add", supplyId, "", orderIds);
    }

    @DesktopCommand("packing.prepareDeliver")
    @RequiresCapability("packing:write")
    public CompletionStage<MutationPreview> prepareDeliver(
            PrepareDeliverRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        String supplyId = requireText(
                request == null ? null : request.supplyId(), MAX_SUPPLY_ID_LENGTH, "invalid_supply");
        return prepare(context, shopId, "deliver", supplyId, "", List.of());
    }

    private CompletionStage<MutationPreview> prepare(
            InvocationContext context,
            int shopId,
            String action,
            String supplyId,
            String supplyName,
            List<Long> orderIds) {
        requireNotCancelled(context);
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            Shop shop = requireShop(shopId);
            PackingWorkflow.PackingBoard board = requireBoard(boards.read(shop));
            List<String> blockers = List.of();
            List<String> warnings = List.of();
            int itemCount;
            int kizCount;
            String resolvedSupplyId = supplyId;
            String resolvedSupplyName = supplyName;

            if ("create".equals(action) || "add".equals(action)) {
                List<Order> selected = requireCurrentOrders(board, orderIds);
                itemCount = selected.size();
                kizCount = (int) selected.stream().filter(Order::isRequiresKiz).count();
                warnings = kizCount == 0 ? List.of() : List.of("kiz_required");
                if ("add".equals(action)) {
                    WbSupplySummary supply = requireOpenSupply(board, supplyId);
                    resolvedSupplyName = safeSupplyName(supply);
                }
            } else {
                WbSupplySummary supply = requireOpenSupply(board, supplyId);
                resolvedSupplyName = safeSupplyName(supply);
                itemCount = supply.getItemCount();
                DeliveryReadiness readiness = requireReadiness(mutations.inspect(shop, supply));
                blockers = readiness.blockers();
                kizCount = readiness.kizComplete() ? 0 : 1;
            }

            PendingPreview pending = new PendingPreview(
                    shopId,
                    UUID.randomUUID().toString(),
                    action,
                    resolvedSupplyId,
                    resolvedSupplyName,
                    orderIds,
                    itemCount,
                    kizCount,
                    blockers,
                    warnings,
                    clock.instant().plus(PREVIEW_TTL));
            store(pending);
            return pending.response();
        });
    }

    @DesktopCommand("packing.execute")
    @RequiresCapability("packing:write")
    public CompletionStage<MutationReceipt> execute(
            ExecuteMutationRequest request, InvocationContext context) {
        int shopId = requireShopId(request == null ? 0 : request.shopId());
        if (request == null || !request.confirmed()) {
            throw invalid("Explicit packing confirmation is required.", "confirmation_required", false);
        }
        String previewId = requireUuid(request.previewId());
        requireNotCancelled(context);
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            Shop shop = requireShop(shopId);
            PendingPreview preview = previews.get(previewId);
            if (preview == null
                    || preview.shopId() != shopId
                    || !preview.expiresAt().isAfter(clock.instant())) {
                throw invalid("The packing preview expired. Prepare it again.", "preview_invalid", true);
            }
            if (!previews.remove(previewId, preview)) {
                throw invalid("The packing preview expired. Prepare it again.", "preview_invalid", true);
            }
            if (!preview.blockers().isEmpty()) {
                throw invalid("The packing preflight is blocked.", "preflight_blocked", false);
            }

            synchronized (mutationLock) {
                requireNotCancelled(context);
                try (ShopActivityGate.Lease ignored = beginActivity(shopId)) {
                    PackingWorkflow.PackingBoard board = requireBoard(boards.read(shop));
                    return executeFresh(shop, board, preview);
                }
            }
        });
    }

    private MutationReceipt executeFresh(
            Shop shop, PackingWorkflow.PackingBoard board, PendingPreview preview) {
        try {
            return switch (preview.action()) {
                case "create" -> {
                    requireCurrentOrders(board, preview.orderIds());
                    String supplyId = requireText(
                            mutations.create(shop, preview.supplyName(), preview.orderIds()),
                            MAX_SUPPLY_ID_LENGTH,
                            "mutation_failed");
                    yield new MutationReceipt("create", supplyId, preview.itemCount(), true);
                }
                case "add" -> {
                    requireCurrentOrders(board, preview.orderIds());
                    requireOpenSupply(board, preview.supplyId());
                    mutations.add(shop, preview.supplyId(), preview.orderIds());
                    yield new MutationReceipt("add", preview.supplyId(), preview.itemCount(), true);
                }
                case "deliver" -> {
                    WbSupplySummary supply = requireOpenSupply(board, preview.supplyId());
                    DeliveryReadiness readiness = requireReadiness(mutations.inspect(shop, supply));
                    if (!readiness.ready()) {
                        throw invalid("The packing state changed. Prepare it again.", "state_changed", true);
                    }
                    mutations.deliver(shop, supply);
                    yield new MutationReceipt("deliver", preview.supplyId(), preview.itemCount(), true);
                }
                default -> throw new IllegalStateException("Unknown packing mutation");
            };
        } catch (JDeskException exception) {
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalid("The packing state changed. Prepare it again.", "state_changed", true);
        } catch (IOException exception) {
            throw invalid("Wildberries is temporarily unavailable.", "unavailable", true);
        } catch (Exception exception) {
            throw new IllegalStateException("Packing mutation failed");
        }
    }

    private ShopActivityGate.Lease beginActivity(int shopId) {
        try {
            return activityGate.begin(shopId);
        } catch (ShopActivityGate.ShopBusyException exception) {
            throw invalid("The shop is busy.", "shop_busy", true);
        }
    }

    private Shop requireShop(int shopId) {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops")).stream()
                .filter(Objects::nonNull)
                .filter(shop -> shop.getId() == shopId)
                .findFirst()
                .orElseThrow(() -> invalid("The selected shop is not available.", "shop_not_found", false));
    }

    private static PackingWorkflow.PackingBoard requireBoard(PackingWorkflow.PackingBoard board) {
        Objects.requireNonNull(board, "packing board");
        return new PackingWorkflow.PackingBoard(
                List.copyOf(Objects.requireNonNull(board.newOrders(), "new orders")),
                List.copyOf(Objects.requireNonNull(board.preparationSupplies(), "preparation supplies")),
                List.copyOf(Objects.requireNonNull(board.dispatchSupplies(), "dispatch supplies")));
    }

    private static List<Order> requireCurrentOrders(
            PackingWorkflow.PackingBoard board, List<Long> orderIds) {
        Map<Long, Order> available = new LinkedHashMap<>();
        for (Order order : board.newOrders()) {
            if (order == null || order.getId() == null || available.putIfAbsent(order.getId(), order) != null) {
                throw new IllegalStateException("Packing board order data is invalid");
            }
        }
        List<Order> selected = orderIds.stream().map(available::get).toList();
        if (selected.stream().anyMatch(Objects::isNull)) {
            throw invalid("The selected orders changed. Refresh packing.", "state_changed", true);
        }
        return selected;
    }

    private static WbSupplySummary requireOpenSupply(
            PackingWorkflow.PackingBoard board, String supplyId) {
        return board.preparationSupplies().stream()
                .filter(Objects::nonNull)
                .filter(supply -> supplyId.equals(supply.getSupplyId()))
                .filter(supply -> !supply.isDone() && supply.getItemCount() > 0)
                .findFirst()
                .orElseThrow(() -> invalid(
                        "The selected supply changed. Refresh packing.", "state_changed", true));
    }

    private static DeliveryReadiness requireReadiness(DeliveryReadiness readiness) {
        Objects.requireNonNull(readiness, "delivery readiness");
        List<String> blockers = List.copyOf(Objects.requireNonNull(readiness.blockers(), "blockers"));
        boolean supplyBlocked = blockers.contains("supply_not_ready");
        if (blockers.size() > ALLOWED_BLOCKERS.size()
                || new HashSet<>(blockers).size() != blockers.size()
                || !ALLOWED_BLOCKERS.containsAll(blockers)
                || readiness.ready() != blockers.isEmpty()
                || (!supplyBlocked
                        && readiness.labelsPrinted() == blockers.contains("labels_missing"))
                || (!supplyBlocked
                        && readiness.kizComplete() == blockers.contains("kiz_missing"))) {
            throw new IllegalStateException("Delivery readiness is invalid");
        }
        return new DeliveryReadiness(
                readiness.ready(), readiness.labelsPrinted(), readiness.kizComplete(), blockers);
    }

    private static String safeSupplyName(WbSupplySummary supply) {
        String name = supply.getName();
        if (name == null || name.isBlank()) return requireText(
                supply.getSupplyId(), MAX_SUPPLY_ID_LENGTH, "invalid_supply");
        return requireText(name, MAX_NAME_LENGTH, "invalid_supply");
    }

    private synchronized void store(PendingPreview preview) {
        Instant now = clock.instant();
        previews.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (previews.size() >= MAX_PREVIEWS) {
            previews.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .limit(previews.size() - MAX_PREVIEWS + 1L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(previews::remove);
        }
        previews.put(preview.previewId(), preview);
    }

    private static int requireShopId(int shopId) {
        if (shopId <= 0) throw invalid("A positive shop id is required.", "invalid_shop", false);
        return shopId;
    }

    private static List<Long> requireOrderIds(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_SELECTION) {
            throw invalid("Select between 1 and 1000 orders.", "invalid_selection", false);
        }
        Set<Long> unique = new HashSet<>();
        List<Long> parsed;
        try {
            parsed = values.stream().map(value -> {
                if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                    throw new NumberFormatException();
                }
                long id = Long.parseLong(value);
                if (id <= 0 || !unique.add(id)) throw new NumberFormatException();
                return id;
            }).toList();
        } catch (NumberFormatException exception) {
            throw invalid("The order selection is invalid.", "invalid_selection", false);
        }
        return parsed;
    }

    private static String requireText(String value, int maximum, String kind) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()
                || normalized.length() > maximum
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("A packing value is invalid.", kind, false);
        }
        return normalized;
    }

    private static String requireUuid(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value)) return value;
        } catch (IllegalArgumentException ignored) {
            // Mapped to the bounded error below.
        }
        throw invalid("The packing preview is invalid.", "preview_invalid", true);
    }

    private static void requireNotCancelled(InvocationContext context) {
        if (context != null && context.isCancelled()) {
            throw new JDeskException(
                    ErrorCode.CANCELLED,
                    "Packing operation cancelled.",
                    new PackingMutationError("cancelled", true),
                    null);
        }
    }

    private static JDeskException invalid(String message, String kind, boolean retryable) {
        return new JDeskException(
                ErrorCode.INVALID_REQUEST,
                message,
                new PackingMutationError(kind, retryable),
                null);
    }

    @FunctionalInterface
    interface BoardReader {
        PackingWorkflow.PackingBoard read(Shop shop);
    }

    interface MutationRunner {
        DeliveryReadiness inspect(Shop shop, WbSupplySummary supply);

        String create(Shop shop, String name, List<Long> orderIds) throws Exception;

        void add(Shop shop, String supplyId, List<Long> orderIds) throws Exception;

        void deliver(Shop shop, WbSupplySummary supply) throws Exception;
    }

    public record PrepareCreateRequest(int shopId, String name, List<String> orderIds) {}

    public record PrepareAddRequest(int shopId, String supplyId, List<String> orderIds) {}

    public record PrepareDeliverRequest(int shopId, String supplyId) {}

    public record ExecuteMutationRequest(int shopId, String previewId, boolean confirmed) {}

    public record DeliveryReadiness(
            boolean ready, boolean labelsPrinted, boolean kizComplete, List<String> blockers) {
        public DeliveryReadiness {
            blockers = List.copyOf(blockers);
        }
    }

    public record MutationPreview(
            int shopId,
            String previewId,
            String action,
            String supplyId,
            String supplyName,
            int itemCount,
            int kizCount,
            boolean ready,
            List<String> blockers,
            List<String> warnings,
            String expiresAt) {
        public MutationPreview {
            blockers = List.copyOf(blockers);
            warnings = List.copyOf(warnings);
        }
    }

    public record MutationReceipt(String action, String supplyId, int itemCount, boolean accepted) {}

    public record PackingMutationError(String kind, boolean retryable) {}

    private record PendingPreview(
            int shopId,
            String previewId,
            String action,
            String supplyId,
            String supplyName,
            List<Long> orderIds,
            int itemCount,
            int kizCount,
            List<String> blockers,
            List<String> warnings,
            Instant expiresAt) {
        private PendingPreview {
            orderIds = List.copyOf(orderIds);
            blockers = List.copyOf(blockers);
            warnings = List.copyOf(warnings);
        }

        private MutationPreview response() {
            return new MutationPreview(
                    shopId,
                    previewId,
                    action,
                    supplyId,
                    supplyName,
                    itemCount,
                    kizCount,
                    blockers.isEmpty(),
                    blockers,
                    warnings,
                    expiresAt.toString());
        }
    }
}
