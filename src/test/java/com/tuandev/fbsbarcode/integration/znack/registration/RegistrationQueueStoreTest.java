package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

class RegistrationQueueStoreTest {
    @TempDir Path directory;
    @BeforeEach void setup() throws Exception {
        System.setProperty("wcode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'test','test')");
        }
    }
    @AfterEach void cleanup() { System.clearProperty("wcode.appdata.dir"); }
    @Test void publicationRetainsOriginalAccountIdentityEvenAfterQueueFinishes() {
        var store = new RegistrationQueueStore();
        assertNull(store.credentialFingerprint(1, 1));
        var sku = RegistrationSelectionTest.sku(1, Status.NOT_CREATED);
        var draft = new Draft("6104", "6104", 1, "Trousers", "Brand", Map.of(), Map.of());
        assertTrue(store.enqueue(1, sku, draft, "original-account", false));
        store.phase(1, 1, "DONE");
        assertEquals("original-account", store.credentialFingerprint(1, 1));
        assertNull(store.credentialFingerprint(2, 1));
    }
    @Test void checkpointsQueueAndRejectsDoubleClicksAcrossInstances() {
        var sku = RegistrationSelectionTest.sku(1, Status.NOT_CREATED);
        var draft = new Draft("6104", "6104", 1, "Trousers", "Brand", Map.of(), Map.of());
        var store = new RegistrationQueueStore();
        assertTrue(store.enqueue(1, sku, draft, "credential-fingerprint", false));
        assertFalse(new RegistrationQueueStore().enqueue(1, sku, draft, "credential-fingerprint", false));
        var job = store.pending().getFirst();
        assertEquals(1, job.shopId());
        assertEquals(draft, job.draft());
        assertEquals(sku, job.sku());
        store.phase(1, 1, "RUNNING");
        store.recoverInterrupted();
        assertTrue(store.pending().isEmpty(), "Uncertain allocation must not generate another GTIN on restart");
        assertEquals("PAUSED", store.phase(1, 1));
    }
    @Test void processedRegistrationCannotBeRequeuedAsNew() {
        var sku = RegistrationSelectionTest.sku(1, Status.NOT_CREATED);
        var draft = new Draft("6104", "6104", 1, "Trousers", "Brand", Map.of(), Map.of());
        var repository = new ZnackCardRegistrationRepository();
        repository.saveGenerated(1, sku, "04631993764363", "6104", 1, "Trousers", "{}");
        assertFalse(new RegistrationQueueStore().enqueue(1, sku, draft, "fingerprint", false));
        repository.updateProgress(1, 1, Status.PUBLISHED, "feed", 1L, null, true);
        assertFalse(new RegistrationQueueStore().enqueue(1, sku, draft, "fingerprint", true));
    }
    @Test void exactMappingKeepsSizesAndShopsSeparateAndBlocksUnsignedCards() throws Exception {
        var repository = new ZnackCardRegistrationRepository();
        var sku = RegistrationSelectionTest.sku(1, Status.NOT_CREATED);
        repository.saveGenerated(1, sku, "04631993764363", "6104", 1, "Trousers", "{}");
        var mappings = new com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository();
        assertThrows(IllegalStateException.class, () -> mappings.registeredGtin(1, 10, sku.sourceBarcode()));
        repository.updateProgress(1, 1, Status.PUBLISHED, "feed", 1L, null, true);
        assertEquals("04631993764363", mappings.registeredGtin(1, 10, sku.sourceBarcode()));
        assertEquals("04631993764363", mappings.registeredGtin(1, 10, "04631993764363"));
        assertNull(mappings.registeredGtin(1, 11, sku.sourceBarcode()));
        assertNull(mappings.registeredGtin(2, 10, sku.sourceBarcode()));
        assertNull(mappings.registeredGtin(1, 10, "another-size"));
    }
    @Test void publicationRetriesReadBackAfterAnUncertainWbWriteWithoutAppendingTwice() throws Exception {
        var sku = RegistrationSelectionTest.sku(1, Status.NOT_CREATED);
        var repository = new ZnackCardRegistrationRepository();
        repository.saveGenerated(1, sku, "04631993764363", "6104", 1, "Trousers", "{}");
        var card = com.google.gson.JsonParser.parseString("""
                {"nmID":10,"vendorCode":"ART","brand":"Brand","title":"Trousers","description":"",
                "dimensions":{"length":1,"width":1,"height":1,"weightBrutto":0.1},"characteristics":[],"kizMarked":true,
                "sizes":[{"chrtID":1,"skus":["old"]}]}
                """).getAsJsonObject();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var wb = new com.tuandev.fbsbarcode.integration.wb.WbApiClient() {
            com.google.gson.JsonObject remote = card;
            @Override public com.google.gson.JsonObject findProductCard(String token, long nmId) { return remote.deepCopy(); }
            @Override public void updateProductCard(String token, com.google.gson.JsonObject payload) throws java.io.IOException {
                remote = payload.deepCopy(); writes.incrementAndGet(); throw new java.io.IOException("timeout after acceptance");
            }
        };
        var writer = new RegistrationPublicationMonitor(wb);
        var shop = new com.tuandev.fbsbarcode.models.Shop(1, "test", "test");
        assertThrows(java.io.IOException.class, () -> writer.writeBack(shop, sku, "04631993764363"));
        assertTrue(writer.writeBack(shop, sku, "04631993764363"));
        assertEquals(1, writes.get());
    }

    @Test void printIntentSurvivesRestartAndKeepsItsOriginalQuantityAndIdempotencyKey() {
        var store = new WbPrintDemandStore();
        var first = store.create(1, "04631993764363", "FBS:[1,2,3]", 3);
        assertEquals(first, new WbPrintDemandStore().create(1, "04631993764363", "FBS:[1,2,3]", 9));
        assertNotEquals(first.requestKey(), store.create(1, "04631993764363", "FBS:[4]", 1).requestKey());
        store.complete(1, "04631993764363", "FBS:[1,2,3]");
        assertNull(store.find(1, "04631993764363", "FBS:[1,2,3]"));
        assertNotNull(store.find(1, "04631993764363", "FBS:[4]"));
    }

    @Test void unresolvedPurchaseBlocksAnotherAutomaticPurchaseEvenAfterAnIntroductionFailure() {
        var repository = new com.tuandev.fbsbarcode.integration.znack.ZnackRepository(
                new com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext(1, "test"));
        String gtin = "04631993764363";
        repository.upsertProducts(java.util.List.of(new com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product(gtin,"test","6104",null,null,null,null)));
        long id = repository.enqueuePipeline(gtin, 3, java.util.UUID.randomUUID().toString());
        var store = new WbPrintDemandStore();
        assertEquals(id, store.outstandingPipeline(1, gtin));
        repository.updatePipeline(id, null, com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage.INTRODUCTION_FAILED, "fixture");
        assertEquals(id, store.outstandingPipeline(1, gtin));
        repository.updatePipeline(id, null, com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage.INTRODUCED, null);
        assertNull(store.outstandingPipeline(1, gtin));
        long older = repository.enqueuePipeline(gtin, 2, java.util.UUID.randomUUID().toString());
        long newer = repository.enqueuePipeline(gtin, 1, java.util.UUID.randomUUID().toString());
        repository.updatePipeline(newer, null, com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage.INTRODUCED, null);
        assertEquals(older, store.outstandingPipeline(1, gtin), "An older active batch still blocks duplicate buying");
    }
}
