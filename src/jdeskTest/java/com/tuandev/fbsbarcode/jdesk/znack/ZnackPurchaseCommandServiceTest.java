package com.tuandev.fbsbarcode.jdesk.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZnackPurchaseCommandServiceTest {
    private static final String GTIN = "04601234567890";
    private static final String SECRET = "raw-kiz-token-response-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void preparesANonMutatingBoundedPreviewAndRequiresFreshVerifiedSettings() {
        FakeSource source = new FakeSource();
        FakeRunner runner = new FakeRunner(source);
        ZnackPurchaseCommandService service = service(source, runner);
        String version = ZnackCommandService.settingsVersion(source.settings);

        ZnackPurchaseCommandService.PurchasePreview response = service.preparePurchase(
                        new ZnackPurchaseCommandService.PreparePurchaseRequest(7, GTIN, 25, version), null)
                .toCompletableFuture().join();

        assertEquals(GTIN, response.gtin());
        assertEquals("Ботинки Alpine", response.productName());
        assertEquals(25, response.quantity());
        assertTrue(response.autoIntroduction());
        assertTrue(response.purchaseId().matches("[0-9a-f-]{36}"));
        assertEquals("2026-07-18T00:10:00Z", response.expiresAt());
        assertEquals(List.of("automatic_introduction"), response.warnings());
        assertEquals(0, runner.starts.get());
        assertFalse(new JacksonJsonCodec().encode(response).contains(SECRET));

        source.active = purchase("existing", PurchaseStage.POLLING_ORDER, 0, "");
        assertInvalid(() -> service.preparePurchase(
                new ZnackPurchaseCommandService.PreparePurchaseRequest(7, GTIN, 1, version), null));
        source.active = null;
        source.settings = unverifiedSettings();
        assertInvalid(() -> service.preparePurchase(
                new ZnackPurchaseCommandService.PreparePurchaseRequest(
                        7, GTIN, 1, ZnackCommandService.settingsVersion(source.settings)), null));
        assertInvalid(() -> service.preparePurchase(
                new ZnackPurchaseCommandService.PreparePurchaseRequest(7, GTIN, 10_001, version), null));
    }

    @Test
    void startsOnceFromASingleUsePreviewAndReplaysThePersistedPurchase() {
        FakeSource source = new FakeSource();
        FakeRunner runner = new FakeRunner(source);
        ZnackPurchaseCommandService service = service(source, runner);
        String version = ZnackCommandService.settingsVersion(source.settings);
        ZnackPurchaseCommandService.PurchasePreview preview = service.preparePurchase(
                        new ZnackPurchaseCommandService.PreparePurchaseRequest(7, GTIN, 2, version), null)
                .toCompletableFuture().join();

        ZnackPurchaseCommandService.StartPurchaseResponse accepted = service.startPurchase(
                        new ZnackPurchaseCommandService.StartPurchaseRequest(
                                7, preview.purchaseId(), version, true), null)
                .toCompletableFuture().join();
        ZnackPurchaseCommandService.StartPurchaseResponse replay = service.startPurchase(
                        new ZnackPurchaseCommandService.StartPurchaseRequest(
                                7, preview.purchaseId(), version, true), null)
                .toCompletableFuture().join();
        source.settings = unverifiedSettings();
        ZnackPurchaseCommandService.StartPurchaseResponse replayAfterSettingsChange = service.startPurchase(
                        new ZnackPurchaseCommandService.StartPurchaseRequest(
                                7, preview.purchaseId(), version, true), null)
                .toCompletableFuture().join();

        assertTrue(accepted.accepted());
        assertFalse(replay.accepted());
        assertFalse(replayAfterSettingsChange.accepted());
        assertEquals(preview.purchaseId(), replay.purchase().purchaseId());
        assertEquals(preview.purchaseId(), replayAfterSettingsChange.purchase().purchaseId());
        assertEquals(1, runner.starts.get());
        assertEquals(preview.purchaseId(), runner.lastPurchaseId.get());
        assertInvalid(() -> service.startPurchase(new ZnackPurchaseCommandService.StartPurchaseRequest(
                7, "00000000-0000-0000-0000-000000000000", version, true), null));
        assertInvalid(() -> service.startPurchase(new ZnackPurchaseCommandService.StartPurchaseRequest(
                7, preview.purchaseId(), version, false), null));
    }

    @Test
    void returnsBoundedSafePurchaseStatusAndNeverSerializesRawIdentifiersOrErrors() {
        FakeSource source = new FakeSource();
        source.purchases.add(purchase(
                "11111111-1111-4111-8111-111111111111", PurchaseStage.CREATING_ORDER, 0,
                "HTTP 500 " + SECRET));
        source.purchases.add(purchase(
                "22222222-2222-4222-8222-222222222222", PurchaseStage.INTRODUCTION_FAILED, 3,
                "rejected " + SECRET));
        ZnackPurchaseCommandService service = service(source, new FakeRunner(source));

        ZnackPurchaseCommandService.PurchasesResponse page = service.purchases(
                        new ZnackPurchaseCommandService.PurchasesRequest(7, 1, 10), null)
                .toCompletableFuture().join();
        ZnackPurchaseCommandService.PurchaseItem ambiguous = page.items().getFirst();
        ZnackPurchaseCommandService.PurchaseItem retryable = page.items().get(1);

        assertEquals("manual_review", ambiguous.state());
        assertEquals("order_creation_ambiguous", ambiguous.errorKind());
        assertFalse(ambiguous.retryable());
        assertTrue(retryable.canRetryIntroduction());
        assertEquals("introduction_failed", retryable.errorKind());
        String json = new JacksonJsonCodec().encode(page);
        assertFalse(json.contains(SECRET));
        assertFalse(json.contains("externalOrder"));
        assertFalse(json.contains("rawCode"));
        assertEquals(new ZnackPurchaseCommandService.PageQuery(7, 11, 0), source.purchaseQuery.get());
    }

    @Test
    void retriesOnlyFailedIntroductionWithoutStartingAnotherPurchase() {
        FakeSource source = new FakeSource();
        FakeRunner runner = new FakeRunner(source);
        String purchaseId = "22222222-2222-4222-8222-222222222222";
        source.purchases.add(purchase(purchaseId, PurchaseStage.INTRODUCTION_FAILED, 3, "failed"));
        ZnackPurchaseCommandService service = service(source, runner);
        String version = ZnackCommandService.settingsVersion(source.settings);

        ZnackPurchaseCommandService.PurchaseItem response = service.retryIntroduction(
                        new ZnackPurchaseCommandService.RetryIntroductionRequest(
                                7, purchaseId, version, true), null)
                .toCompletableFuture().join();

        assertEquals("running", response.state());
        assertEquals(1, runner.retries.get());
        assertEquals(purchaseId, runner.lastRetryPurchaseId.get());
        assertEquals(0, runner.starts.get());
        assertInvalid(() -> service.retryIntroduction(new ZnackPurchaseCommandService.RetryIntroductionRequest(
                7, "11111111-1111-4111-8111-111111111111", version, true), null));
    }

    @Test
    void returnsBoundedSanitizedShopJournal() {
        FakeSource source = new FakeSource();
        source.logs.add(new ZnackPurchaseCommandService.LogRow(
                "BUY_KIZ\n" + SECRET, GTIN, "ERROR", "upstream\u0000 " + SECRET, 503,
                Instant.parse("2026-07-18T00:00:00Z")));

        ZnackPurchaseCommandService.LogsResponse response = service(source, new FakeRunner(source)).operationLogs(
                        new ZnackPurchaseCommandService.LogsRequest(7, 1, 20), null)
                .toCompletableFuture().join();

        assertEquals("operation", response.items().getFirst().action());
        assertEquals("error", response.items().getFirst().severity());
        assertEquals("upstream_error", response.items().getFirst().messageKind());
        assertEquals("5xx", response.items().getFirst().httpClass());
        assertFalse(new JacksonJsonCodec().encode(response).contains(SECRET));
        assertEquals(new ZnackPurchaseCommandService.PageQuery(7, 21, 0), source.logQuery.get());
    }

    @Test
    void sqliteSourceReturnsOnlyAggregatesAndBackfillsAnOpaqueLegacyRequestKey(@TempDir Path temp) throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try {
            Database.initDatabase();
            try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO shops(id,name,api_key) VALUES(7,'Main','" + SECRET + "')");
                statement.execute("""
                        INSERT INTO znack_products(shop_id,gtin,product_name,synced_at)
                        VALUES(7,'04601234567890','Ботинки Alpine','2026-07-18T00:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO kiz_orders(id,shop_id,external_order_id,gtin,quantity,local_status,created_at,updated_at)
                        VALUES(70,7,'external-secret','04601234567890',2,'CODES_DOWNLOADED',
                               '2026-07-18T00:00:00Z','2026-07-18T00:01:00Z')
                        """);
                statement.execute("""
                        INSERT INTO znack_purchase_pipelines(
                          id,shop_id,gtin,quantity,order_id,request_key,stage,error_message,created_at,updated_at)
                        VALUES(80,7,'04601234567890',2,70,NULL,'COMPLETED','raw %s',
                               '2026-07-18T00:00:00Z','2026-07-18T00:01:00Z')
                        """.formatted(SECRET));
                statement.execute("""
                        INSERT INTO kiz_codes(
                          shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at)
                        VALUES(7,70,'raw-%s','safe','04601234567890','AVAILABLE','RECEIVED',
                               '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                        """.formatted(SECRET));
                statement.execute("""
                        INSERT INTO znack_operation_logs(
                          shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                        VALUES(7,'Main','PURCHASE_PIPELINE','04601234567890','ERROR','raw %s',503,
                               '2026-07-18T00:02:00Z')
                        """.formatted(SECRET));
                com.tuandev.fbsbarcode.integration.znack.ZnackSchemaSupport.initialize(connection);
            }

            ZnackPurchaseCommandService service = new ZnackPurchaseCommandService();
            ZnackPurchaseCommandService.PurchasesResponse purchases = service.purchases(
                            new ZnackPurchaseCommandService.PurchasesRequest(7, 1, 10), null)
                    .toCompletableFuture().join();
            ZnackPurchaseCommandService.LogsResponse logs = service.operationLogs(
                            new ZnackPurchaseCommandService.LogsRequest(7, 1, 10), null)
                    .toCompletableFuture().join();

            assertEquals(1, purchases.items().size());
            assertEquals(1, purchases.items().getFirst().downloadedCodes());
            assertTrue(purchases.items().getFirst().purchaseId().matches("[0-9a-f-]{36}"));
            String json = new JacksonJsonCodec().encode(List.of(purchases, logs));
            assertFalse(json.contains(SECRET));
            assertFalse(json.contains("external-secret"));
            assertFalse(json.contains("rawCode"));

            try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT request_key FROM znack_purchase_pipelines WHERE id=80")) {
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(purchases.items().getFirst().purchaseId(), rows.getString(1));
                }
            }
        } finally {
            System.clearProperty("wcode.appdata.dir");
        }
    }

    private static ZnackPurchaseCommandService service(FakeSource source, FakeRunner runner) {
        return new ZnackPurchaseCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)), source, runner, CLOCK);
    }

    private static ZnackPurchaseCommandService.PurchaseRow purchase(
            String purchaseId, PurchaseStage stage, int downloaded, String error) {
        return new ZnackPurchaseCommandService.PurchaseRow(
                purchaseId, GTIN, "Ботинки Alpine", 3, stage, downloaded, error,
                Instant.parse("2026-07-18T00:00:00Z"), Instant.parse("2026-07-18T00:01:00Z"));
    }

    private static Settings verifiedSettings() {
        Settings empty = Settings.empty();
        return new Settings(
                empty.trueApiBaseUrl(), empty.suzBaseUrl(), "OMS-7", "CONNECTION-7", "7700000000",
                "7700000000", "7700000000", SECRET, SECRET, "[]", "DOC-7", "18.07.2026", SECRET,
                true, SECRET, "[]", "{\"label\":\"Owner\"}", Instant.parse("2026-07-17T00:00:00Z"),
                SECRET, SECRET, SECRET, 60, "", Settings.DEFAULT_DOCUMENT_TYPE);
    }

    private static Settings unverifiedSettings() {
        Settings verified = verifiedSettings();
        return new Settings(
                verified.trueApiBaseUrl(), verified.suzBaseUrl(), verified.omsId(), verified.omsConnection(),
                verified.participantInn(), verified.producerInn(), verified.ownerInn(), verified.signerExecutable(),
                verified.signerCertificate(), verified.signerArgumentsJson(), verified.documentNumber(),
                verified.documentDate(), verified.pdfFolder(), verified.autoIntroduction(),
                verified.certificateListExecutable(), verified.certificateListArgumentsJson(),
                verified.certificateMetadataJson(), null, verified.certmgrPath(), verified.cryptcpPath(),
                verified.csptestPath(), verified.cryptoProTimeoutSeconds(), verified.documentExpiryDate(),
                verified.documentType());
    }

    private static void assertInvalid(Runnable action) {
        JDeskException error = assertThrows(JDeskException.class, action::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }

    private static final class FakeSource implements ZnackPurchaseCommandService.PurchaseSource {
        private Settings settings = verifiedSettings();
        private final List<ZnackPurchaseCommandService.PurchaseRow> purchases = new ArrayList<>();
        private final List<ZnackPurchaseCommandService.LogRow> logs = new ArrayList<>();
        private ZnackPurchaseCommandService.PurchaseRow active;
        private final AtomicReference<ZnackPurchaseCommandService.PageQuery> purchaseQuery = new AtomicReference<>();
        private final AtomicReference<ZnackPurchaseCommandService.PageQuery> logQuery = new AtomicReference<>();

        @Override public Settings settings(int shopId) { return settings; }
        @Override public ZnackPurchaseCommandService.ProductRow product(int shopId, String gtin) {
            return GTIN.equals(gtin) ? new ZnackPurchaseCommandService.ProductRow(GTIN, "Ботинки Alpine", false) : null;
        }
        @Override public ZnackPurchaseCommandService.PurchaseRow active(int shopId, String gtin) { return active; }
        @Override public ZnackPurchaseCommandService.PurchaseRow purchase(int shopId, String purchaseId) {
            return purchases.stream().filter(row -> row.purchaseId().equals(purchaseId)).findFirst().orElse(null);
        }
        @Override public List<ZnackPurchaseCommandService.PurchaseRow> purchases(ZnackPurchaseCommandService.PageQuery query) {
            purchaseQuery.set(query);
            return purchases;
        }
        @Override public List<ZnackPurchaseCommandService.LogRow> logs(ZnackPurchaseCommandService.PageQuery query) {
            logQuery.set(query);
            return logs;
        }
    }

    private static final class FakeRunner implements ZnackPurchaseCommandService.PurchaseRunner {
        private final FakeSource source;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger retries = new AtomicInteger();
        private final AtomicReference<String> lastPurchaseId = new AtomicReference<>();
        private final AtomicReference<String> lastRetryPurchaseId = new AtomicReference<>();

        private FakeRunner(FakeSource source) { this.source = source; }

        @Override public void start(Shop shop, Settings settings, String gtin, int quantity, String purchaseId) {
            starts.incrementAndGet();
            lastPurchaseId.set(purchaseId);
            source.purchases.add(purchase(purchaseId, PurchaseStage.VALIDATING, 0, ""));
        }

        @Override public void retryIntroduction(Shop shop, Settings settings, String gtin, String purchaseId) {
            retries.incrementAndGet();
            lastRetryPurchaseId.set(purchaseId);
            int index = java.util.stream.IntStream.range(0, source.purchases.size())
                    .filter(candidate -> source.purchases.get(candidate).purchaseId().equals(purchaseId))
                    .findFirst().orElseThrow();
            ZnackPurchaseCommandService.PurchaseRow row = source.purchases.get(index);
            source.purchases.set(index, new ZnackPurchaseCommandService.PurchaseRow(
                    row.purchaseId(), row.gtin(), row.productName(), row.quantity(),
                    PurchaseStage.WAITING_INTRODUCTION_READINESS, row.downloadedCodes(), "",
                    row.createdAt(), row.updatedAt()));
        }
    }
}
