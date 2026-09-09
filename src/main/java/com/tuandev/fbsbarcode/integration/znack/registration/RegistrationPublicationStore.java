package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

final class RegistrationPublicationStore {
    record Target(int shopId, long chrtId) { }
    List<Target> due() {
        return due(null);
    }
    List<Target> due(Set<Integer> authorizedShops) {
        if (authorizedShops != null && authorizedShops.isEmpty()) return List.of();
        List<Integer> shopIds = authorizedShops == null ? List.of() : authorizedShops.stream().sorted().toList();
        String shopFilter = authorizedShops == null ? "" : " AND r.shop_id IN ("
                + String.join(",", java.util.Collections.nCopies(shopIds.size(), "?")) + ")";
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT r.shop_id,r.chrt_id FROM znack_card_registrations r
                LEFT JOIN znack_registration_publication p ON p.shop_id=r.shop_id AND p.chrt_id=r.chrt_id
                WHERE COALESCE(r.gtin,'')<>'' AND COALESCE(r.feed_id,'')<>''
                AND r.status NOT IN ('QUEUED','CHECKING','GTIN_GENERATED')
                AND (r.wb_updated=0 OR COALESCE(p.ready_for_kiz,0)=0)
                AND COALESCE(p.next_check_at,'')<=?
                """ + shopFilter + " ORDER BY COALESCE(p.next_check_at,''),r.updated_at LIMIT 100")) {
            s.setString(1, Instant.now().toString());
            for (int index = 0; index < shopIds.size(); index++) s.setInt(index + 2, shopIds.get(index));
            try (ResultSet r = s.executeQuery()) {
                List<Target> result = new ArrayList<>();
                while (r.next()) result.add(new Target(r.getInt(1), r.getLong(2)));
                return result;
            }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    void schedule(int shopId, long chrtId, boolean ready, Duration delay) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO znack_registration_publication(shop_id,chrt_id,next_check_at,ready_for_kiz) VALUES(?,?,?,?)
                ON CONFLICT(shop_id,chrt_id) DO UPDATE SET next_check_at=excluded.next_check_at,ready_for_kiz=excluded.ready_for_kiz
                """)) {
            s.setInt(1, shopId); s.setLong(2, chrtId); s.setString(3, Instant.now().plus(delay).toString());
            s.setInt(4, ready ? 1 : 0); s.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    boolean mayWrite(int shopId, long chrtId) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT wb_attempt_at FROM znack_registration_publication WHERE shop_id=? AND chrt_id=?")) {
            s.setInt(1, shopId); s.setLong(2, chrtId);
            try (ResultSet r = s.executeQuery()) {
                return !r.next() || r.getString(1) == null || Instant.parse(r.getString(1)).plus(Duration.ofMinutes(30)).isBefore(Instant.now());
            }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    void beforeWrite(int shopId, long chrtId) {
        try (Connection c = Database.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE znack_registration_publication SET wb_attempt_at=? WHERE shop_id=? AND chrt_id=?")) {
            s.setString(1, Instant.now().toString()); s.setInt(2, shopId); s.setLong(3, chrtId);
            if (s.executeUpdate() != 1) throw new IllegalStateException("Missing WB write checkpoint.");
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
}
