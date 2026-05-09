package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableBoolean;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableInteger;

public class WbSupplyRepository {
    public void saveSupplies(int shopId, List<WbSupplyDto> supplies) {
        if (supplies == null || supplies.isEmpty()) {
            return;
        }
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO wb_supplies (
                    shop_id, supply_id, is_b2b, done, created_at, closed_at, scan_dt, reject_dt, name, cargo_type, cross_border_type, destination_office_id, recommended_wh_id, synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(shop_id, supply_id) DO UPDATE SET
                    is_b2b = excluded.is_b2b,
                    done = excluded.done,
                    created_at = excluded.created_at,
                    closed_at = excluded.closed_at,
                    scan_dt = excluded.scan_dt,
                    reject_dt = excluded.reject_dt,
                    name = excluded.name,
                    cargo_type = excluded.cargo_type,
                    cross_border_type = excluded.cross_border_type,
                    destination_office_id = excluded.destination_office_id,
                    recommended_wh_id = excluded.recommended_wh_id,
                    synced_at = excluded.synced_at
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (WbSupplyDto supply : supplies) {
                ps.setInt(1, shopId);
                ps.setString(2, supply.getId());
                setNullableBoolean(ps, 3, supply.getIsB2b());
                setNullableBoolean(ps, 4, supply.getDone());
                ps.setString(5, supply.getCreatedAt());
                ps.setString(6, supply.getClosedAt());
                ps.setString(7, supply.getScanDt());
                ps.setString(8, supply.getRejectDt());
                ps.setString(9, supply.getName());
                setNullableInteger(ps, 10, supply.getCargoType());
                setNullableInteger(ps, 11, supply.getCrossBorderType());
                setNullableInteger(ps, 12, supply.getDestinationOfficeId());
                setNullableInteger(ps, 13, supply.getRecommendedWhId());
                ps.setString(14, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<WbSupplySummary> getSupplySummaries(int shopId) {
        List<WbSupplySummary> supplies = new ArrayList<>();
        String sql = """
                SELECT supply_id, name, done, is_b2b, created_at
                FROM wb_supplies
                WHERE shop_id = ?
                ORDER BY done ASC, created_at DESC, supply_id DESC
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                supplies.add(new WbSupplySummary(
                        rs.getString("supply_id"),
                        rs.getString("name"),
                        rs.getInt("done") == 1,
                        rs.getObject("is_b2b") == null ? null : rs.getInt("is_b2b") == 1,
                        rs.getString("created_at")
                ));
            }
            return supplies;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getRecentSupplyIds(int shopId, int limit) {
        List<String> supplyIds = new ArrayList<>();
        String sql = """
                SELECT DISTINCT supply_id
                FROM wb_orders
                WHERE shop_id = ? AND supply_id IS NOT NULL AND supply_id <> ''
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                supplyIds.add(rs.getString("supply_id"));
            }
            return supplyIds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getOpenSupplyIds(int shopId, int limit) {
        List<String> supplyIds = new ArrayList<>();
        String sql = """
                SELECT supply_id
                FROM wb_supplies
                WHERE shop_id = ? AND done = 0
                ORDER BY created_at DESC, supply_id DESC
                LIMIT ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                supplyIds.add(rs.getString("supply_id"));
            }
            return supplyIds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
