package com.tuandev.fbsbarcode.features.dashboard;

import com.tuandev.fbsbarcode.integration.wb.SalesFunnelRequest;
import com.tuandev.fbsbarcode.integration.wb.SalesFunnelResponse;
import com.tuandev.fbsbarcode.integration.wb.WbApiClient;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class DashboardService {
    private static final Duration ANALYTICS_CACHE_TTL = Duration.ofHours(24);
    private final DashboardRepository repository;
    private final WbApiClient apiClient;
    private final SalesFunnelAnalyzer analyzer;
    private final Clock clock;
    private final Map<String, CacheEntry> analyticsCache = new HashMap<>();
    private final Map<String, DashboardData> inFlightLoads = new HashMap<>();

    public DashboardService() {
        this(new DashboardRepository(), new WbApiClient(), new SalesFunnelAnalyzer());
    }

    DashboardService(DashboardRepository repository, WbApiClient apiClient, SalesFunnelAnalyzer analyzer) {
        this(repository, apiClient, analyzer, Clock.systemDefaultZone());
    }

    DashboardService(DashboardRepository repository, WbApiClient apiClient, SalesFunnelAnalyzer analyzer, Clock clock) {
        this.repository = repository;
        this.apiClient = apiClient;
        this.analyzer = analyzer;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public synchronized DashboardData load(Shop shop, boolean forceRefresh) {
        DashboardKpis kpis = repository.loadKpis(shop.getId());
        SalesFunnelRequest request = SalesFunnelRequest.lastSevenDays(LocalDate.now(clock));
        String key = cacheKey(shop.getId(), request);
        if (!forceRefresh) {
            CacheEntry cached = analyticsCache.get(key);
            if (cached != null && !cached.isExpired(clock)) {
                return dataFromCache(shop.getId(), kpis, cached);
            }
            DashboardData inFlightResult = inFlightLoads.get(key);
            if (inFlightResult != null) {
                return inFlightResult;
            }
        }
        try {
            SalesFunnelResponse response = requestAnalytics(shop, request);
            CacheEntry entry = CacheEntry.success(response, Instant.now(clock));
            analyticsCache.put(key, entry);
            DashboardData data = dataFromCache(shop.getId(), kpis, entry);
            inFlightLoads.put(key, data);
            return data;
        } catch (Exception ex) {
            String error = readableAnalyticsError(ex);
            CacheEntry entry = CacheEntry.failure(error, Instant.now(clock));
            analyticsCache.put(key, entry);
            DashboardData data = new DashboardData(kpis, java.util.List.of(), java.util.List.of(), error);
            inFlightLoads.put(key, data);
            return data;
        } finally {
            inFlightLoads.remove(key);
        }
    }

    private SalesFunnelResponse requestAnalytics(Shop shop, SalesFunnelRequest request) throws IOException {
        try {
            return apiClient.getSalesFunnelProducts(shop.getApiKey(), request);
        } catch (WbApiException ex) {
            if (mayBeOrderByRejected(ex)) {
                request.setOrderBy(null);
                return apiClient.getSalesFunnelProducts(shop.getApiKey(), request);
            }
            throw ex;
        }
    }

    private boolean mayBeOrderByRejected(WbApiException ex) {
        int statusCode = ex.getStatusCode();
        String body = ex.getResponseBody() == null ? "" : ex.getResponseBody().toLowerCase(java.util.Locale.ROOT);
        return statusCode == 400 && body.contains("orderby");
    }

    private String cacheKey(int shopId, SalesFunnelRequest request) {
        SalesFunnelRequest.Period period = request.getSelectedPeriod();
        return shopId + ":" + period.getStart() + ":" + period.getEnd();
    }

    private String readableAnalyticsError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Không tải được dữ liệu Analytics";
        }
        return "Không tải được dữ liệu Analytics: " + message;
    }

    private DashboardData dataFromCache(int shopId, DashboardKpis kpis, CacheEntry entry) {
        if (entry.error() != null && !entry.error().isBlank()) {
            return new DashboardData(kpis, java.util.List.of(), java.util.List.of(), entry.error());
        }
        var items = entry.response() == null ? java.util.List.<SalesFunnelResponse.SalesFunnelProductItem>of() : entry.response().getItems();
        var localProducts = repository.findProductInfo(shopId, analyzer.nmIds(items));
        return new DashboardData(
                kpis,
                analyzer.topSelling(items, localProducts),
                analyzer.potentialProducts(items, localProducts),
                null
        );
    }

    private record CacheEntry(SalesFunnelResponse response, String error, Instant fetchedAt) {
        static CacheEntry success(SalesFunnelResponse response, Instant fetchedAt) {
            return new CacheEntry(response, null, fetchedAt);
        }

        static CacheEntry failure(String error, Instant fetchedAt) {
            return new CacheEntry(null, error, fetchedAt);
        }

        boolean isExpired(Clock clock) {
            return Duration.between(fetchedAt(), Instant.now(clock)).compareTo(ANALYTICS_CACHE_TTL) >= 0;
        }
    }
}
