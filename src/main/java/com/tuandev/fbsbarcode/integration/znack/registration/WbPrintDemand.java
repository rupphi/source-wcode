package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.I18nService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/** Called only by explicit WB print actions. Never by sync, publication or screen refresh. */
public final class WbPrintDemand {
    private static final Object PURCHASE_LOCK = new Object();
    private final WbPrintDemandStore store = new WbPrintDemandStore();
    private final ZnackGtinInventoryService inventory = new ZnackGtinInventoryService();

    static int missing(int requested, int available, boolean pending) {
        if (requested < 1 || available < 0) throw new IllegalArgumentException("Invalid print quantity.");
        return pending ? 0 : Math.max(0, requested - available);
    }

    public void awaitAvailable(Shop shop, String gtin, int quantity, String demandKey) throws IOException {
        com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard.requireWildberries(shop);
        if (quantity < 1 || demandKey == null || demandKey.isBlank()) throw new IllegalArgumentException("Missing print demand.");
        ZnackSigningSession.authorizeShop(shop.getId());
        var repository = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName()));
        Instant deadline = Instant.now().plus(Duration.ofMinutes(15));
        boolean resumed = false;
        while (inventory.availableCount(shop.getId(), gtin) < quantity) {
            if (Thread.currentThread().isInterrupted()) throw new IOException(message("wb.print.auto_cancelled", gtin));
            try {
                synchronized (PURCHASE_LOCK) {
                    int available = inventory.availableCount(shop.getId(), gtin);
                    if (available >= quantity) break;
                    var settings = repository.getSettings();
                    if (!settings.autoIntroduction()) {
                        settings = settings.withAutoIntroduction(true);
                        repository.saveSettings(settings);
                    }
                    var intent = store.find(shop.getId(), gtin, demandKey);
                    var own = intent == null ? null : repository.findPipelineByRequestKey(intent.requestKey()).orElse(null);
                    if (own != null && own.stage() == ZnackModels.PurchaseStage.INTRODUCED) {
                        store.complete(shop.getId(), gtin, demandKey);
                        intent = null; own = null; // A completed smaller wave may not cover this print demand.
                    }
                    Long outstanding = own != null ? Long.valueOf(own.id()) : store.outstandingPipeline(shop.getId(), gtin);
                    if (outstanding != null) {
                        var pipeline = repository.findPipeline(outstanding).orElseThrow();
                        if (!pipeline.active()) throw new IllegalStateException(message("wb.print.auto_action_required", gtin));
                        if (!resumed) { ZnackPurchaseCoordinator.create(repository).resumeAsync(settings); resumed = true; }
                    } else {
                        verifyReady(shop, gtin, settings);
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Print wait cancelled");
                        if (intent == null) intent = store.create(shop.getId(), gtin, demandKey, missing(quantity, available, false));
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Print wait cancelled");
                        ZnackPurchaseCoordinator.create(repository).enqueue(settings, gtin, intent.quantity(), intent.requestKey());
                    }
                }
                if (Instant.now().isAfter(deadline)) throw new IllegalStateException(message("wb.print.auto_pending", gtin));
                Thread.sleep(2000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException(message("wb.print.auto_cancelled", gtin), error);
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException(error.getMessage(), error); }
        }
        // Only local intent bookkeeping is removed. The idempotent purchase history remains durable.
        store.complete(shop.getId(), gtin, demandKey);
        if (Thread.currentThread().isInterrupted()) throw new IOException(message("wb.print.auto_cancelled", gtin));
    }

    private static void verifyReady(Shop shop, String gtin, ZnackModels.Settings settings) throws Exception {
        var session = RegistrationRunner.session(shop, settings);
        var response = new ZnackApiClient().productCards(settings.resolvedTrueApiBaseUrl(), session.auth().trueApiToken(settings), gtin);
        var publication = RegistrationPublication.parse(response, gtin, session.auth().resolvedParticipantInn(settings));
        boolean hasCardPermit = !ZnackPermitDocumentParser.selectForCirculation(
                ZnackPermitDocumentParser.fromProductCard(publication.card())).isEmpty();
        boolean hasConfiguredPermit = settings.hasDefaultGoodsDocument() || !RegistrationDocuments.load(shop.getId(), settings).isEmpty();
        if (!publication.readyForKiz() || (!hasCardPermit && !hasConfiguredPermit))
            throw new IllegalStateException(message("wb.print.auto_not_ready", gtin));
        var latest = new com.tuandev.fbsbarcode.features.shop.ShopRepository().findById(shop.getId());
        if (latest == null || !session.fingerprint().equals(RegistrationRunner.fingerprint(latest, RegistrationRunner.settings(latest))))
            throw new IllegalStateException(message("wb.print.auto_action_required", gtin));
    }
    private static String message(String key, String gtin) {
        return java.text.MessageFormat.format(I18nService.getInstance().tr(key), gtin);
    }
}
