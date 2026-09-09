package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.integration.wb.WbApiClient;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Only observes user signatures; never generates GTINs, submits feeds or signs cards. */
final class RegistrationPublicationMonitor {
    private final RegistrationPublicationStore store = new RegistrationPublicationStore();
    private final ZnackCardRegistrationRepository registrations = new ZnackCardRegistrationRepository();
    private final WbApiClient wb;
    RegistrationPublicationMonitor(WbApiClient wb) { this.wb = wb; }

    void tick() {
        for (var target : store.due(ZnackSigningSession.authorizedShopIds())) {
            if (!ZnackSigningSession.isShopAuthorized(target.shopId())) continue;
            Shop shop = new ShopRepository().findById(target.shopId());
            if (shop == null || shop.getMarketplace() != com.tuandev.fbsbarcode.integration.marketplace.Marketplace.WILDBERRIES) continue;
            Sku sku = registrations.find(target.shopId(), target.chrtId());
            store.schedule(target.shopId(), target.chrtId(), false, Duration.ofMinutes(2));
            if (sku == null) continue;
            boolean verifiedPublication = false;
            try {
                var settings = RegistrationRunner.settings(shop);
                String savedIdentity = new RegistrationQueueStore().credentialFingerprint(shop.getId(), sku.chrtId());
                if (savedIdentity != null && !savedIdentity.equals(RegistrationRunner.fingerprint(shop, settings)))
                    throw new IllegalArgumentException("Registration account identity changed; review this card before updating WB.");
                var session = RegistrationRunner.session(shop, settings);
                String token = session.auth().trueApiToken(settings);
                if (sku.status() != Status.ERROR && sku.status() != Status.PUBLISHED && sku.status() != Status.WB_UPDATE_PENDING) {
                    var progress = session.catalog().progress(token, sku.feedId(), sku.gtin());
                    if (progress.failed()) {
                        registrations.updateProgress(shop.getId(), sku.chrtId(), Status.ERROR, null, progress.goodId(), progress.errorMessage(), null);
                        store.schedule(shop.getId(), sku.chrtId(), false, Duration.ofDays(1));
                        return;
                    }
                    if (!progress.signed() && !progress.readyToSign()) {
                        registrations.updateProgress(shop.getId(), sku.chrtId(), progress.readyToSign() ? Status.READY_TO_SIGN : Status.PROCESSING,
                                null, progress.goodId(), null, null);
                        return;
                    }
                }
                var response = new ZnackApiClient().productCards(settings.resolvedTrueApiBaseUrl(), token, sku.gtin());
                var publication = RegistrationPublication.parse(response, sku.gtin(), session.auth().resolvedParticipantInn(settings));
                if (sku.goodId() != null && sku.goodId() != publication.goodId())
                    throw new IllegalArgumentException("National Catalog good_id changed; review the registered card.");
                if (!publication.published()) {
                    registrations.updateProgress(shop.getId(), sku.chrtId(), publication.needsSignature() ? Status.READY_TO_SIGN : Status.PROCESSING,
                            null, publication.goodId(), null, null);
                    return;
                }
                verifiedPublication = true;
                // Recheck the persisted signing identity before accepting a delayed response.
                Shop latestShop = new ShopRepository().findById(shop.getId());
                if (latestShop == null || !session.fingerprint().equals(RegistrationRunner.fingerprint(latestShop, RegistrationRunner.settings(latestShop))))
                    throw new IllegalArgumentException("Signing identity changed while checking publication.");
                syncProduct(shop, sku, publication, settings);
                boolean confirmed = writeBack(shop, sku, sku.gtin());
                registrations.updateProgress(shop.getId(), sku.chrtId(), confirmed ? Status.PUBLISHED : Status.WB_UPDATE_PENDING,
                        null, publication.goodId(), null, confirmed);
                store.schedule(shop.getId(), sku.chrtId(), publication.readyForKiz(), Duration.ofMinutes(2));
            } catch (Exception error) {
                registrations.updateProgress(shop.getId(), sku.chrtId(), verifiedPublication ? Status.WB_UPDATE_PENDING : sku.status(), null, null,
                        ZnackErrorDetails.summary(error), null);
                store.schedule(shop.getId(), sku.chrtId(), false, Duration.ofMinutes(10));
            }
            return; // One remote reconciliation per tick, independent of visible tab/page.
        }
    }

    boolean writeBack(Shop shop, Sku sku, String gtin) throws java.io.IOException {
        store.schedule(shop.getId(), sku.chrtId(), false, Duration.ofMinutes(2));
        JsonObject current = wb.findProductCard(shop.getApiKey(), sku.nmId());
        if (contains(current, sku, gtin)) return true;
        JsonObject payload = WbRegisteredGtinWriter.append(current, sku.nmId(), sku.chrtId(), gtin);
        if (!store.mayWrite(shop.getId(), sku.chrtId())) return false;
        Shop latestShop = new ShopRepository().findById(shop.getId());
        if (latestShop == null || !RegistrationRunner.fingerprint(shop, RegistrationRunner.settings(shop))
                .equals(RegistrationRunner.fingerprint(latestShop, RegistrationRunner.settings(latestShop))))
            throw new java.io.IOException("Shop credentials changed before WB update.");
        store.beforeWrite(shop.getId(), sku.chrtId());
        wb.updateProductCard(shop.getApiKey(), payload);
        return contains(wb.findProductCard(shop.getApiKey(), sku.nmId()), sku, gtin);
    }
    private static boolean contains(JsonObject card, Sku sku, String gtin) {
        // Validate identity even when the barcode appears present.
        var expected = WbRegisteredGtinWriter.append(card, sku.nmId(), sku.chrtId(), gtin);
        return expected.get("sizes").equals(card.get("sizes"));
    }
    private void syncProduct(Shop shop, Sku sku, RegistrationPublication publication, ZnackModels.Settings settings) {
        var repository = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName()));
        var card = publication.card();
        String tnved = "";
        if (card.has("good_attrs")) for (var attr : card.getAsJsonArray("good_attrs")) {
            var a = attr.getAsJsonObject();
            if (a.has("attr_id") && a.get("attr_id").getAsLong() == 13933)
                tnved = RegistrationPublication.text(a, "attr_value");
        }
        repository.upsertProducts(List.of(new ZnackModels.Product(GtinNormalizer.normalize(sku.gtin()),
                RegistrationPublication.text(card, "good_name"), tnved, null, null, null, null,
                RegistrationPublication.bool(card, "good_mark_flag"), RegistrationPublication.bool(card, "good_turn_flag"), "published", "published", sku.subjectName(), Instant.now())), settings);
        repository.updateProductDocuments(sku.gtin(), ZnackPermitDocumentParser.fromProductCard(card));
        var metadata = ZnackProductLabelMetadataParser.fromProductCard(card);
        repository.updateProductLabelMetadata(sku.gtin(), metadata.gender(), metadata.size());
    }
}
