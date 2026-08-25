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
        OzonPostingSyncService postingSync = new OzonPostingSyncService(shop.getId(), api);
        OzonSyncReport postings = postingSync.sync();
        postingSync.refreshActiveDetails();
        return new OzonSyncReport(products, postings.postings(), postings.items());
    }

    /** Refreshes one posting before a print decision so stale list data cannot omit a KIZ rule. */
    public OzonPostingDto refreshPosting(Shop shop, String postingNumber) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        return new OzonPostingSyncService(shop.getId(), client(shop)).refresh(postingNumber, false);
    }

    private static OzonApiClient client(Shop shop) {
        return new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
    }
}
