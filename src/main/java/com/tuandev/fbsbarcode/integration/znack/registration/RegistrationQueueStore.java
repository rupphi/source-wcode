package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Database arbitration shared by single-row and bulk registration. No credentials are stored here. */
public final class RegistrationQueueStore {
    private static final Gson JSON = new Gson();
    public record Job(int shopId, Sku sku, Draft draft, String fingerprint) { }

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
                    ON CONFLICT(shop_id,chrt_id) DO UPDATE SET status='QUEUED',error_message=NULL
                    WHERE ?=1 AND znack_card_registrations.status='ERROR' AND znack_card_registrations.nm_id=excluded.nm_id
                    """)) {
                s.setInt(1, shopId); s.setLong(2, sku.chrtId()); s.setLong(3, sku.nmId());
                s.setString(4, sku.vendorCode()); s.setString(5, sku.sourceBarcode());
                s.setString(6, Instant.now().toString()); s.setString(7, Instant.now().toString()); s.setInt(8, retry ? 1 : 0);
                if (s.executeUpdate() == 0) { c.rollback(); return false; }
            }
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO znack_registration_queue VALUES(?,?,?,?,?,CASE WHEN EXISTS(
                        SELECT 1 FROM znack_registration_queue WHERE shop_id=? AND phase='ACCOUNT_PAUSED'
                    ) THEN 'ACCOUNT_PAUSED' ELSE 'QUEUED' END,?)
                    ON CONFLICT(shop_id,chrt_id) DO UPDATE SET sku_json=excluded.sku_json,draft_json=excluded.draft_json,
                    credential_fingerprint=excluded.credential_fingerprint,phase=excluded.phase,created_at=excluded.created_at
                    WHERE znack_registration_queue.phase IN ('DONE','FAILED','PAUSED')
                    """)) {
                s.setInt(1, shopId); s.setLong(2, sku.chrtId()); s.setString(3, JSON.toJson(sku));
                s.setString(4, JSON.toJson(draft)); s.setString(5, fingerprint); s.setInt(6, shopId);
                s.setString(7, Instant.now().toString());
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
                "SELECT shop_id,sku_json,draft_json,credential_fingerprint FROM znack_registration_queue WHERE phase='QUEUED'"
                        + shopFilter + " ORDER BY created_at,shop_id,chrt_id LIMIT 100")) {
            for (int index = 0; index < shopIds.size(); index++) s.setInt(index + 1, shopIds.get(index));
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
        // A process crash can occur between remote GTIN allocation and the local checkpoint.
        // Keep uncertain work paused; never silently allocate another code.
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
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
}
