package com.tuandev.fbsbarcode.features.finance;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsDatabaseTest {
    @TempDir Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void keepsAnalyticsSchemaInASeparateWalDatabase() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        AnalyticsDatabase.initialize();

        assertTrue(Files.exists(tempDir.resolve("database.db")));
        assertTrue(Files.exists(tempDir.resolve(AnalyticsDatabase.FILE_NAME)));
        try (Connection operational = Database.getConnection()) {
            assertFalse(tableExists(operational, "finance_raw"));
            assertFalse(tableExists(operational, "finance_daily"));
        }
        try (Connection analytics = AnalyticsDatabase.getConnection(); Statement statement = analytics.createStatement()) {
            assertTrue(tableExists(analytics, "finance_raw"));
            assertTrue(tableExists(analytics, "advertising_raw"));
            assertTrue(tableExists(analytics, "finance_daily"));
            assertTrue(tableExists(analytics, "finance_sync_state"));
            assertTrue(columnExists(analytics, "finance_raw", "other_cost"));
            assertTrue(columnExists(analytics, "finance_raw", "advertising_cost"));
            assertTrue(columnExists(analytics, "finance_daily", "other_cost"));
            try (ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
                assertTrue(result.next());
                assertEquals("wal", result.getString(1).toLowerCase());
            }
        }
    }

    @Test
    void bulkUpsertRebuildsDailyReadModelIdempotently() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        FinanceAnalyticsRepository repository = new FinanceAnalyticsRepository();
        String day = LocalDate.of(2026, 8, 20).toString();
        FinanceRawRow sale = row("100", day, false, 500, 380, 1);
        FinanceRawRow returned = row("101", day, true, 100, -80, 1);
        repository.upsertFinanceRows(7, List.of(sale, returned));
        repository.upsertAdvertisingRows(7, List.of(new AdvertisingRawRow(
                "ad-1", day, "1", day + "T10:00:00+03:00", "77", "Campaign", 8,
                "Баланс", 25, "{}")));

        FinanceDashboardSnapshot first = new FinanceDashboardRepository().load(
                7, LocalDate.parse(day), LocalDate.parse(day));
        assertEquals(500, first.grossSales(), 0.001);
        assertEquals(100, first.returnsAmount(), 0.001);
        assertEquals(300, first.netPayout(), 0.001);
        assertEquals(25, first.advertisingCost(), 0.001);
        assertEquals(20, first.logisticsCost(), 0.001);
        assertEquals(0, first.storageCost(), 0.001);
        assertEquals(0, first.otherCost(), 0.001);
        assertEquals(255, first.netProfit(), 0.001);
        assertEquals(1, first.days().getFirst().orderCount());

        FinanceRawRow correctedSale = row("100", day, false, 550, 420, 1);
        repository.upsertFinanceRows(7, List.of(correctedSale));
        FinanceDashboardSnapshot corrected = new FinanceDashboardRepository().load(
                7, LocalDate.parse(day), LocalDate.parse(day));
        assertEquals(550, corrected.grossSales(), 0.001);
        assertEquals(340, corrected.netPayout(), 0.001);
        assertEquals(1, corrected.days().getFirst().orderCount());
    }

    @Test
    void persistsCursorAndRateGateAcrossRepositoryInstances() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        FinanceAnalyticsRepository first = new FinanceAnalyticsRepository();
        LocalDate anchor = LocalDate.of(2026, 8, 25);
        first.ensureShopState(9, anchor);
        FinanceSyncState initial = first.loadState(9, "FINANCE_RECENT");
        Instant attemptedAt = Instant.parse("2026-08-25T02:00:00Z");
        first.startWindow(initial, "INITIAL_30", anchor.minusDays(29), anchor, attemptedAt);
        first.markAttempt(9, "FINANCE_RECENT", attemptedAt.plusSeconds(61));
        FinanceSyncState running = first.loadState(9, "FINANCE_RECENT");
        first.saveProgress(running, "998877665544", attemptedAt.plusSeconds(61), attemptedAt);

        FinanceAnalyticsRepository afterRestart = new FinanceAnalyticsRepository();
        FinanceSyncState restored = afterRestart.loadState(9, "FINANCE_RECENT");
        assertEquals("998877665544", restored.cursor());
        assertEquals(anchor.minusDays(29), restored.windowFrom());
        assertEquals(attemptedAt.plusSeconds(61), afterRestart.familyNextAllowedAt(9, "FINANCE"));
    }

    @Test
    void repairsLegacyThirtyOneDayOzonCheckpointWithoutWaitingForOldErrorGate() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        FinanceAnalyticsRepository repository = new FinanceAnalyticsRepository();
        LocalDate anchor = LocalDate.of(2026, 8, 25);
        Instant now = Instant.parse("2026-08-25T02:00:00Z");
        repository.ensureShopState(12, Marketplace.OZON, anchor, now);
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE finance_sync_state SET phase='BACKFILL_31_90', status='ERROR',
                         window_from='2026-06-26', window_to='2026-07-26', cursor='9',
                         next_allowed_at='2026-08-25T08:00:00Z', last_error='too long period'
                     WHERE shop_id=12 AND stream_name='OZON_FINANCE_BACKFILL'
                     """)) {
            statement.executeUpdate();
        }

        repository.ensureShopState(12, Marketplace.OZON, anchor, now);
        FinanceSyncState repaired = repository.loadState(12, "OZON_FINANCE_BACKFILL");
        assertEquals(LocalDate.of(2026, 6, 29), repaired.windowFrom());
        assertEquals("0", repaired.cursor());
        assertEquals("IDLE", repaired.status());
        assertEquals(now, repaired.nextRunAt());
        assertEquals(null, repaired.nextAllowedAt());
        assertEquals(null, repaired.lastError());
    }

    private static FinanceRawRow row(String id, String day, boolean returned,
                                     double retail, double payout, double quantity) {
        return new FinanceRawRow(id, "report", day, "RUB", returned ? "Возврат" : "Продажа",
                returned ? "Возврат" : "Продажа", "order-1", "nm", "article", "sku",
                quantity, returned, retail, payout, 20, 5, 10, 0, 0, 0, 0, 0, 0, 0, "{}");
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }
}
