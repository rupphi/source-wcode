package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.models.Kiz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ZnackGtinWorkflowTest {
    private static final String A = "04601234567890";
    private static final String B = "04601234567891";
    private static final String NORMALIZED_CIS = "010460123456789021abcdefghijklm";
    private static final String RAW_CIS = NORMALIZED_CIS + "\u001D91ABCD\u001D92signature";
    @TempDir Path temp;
    private ZnackRepository repository;
    private KizMappingRepository mappings;

    @BeforeEach void init() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'A','a'),(2,'B','b')");
            st.execute("INSERT INTO wb_product_cards(shop_id,nm_id,subject_name,need_kiz,synced_at) VALUES(1,1,'Shoes',1,'now'),(1,2,'Shoes',1,'now'),(2,3,'Shoes',1,'now')");
            st.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,1,204557,'Gender','[\"Male\"]')");
        }
        repository = new ZnackRepository(new ShopContext(1, "A"));
        repository.upsertProducts(List.of(new Product(A,"A",null,null,null,null,null), new Product(B,"B",null,null,null,null,null)));
        new ZnackRepository(new ShopContext(2, "B")).upsertProducts(List.of(new Product(A,"A",null,null,null,null,null)));
        mappings = new KizMappingRepository();
    }

    @AfterEach void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test void wildcardIncludesExistingAndNewOrUnspecifiedGendersAndIsShopScoped() {
        mappings.replaceRulesForGtin(1, A, List.of(new ZnackGtinMappingSelection("Shoes", null, true)));
        assertEquals(A, mappings.findMappings(1, List.of(1L,2L)).get(1L));
        assertEquals(A, mappings.findMappings(1, List.of(1L,2L)).get(2L));
        assertTrue(mappings.findMappings(2, List.of(3L)).isEmpty());
    }

    @Test void exactGenderCanSplitAcrossGtinsAndConflictIsBlocked() {
        mappings.replaceRulesForGtin(1, A, List.of(new ZnackGtinMappingSelection("Shoes","Male",false)));
        mappings.replaceRulesForGtin(1, B, List.of(new ZnackGtinMappingSelection("Shoes",KizMappingRepository.UNSPECIFIED_GENDER,false)));
        assertEquals(A, mappings.findMappings(1,List.of(1L)).get(1L));
        assertEquals(B, mappings.findMappings(1,List.of(2L)).get(2L));
        assertThrows(IllegalStateException.class, () -> mappings.replaceRulesForGtin(1, B,
                List.of(new ZnackGtinMappingSelection("Shoes","Male",false))));
    }

    @Test void oneGtinCanOwnMultipleCategoryGenderPairsWhileAnotherOwnsOtherPairs() throws Exception {
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id,nm_id,subject_name,need_kiz,synced_at) VALUES
                    (1,10,'Pants',1,'now'),(1,11,'Pants',1,'now'),
                    (1,12,'Sports pants',1,'now'),(1,13,'Sports pants',1,'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES
                    (1,10,204557,'Gender','["Female"]'),(1,11,204557,'Gender','["Male"]'),
                    (1,12,204557,'Gender','["Female"]'),(1,13,204557,'Gender','["Male"]')
                    """);
        }
        mappings.replaceRulesForGtin(1, A, List.of(
                new ZnackGtinMappingSelection("Pants", "Female", false),
                new ZnackGtinMappingSelection("Sports pants", null, true)));
        mappings.replaceRulesForGtin(1, B, List.of(new ZnackGtinMappingSelection("Pants", "Male", false)));

        Map<Long, String> resolved = mappings.findMappings(1, List.of(10L, 11L, 12L, 13L));
        assertEquals(A, resolved.get(10L));
        assertEquals(B, resolved.get(11L));
        assertEquals(A, resolved.get(12L));
        assertEquals(A, resolved.get(13L));
        assertEquals(2, mappings.findRulesForGtin(1, A).size());
        assertThrows(IllegalStateException.class, () -> mappings.replaceRulesForGtin(1, B,
                List.of(new ZnackGtinMappingSelection("Sports pants", "Male", false))));
    }

    @Test void inventoryReservationConsumptionAndReleaseNeverDuplicate() throws Exception {
        long order = repository.createDraft(A, 2);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("one","two"), "b"));
        ZnackGtinInventoryService inventory = new ZnackGtinInventoryService();
        List<Kiz> first = inventory.reserveAvailable(1, A, 1, "first");
        List<Kiz> second = inventory.reserveAvailable(1, A, 1, "second");
        assertNotEquals(first.getFirst().getId(), second.getFirst().getId());
        assertThrows(IllegalStateException.class, () -> inventory.reserveAvailable(1, A, 1));
        inventory.consume(1, first);
        inventory.release(1, second);
        assertEquals(1, inventory.availableCount(1, A));
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM kiz_codes WHERE status='CONSUMED'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test void staleReservationCannotConsumeCodeAfterItWasReleasedAndReservedAgain() {
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("one"), "b"));
        ZnackGtinInventoryService inventory = new ZnackGtinInventoryService();
        List<Kiz> stale = inventory.reserveAvailable(1, A, 1, "stale");
        inventory.release(1, stale);
        List<Kiz> current = inventory.reserveAvailable(1, A, 1, "current");

        assertThrows(IllegalStateException.class, () -> inventory.consume(1, stale));
        assertEquals(0, inventory.availableCount(1, A));
        assertEquals(1, inventory.consume(1, current));
    }

    @Test void releaseRejectsMissingReservationTokenInsteadOfSilentlyLeakingCode() throws Exception {
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("one"), "b"));
        ZnackGtinInventoryService inventory = new ZnackGtinInventoryService();
        List<Kiz> reserved = inventory.reserveAvailable(1, A, 1, "owned");

        assertThrows(IllegalStateException.class,
                () -> inventory.release(1, List.of(new Kiz(reserved.getFirst().getId(), reserved.getFirst().getCode()))));
        assertEquals(0, inventory.availableCount(1, A));
        assertEquals(1, inventory.release(1, reserved));
        assertEquals(1, inventory.availableCount(1, A));
    }

    @Test void startupRecoveryReleasesOnlyReservationsCreatedByTheRecoverableLifecycle() throws Exception {
        long order = repository.createDraft(A, 2);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("recoverable", "legacy-reserved"), "b"));
        ZnackGtinInventoryService inventory = new ZnackGtinInventoryService();
        inventory.reserveAvailable(1, A, 1, "recoverable-token");
        List<Kiz> legacy = inventory.reserveAvailable(1, A, 1, "legacy-token");
        try (Connection c = Database.getConnection(); var ps = c.prepareStatement(
                "UPDATE kiz_codes SET reservation_recoverable=NULL WHERE id=?")) {
            ps.setInt(1, legacy.getFirst().getId());
            ps.executeUpdate();
        }

        assertEquals(1, inventory.releaseRecoverableReservations());
        assertEquals(1, inventory.availableCount(1, A));
        assertEquals(1, inventory.consume(1, legacy));
    }

    @Test void existingCrossShopDuplicateCodesAreArchivedBeforeGlobalUniquenessIsEnforced() throws Exception {
        ZnackRepository other = new ZnackRepository(new ShopContext(2, "B"));
        long firstOrder = repository.createDraft(A, 1);
        long secondOrder = other.createDraft(A, 1);
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP INDEX uq_kiz_codes_raw_code");
        }
        assertEquals(1, repository.insertCodes(firstOrder, A, new DownloadedCodes(List.of("same"), "first")));
        assertEquals(1, other.insertCodes(secondOrder, A, new DownloadedCodes(List.of("same"), "second")));

        Database.initDatabase();

        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM kiz_codes WHERE raw_code='same'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM znack_duplicate_kiz_code_audit WHERE raw_code='same'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
        assertEquals(0, other.insertCodes(secondOrder, A, new DownloadedCodes(List.of("same"), "again")));
    }

    @Test void reservationCannotBeConsumedThroughAnotherShop() {
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("one"), "b"));
        ZnackGtinInventoryService inventory = new ZnackGtinInventoryService();
        List<Kiz> reserved = inventory.reserveAvailable(1, A, 1, "shop-one");

        assertThrows(IllegalStateException.class, () -> inventory.consume(2, reserved));
        assertEquals(0, inventory.availableCount(1, A));
        assertEquals(1, inventory.consume(1, reserved));
    }

    @Test void gtinIsAlwaysStoredAsFourteenDigitText() {
        repository.upsertProducts(List.of(new Product("123", "short", null, null, null, null, null)));
        assertTrue(repository.findProducts().stream().anyMatch(p -> p.gtin().equals("00000000000123")));
        assertThrows(IllegalArgumentException.class, () -> GtinNormalizer.normalize("123456789012345"));
        assertThrows(IllegalArgumentException.class, () -> GtinNormalizer.normalize("１２３"));
        assertTrue(GtinNormalizer.isTechnicalRange("02900699308808"));
        assertFalse(GtinNormalizer.isTechnicalRange(A));
    }

    @Test void productionPurchaseRejectsTechnical029GtinBeforeCreatingPipeline() {
        String technical = "02900699308808";
        repository.upsertProducts(List.of(new Product(technical, "technical", null, null, null, null, null)));
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> coordinator.start(testedSettings(), technical, 1));

        assertEquals(GtinNormalizer.TECHNICAL_GTIN_PURCHASE_UNSUPPORTED, error.getMessage());
        assertTrue(repository.findActivePipeline(technical).isEmpty());
    }

    @Test void technicalGtinWithAuditHistoryIsRetainedButHiddenFromOperationalLists() throws Exception {
        String technical = "02900699308808";
        repository.upsertProducts(List.of(new Product(technical, "technical", null, null, null, null, null)));
        repository.createDraft(technical, 1);
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO znack_gtin_mapping_rules
                    (shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at)
                    VALUES(1,'02900699308808','Shoes','*',1,'now','now')
                    """);
        }

        assertEquals(0, repository.pruneTechnicalProducts());
        assertTrue(repository.findProducts().stream().noneMatch(product -> technical.equals(product.gtin())));
        assertTrue(mappings.findGtinSummaries(1).stream().noneMatch(summary -> technical.equals(summary.gtin())));
        assertTrue(mappings.findMappings(1, List.of(1L)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> mappings.replaceRulesForGtin(1, technical,
                List.of(new ZnackGtinMappingSelection("Shoes", null, true))));
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM znack_products WHERE shop_id=1 AND gtin='" + technical + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test void legacyLegalStatusesAreKeptForAuditButMadeUnavailableToInventory() throws Exception {
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("legacy"), "b"));
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE kiz_codes SET status='PRINTED',legal_status=NULL WHERE raw_code='legacy'");
            ZnackSchemaSupport.initialize(c);
        }
        KizCode code = repository.findCodes(order).getFirst();
        assertEquals(KizInventoryStatus.CONSUMED, code.inventoryStatus());
        assertEquals(KizLegalStatus.PRINTED, code.legalStatus());
        assertEquals(0, new ZnackGtinInventoryService().availableCount(1, A));
    }

    @Test void purchasePipelinePersistsResumesAndPreventsDuplicatePurchase() throws Exception {
        Settings settings = testedSettings();
        AtomicInteger buys = new AtomicInteger();
        ZnackKizOrderService orderService = new ZnackKizOrderService(null,null,null,repository) {
            @Override public KizOrder buy(Settings ignored, String gtin, int quantity) {
                buys.incrementAndGet();
                long id = repository.createDraft(gtin, quantity);
                repository.updateOrder(id, "external", "CREATED", OrderStatus.SUBMITTED, null);
                return repository.findOrder(id).orElseThrow();
            }
            @Override public KizOrder refresh(Settings ignored, long id) {
                repository.updateOrder(id, null, "READY", OrderStatus.CODES_READY, null);
                return repository.findOrder(id).orElseThrow();
            }
        };
        ZnackKizCodeService codeService = new ZnackKizCodeService(null,null,repository) {
            @Override public int download(Settings ignored, long id) {
                KizOrder order = repository.findOrder(id).orElseThrow();
                int inserted = repository.insertCodes(id, order.gtin(), new DownloadedCodes(List.of("pipeline-code"), "b"));
                repository.updateOrder(id, null, "READY", OrderStatus.CODES_DOWNLOADED, null);
                return inserted;
            }
        };
        ZnackPurchaseCoordinator first = new ZnackPurchaseCoordinator(repository, orderService, codeService, null);
        long pipeline = first.start(settings, A, 1);
        assertThrows(IllegalStateException.class, () -> first.start(settings, A, 1));
        new ZnackPurchaseCoordinator(repository, orderService, codeService, null).resume(settings);
        assertEquals(PurchaseStage.COMPLETED, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(1, buys.get());
        assertEquals(1, new ZnackGtinInventoryService().availableCount(1, A));
    }

    @Test void ambiguousCreateIsPersistedAndNeverRetriedAutomatically() throws Exception {
        Settings settings = testedSettings();
        AtomicInteger buys = new AtomicInteger();
        ZnackKizOrderService orderService = new ZnackKizOrderService(null,null,null,repository) {
            @Override public KizOrder buy(Settings ignored, String gtin, int quantity) throws Exception {
                buys.incrementAndGet();
                throw new ZnackOrderCreationAmbiguousException("ambiguous network response", new java.io.IOException());
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, orderService, null, null);
        assertThrows(ZnackOrderCreationAmbiguousException.class, () -> coordinator.start(settings, A, 1));
        assertEquals(PurchaseStage.CREATING_ORDER, repository.findActivePipeline(A).orElseThrow().stage());
        coordinator.resume(settings);
        assertEquals(1, buys.get());
        assertEquals(PurchaseStage.CREATING_ORDER, repository.findActivePipeline(A).orElseThrow().stage());
    }

    @Test void preRequestPurchaseFailureDoesNotPermanentlyBlockGtin() throws Exception {
        Settings settings = testedSettings();
        ZnackKizOrderService orderService = new ZnackKizOrderService(null,null,null,repository) {
            @Override public KizOrder buy(Settings ignored, String gtin, int quantity) {
                throw new IllegalStateException("signature failed before request");
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, orderService, null, null);

        assertThrows(IllegalStateException.class, () -> coordinator.start(settings, A, 1));
        assertTrue(repository.findActivePipeline(A).isEmpty());
        assertThrows(IllegalStateException.class, () -> coordinator.start(settings, A, 1));
        assertTrue(repository.findActivePipeline(A).isEmpty());
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM znack_purchase_pipelines WHERE gtin='" + A + "' AND stage='FAILED'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test void missingIntroductionMetadataKeepsDownloadedCodesAvailable() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "", "", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "");
        ZnackKizOrderService orderService = new ZnackKizOrderService(null,null,null,repository) {
            @Override public KizOrder buy(Settings ignored, String gtin, int quantity) {
                long id = repository.createDraft(gtin, quantity);
                repository.updateOrder(id, "external", "CREATED", OrderStatus.SUBMITTED, null);
                return repository.findOrder(id).orElseThrow();
            }
            @Override public KizOrder refresh(Settings ignored, long id) {
                repository.updateOrder(id, null, "READY", OrderStatus.CODES_READY, null);
                return repository.findOrder(id).orElseThrow();
            }
        };
        ZnackKizCodeService codeService = new ZnackKizCodeService(null,null,repository) {
            @Override public int download(Settings ignored, long id) {
                KizOrder order = repository.findOrder(id).orElseThrow();
                return repository.insertCodes(id, order.gtin(), new DownloadedCodes(List.of("available-after-skip"), "b"));
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, orderService, codeService, null);
        long pipeline = coordinator.start(auto, A, 1);
        coordinator.resume(auto);
        assertEquals(PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS,
                repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(1, new ZnackGtinInventoryService().availableCount(1, A));
    }

    @Test void skippedIntroductionResumesAfterDocumentsAndMetadataBecomeAvailable() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "DOC-1", "20.06.2024", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "20.06.2029", "CONFORMITY_DECLARATION");
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, "21.06.2024"));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("resume-introduction"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.INTRODUCTION_SKIPPED_MISSING_METADATA, null);
        AtomicInteger submissions = new AtomicInteger();
        ZnackIntroductionService introduction = new ZnackIntroductionService(null, null, null, repository) {
            @Override public long submit(Settings ignored, KizOrder ignoredOrder, Product ignoredProduct,
                                         List<KizCode> ignoredCodes) {
                submissions.incrementAndGet();
                return 1;
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, introduction) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.resumeEligibleIntroductions(auto);
        coordinator.resumeEligibleIntroductions(auto);

        assertEquals(1, submissions.get());
        assertEquals(PurchaseStage.POLLING_INTRODUCTION, repository.findPipeline(pipeline).orElseThrow().stage());
    }

    @Test void skippedIntroductionStaysRetryableWhenSigningFailsBeforeDocumentCreation() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "DOC-1", "20.06.2024", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "20.06.2029", "CONFORMITY_DECLARATION");
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, "21.06.2024"));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("retry-introduction"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, null);
        ZnackIntroductionService introduction = new ZnackIntroductionService(null, null, null, repository) {
            @Override public long submit(Settings ignored, KizOrder ignoredOrder, Product ignoredProduct,
                                         List<KizCode> ignoredCodes) {
                throw new IllegalStateException("token unavailable");
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, introduction) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.resumeEligibleIntroductions(auto);

        assertEquals(PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS,
                repository.findPipeline(pipeline).orElseThrow().stage());
        assertTrue(repository.findLatestDocument(order).isEmpty());
    }

    @Test void legacyHttp422IntroductionIsRetriedOnceAfterEndpointCorrection() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "DOC-1", "20.06.2024", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "", "CONFORMITY_DECLARATION");
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, null));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("legacy-http-422"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.FAILED, "Znack API request failed (HTTP 422)");
        long document = repository.createDocument(order, "{}");
        repository.updateDocument(document, null, "FAILED", "Znack API request failed (HTTP 422)");
        AtomicInteger submissions = new AtomicInteger();
        ZnackIntroductionService introduction = new ZnackIntroductionService(null, null, null, repository) {
            @Override public long submit(Settings ignored, KizOrder ignoredOrder, Product ignoredProduct,
                                         List<KizCode> ignoredCodes) {
                submissions.incrementAndGet();
                return 2;
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, introduction) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.resumeEligibleIntroductions(auto);
        coordinator.resumeEligibleIntroductions(auto);

        assertEquals(1, submissions.get());
        assertEquals(PurchaseStage.POLLING_INTRODUCTION, repository.findPipeline(pipeline).orElseThrow().stage());
    }

    @Test void legacyPrimitiveDocumentResponseRecoversUuidWithoutSubmittingAgain() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "DOC-1", "20.06.2024", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "", "CONFORMITY_DECLARATION");
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, null));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("legacy-primitive-response"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        String externalDocumentId = "72f9fdad-b271-4184-a525-7ffefd4d8ec3";
        repository.updatePipeline(pipeline, order, PurchaseStage.FAILED, "Not a JSON Object");
        long document = repository.createDocument(order, "{}");
        repository.updateDocument(document, null, "FAILED",
                "java.lang.IllegalStateException: Not a JSON Object: \"" + externalDocumentId + "\"");
        AtomicInteger submissions = new AtomicInteger();
        ZnackIntroductionService introduction = new ZnackIntroductionService(null, null, null, repository) {
            @Override public long submit(Settings ignored, KizOrder ignoredOrder, Product ignoredProduct,
                                         List<KizCode> ignoredCodes) {
                submissions.incrementAndGet();
                return 2;
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, introduction) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.resumeEligibleIntroductions(auto);

        Document recovered = repository.findLatestDocument(order).orElseThrow();
        assertEquals(0, submissions.get());
        assertEquals(externalDocumentId, recovered.externalDocumentId());
        assertEquals("SUBMITTED", recovered.status());
        assertEquals(PurchaseStage.POLLING_INTRODUCTION, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(KizLegalStatus.INTRO_SENT, repository.findCodes(order).getFirst().legalStatus());
    }

    @Test void resumedIntroductionKeepsWaitingStageAfterReadinessNetworkFailure() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "DOC-1", "20.06.2024", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "", "CONFORMITY_DECLARATION");
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, null));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("network-retry-code"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.INTRODUCTION_SKIPPED_MISSING_METADATA, null);
        ZnackIntroductionReadinessService readiness = new ZnackIntroductionReadinessService(null, null, repository) {
            @Override public Readiness check(Settings ignoredSettings, Product ignoredProduct,
                                             List<KizCode> ignoredCodes) throws Exception {
                throw new java.io.IOException("temporary readiness failure");
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, null, readiness) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.resumeEligibleIntroductions(auto);

        ZnackPurchasePipelineState persisted = repository.findPipeline(pipeline).orElseThrow();
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS, persisted.stage());
        assertEquals("temporary readiness failure", persisted.errorMessage());
    }

    @Test void readinessAcceptsAppliedCodesWithoutProductNameAndPersistsCardFlags() throws Exception {
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, null));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("ready-code"), "block"));
        ZnackIntroductionReadinessService readiness = readinessService(true, "APPLIED", "EMPTY", "-");

        ZnackIntroductionReadinessService.Readiness result = readiness.check(
                testedSettings(), repository.findProduct(A).orElseThrow(), repository.findCodes(order));

        assertTrue(result.ready());
        assertFalse(result.allIntroduced());
        assertTrue(result.message().contains("Product name"));
        Product persisted = repository.findProduct(A).orElseThrow();
        assertEquals(Boolean.TRUE, persisted.goodMarkFlag());
        assertEquals(Boolean.TRUE, persisted.goodTurnFlag());
        assertNotNull(persisted.readinessCheckedAt());
    }

    @Test void trueApiLookupsUseNormalizedCisWithoutTheDataMatrixCryptoTail() throws Exception {
        assertEquals(NORMALIZED_CIS, ZnackCisNormalizer.forTrueApi(RAW_CIS));
        assertEquals(NORMALIZED_CIS, ZnackCisNormalizer.forTrueApi("]d2" + RAW_CIS));
        assertEquals(NORMALIZED_CIS, ZnackCisNormalizer.forTrueApi(NORMALIZED_CIS));

        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, null));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of(RAW_CIS), "block"));
        AtomicReference<String> requested = new AtomicReference<>();
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement productCards(String base, String token, String gtins) {
                return JsonParser.parseString("""
                        {"result":[{"good_name":"A","good_mark_flag":true,"good_turn_flag":true,
                        "identified_by":[{"type":"gtin","value":"%s"}]}]}
                        """.formatted(A));
            }

            @Override public JsonElement cisesInfo(String base, String token, JsonElement body) {
                requested.set(body.getAsJsonArray().get(0).getAsString());
                return JsonParser.parseString("""
                        [{"cisInfo":{"gtin":"%s","productName":"A","status":"APPLIED","statusEx":"EMPTY"}}]
                        """.formatted(A));
            }
        };
        ZnackAuthService auth = new ZnackAuthService(api, (input, context) -> null) {
            @Override public String trueApiToken(Settings ignored) { return "token"; }
        };

        ZnackIntroductionReadinessService.Readiness result = new ZnackIntroductionReadinessService(api, auth, repository)
                .check(testedSettings(), repository.findProduct(A).orElseThrow(), repository.findCodes(order));

        assertTrue(result.ready());
        assertEquals(NORMALIZED_CIS, requested.get());
    }

    @Test void readinessRecognizesAlreadyIntroducedCodesEvenWhenProductCardIsNotReady() throws Exception {
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("introduced-code"), "block"));

        ZnackIntroductionReadinessService.Readiness result = readinessService(false, "INTRODUCED", "BLOCKED", "A")
                .check(testedSettings(), repository.findProduct(A).orElseThrow(), repository.findCodes(order));

        assertTrue(result.allIntroduced());
        assertFalse(result.ready());
    }

    @Test void readinessAcceptsSingleObjectCisesResponse() throws Exception {
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("single-object-code"), "block"));

        ZnackIntroductionReadinessService.Readiness result = readinessService(true, "APPLIED", "EMPTY", "A", true)
                .check(testedSettings(), repository.findProduct(A).orElseThrow(), repository.findCodes(order));

        assertTrue(result.ready());
        assertFalse(result.allIntroduced());
    }

    @Test void readinessChecksEveryBatchAndReportsProgressForLargeOrders() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement productCards(String base, String token, String gtins) {
                return JsonParser.parseString("""
                        {"result":[{"good_name":"A","good_mark_flag":true,"good_turn_flag":true,
                        "identified_by":[{"type":"gtin","value":"%s"}]}]}
                        """.formatted(A));
            }

            @Override public JsonElement cisesInfo(String base, String token, JsonElement body) {
                boolean firstBatch = requests.incrementAndGet() == 1;
                JsonArray result = new JsonArray();
                body.getAsJsonArray().forEach(code -> {
                    JsonObject entry = new JsonObject();
                    JsonObject info = new JsonObject();
                    info.addProperty("requestedCis", code.getAsString());
                    info.addProperty("cis", code.getAsString());
                    if (firstBatch) {
                        entry.addProperty("errorMessage", "КМ/КИ не найден");
                        entry.addProperty("errorCode", "404");
                    } else {
                        info.addProperty("gtin", A);
                        info.addProperty("productName", "A");
                        info.addProperty("status", "APPLIED");
                        info.addProperty("statusEx", "EMPTY");
                    }
                    entry.add("cisInfo", info);
                    result.add(entry);
                });
                return result;
            }
        };
        ZnackAuthService auth = new ZnackAuthService(api, (input, context) -> null) {
            @Override public String trueApiToken(Settings ignored) { return "token"; }
        };
        List<KizCode> codes = IntStream.range(0, 3_000)
                .mapToObj(index -> new KizCode(index + 1L, 1L, "code-" + index, "code-" + index, A, "block",
                        null, null, KizInventoryStatus.AVAILABLE, KizLegalStatus.RECEIVED))
                .toList();

        ZnackIntroductionReadinessService.Readiness result =
                new ZnackIntroductionReadinessService(api, auth, repository)
                        .check(testedSettings(), repository.findProduct(A).orElseThrow(), codes);

        assertEquals(3, requests.get());
        assertFalse(result.ready());
        assertTrue(result.message().contains("2000/3000 KIZ ready"));
        assertTrue(result.message().contains("1000 pending"));
    }

    @Test void readinessStageIsSafeToRetryAndKeepsDownloadedCodesAvailable() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "DOC-1", "20.06.2024", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "", "CONFORMITY_DECLARATION");
        repository.updateProductMetadata(new Product(A, "A", "6201000000", null, null, null, null));
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("waiting-code"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, null,
                readinessService(true, "EMITTED", "EMPTY", "A")) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.advance(auto, pipeline);

        ZnackPurchasePipelineState persisted = repository.findPipeline(pipeline).orElseThrow();
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS, persisted.stage());
        assertTrue(persisted.errorMessage().contains("EMITTED"));
        assertEquals(1, new ZnackGtinInventoryService().availableCount(1, A));
    }

    @Test void readinessStageCompletesIdempotentlyWhenCodesWereIntroducedManually() throws Exception {
        Settings base = testedSettings();
        Settings auto = new Settings(base.trueApiBaseUrl(), base.suzBaseUrl(), base.omsId(), base.omsConnection(),
                base.participantInn(), base.producerInn(), base.ownerInn(), base.signerExecutable(),
                base.signerCertificate(), base.signerArgumentsJson(), "", "", "", true,
                base.certificateListExecutable(), base.certificateListArgumentsJson(), base.certificateMetadataJson(),
                base.signerTestedAt(), base.certmgrPath(), base.cryptcpPath(), base.csptestPath(),
                base.cryptoProTimeoutSeconds(), "", "");
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("manual-code"), "block"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.WAITING_INTRODUCTION_READINESS, null);
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, null,
                readinessService(false, "INTRODUCED", "BLOCKED", "A")) {
            @Override void schedule(long ignoredPipeline) {
            }
        };

        coordinator.advance(auto, pipeline);

        assertEquals(PurchaseStage.INTRODUCED, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(OrderStatus.INTRODUCED, repository.findOrder(order).orElseThrow().localStatus());
        assertEquals(KizLegalStatus.IN_CIRCULATION, repository.findCodes(order).getFirst().legalStatus());
        assertEquals(1, new ZnackGtinInventoryService().availableCount(1, A));
    }

    @Test void incompleteCodeDownloadRemainsActiveUntilAllCodesArePresent() throws Exception {
        Settings settings = testedSettings();
        AtomicInteger downloads = new AtomicInteger();
        ZnackKizOrderService orderService = readyOrderService();
        ZnackKizCodeService codeService = new ZnackKizCodeService(null, null, repository) {
            @Override public int download(Settings ignored, long id) {
                if (downloads.incrementAndGet() == 1) return 0;
                KizOrder order = repository.findOrder(id).orElseThrow();
                return repository.insertCodes(id, order.gtin(), new DownloadedCodes(List.of("one", "two"), "b"));
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, orderService, codeService, null);
        long pipeline = coordinator.start(settings, A, 2);

        coordinator.resume(settings);
        assertEquals(PurchaseStage.DOWNLOADING_CODES, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(0, new ZnackGtinInventoryService().availableCount(1, A));

        coordinator.resume(settings);
        assertEquals(PurchaseStage.COMPLETED, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(2, new ZnackGtinInventoryService().availableCount(1, A));
    }

    @Test void samePipelineCannotAdvanceConcurrently() throws Exception {
        Settings settings = testedSettings();
        long order = repository.createDraft(A, 1);
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.POLLING_ORDER, null);
        AtomicInteger refreshes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ZnackKizOrderService orderService = new ZnackKizOrderService(null, null, null, repository) {
            @Override public KizOrder refresh(Settings ignored, long id) throws Exception {
                refreshes.incrementAndGet();
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return repository.findOrder(id).orElseThrow();
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, orderService, null, null);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                coordinator.advance(settings, pipeline);
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                coordinator.advance(settings, pipeline);
                return null;
            });
            second.get(5, TimeUnit.SECONDS);
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, refreshes.get());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test void submittedIntroductionIsPolledInsteadOfSubmittedAgainAfterRestart() throws Exception {
        Settings settings = testedSettings();
        long order = repository.createDraft(A, 1);
        repository.insertCodes(order, A, new DownloadedCodes(List.of("one"), "b"));
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.SUBMITTING_INTRODUCTION, null);
        long document = repository.createDocument(order, "{}");
        repository.updateDocument(document, "external-document", "SUBMITTED", null);
        AtomicInteger submits = new AtomicInteger();
        ZnackIntroductionService service = new ZnackIntroductionService(null, null, null, repository) {
            @Override public long submit(Settings ignored, KizOrder ignoredOrder, Product ignoredProduct,
                                         List<KizCode> ignoredCodes) {
                submits.incrementAndGet();
                return document;
            }
        };
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, null, null, service);

        coordinator.advance(settings, pipeline);

        assertEquals(0, submits.get());
        assertEquals(PurchaseStage.POLLING_INTRODUCTION, repository.findPipeline(pipeline).orElseThrow().stage());
    }

    @Test void resumeSchedulesSafeRetryAfterTransientPollingFailure() {
        Settings settings;
        try {
            settings = testedSettings();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        long order = repository.createDraft(A, 1);
        repository.updateOrder(order, "external", "PENDING", OrderStatus.WAITING_CODES, null);
        long pipeline = repository.createPipeline(A, 1);
        repository.updatePipeline(pipeline, order, PurchaseStage.POLLING_ORDER, null);
        ZnackKizOrderService orderService = new ZnackKizOrderService(null, null, null, repository) {
            @Override public KizOrder refresh(Settings ignored, long ignoredOrder) {
                throw new IllegalStateException("temporary polling failure");
            }
        };
        AtomicInteger scheduled = new AtomicInteger();
        ZnackPurchaseCoordinator coordinator = new ZnackPurchaseCoordinator(repository, orderService, null, null) {
            @Override void schedule(long ignoredPipeline) {
                scheduled.incrementAndGet();
            }
        };

        coordinator.resume(settings);

        assertEquals(1, scheduled.get());
        assertEquals(PurchaseStage.POLLING_ORDER, repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals("temporary polling failure", repository.findPipeline(pipeline).orElseThrow().errorMessage());
    }

    private ZnackKizOrderService readyOrderService() {
        return new ZnackKizOrderService(null, null, null, repository) {
            @Override public KizOrder buy(Settings ignored, String gtin, int quantity) {
                long id = repository.createDraft(gtin, quantity);
                repository.updateOrder(id, "external", "CREATED", OrderStatus.SUBMITTED, null);
                return repository.findOrder(id).orElseThrow();
            }

            @Override public KizOrder refresh(Settings ignored, long id) {
                repository.updateOrder(id, null, "READY", OrderStatus.CODES_READY, null);
                return repository.findOrder(id).orElseThrow();
            }
        };
    }

    private ZnackIntroductionReadinessService readinessService(boolean cardReady, String status, String statusEx,
                                                               String productName) {
        return readinessService(cardReady, status, statusEx, productName, false);
    }

    private ZnackIntroductionReadinessService readinessService(boolean cardReady, String status, String statusEx,
                                                               String productName, boolean singleObjectResponse) {
        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement productCards(String base, String token, String gtins) {
                return JsonParser.parseString("""
                        {"result":[{"good_name":"%s","good_mark_flag":%s,"good_turn_flag":%s,
                        "good_status":"published","identified_by":[{"type":"gtin","value":"%s"}]}]}
                        """.formatted(productName, cardReady, cardReady, A));
            }

            @Override public JsonElement cisesInfo(String base, String token, JsonElement body) {
                String code = body.getAsJsonArray().get(0).getAsString();
                String entry = """
                        {"cisInfo":{"requestedCis":"%s","cis":"%s","gtin":"%s","productName":"%s",
                        "status":"%s","statusEx":"%s"}}
                        """.formatted(code, code, A, productName, status, statusEx);
                return JsonParser.parseString(singleObjectResponse ? entry : "[" + entry + "]");
            }
        };
        ZnackAuthService auth = new ZnackAuthService(api, (input, context) -> null) {
            @Override public String trueApiToken(Settings ignored) { return "token"; }
        };
        return new ZnackIntroductionReadinessService(api, auth, repository);
    }

    private Settings testedSettings() throws Exception {
        Path cryptcp = executableFixture();
        return new Settings("", "", "oms", "connection", "", "", "", "", "certificate", "[]",
                "", "", "", false, "", "[]", "", Instant.now(), "", cryptcp.toString(), "", 60, "");
    }

    private Path executableFixture() throws Exception {
        String comSpec = System.getenv("COMSPEC");
        if (comSpec != null && Files.isRegularFile(Path.of(comSpec))) {
            return Path.of(comSpec);
        }
        Path cryptcp = temp.resolve("cryptcp");
        Files.writeString(cryptcp, "#!/bin/sh\nexit 0\n");
        assertTrue(cryptcp.toFile().setExecutable(true));
        return cryptcp;
    }
}
