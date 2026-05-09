package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class WbSupplySyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbSupplySyncService.class);
    private static final int PAGE_LIMIT = 1000;
    private static final int RECENT_SUPPLY_DETAIL_LIMIT = 50;
    private static final int OPEN_SUPPLY_DETAIL_LIMIT = 20;

    private final WbApiClient apiClient;
    private final WbSupplyRepository supplyRepository;
    private final WbSyncStateRepository syncStateRepository;
    private final WbSyncRunRepository syncRunRepository;

    public WbSupplySyncService() {
        this(new WbApiClient(), new WbSupplyRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());
    }

    WbSupplySyncService(WbApiClient apiClient, WbSupplyRepository supplyRepository, WbSyncStateRepository syncStateRepository, WbSyncRunRepository syncRunRepository) {
        this.apiClient = apiClient;
        this.supplyRepository = supplyRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncRunRepository = syncRunRepository;
    }

    public int sync(Shop shop) throws IOException {
        return sync(shop, false);
    }

    public int syncIncremental(Shop shop) throws IOException {
        return sync(shop, true);
    }

    private int sync(Shop shop, boolean singlePageOnly) throws IOException {
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies");
        int read = 0;
        int written = 0;
        long next = Math.max(0L, state.suppliesNext());
        try {
            while (true) {
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                if (response == null || response.getSupplies() == null || response.getSupplies().isEmpty()) {
                    syncStateRepository.updateSuppliesCursor(shop.getId(), next, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                supplyRepository.saveSupplies(shop.getId(), response.getSupplies());
                read += response.getSupplies().size();
                written += response.getSupplies().size();
                if (response.getNext() != null) {
                    next = response.getNext();
                }
                syncStateRepository.updateSuppliesCursor(shop.getId(), next, Instant.now().toString());
                if (singlePageOnly || response.getSupplies().size() < PAGE_LIMIT) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync supplies thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    public int syncRecentSupplyDetails(Shop shop) throws IOException {
        List<String> supplyIds = supplyRepository.getRecentSupplyIds(shop.getId(), RECENT_SUPPLY_DETAIL_LIMIT);
        return syncSupplyDetails(shop, supplyIds);
    }

    public int syncOpenSupplyDetails(Shop shop) throws IOException {
        List<String> supplyIds = supplyRepository.getOpenSupplyIds(shop.getId(), OPEN_SUPPLY_DETAIL_LIMIT);
        return syncSupplyDetails(shop, supplyIds);
    }

    private int syncSupplyDetails(Shop shop, List<String> supplyIds) throws IOException {
        if (supplyIds.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (String supplyId : supplyIds) {
            WbSupplyDto detail = apiClient.getSupplyDetail(shop.getApiKey(), supplyId);
            if (detail != null) {
                supplyRepository.saveSupplies(shop.getId(), List.of(detail));
                written++;
            }
        }
        return written;
    }
}
