package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Database arbitration shared by single-row and bulk registration. No credentials are stored here. */
public final class RegistrationQueueStore {
    private static final Gson JSON = new Gson();
    public record Job(int shopId, Sku sku, Draft draft, String fingerprint) { }
    public record Retry(int attempt, Instant nextAttemptAt, String error) { }

    public String credentialFingerprint(int shopId, long chrtId) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT credential_fingerprint FROM znack_registration_queue WHERE shop_id=? AND chrt_id=?")) {
            s.setInt(1, shopId); s.setLong(2, chrtId);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    public boolean enqueue(int shopId, Sku sku, Draft draft, String fingerprint, boolean retry) {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,vendor_code,source_barcode,status,created_at,updated_at)
                    VALUES(?,?,?,?,?,'QUEUED',?,?)
                    ON CONFLICT(shop_id,chrt_id) DO UPDATE SET status='QUEUED',error_message=NULL,feed_id=NULL
                    WHERE ?=1 AND znack_card_registrations.status='ERROR' AND znack_card_registrations.nm_id=excluded.nm_id
                    """)) {
                s.setInt(1, shopId); s.setLong(2, sku.chrtId()); s.setLong(3, sku.nmId());
                s.setString(4, sku.vendorCode()); s.setString(5, sku.sourceBarcode());
                s.setString(6, Instant.now().toString()); s.setString(7, Instant.now().toString()); s.setInt(8, retry ? 1 : 0);
                if (s.executeUpdate() == 0) { c.rollback(); return false; }
            }
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO znack_registration_queue(
                        shop_id,chrt_id,sku_json,draft_json,credential_fingerprint,phase,created_at,
                        attempt_count,next_attempt_at,last_attempt_at,last_error)
                    VALUES(?,?,?,?,?,'QUEUED',?,0,'',NULL,NULL)
                    ON CONFLICT(shop_id,chrt_id) DO UPDATE SET sku_json=excluded.sku_json,draft_json=excluded.draft_json,
                    credential_fingerprint=excluded.credential_fingerprint,phase='QUEUED',created_at=excluded.created_at,
                    attempt_count=0,next_attempt_at='',last_attempt_at=NULL,last_error=NULL
                    WHERE znack_registration_queue.phase IN ('DONE','FAILED','PAUSED')
                    """)) {
                s.setInt(1, shopId); s.setLong(2, sku.chrtId()); s.setString(3, JSON.toJson(sku));
                s.setString(4, JSON.toJson(draft)); s.setString(5, fingerprint);
                s.setString(6, Instant.now().toString());
                if (s.executeUpdate() == 0) { c.rollback(); return false; }
            }
            c.commit(); return true;
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    public List<Job> pending() {
        return pending(null);
    }
    public List<Job> pending(Set<Integer> authorizedShops) {
        if (authorizedShops != null && authorizedShops.isEmpty()) return List.of();
        List<Integer> shopIds = authorizedShops == null ? List.of() : authorizedShops.stream().sorted().toList();
        String shopFilter = authorizedShops == null ? "" : " AND shop_id IN ("
                + String.join(",", java.util.Collections.nCopies(shopIds.size(), "?")) + ")";
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT shop_id,sku_json,draft_json,credential_fingerprint FROM znack_registration_queue "
                        + "WHERE (phase='QUEUED' OR (phase='RETRY_WAIT' AND (next_attempt_at='' OR next_attempt_at<=?)))"
                        + shopFilter + " ORDER BY created_at,shop_id,chrt_id LIMIT 100")) {
            s.setString(1, Instant.now().toString());
            for (int index = 0; index < shopIds.size(); index++) s.setInt(index + 2, shopIds.get(index));
            try (ResultSet r = s.executeQuery()) {
                List<Job> result = new ArrayList<>();
                while (r.next()) result.add(new Job(r.getInt(1), JSON.fromJson(r.getString(2), Sku.class),
                        JSON.fromJson(r.getString(3), Draft.class), r.getString(4)));
                return List.copyOf(result);
            }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    public void pauseAccount(int shopId) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE znack_registration_queue SET phase='ACCOUNT_PAUSED' WHERE shop_id=? AND phase IN ('QUEUED','RUNNING')")) {
            s.setInt(1, shopId); s.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    public Retry retryLater(int shopId, long chrtId, String error) {
        return retryLater(shopId, chrtId, error, Instant.now());
    }

    Retry retryLater(int shopId, long chrtId, String error, Instant now) {
        String message = error == null || error.isBlank() ? "Temporary Znack error." : error.trim();
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            int attempt;
            try (PreparedStatement read = c.prepareStatement(
                    "SELECT attempt_count FROM znack_registration_queue WHERE shop_id=? AND chrt_id=?")) {
                read.setInt(1, shopId); read.setLong(2, chrtId);
                try (ResultSet result = read.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("Registration queue item was not found.");
                    attempt = result.getInt(1) + 1;
                }
            }
            Instant next = now.plus(retryDelaySeconds(attempt), ChronoUnit.SECONDS);
            try (PreparedStatement update = c.prepareStatement("""
                    UPDATE znack_registration_queue
                    SET phase='RETRY_WAIT',attempt_count=?,next_attempt_at=?,last_attempt_at=?,last_error=?
                    WHERE shop_id=? AND chrt_id=?
                    """)) {
                update.setInt(1, attempt); update.setString(2, next.toString());
                update.setString(3, now.toString()); update.setString(4, message);
                update.setInt(5, shopId); update.setLong(6, chrtId); update.executeUpdate();
            }
            c.commit();
            return new Retry(attempt, next, message);
        } catch (SQLException sqlError) { throw new IllegalStateException(sqlError); }
    }

    static long retryDelaySeconds(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 5));
        return Math.min(900L, 30L << exponent);
    }

    /** A non-retryable account problem must be visible on every affected card. */
    public int failAccount(int shopId, String error) {
        String message = error == null || error.isBlank() ? "Znack account action is required." : error.trim();
        String now = Instant.now().toString();
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            int failed;
            try (PreparedStatement registrations = c.prepareStatement("""
                    UPDATE znack_card_registrations
                    SET status='ERROR',error_message=?,updated_at=?
                    WHERE shop_id=? AND EXISTS(
                        SELECT 1 FROM znack_registration_queue q
                        WHERE q.shop_id=znack_card_registrations.shop_id
                          AND q.chrt_id=znack_card_registrations.chrt_id
                          AND q.phase IN ('QUEUED','RUNNING','RETRY_WAIT','ACCOUNT_PAUSED'))
                    """)) {
                registrations.setString(1, message); registrations.setString(2, now);
                registrations.setInt(3, shopId); registrations.executeUpdate();
            }
            try (PreparedStatement queue = c.prepareStatement("""
                    UPDATE znack_registration_queue
                    SET phase='FAILED',last_attempt_at=?,last_error=?
                    WHERE shop_id=? AND phase IN ('QUEUED','RUNNING','RETRY_WAIT','ACCOUNT_PAUSED')
                    """)) {
                queue.setString(1, now); queue.setString(2, message); queue.setInt(3, shopId);
                failed = queue.executeUpdate();
            }
            c.commit(); return failed;
        } catch (SQLException sqlError) { throw new IllegalStateException(sqlError); }
    }

    public boolean isAccountPaused(int shopId) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT 1 FROM znack_registration_queue WHERE shop_id=? AND phase='ACCOUNT_PAUSED' LIMIT 1")) {
            s.setInt(1, shopId);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    /** Resume only under the saved account identity; changing credentials requires reviewing each job. */
    public int resumeAccount(int shopId, String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("Missing account fingerprint.");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM znack_registration_queue WHERE shop_id=? AND phase='ACCOUNT_PAUSED' AND credential_fingerprint<>? LIMIT 1")) {
                check.setInt(1, shopId); check.setString(2, fingerprint);
                try (ResultSet r = check.executeQuery()) {
                    if (r.next()) throw new IllegalStateException("Shop credentials changed; review registration before retrying.");
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE znack_registration_queue SET phase='QUEUED' WHERE shop_id=? AND phase='ACCOUNT_PAUSED'")) {
                s.setInt(1, shopId); int resumed = s.executeUpdate(); c.commit(); return resumed;
            }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    public void phase(int shopId, long chrtId, String phase) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE znack_registration_queue SET phase=? WHERE shop_id=? AND chrt_id=?")) {
            s.setString(1, phase); s.setInt(2, shopId); s.setLong(3, chrtId); s.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    public String phase(int shopId, long chrtId) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT phase FROM znack_registration_queue WHERE shop_id=? AND chrt_id=?")) {
            s.setInt(1, shopId); s.setLong(2, chrtId);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : ""; }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    public void recoverInterrupted() {
        recoverTimeoutFailures();
        // A process crash can occur between remote GTIN allocation and the local checkpoint.
        // Keep uncertain work paused; never silently allocate another code.
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            // Versions up to 1.1.32 paused an entire account forever even for a transient HTTP
            // timeout. Upgrade those queues to the automatic retry state.
            s.executeUpdate("""
                    UPDATE znack_registration_queue
                    SET phase='RETRY_WAIT',next_attempt_at='',attempt_count=0,
                        last_error=COALESCE(last_error,(
                            SELECT r.error_message FROM znack_card_registrations r
                            WHERE r.shop_id=znack_registration_queue.shop_id
                              AND r.chrt_id=znack_registration_queue.chrt_id),'Previous Znack request was interrupted.')
                    WHERE phase='ACCOUNT_PAUSED'
                    """);
            s.executeUpdate("""
                    UPDATE znack_card_registrations SET status='RETRYING',
                        error_message=COALESCE(NULLIF(error_message,''),'Automatic retry scheduled after a temporary Znack failure.'),
                        updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
                    WHERE status IN ('QUEUED','ERROR') AND EXISTS(
                        SELECT 1 FROM znack_registration_queue q
                        WHERE q.shop_id=znack_card_registrations.shop_id
                          AND q.chrt_id=znack_card_registrations.chrt_id AND q.phase='RETRY_WAIT')
                    """);
            s.executeUpdate("""
                    UPDATE znack_registration_queue SET phase=CASE WHEN EXISTS(
                    SELECT 1 FROM znack_card_registrations r WHERE r.shop_id=znack_registration_queue.shop_id
                    AND r.chrt_id=znack_registration_queue.chrt_id AND COALESCE(r.gtin,'')<>'')
                    THEN 'QUEUED' ELSE 'PAUSED' END WHERE phase='RUNNING'
                    """);
            s.executeUpdate("""
                    UPDATE znack_card_registrations SET status='ERROR',
                    error_message='Registration interrupted; check the existing National Catalog card before retrying.'
                    WHERE EXISTS(SELECT 1 FROM znack_registration_queue q WHERE q.shop_id=znack_card_registrations.shop_id
                    AND q.chrt_id=znack_card_registrations.chrt_id AND q.phase='PAUSED')
                    """);
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private void recoverTimeoutFailures() {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            List<long[]> targets = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("""
                    SELECT r.shop_id,r.chrt_id,r.error_message
                    FROM znack_card_registrations r JOIN znack_registration_queue q
                    ON q.shop_id=r.shop_id AND q.chrt_id=r.chrt_id
                    WHERE r.status='ERROR' AND q.phase='FAILED'
                    """)) {
                while (rows.next()) {
                    if (com.tuandev.fbsbarcode.integration.znack.ZnackTimeouts.isStoredTimeout(rows.getString(3)))
                        targets.add(new long[]{rows.getLong(1), rows.getLong(2)});
                }
            }
            try (PreparedStatement q = c.prepareStatement("""
                    UPDATE znack_registration_queue SET phase='RETRY_WAIT',next_attempt_at='',attempt_count=0
                    WHERE shop_id=? AND chrt_id=? AND phase='FAILED'
                    """); PreparedStatement r = c.prepareStatement("""
                    UPDATE znack_card_registrations SET status='RETRYING'
                    WHERE shop_id=? AND chrt_id=? AND status='ERROR'
                    """)) {
                for (long[] target : targets) {
                    q.setLong(1, target[0]); q.setLong(2, target[1]);
                    if (q.executeUpdate() == 1) {
                        r.setLong(1, target[0]); r.setLong(2, target[1]); r.executeUpdate();
                    }
                }
            }
            c.commit();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
}
