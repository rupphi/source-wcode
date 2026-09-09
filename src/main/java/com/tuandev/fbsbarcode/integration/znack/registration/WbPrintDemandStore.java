package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Persists the exact explicit print demand before enqueueing a purchase. */
final class WbPrintDemandStore {
    record Intent(String requestKey, int quantity) { }
    Intent find(int shopId, String gtin, String demand) {
        try (var c = Database.getConnection(); var s = c.prepareStatement(
                "SELECT request_key,quantity FROM wb_print_kiz_demands WHERE shop_id=? AND gtin=? AND demand_key=?")) {
            s.setInt(1, shopId); s.setString(2, gtin); s.setString(3, demand);
            try (var r = s.executeQuery()) { return r.next() ? new Intent(r.getString(1), r.getInt(2)) : null; }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    Intent create(int shopId, String gtin, String demand, int quantity) {
        try (var c = Database.getConnection(); var s = c.prepareStatement(
                "INSERT OR IGNORE INTO wb_print_kiz_demands VALUES(?,?,?,?,?,?)")) {
            s.setInt(1, shopId); s.setString(2, gtin); s.setString(3, demand); s.setInt(4, quantity);
            s.setString(5, UUID.randomUUID().toString()); s.setString(6, Instant.now().toString()); s.executeUpdate();
            return find(shopId, gtin, demand);
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    void complete(int shopId, String gtin, String demand) {
        try (var c = Database.getConnection(); var s = c.prepareStatement(
                "DELETE FROM wb_print_kiz_demands WHERE shop_id=? AND gtin=? AND demand_key=?")) {
            s.setInt(1, shopId); s.setString(2, gtin); s.setString(3, demand); s.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    Long outstandingPipeline(int shopId, String gtin) {
        try (var c = Database.getConnection(); var s = c.prepareStatement("""
                SELECT id FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=? AND stage<>'INTRODUCED'
                AND (stage NOT IN ('FAILED','COMPLETED') OR id > COALESCE((SELECT MAX(done.id) FROM znack_purchase_pipelines done
                    WHERE done.shop_id=znack_purchase_pipelines.shop_id AND done.gtin=znack_purchase_pipelines.gtin
                    AND done.stage='INTRODUCED'),0))
                ORDER BY id DESC LIMIT 1
                """)) {
            s.setInt(1, shopId); s.setString(2, gtin);
            try (var r = s.executeQuery()) { return r.next() ? r.getLong(1) : null; }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
}
