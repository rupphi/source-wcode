package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

class RegistrationQueueRecoveryTest {
    @TempDir Path directory;
    private final RegistrationQueueStore queue = new RegistrationQueueStore();
    private final Draft draft = new Draft("6104", "6104", 1, "Trousers", "Brand", Map.of(), Map.of());
    @BeforeEach void setup() throws Exception {
        System.setProperty("wcode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(701,'A','test'),(702,'B','test')");
        }
    }
    @AfterEach void cleanup() { System.clearProperty("wcode.appdata.dir"); }

    @Test void authorizedShopIsNotStarvedBehindOneHundredUnopenedShopJobs() {
        for (int id = 1; id <= 100; id++) enqueue(701, id);
        enqueue(702, 101);
        assertEquals(702, queue.pending(Set.of(702)).getFirst().shopId());
        assertTrue(queue.pending(Set.of()).isEmpty());
    }

    @Test void pausedAccountStaysPausedAcrossRestartAndNewSelectionsUntilExplicitResume() {
        enqueue(701, 1); enqueue(701, 2); enqueue(702, 3);
        queue.phase(701, 1, "RUNNING");
        queue.pauseAccount(701);
        enqueue(701, 4);
        var restarted = new RegistrationQueueStore();
        restarted.recoverInterrupted();
        assertTrue(restarted.isAccountPaused(701));
        assertEquals(1, restarted.pending(Set.of(701, 702)).size());
        assertEquals(702, restarted.pending(Set.of(701, 702)).getFirst().shopId());
        assertThrows(IllegalStateException.class, () -> restarted.resumeAccount(701, "changed-fingerprint"));
        assertTrue(restarted.isAccountPaused(701));
        assertEquals(3, restarted.resumeAccount(701, "fingerprint"));
        assertFalse(restarted.isAccountPaused(701));
        assertEquals(3, restarted.pending(Set.of(701)).size());
        assertTrue(restarted.pending(Set.of(701)).stream()
                .allMatch(job -> job.fingerprint().equals("fingerprint")));
    }

    @Test void publicationQueryFiltersAuthorizedShopsBeforeApplyingItsLimit() {
        var registrations = new ZnackCardRegistrationRepository();
        for (int id = 1; id <= 101; id++) {
            int shopId = id <= 100 ? 701 : 702;
            var sku = RegistrationSelectionTest.sku(id, Status.NOT_CREATED);
            registrations.saveGenerated(shopId, sku, String.format("%014d", 4631993764300L + id), "6104", 1, "Name", "{}");
            registrations.updateProgress(shopId, id, Status.PROCESSING, "feed-" + id, null, null, false);
        }
        var store = new RegistrationPublicationStore();
        assertEquals(702, store.due(Set.of(702)).getFirst().shopId());
        assertTrue(store.due(Set.of()).isEmpty());
    }

    @Test void knownFeedCheckpointWinsOverOriginalRetryIntentAfterRestart() {
        Sku submitted = new Sku(10, 1, 1, "ART", "Trousers", "Brand", "Name", "black", "L",
                java.util.List.of("old"), "", true, "04631993764363", null, "new-feed",
                Status.FEED_SUBMITTED, null, false);
        assertEquals(Status.FEED_SUBMITTED, RegistrationRunner.retrySnapshot(submitted, Status.ERROR).status());
        var processing = new Sku(10, 1, 1, "ART", "Trousers", "Brand", "Name", "black", "L",
                java.util.List.of("old"), "", true, "04631993764363", null, "new-feed",
                Status.PROCESSING, null, false);
        assertEquals(Status.PROCESSING, RegistrationRunner.retrySnapshot(processing, Status.ERROR).status());
    }

    @Test void accountErrorsPauseButCardValidationDoesNotPauseOtherProducts() {
        assertTrue(RegistrationRunner.accountWideFailure(new ZnackApiClient.ZnackApiException("failure", 401, "")));
        assertTrue(RegistrationRunner.accountWideFailure(new ZnackApiClient.ZnackApiException("failure", 429, "")));
        assertTrue(RegistrationRunner.accountWideFailure(new java.io.IOException("connection lost")));
        assertTrue(RegistrationRunner.accountWideFailure(new IllegalStateException("GS1/GTIN quota is unavailable or exhausted")));
        assertFalse(RegistrationRunner.accountWideFailure(new IllegalStateException("National Catalog rejected feed: invalid size")));
        assertFalse(RegistrationRunner.accountWideFailure(new ZnackApiClient.ZnackApiException("failure", 400, "invalid size")));
    }

    private void enqueue(int shopId, long id) {
        assertTrue(queue.enqueue(shopId, RegistrationSelectionTest.sku(id, Status.NOT_CREATED), draft, "fingerprint", false));
    }
}
