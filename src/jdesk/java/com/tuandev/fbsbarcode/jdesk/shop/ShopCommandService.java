package com.tuandev.fbsbarcode.jdesk.shop;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.IntPredicate;

/** Owns local shop CRUD while keeping seller credentials out of every bridge response. */
public final class ShopCommandService {
    private static final int MAX_SHOPS = 500;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_TOKEN_LENGTH = 16 * 1024;

    private final ShopStore store;
    private final ShopActivityGate activityGate;
    private final IntPredicate activeJob;
    private final Object mutationLock = new Object();

    public ShopCommandService(ShopActivityGate activityGate, IntPredicate activeJob) {
        this(new SqliteShopStore(Database::getConnection), activityGate, activeJob);
    }

    ShopCommandService(ShopStore store, ShopActivityGate activityGate, IntPredicate activeJob) {
        this.store = Objects.requireNonNull(store, "store");
        this.activityGate = Objects.requireNonNull(activityGate, "activityGate");
        this.activeJob = Objects.requireNonNull(activeJob, "activeJob");
    }

    @DesktopCommand("shops.list")
    @RequiresCapability("shops:read")
    public CompletionStage<ShopState> list(ShopListRequest request, InvocationContext context) {
        if (request == null) {
            throw invalid("Shop list request is required.", "invalid_request");
        }
        return SafeCommandExecutor.execute(() -> requireState(store.list()));
    }

    @DesktopCommand("shops.create")
    @RequiresCapability("shops:write")
    public CompletionStage<ShopState> create(CreateShopRequest request, InvocationContext context) {
        if (request == null) {
            throw invalid("Shop details are required.", "invalid_request");
        }
        String name = requireName(request.name());
        String apiKey = requireToken(request.apiKey(), true);
        return mutate(context, () -> store.create(name, apiKey));
    }

    @DesktopCommand("shops.update")
    @RequiresCapability("shops:write")
    public CompletionStage<ShopState> update(UpdateShopRequest request, InvocationContext context) {
        if (request == null || request.shopId() <= 0) {
            throw invalid("A valid shop is required.", "invalid_shop");
        }
        String name = requireName(request.name());
        String apiKey = requireToken(request.apiKey(), false);
        return mutate(context, () -> store.update(request.shopId(), name, apiKey));
    }

    @DesktopCommand("shops.select")
    @RequiresCapability("shops:write")
    public CompletionStage<ShopState> select(SelectShopRequest request, InvocationContext context) {
        if (request == null || request.shopId() <= 0) {
            throw invalid("A valid shop is required.", "invalid_shop");
        }
        return mutate(context, () -> store.select(request.shopId()));
    }

    @DesktopCommand("shops.delete")
    @RequiresCapability("shops:write")
    public CompletionStage<ShopState> delete(DeleteShopRequest request, InvocationContext context) {
        if (request == null || request.shopId() <= 0) {
            throw invalid("A valid shop is required.", "invalid_shop");
        }
        if (!request.confirmed()) {
            throw invalid("Explicit confirmation is required to delete local shop data.", "confirmation_required");
        }
        return mutate(context, () -> {
            try {
                return activityGate.deleteWhenIdle(request.shopId(), () -> {
                    if (activeJob.test(request.shopId())) {
                        throw new ShopStoreException("shop_busy");
                    }
                    return store.delete(request.shopId());
                });
            } catch (ShopActivityGate.ShopBusyException exception) {
                throw new ShopStoreException("shop_busy");
            }
        });
    }

    private CompletionStage<ShopState> mutate(InvocationContext context, Mutation mutation) {
        requireNotCancelled(context);
        return SafeCommandExecutor.execute(() -> {
            synchronized (mutationLock) {
                requireNotCancelled(context);
                try {
                    return requireState(mutation.run());
                } catch (ShopStoreException exception) {
                    throw switch (exception.kind()) {
                        case "shop_busy" -> invalid(
                                "The shop has an active background operation.", "shop_busy");
                        case "shop_limit" -> invalid(
                                "The local shop limit has been reached.", "shop_limit");
                        case "shop_not_found" -> invalid(
                                "The selected shop is no longer available.", "shop_not_found");
                        default -> throw new IllegalStateException("Unknown shop store failure");
                    };
                }
            }
        });
    }

    private static ShopState requireState(ShopState state) {
        Objects.requireNonNull(state, "shop state");
        List<ManagedShopSummary> shops = List.copyOf(Objects.requireNonNull(state.shops(), "shops"));
        if (shops.size() > MAX_SHOPS) {
            throw new IllegalStateException("Shop response exceeds the bound");
        }
        Set<Integer> ids = new HashSet<>();
        for (ManagedShopSummary shop : shops) {
            if (shop == null
                    || shop.id() <= 0
                    || !ids.add(shop.id())
                    || !isSafeName(shop.name())) {
                throw new IllegalStateException("Shop response is invalid");
            }
        }
        boolean selectedExists = state.hasSelectedShop() && ids.contains(state.selectedShopId());
        if (state.hasSelectedShop() != selectedExists
                || (!state.hasSelectedShop() && state.selectedShopId() != 0)) {
            throw new IllegalStateException("Shop selection is invalid");
        }
        return new ShopState(shops, state.hasSelectedShop(), state.selectedShopId());
    }

    private static String requireName(String value) {
        String name = value == null ? "" : value.strip();
        if (!isSafeName(name)) {
            throw invalid("Shop name must contain 1 to 120 printable characters.", "invalid_name");
        }
        return name;
    }

    private static boolean isSafeName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        return name.codePoints().noneMatch(Character::isISOControl);
    }

    private static String requireToken(String value, boolean required) {
        String token = value == null ? "" : value.strip();
        if (token.isEmpty() && !required) {
            return null;
        }
        if (token.isEmpty()
                || token.length() > MAX_TOKEN_LENGTH
                || token.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("A valid Wildberries API token is required.", "invalid_token");
        }
        return token;
    }

    private static void requireNotCancelled(InvocationContext context) {
        if (context != null && context.isCancelled()) {
            throw new JDeskException(
                    ErrorCode.CANCELLED,
                    "The shop operation was cancelled before local data changed.",
                    new ShopError("cancelled"),
                    null);
        }
    }

    private static JDeskException invalid(String message, String kind) {
        return new JDeskException(
                ErrorCode.INVALID_REQUEST, message, new ShopError(kind), null);
    }

    @FunctionalInterface
    private interface Mutation {
        ShopState run();
    }

    interface ShopStore {
        ShopState list();

        ShopState create(String name, String apiKey);

        /** A null apiKey retains the current credential. */
        ShopState update(int shopId, String name, String apiKey);

        ShopState select(int shopId);

        ShopState delete(int shopId);
    }

    static final class ShopStoreException extends RuntimeException {
        private final String kind;

        ShopStoreException(String kind) {
            super(kind, null, false, false);
            this.kind = kind;
        }

        String kind() {
            return kind;
        }
    }

    public record ShopListRequest() {
    }

    public record CreateShopRequest(String name, String apiKey) {
    }

    public record UpdateShopRequest(int shopId, String name, String apiKey) {
    }

    public record SelectShopRequest(int shopId) {
    }

    public record DeleteShopRequest(int shopId, boolean confirmed) {
    }

    public record ManagedShopSummary(int id, String name, boolean tokenConfigured) {
    }

    public record ShopState(
            List<ManagedShopSummary> shops, boolean hasSelectedShop, int selectedShopId) {
    }

    public record ShopError(String kind) {
    }
}
