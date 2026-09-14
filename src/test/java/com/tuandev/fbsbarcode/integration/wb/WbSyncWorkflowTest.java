package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WbSyncWorkflowTest {

    @Test
    void overviewSyncRefreshesProductsEvenAfterInitialCatalogSync() throws Exception {
        CountingProductSyncService products = new CountingProductSyncService();
        NoopSupplySyncService supplies = new NoopSupplySyncService();
        WbShopSyncState existingState = new WbShopSyncState(
                "2026-09-01T10:00:00Z", 123L, "2026-09-01T10:01:00Z",
                10L, "2026-09-01T10:02:00Z", 0L, null, null, null, null);
        WbSyncWorkflow workflow = new WbSyncWorkflow(
                products, supplies, new WbOrderSyncService(), new StubSyncStateRepository(existingState));

        workflow.syncOverview(new Shop(7, "WB", "token"));

        assertEquals(1, products.calls);
    }

    private static final class CountingProductSyncService extends WbProductSyncService {
        private int calls;

        @Override
        public int sync(Shop shop) {
            calls++;
            return 1;
        }
    }

    private static final class NoopSupplySyncService extends WbSupplySyncService {
        @Override
        public int syncIncremental(Shop shop) {
            return 0;
        }

        @Override
        public int syncOpenSupplyDetails(Shop shop) {
            return 0;
        }

        @Override
        public int syncOpenSupplyCounts(Shop shop) {
            return 0;
        }
    }

    private static final class StubSyncStateRepository extends WbSyncStateRepository {
        private final WbShopSyncState state;

        private StubSyncStateRepository(WbShopSyncState state) {
            this.state = state;
        }

        @Override
        public WbShopSyncState getShopSyncState(int shopId) {
            return state;
        }
    }
}
