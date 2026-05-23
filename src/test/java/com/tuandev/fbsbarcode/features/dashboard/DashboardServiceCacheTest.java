package com.tuandev.fbsbarcode.features.dashboard;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.integration.wb.SalesFunnelRequest;
import com.tuandev.fbsbarcode.integration.wb.SalesFunnelResponse;
import com.tuandev.fbsbarcode.integration.wb.WbApiClient;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardServiceCacheTest {
    @Test
    void shouldUseCacheForSameShopAndPeriod() {
        CountingApiClient apiClient = new CountingApiClient();
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer());
        Shop shop = new Shop(1, "Shop 1", "token");

        service.load(shop, false);
        service.load(shop, false);

        assertEquals(1, apiClient.calls.get());
    }

    @Test
    void shouldCallApiAgainForDifferentShop() {
        CountingApiClient apiClient = new CountingApiClient();
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer());

        service.load(new Shop(1, "Shop 1", "token"), false);
        service.load(new Shop(2, "Shop 2", "token"), false);

        assertEquals(2, apiClient.calls.get());
    }

    @Test
    void shouldBypassCacheOnManualRefresh() {
        CountingApiClient apiClient = new CountingApiClient();
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer());
        Shop shop = new Shop(1, "Shop 1", "token");

        service.load(shop, false);
        service.load(shop, true);

        assertEquals(2, apiClient.calls.get());
    }

    @Test
    void shouldShareAnalyticsRequestForConcurrentLoads() throws Exception {
        CountingApiClient apiClient = new CountingApiClient(200);
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer());
        Shop shop = new Shop(1, "Shop 1", "token");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    service.load(shop, false);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertEquals(1, apiClient.calls.get());
    }

    @Test
    void shouldNotSendOrderByByDefault() {
        CountingApiClient apiClient = new CountingApiClient();
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer());

        service.load(new Shop(1, "Shop 1", "token"), false);

        assertFalse(apiClient.lastRequestJson.get().contains("orderBy"));
    }

    @Test
    void shouldKeepAnalyticsCacheForTwentyFourHours() {
        CountingApiClient apiClient = new CountingApiClient();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-23T00:00:00Z"));
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer(), clock);
        Shop shop = new Shop(1, "Shop 1", "token");

        service.load(shop, false);
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        service.load(shop, false);
        clock.advance(Duration.ofMinutes(2));
        service.load(shop, false);

        assertEquals(2, apiClient.calls.get());
    }

    @Test
    void shouldCacheAnalyticsErrorsForTwentyFourHours() {
        FailingApiClient apiClient = new FailingApiClient();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-23T00:00:00Z"));
        DashboardService service = new DashboardService(new FakeRepository(), apiClient, new SalesFunnelAnalyzer(), clock);
        Shop shop = new Shop(1, "Shop 1", "token");

        DashboardData first = service.load(shop, false);
        DashboardData second = service.load(shop, false);
        clock.advance(Duration.ofHours(24).plusMinutes(1));
        DashboardData third = service.load(shop, false);

        assertTrue(first.hasAnalyticsError());
        assertTrue(second.hasAnalyticsError());
        assertTrue(third.hasAnalyticsError());
        assertEquals(2, apiClient.calls.get());
    }

    private static final class CountingApiClient extends WbApiClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastRequestJson = new AtomicReference<>("");
        private final long delayMillis;
        private final SalesFunnelResponse response = new Gson().fromJson("""
                {"data": [{"product": {"nmID": 10, "name": "Item"}, "selectedPeriod": {"orderCount": 1}}]}
                """, SalesFunnelResponse.class);

        private CountingApiClient() {
            this(0);
        }

        private CountingApiClient(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public SalesFunnelResponse getSalesFunnelProducts(String apiKey, SalesFunnelRequest request) throws IOException {
            calls.incrementAndGet();
            lastRequestJson.set(new Gson().toJson(request));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
            }
            return response;
        }
    }

    private static final class FailingApiClient extends WbApiClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public SalesFunnelResponse getSalesFunnelProducts(String apiKey, SalesFunnelRequest request) throws IOException {
            calls.incrementAndGet();
            throw new com.tuandev.fbsbarcode.integration.wb.WbApiException("Rate limited", 429, "rate");
        }
    }

    private static final class FakeRepository extends DashboardRepository {
        @Override
        public DashboardKpis loadKpis(int shopId) {
            return new DashboardKpis(1, 0, 0);
        }

        @Override
        public Map<Long, DashboardProductInfo> findProductInfo(int shopId, java.util.List<Long> nmIds) {
            return Map.of();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
