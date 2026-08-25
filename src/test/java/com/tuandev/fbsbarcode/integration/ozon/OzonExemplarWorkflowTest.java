package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonExemplarWorkflowTest {
    private static final String RAW_KIZ = "010460000000000121ABC";

    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;
    private Shop shop;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        System.setProperty("wcode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client-1','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'04600000000001','Marked item','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) "
                    + "VALUES(1,1,'04600000000001',1,'COMPLETED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,created_at,updated_at) "
                    + "VALUES(1,1,1,'" + RAW_KIZ + "','" + RAW_KIZ + "','04600000000001','AVAILABLE',"
                    + "'2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at) "
                    + "VALUES(1,'101','04600000000001','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("wcode.appdata.dir");
        server.shutdown();
    }

    @Test
    void successConsumesOneKizAndDuplicateClickResumesWithoutAnotherMutation() throws Exception {
        enqueueHappyPath();
        OzonExemplarService service = service();

        OzonPreparationResult first = service.prepare(shop, "POST-1");

        assertEquals("ACCEPTED", first.stage());
        assertTrue(first.shipReady());
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals("passed", scalar("SELECT check_status FROM ozon_exemplars WHERE id=1"));
        List<RecordedRequest> firstRun = takeRequests(5);
        assertEquals(List.of(
                        "/v3/posting/fbs/get",
                        "/v6/fbs/posting/product/exemplar/create-or-get",
                        "/v5/fbs/posting/product/exemplar/validate",
                        "/v6/fbs/posting/product/exemplar/set",
                        "/v5/fbs/posting/product/exemplar/status"),
                firstRun.stream().map(RecordedRequest::getPath).toList());
        assertFalse(firstRun.get(1).getBody().readUtf8().contains(RAW_KIZ));

        server.enqueue(json(posting("awaiting_packaging")));
        OzonPreparationResult duplicate = service.prepare(shop, "POST-1");

        assertEquals("ACCEPTED", duplicate.stage());
        assertEquals("/v3/posting/fbs/get", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        assertEquals(6, server.getRequestCount());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_codes WHERE status='CONSUMED'"));
    }

    @Test
    void automaticallyPushesKizForEveryMappedProductEvenWithoutOzonRequirementFlag() throws Exception {
        server.enqueue(json(postingWithoutRequirements("awaiting_packaging")));
        server.enqueue(json(createResponse()));
        server.enqueue(json(validateResponse()));
        server.enqueue(json("{}"));
        server.enqueue(json(statusAccepted()));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("ACCEPTED", result.stage());
        assertEquals(1, result.exemplarCount());
        List<String> paths = takeRequests(5).stream().map(RecordedRequest::getPath).toList();
        assertEquals(List.of(
                "/v3/posting/fbs/get",
                "/v6/fbs/posting/product/exemplar/create-or-get",
                "/v5/fbs/posting/product/exemplar/validate",
                "/v6/fbs/posting/product/exemplar/set",
                "/v5/fbs/posting/product/exemplar/status"), paths);
    }

    @Test
    void printStagingStopsAfterValidationAndPushResumesWithoutAllocatingAnotherKiz() throws Exception {
        server.enqueue(json(posting("awaiting_deliver")));
        server.enqueue(json(createResponse()));
        server.enqueue(json(validateResponse()));
        OzonExemplarService service = service();

        OzonPreparationResult staged = service.stageForPrint(shop, "POST-1");

        assertEquals("VALIDATED", staged.stage());
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(List.of(
                        "/v3/posting/fbs/get",
                        "/v6/fbs/posting/product/exemplar/create-or-get",
                        "/v5/fbs/posting/product/exemplar/validate"),
                takeRequests(3).stream().map(RecordedRequest::getPath).toList());

        server.enqueue(json(posting("awaiting_deliver")));
        server.enqueue(json("{}"));
        server.enqueue(json(statusAccepted()));
        OzonPreparationResult pushed = service.prepare(shop, "POST-1");

        assertEquals("ACCEPTED", pushed.stage());
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(List.of(
                        "/v3/posting/fbs/get",
                        "/v6/fbs/posting/product/exemplar/set",
                        "/v5/fbs/posting/product/exemplar/status"),
                takeRequests(3).stream().map(RecordedRequest::getPath).toList());
    }

    @Test
    void concurrentDoubleClickIsSerializedAndReservesOnlyOneKiz() throws Exception {
        enqueueHappyPath();
        server.enqueue(json(posting("awaiting_packaging")));
        OzonExemplarService service = service();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<OzonPreparationResult> first = prepareAsync(service, ready, start);
        CompletableFuture<OzonPreparationResult> second = prepareAsync(service, ready, start);
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();

        assertEquals("ACCEPTED", first.join().stage());
        assertEquals("ACCEPTED", second.join().stage());
        List<String> paths = takeRequests(6).stream().map(RecordedRequest::getPath).toList();
        assertEquals(1, paths.stream().filter(path -> path.endsWith("/create-or-get")).count());
        assertEquals(1, paths.stream().filter(path -> path.endsWith("/set")).count());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_codes WHERE status='CONSUMED'"));
    }

    @Test
    void deterministicValidationFailureReleasesReservationAndNeverCallsSet() throws Exception {
        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(createResponse()));
        server.enqueue(json("{\"result\":{\"products\":[{\"exemplars\":[{\"valid\":false}]}]}}"));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("REJECTED", result.stage());
        assertEquals("AVAILABLE", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(3, server.getRequestCount());
        assertFalse(takeRequests(3).stream().anyMatch(request -> request.getPath().endsWith("/set")));
    }

    @Test
    void missingKizKeepsCreatedJobRetryableWithoutCreatingASecondJob() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM kiz_codes");
        }
        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(createResponse()));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("CREATED", result.stage());
        assertEquals("kiz_unavailable", result.safeErrorCode());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplar_jobs"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void timeoutAfterSetReconcilesByReadbackWithoutSubmittingSetTwice() throws Exception {
        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(createResponse()));
        server.enqueue(json(validateResponse()));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(json(statusAccepted()));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("ACCEPTED", result.stage());
        List<String> paths = takeRequests(5).stream().map(RecordedRequest::getPath).toList();
        assertEquals(1, paths.stream().filter(path -> path.endsWith("/set")).count());
        assertEquals(1, paths.stream().filter(path -> path.endsWith("/status")).count());
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }

    @Test
    void terminalRejectedReadbackWithoutRemoteMarksSafelyReleasesKiz() throws Exception {
        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(createResponse()));
        server.enqueue(json(validateResponse()));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(json("{\"status\":\"rejected\",\"products\":[{\"exemplars\":[{\"exemplar_id\":7001}]}]}"));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("REJECTED", result.stage());
        assertEquals("AVAILABLE", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_codes WHERE reservation_token IS NULL"));
    }

    @Test
    void restartAfterUnknownSetResultKeepsReservationAndOnlyReadsStatus() throws Exception {
        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(createResponse()));
        server.enqueue(json(validateResponse()));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setResponseCode(503));

        OzonPreparationResult uncertain = service().prepare(shop, "POST-1");
        assertEquals("RECONCILE_REQUIRED", uncertain.stage());
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        takeRequests(5);

        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(statusAccepted()));
        OzonPreparationResult resumed = service().prepare(shop, "POST-1");

        assertEquals("ACCEPTED", resumed.stage());
        List<String> resumedPaths = takeRequests(2).stream().map(RecordedRequest::getPath).toList();
        assertEquals(List.of("/v3/posting/fbs/get", "/v5/fbs/posting/product/exemplar/status"), resumedPaths);
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='exemplar_set'"));
    }

    private OzonExemplarService service() {
        return new OzonExemplarService(
                new OzonPostingRepository(),
                new OzonProductGtinMappingRepository(),
                new OzonExemplarJobRepository(),
                (shopId, credentials) -> new OzonApiClient(
                        shopId,
                        credentials,
                        server.url("/"),
                        new OkHttpClient.Builder()
                                .connectTimeout(200, TimeUnit.MILLISECONDS)
                                .readTimeout(200, TimeUnit.MILLISECONDS)
                                .writeTimeout(200, TimeUnit.MILLISECONDS)
                                .callTimeout(500, TimeUnit.MILLISECONDS)
                                .build(),
                        new OzonApiRateLimiter(Duration.ZERO),
                        new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1))));
    }

    private CompletableFuture<OzonPreparationResult> prepareAsync(
            OzonExemplarService service, CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                assertTrue(start.await(1, TimeUnit.SECONDS));
                return service.prepare(shop, "POST-1");
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private void enqueueHappyPath() {
        server.enqueue(json(posting("awaiting_packaging")));
        server.enqueue(json(createResponse()));
        server.enqueue(json(validateResponse()));
        server.enqueue(json("{}"));
        server.enqueue(json(statusAccepted()));
    }

    private List<RecordedRequest> takeRequests(int count) throws Exception {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            requests.add(server.takeRequest(1, TimeUnit.SECONDS));
        }
        return requests;
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private int count(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String posting(String status) {
        return "{\"result\":{\"posting_number\":\"POST-1\",\"status\":\"" + status
                + "\",\"products\":[{\"sku\":101,\"offer_id\":\"sku-a\",\"name\":\"Item\",\"quantity\":1}],"
                + "\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},"
                + "\"available_actions\":[\"ship\"]}}";
    }

    private static String postingWithoutRequirements(String status) {
        return "{\"result\":{\"posting_number\":\"POST-1\",\"status\":\"" + status
                + "\",\"products\":[{\"sku\":101,\"offer_id\":\"sku-a\",\"name\":\"Item\",\"quantity\":1}],"
                + "\"requirements\":{},\"available_actions\":[\"ship\"]}}";
    }

    private static String createResponse() {
        return "{\"result\":{\"products\":[{\"exemplars\":[{\"exemplar_id\":7001}]}]}}";
    }

    private static String validateResponse() {
        return "{\"result\":{\"products\":[{\"exemplars\":[{\"valid\":true}]}]}}";
    }

    private static String statusAccepted() {
        return "{\"status\":\"ship_available\",\"products\":[{\"exemplars\":[{\"exemplar_id\":7001,"
                + "\"marks\":[{\"mandatory_mark\":\"stored\",\"check_status\":\"passed\"}]}]}]}";
    }
}
