package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;

public class WbProductSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbProductSyncService.class);
    private static final int PAGE_LIMIT = 100;

    private final WbApiClient apiClient;
    private final WbProductRepository productRepository;
    private final WbSyncStateRepository syncStateRepository;
    private final WbSyncRunRepository syncRunRepository;

    public WbProductSyncService() {
        this(new WbApiClient(), new WbProductRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());
    }

    WbProductSyncService(WbApiClient apiClient, WbProductRepository productRepository, WbSyncStateRepository syncStateRepository, WbSyncRunRepository syncRunRepository) {
        this.apiClient = apiClient;
        this.productRepository = productRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncRunRepository = syncRunRepository;
    }

    public int sync(Shop shop) throws IOException {
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "products");
        int read = 0;
        int written = 0;
        String cursorUpdatedAt = state.productsCursorUpdatedAt();
        Long cursorNmId = state.productsCursorNmId();
        try {
            while (true) {
                WbProductCardsResponse response = apiClient.getProductCards(shop.getApiKey(), "ru", cursorUpdatedAt, cursorNmId, PAGE_LIMIT);
                if (response == null || response.getCards() == null || response.getCards().isEmpty()) {
                    syncStateRepository.updateProductsCursor(shop.getId(), cursorUpdatedAt, cursorNmId, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                productRepository.saveProductBatch(shop.getId(), response.getCards());
                read += response.getCards().size();
                written += response.getCards().size();

                if (response.getCursor() != null) {
                    cursorUpdatedAt = response.getCursor().getUpdatedAt();
                    cursorNmId = response.getCursor().getNmID();
                }

                syncStateRepository.updateProductsCursor(shop.getId(), cursorUpdatedAt, cursorNmId, Instant.now().toString());
                Integer total = response.getCursor() == null ? null : response.getCursor().getTotal();
                if (total == null || total < PAGE_LIMIT) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync products thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }
}
