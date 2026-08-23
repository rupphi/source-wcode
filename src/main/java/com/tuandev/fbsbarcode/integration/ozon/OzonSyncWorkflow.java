package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;

public final class OzonSyncWorkflow {
    public OzonConnectionCheck checkConnection(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        return client(shop).checkConnection();
    }

    public OzonSyncReport syncOverview(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        OzonApiClient api = client(shop);
        int products = new OzonCatalogSyncService(shop.getId(), api).sync();
        OzonSyncReport postings = new OzonPostingSyncService(shop.getId(), api).sync();
        return new OzonSyncReport(products, postings.postings(), postings.items());
    }

    private static OzonApiClient client(Shop shop) {
        return new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
    }
}
