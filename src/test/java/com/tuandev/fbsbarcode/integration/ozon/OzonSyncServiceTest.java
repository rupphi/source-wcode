package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonSyncServiceTest {
    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;
    private OzonApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        System.setProperty("wcode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client-1','secret')");
        }
        api = new OzonApiClient(
                1,
                new OzonCredentials("client-1", "secret"),
                server.url("/"),
                new OkHttpClient.Builder()
                        .connectTimeout(200, TimeUnit.MILLISECONDS)
                        .readTimeout(200, TimeUnit.MILLISECONDS)
                        .writeTimeout(200, TimeUnit.MILLISECONDS)
                        .callTimeout(500, TimeUnit.MILLISECONDS)
                        .build(),
                new OzonApiRateLimiter(Duration.ZERO),
                new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("wcode.appdata.dir");
        server.shutdown();
    }

    @Test
    void catalogDoesNotAdvanceCursorWhenDetailFetchFails() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"90071992547409931234\","
                + "\"offer_id\":\"offer-1\"}],\"last_id\":\"cursor-2\"}}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(OzonApiException.class, () -> new OzonCatalogSyncService(1, api).sync());

        assertEquals(0, count("SELECT COUNT(*) FROM ozon_products"));
        assertEquals("", new OzonSyncStateRepository().find(1).productsLastId());
        assertEquals("upstream", new OzonSyncStateRepository().find(1).lastError());
    }

    @Test
    void postingPageCommitsButRollingCursorDoesNotAdvanceAfterLaterPageFailure() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"cursor-2\",\"has_next\":true}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(OzonApiException.class, () -> new OzonPostingSyncService(1, api).sync());

        assertEquals(1, count("SELECT COUNT(*) FROM ozon_postings"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_posting_items"));
        assertEquals("", new OzonSyncStateRepository().find(1).postingsChangedSince());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()
                .contains("\"cursor\":\"cursor-2\""));
    }

    @Test
    void invalidPostingCursorIsRecordedWithoutAdvancingRollingWindow() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting() + "],\"cursor\":\"\",\"has_next\":true}"));

        assertThrows(IOException.class, () -> new OzonPostingSyncService(1, api).sync());

        OzonSyncState syncState = new OzonSyncStateRepository().find(1);
        assertEquals("", syncState.postingsChangedSince());
        assertEquals("invalid_response", syncState.lastError());
    }

    @Test
    void successfulCatalogPageKeepsExternalIdsAsTextAndAdvancesCursorAtomically() throws Exception {
        String externalId = "90071992547409931234";
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\"}],\"last_id\":\"cursor-2\"}}"));
        server.enqueue(json("{\"items\":[{\"id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\",\"sku\":\"sku-1\",\"name\":\"Item\"}]}"));

        assertEquals(1, new OzonCatalogSyncService(1, api).sync());

        assertEquals(externalId, scalar("SELECT product_id FROM ozon_products"));
        assertEquals("cursor-2", new OzonSyncStateRepository().find(1).productsLastId());
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v3/product/list"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v3/product/info/list"));
    }

    @Test
    void catalogPageWithoutForwardCursorFailsClosedBeforePersistingProducts() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"101\","
                + "\"offer_id\":\"offer-1\"}],\"last_id\":\"\"}}"));
        server.enqueue(json("{\"items\":[{\"id\":\"101\",\"offer_id\":\"offer-1\"}]}"));

        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).sync());

        assertEquals(0, count("SELECT COUNT(*) FROM ozon_products"));
        OzonSyncState syncState = new OzonSyncStateRepository().find(1);
        assertEquals("", syncState.productsLastId());
        assertEquals("invalid_response", syncState.lastError());
    }

    private int count(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String posting() {
        return "{\"posting_number\":\"POST-TEXT-1\",\"status\":\"awaiting_packaging\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{},\"available_actions\":[]}";
    }
}
