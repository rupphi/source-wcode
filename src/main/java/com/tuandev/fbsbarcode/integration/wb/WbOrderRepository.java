package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.deleteByKey;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.safeLong;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableBoolean;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableDouble;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableInteger;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableLong;

public class WbOrderRepository {
    public void saveOrders(int shopId, List<WbOrderDto> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                saveOrders(conn, shopId, orders);
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    void saveOrders(Connection conn, int shopId, List<WbOrderDto> orders) throws SQLException {
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO wb_orders (
                    shop_id, order_id, order_uid, rid, supply_id, delivery_type, ddate, seller_date, comment,
                    user_id, article, color_code, warehouse_id, office_id, nm_id, chrt_id, price, final_price, sale_price,
                    converted_price, converted_final_price, currency_code, converted_currency_code, cargo_type, cross_border_type,
                    scan_price, is_zero_order, is_b2b, created_at, synced_at, address_full, address_longitude, address_latitude
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(shop_id, order_id) DO UPDATE SET
                    order_uid = excluded.order_uid,
                    rid = excluded.rid,
                    supply_id = excluded.supply_id,
                    delivery_type = excluded.delivery_type,
                    ddate = excluded.ddate,
                    seller_date = excluded.seller_date,
                    comment = excluded.comment,
                    user_id = excluded.user_id,
                    article = excluded.article,
                    color_code = excluded.color_code,
                    warehouse_id = excluded.warehouse_id,
                    office_id = excluded.office_id,
                    nm_id = excluded.nm_id,
                    chrt_id = excluded.chrt_id,
                    price = excluded.price,
                    final_price = excluded.final_price,
                    sale_price = excluded.sale_price,
                    converted_price = excluded.converted_price,
                    converted_final_price = excluded.converted_final_price,
                    currency_code = excluded.currency_code,
                    converted_currency_code = excluded.converted_currency_code,
                    cargo_type = excluded.cargo_type,
                    cross_border_type = excluded.cross_border_type,
                    scan_price = excluded.scan_price,
                    is_zero_order = excluded.is_zero_order,
                    is_b2b = excluded.is_b2b,
                    created_at = excluded.created_at,
                    synced_at = excluded.synced_at,
                    address_full = excluded.address_full,
                    address_longitude = excluded.address_longitude,
                    address_latitude = excluded.address_latitude
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (WbOrderDto order : orders) {
                ps.setInt(1, shopId);
                ps.setLong(2, safeLong(order.getId()));
                ps.setString(3, order.getOrderUid());
                ps.setString(4, order.getRid());
                ps.setString(5, order.getSupplyId());
                ps.setString(6, order.getDeliveryType());
                ps.setString(7, order.getDdate());
                ps.setString(8, order.getSellerDate());
                ps.setString(9, order.getComment());
                setNullableLong(ps, 10, order.getUserId());
                ps.setString(11, order.getArticle());
                ps.setString(12, order.getColorCode());
                setNullableInteger(ps, 13, order.getWarehouseId());
                setNullableInteger(ps, 14, order.getOfficeId());
                setNullableLong(ps, 15, order.getNmId());
                setNullableLong(ps, 16, order.getChrtId());
                setNullableInteger(ps, 17, order.getPrice());
                setNullableInteger(ps, 18, order.getFinalPrice());
                setNullableInteger(ps, 19, order.getSalePrice());
                setNullableInteger(ps, 20, order.getConvertedPrice());
                setNullableInteger(ps, 21, order.getConvertedFinalPrice());
                setNullableInteger(ps, 22, order.getCurrencyCode());
                setNullableInteger(ps, 23, order.getConvertedCurrencyCode());
                setNullableInteger(ps, 24, order.getCargoType());
                setNullableInteger(ps, 25, order.getCrossBorderType());
                setNullableInteger(ps, 26, order.getScanPrice());
                setNullableBoolean(ps, 27, order.getIsZeroOrder());
                setNullableBoolean(ps, 28, order.getOptions() == null ? null : order.getOptions().getIsB2B());
                ps.setString(29, order.getCreatedAt());
                ps.setString(30, now);
                ps.setString(31, order.getAddress() == null ? null : order.getAddress().getFullAddress());
                setNullableDouble(ps, 32, order.getAddress() == null ? null : order.getAddress().getLongitude());
                setNullableDouble(ps, 33, order.getAddress() == null ? null : order.getAddress().getLatitude());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        for (WbOrderDto order : orders) {
            long orderId = safeLong(order.getId());
            deleteOrderChildren(conn, shopId, orderId);
            insertOrderOffices(conn, shopId, orderId, order.getOffices());
            insertOrderSkus(conn, shopId, orderId, order.getSkus());
            insertOrderMetaRequirements(conn, shopId, orderId, order.getRequiredMeta(), "required");
            insertOrderMetaRequirements(conn, shopId, orderId, order.getOptionalMeta(), "optional");
            if (order.getSupplyId() != null && !order.getSupplyId().isBlank()) {
                linkSupplyOrder(conn, shopId, order.getSupplyId(), orderId);
            }
        }
    }

    public void updateOrderStatuses(int shopId, List<WbOrderStatusDto> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        String sql = """
                UPDATE wb_orders
                SET supplier_status = ?, wb_status = ?, is_cancellable = ?, status_synced_at = ?
                WHERE shop_id = ? AND order_id = ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (WbOrderStatusDto status : statuses) {
                ps.setString(1, status.getSupplierStatus());
                ps.setString(2, status.getWbStatus());
                setNullableBoolean(ps, 3, status.getIsCancellable());
                ps.setString(4, now);
                ps.setInt(5, shopId);
                ps.setLong(6, safeLong(status.getId()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void replaceSupplyOrders(int shopId, String supplyId, List<Long> orderIds) {
        List<Long> safeOrderIds = orderIds == null ? Collections.emptyList() : orderIds;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM wb_supply_orders WHERE shop_id = ? AND supply_id = ?");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT OR IGNORE INTO wb_supply_orders (shop_id, supply_id, order_id) " +
                                 "SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM wb_orders WHERE shop_id = ? AND order_id = ?)");
                 PreparedStatement updateOrders = conn.prepareStatement(
                         "UPDATE wb_orders SET supply_id = ? WHERE shop_id = ? AND order_id = ?")) {
                delete.setInt(1, shopId);
                delete.setString(2, supplyId);
                delete.executeUpdate();

                for (Long orderId : safeOrderIds) {
                    insert.setInt(1, shopId);
                    insert.setString(2, supplyId);
                    insert.setLong(3, orderId);
                    insert.setInt(4, shopId);
                    insert.setLong(5, orderId);
                    insert.addBatch();

                    updateOrders.setString(1, supplyId);
                    updateOrders.setInt(2, shopId);
                    updateOrders.setLong(3, orderId);
                    updateOrders.addBatch();
                }
                insert.executeBatch();
                updateOrders.executeBatch();
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Long> getOrderIdsForSupply(int shopId, String supplyId) {
        List<Long> orderIds = new ArrayList<>();
        String sql = "SELECT order_id FROM wb_supply_orders WHERE shop_id = ? AND supply_id = ? ORDER BY order_id";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                orderIds.add(rs.getLong("order_id"));
            }
            return orderIds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Order> getOrdersForSupply(int shopId, String supplyId) {
        List<Order> orders = new ArrayList<>();
        String sql = """
                SELECT o.order_id, COALESCE(pc.brand, '') AS brand, COALESCE(pc.title, '') AS title,
                       COALESCE(pc.subject_name, '') AS subject_name,
                       COALESCE(ps.tech_size, '') AS tech_size,
                       COALESCE(NULLIF(o.color_code, ''),
                                (SELECT COALESCE(json_extract(ch.value_json, '$[0]'), json_extract(ch.value_json, '$'))
                                 FROM wb_product_characteristics ch
                                 WHERE ch.shop_id = o.shop_id
                                   AND ch.nm_id = o.nm_id
                                   AND ch.characteristic_id IN (14177449, 204557)
                                 ORDER BY CASE ch.characteristic_id
                                          WHEN 14177449 THEN 0
                                          WHEN 204557 THEN 1
                                          ELSE 9
                                          END
                                 LIMIT 1),
                                '') AS color_code,
                       COALESCE(o.article, COALESCE(pc.vendor_code, '')) AS article,
                       COALESCE((SELECT sku FROM wb_order_skus os WHERE os.shop_id = o.shop_id AND os.order_id = o.order_id ORDER BY sku LIMIT 1),
                                (SELECT sku FROM wb_product_size_skus pss WHERE pss.shop_id = o.shop_id AND pss.chrt_id = o.chrt_id ORDER BY sku LIMIT 1),
                                '') AS barcode,
                       COALESCE((SELECT COALESCE(pp.c246x328_url, pp.square_url, pp.big_url, pp.hq_url, pp.tm_url, '')
                                 FROM wb_product_photos pp
                                 WHERE pp.shop_id = o.shop_id AND pp.nm_id = o.nm_id
                                 ORDER BY pp.photo_index
                                 LIMIT 1), '') AS image_url
                FROM wb_orders o
                LEFT JOIN wb_product_cards pc ON pc.shop_id = o.shop_id AND pc.nm_id = o.nm_id
                LEFT JOIN wb_product_sizes ps ON ps.shop_id = o.shop_id AND ps.chrt_id = o.chrt_id
                WHERE o.shop_id = ? AND o.supply_id = ?
                ORDER BY article COLLATE NOCASE, o.order_id
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Order order = new Order();
                order.setId(rs.getLong("order_id"));
                order.setBrand(rs.getString("brand"));
                order.setName(rs.getString("title"));
                order.setSubjectName(rs.getString("subject_name"));
                order.setSize(rs.getString("tech_size"));
                order.setColor(rs.getString("color_code"));
                order.setArticle(rs.getString("article"));
                order.setBarcode(rs.getString("barcode"));
                order.setImageUrl(rs.getString("image_url"));
                orders.add(order);
            }
            return orders;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasMissingProductsForSupply(int shopId, String supplyId) {
        String sql = """
                SELECT COUNT(*)
                FROM wb_supply_orders so
                JOIN wb_orders o ON o.shop_id = so.shop_id AND o.order_id = so.order_id
                LEFT JOIN wb_product_cards pc ON pc.shop_id = o.shop_id AND pc.nm_id = o.nm_id
                WHERE so.shop_id = ? AND so.supply_id = ? AND o.nm_id IS NOT NULL AND pc.nm_id IS NULL
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteOrderChildren(Connection conn, int shopId, long orderId) throws SQLException {
        deleteByKey(conn, "DELETE FROM wb_order_offices WHERE shop_id = ? AND order_id = ?", shopId, orderId);
        deleteByKey(conn, "DELETE FROM wb_order_skus WHERE shop_id = ? AND order_id = ?", shopId, orderId);
        deleteByKey(conn, "DELETE FROM wb_order_meta_requirements WHERE shop_id = ? AND order_id = ?", shopId, orderId);
        deleteByKey(conn, "DELETE FROM wb_supply_orders WHERE shop_id = ? AND order_id = ?", shopId, orderId);
    }

    private void insertOrderOffices(Connection conn, int shopId, long orderId, List<String> offices) throws SQLException {
        if (offices == null || offices.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO wb_order_offices (shop_id, order_id, office_index, office_name) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < offices.size(); i++) {
                ps.setInt(1, shopId);
                ps.setLong(2, orderId);
                ps.setInt(3, i);
                ps.setString(4, offices.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertOrderSkus(Connection conn, int shopId, long orderId, List<String> skus) throws SQLException {
        if (skus == null || skus.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO wb_order_skus (shop_id, order_id, sku) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String sku : skus) {
                ps.setInt(1, shopId);
                ps.setLong(2, orderId);
                ps.setString(3, sku);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertOrderMetaRequirements(Connection conn, int shopId, long orderId, List<String> metaKeys, String requirementType) throws SQLException {
        if (metaKeys == null || metaKeys.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO wb_order_meta_requirements (shop_id, order_id, meta_key, requirement_type) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String metaKey : metaKeys) {
                ps.setInt(1, shopId);
                ps.setLong(2, orderId);
                ps.setString(3, metaKey);
                ps.setString(4, requirementType);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void linkSupplyOrder(Connection conn, int shopId, String supplyId, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO wb_supply_orders (shop_id, supply_id, order_id) VALUES (?, ?, ?)")) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ps.setLong(3, orderId);
            ps.executeUpdate();
        }
    }
}
