package com.tuandev.fbsbarcode.jdesk.workspace;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.features.dashboard.DashboardKpis;
import com.tuandev.fbsbarcode.features.dashboard.DashboardRepository;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.ConfigService;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public final class WorkspaceCommandService {
    private static final int MAX_SHOP_NAME_LENGTH = 120;

    private final Supplier<List<Shop>> shops;
    private final IntFunction<DashboardKpis> dashboard;
    private final Supplier<Integer> selectedShopId;
    private final Supplier<String> appVersion;

    public WorkspaceCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        DashboardRepository dashboardRepository = new DashboardRepository();
        this.shops = shopRepository::findAll;
        this.dashboard = dashboardRepository::loadKpis;
        this.selectedShopId = ConfigService::getLastSelectedShopId;
        this.appVersion = BuildConfig::getAppVersion;
    }

    WorkspaceCommandService(
            Supplier<List<Shop>> shops,
            IntFunction<DashboardKpis> dashboard,
            Supplier<Integer> selectedShopId,
            Supplier<String> appVersion) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.dashboard = Objects.requireNonNull(dashboard, "dashboard");
        this.selectedShopId = Objects.requireNonNull(selectedShopId, "selectedShopId");
        this.appVersion = Objects.requireNonNull(appVersion, "appVersion");
    }

    @DesktopCommand("workspace.bootstrap")
    @RequiresCapability("workspace:read")
    public CompletionStage<BootstrapResponse> bootstrap(
            BootstrapRequest request, InvocationContext context) {
        if (request == null) {
            throw SafeCommandExecutor.invalidRequest("Bootstrap request is required.");
        }
        return SafeCommandExecutor.execute(() -> {
            List<ShopSummary> summaries = requireShops().stream().map(WorkspaceCommandService::toSummary).toList();
            Integer configuredSelection = selectedShopId.get();
            Integer selected = configuredSelection != null
                            && summaries.stream().anyMatch(shop -> shop.id() == configuredSelection)
                    ? configuredSelection
                    : null;
            return new BootstrapResponse(
                    new AppMetadata("WCode", requireVersion(appVersion.get())),
                    summaries,
                    Optional.ofNullable(selected));
        });
    }

    @DesktopCommand("dashboard.load")
    @RequiresCapability("dashboard:read")
    public CompletionStage<DashboardResponse> loadDashboard(
            DashboardRequest request, InvocationContext context) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        return SafeCommandExecutor.execute(() -> {
            boolean owned = requireShops().stream().anyMatch(shop -> shop.getId() == request.shopId());
            if (!owned) {
                throw SafeCommandExecutor.invalidRequest("The selected shop is not available.");
            }
            DashboardKpis kpis = Objects.requireNonNull(dashboard.apply(request.shopId()), "dashboard KPIs");
            if (kpis.productCount() < 0 || kpis.newOrderCount() < 0 || kpis.openSupplyCount() < 0) {
                throw new IllegalStateException("Dashboard counts must not be negative");
            }
            return new DashboardResponse(
                    request.shopId(), kpis.productCount(), kpis.newOrderCount(), kpis.openSupplyCount());
        });
    }

    private List<Shop> requireShops() {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
    }

    private static ShopSummary toSummary(Shop shop) {
        Objects.requireNonNull(shop, "shop");
        if (shop.getId() <= 0) {
            throw new IllegalStateException("Shop id must be positive");
        }
        String name = shop.getName() == null ? "" : shop.getName().replaceAll("\\p{Cntrl}", " ").strip();
        if (name.isBlank()) {
            name = "Shop " + shop.getId();
        } else if (name.length() > MAX_SHOP_NAME_LENGTH) {
            name = name.substring(0, MAX_SHOP_NAME_LENGTH);
        }
        boolean tokenConfigured = shop.getApiKey() != null && !shop.getApiKey().isBlank();
        return new ShopSummary(shop.getId(), name, tokenConfigured);
    }

    private static String requireVersion(String version) {
        if (version == null || version.isBlank() || version.length() > 32) {
            throw new IllegalStateException("App version is unavailable");
        }
        return version;
    }

    public record BootstrapRequest() {
    }

    public record AppMetadata(String name, String version) {
    }

    public record ShopSummary(int id, String name, boolean tokenConfigured) {
    }

    public record BootstrapResponse(
            AppMetadata app, List<ShopSummary> shops, Optional<Integer> selectedShopId) {
    }

    public record DashboardRequest(int shopId) {
    }

    public record DashboardResponse(
            int shopId, long productCount, long newOrderCount, long openSupplyCount) {
    }
}
