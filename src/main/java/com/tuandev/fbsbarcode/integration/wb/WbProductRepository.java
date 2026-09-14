package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.safeLong;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableBoolean;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableDouble;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableInteger;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableLong;

public class WbProductRepository {
    private static final int IN_CLAUSE_BATCH_SIZE = 250;

    public void saveProductBatch(int shopId, List<WbProductCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                saveProductBatch(conn, shopId, cards);
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

    /**
     * Removes cards which were not present in a successfully completed WB catalog snapshot.
     * The comparison and deletion are scoped to one shop and committed atomically.
     */
    public int deleteProductsMissingFromSnapshot(int shopId, Set<Long> activeNmIds) {
        if (shopId <= 0) throw new IllegalArgumentException("Invalid WB shop id.");
        Set<Long> active = activeNmIds == null ? Set.of() : activeNmIds.stream()
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<Long> missing = findProductIds(conn, shopId).stream()
                        .filter(nmId -> !active.contains(nmId))
                        .toList();
                int deleted = deleteProducts(conn, shopId, missing);
                conn.commit();
                return deleted;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private List<Long> findProductIds(Connection conn, int shopId) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT nm_id FROM wb_product_cards WHERE shop_id = ?")) {
            statement.setInt(1, shopId);
            try (var result = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (result.next()) ids.add(result.getLong(1));
                return ids;
            }
        }
    }

    private int deleteProducts(Connection conn, int shopId, List<Long> nmIds) throws SQLException {
        int deleted = 0;
        for (int start = 0; start < nmIds.size(); start += IN_CLAUSE_BATCH_SIZE) {
            List<Long> batch = nmIds.subList(start, Math.min(start + IN_CLAUSE_BATCH_SIZE, nmIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement statement = conn.prepareStatement(
                    "DELETE FROM wb_product_cards WHERE shop_id = ? AND nm_id IN (" + placeholders + ")")) {
                statement.setInt(1, shopId);
                int parameter = 2;
                for (Long nmId : batch) statement.setLong(parameter++, nmId);
                deleted += statement.executeUpdate();
            }
        }
        return deleted;
    }

    void saveProductBatch(Connection conn, int shopId, List<WbProductCard> cards) throws SQLException {
        String now = Instant.now().toString();
        String upsertCard = """
                INSERT INTO wb_product_cards (
                    shop_id, nm_id, imt_id, nm_uuid, subject_id, subject_name, vendor_code,
                    kiz_marked, need_kiz, brand, title, description, video_url,
                    is_swatch_try_on,
                    wholesale_enabled, wholesale_quantum,
                    dimension_length, dimension_width, dimension_height, dimension_weight_brutto, dimension_is_valid,
                    created_at, updated_at, synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(shop_id, nm_id) DO UPDATE SET
                    imt_id = excluded.imt_id,
                    nm_uuid = excluded.nm_uuid,
                    subject_id = excluded.subject_id,
                    subject_name = excluded.subject_name,
                    vendor_code = excluded.vendor_code,
                    kiz_marked = excluded.kiz_marked,
                    need_kiz = excluded.need_kiz,
                    brand = excluded.brand,
                    title = excluded.title,
                    description = excluded.description,
                    video_url = excluded.video_url,
                    is_swatch_try_on = excluded.is_swatch_try_on,
                    wholesale_enabled = excluded.wholesale_enabled,
                    wholesale_quantum = excluded.wholesale_quantum,
                    dimension_length = excluded.dimension_length,
                    dimension_width = excluded.dimension_width,
                    dimension_height = excluded.dimension_height,
                    dimension_weight_brutto = excluded.dimension_weight_brutto,
                    dimension_is_valid = excluded.dimension_is_valid,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    synced_at = excluded.synced_at
                """;
        try (PreparedStatement psCard = conn.prepareStatement(upsertCard)) {
            for (WbProductCard card : cards) {
                long nmId = safeLong(card.getNmID());
                psCard.setInt(1, shopId);
                psCard.setLong(2, nmId);
                setNullableLong(psCard, 3, card.getImtID());
                psCard.setString(4, card.getNmUUID());
                setNullableInteger(psCard, 5, card.getSubjectID());
                psCard.setString(6, card.getSubjectName());
                psCard.setString(7, card.getVendorCode());
                setNullableBoolean(psCard, 8, card.getKizMarked());
                setNullableBoolean(psCard, 9, card.getNeedKiz());
                psCard.setString(10, card.getBrand());
                psCard.setString(11, card.getTitle());
                psCard.setString(12, card.getDescription());
                psCard.setString(13, card.getVideo());
                setNullableBoolean(psCard, 14, card.getIsSwatchTryOn());
                setNullableBoolean(psCard, 15, card.getWholesale() == null ? null : card.getWholesale().getEnabled());
                setNullableInteger(psCard, 16, card.getWholesale() == null ? null : card.getWholesale().getQuantum());
                setNullableDouble(psCard, 17, card.getDimensions() == null ? null : card.getDimensions().getLength());
                setNullableDouble(psCard, 18, card.getDimensions() == null ? null : card.getDimensions().getWidth());
                setNullableDouble(psCard, 19, card.getDimensions() == null ? null : card.getDimensions().getHeight());
                setNullableDouble(psCard, 20, card.getDimensions() == null ? null : card.getDimensions().getWeightBrutto());
                setNullableBoolean(psCard, 21, card.getDimensions() == null ? null : card.getDimensions().getIsValid());
                psCard.setString(22, card.getCreatedAt());
                psCard.setString(23, card.getUpdatedAt());
                psCard.setString(24, now);
                psCard.addBatch();
            }
            psCard.executeBatch();
        }
        List<Long> nmIds = cards.stream()
                .map(WbProductCard::getNmID)
                .map(WbRepositorySupport::safeLong)
                .filter(nmId -> nmId > 0)
                .distinct()
                .toList();
        deleteProductChildren(conn, shopId, nmIds);
        insertProductChildren(conn, shopId, cards);
    }

    private void deleteProductChildren(Connection conn, int shopId, List<Long> nmIds) throws SQLException {
        if (nmIds == null || nmIds.isEmpty()) {
            return;
        }
        for (int start = 0; start < nmIds.size(); start += IN_CLAUSE_BATCH_SIZE) {
            List<Long> batch = nmIds.subList(start, Math.min(start + IN_CLAUSE_BATCH_SIZE, nmIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            executeDeleteByNmBatch(conn, "DELETE FROM wb_product_photos WHERE shop_id = ? AND nm_id IN (" + placeholders + ")", shopId, batch);
            executeDeleteSizeSkusByNmBatch(conn, shopId, batch, placeholders);
            executeDeleteByNmBatch(conn, "DELETE FROM wb_product_sizes WHERE shop_id = ? AND nm_id IN (" + placeholders + ")", shopId, batch);
            executeDeleteByNmBatch(conn, "DELETE FROM wb_product_characteristics WHERE shop_id = ? AND nm_id IN (" + placeholders + ")", shopId, batch);
            executeDeleteByNmBatch(conn, "DELETE FROM wb_product_tags WHERE shop_id = ? AND nm_id IN (" + placeholders + ")", shopId, batch);
        }
    }

    private void executeDeleteByNmBatch(Connection conn, String sql, int shopId, List<Long> nmIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            int index = 2;
            for (Long nmId : nmIds) {
                ps.setLong(index++, nmId);
            }
            ps.executeUpdate();
        }
    }

    private void executeDeleteSizeSkusByNmBatch(Connection conn, int shopId, List<Long> nmIds, String placeholders) throws SQLException {
        String sql = "DELETE FROM wb_product_size_skus WHERE shop_id = ? AND chrt_id IN (" +
                "SELECT chrt_id FROM wb_product_sizes WHERE shop_id = ? AND nm_id IN (" + placeholders + "))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, shopId);
            int index = 3;
            for (Long nmId : nmIds) {
                ps.setLong(index++, nmId);
            }
            ps.executeUpdate();
        }
    }

    private void insertProductChildren(Connection conn, int shopId, List<WbProductCard> cards) throws SQLException {
        String photosSql = "INSERT INTO wb_product_photos (shop_id, nm_id, photo_index, big_url, c246x328_url, c516x688_url, hq_url, square_url, tm_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String sizesSql = "INSERT INTO wb_product_sizes (shop_id, chrt_id, nm_id, tech_size, wb_size) VALUES (?, ?, ?, ?, ?)";
        String skusSql = "INSERT INTO wb_product_size_skus (shop_id, chrt_id, sku) VALUES (?, ?, ?)";
        String characteristicsSql = "INSERT INTO wb_product_characteristics (shop_id, nm_id, characteristic_id, name, value_json) VALUES (?, ?, ?, ?, ?)";
        String tagsSql = "INSERT INTO wb_product_tags (shop_id, nm_id, tag_id, name, color) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement photosPs = conn.prepareStatement(photosSql);
             PreparedStatement sizesPs = conn.prepareStatement(sizesSql);
             PreparedStatement skusPs = conn.prepareStatement(skusSql);
             PreparedStatement characteristicsPs = conn.prepareStatement(characteristicsSql);
             PreparedStatement tagsPs = conn.prepareStatement(tagsSql)) {
            for (WbProductCard card : cards) {
                long nmId = safeLong(card.getNmID());
                if (nmId <= 0) {
                    continue;
                }
                addPhotosBatch(photosPs, shopId, nmId, card.getPhotos(), card.getUpdatedAt());
                addSizesBatch(sizesPs, skusPs, shopId, nmId, card.getSizes());
                addCharacteristicsBatch(characteristicsPs, shopId, nmId, card.getCharacteristics());
                addTagsBatch(tagsPs, shopId, nmId, card.getTags());
            }
            photosPs.executeBatch();
            sizesPs.executeBatch();
            skusPs.executeBatch();
            characteristicsPs.executeBatch();
            tagsPs.executeBatch();
        }
    }

    private void addPhotosBatch(PreparedStatement ps, int shopId, long nmId,
                                List<WbProductCard.Photo> photos, String updatedAt) throws SQLException {
        if (photos == null || photos.isEmpty()) {
            return;
        }
        for (int i = 0; i < photos.size(); i++) {
            WbProductCard.Photo photo = photos.get(i);
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setInt(3, i);
            ps.setString(4, versionPhotoUrl(photo.getBig(), updatedAt));
            ps.setString(5, versionPhotoUrl(photo.getC246x328(), updatedAt));
            ps.setString(6, versionPhotoUrl(photo.getC516x688(), updatedAt));
            ps.setString(7, versionPhotoUrl(photo.getHq(), updatedAt));
            ps.setString(8, versionPhotoUrl(photo.getSquare(), updatedAt));
            ps.setString(9, versionPhotoUrl(photo.getTm(), updatedAt));
            ps.addBatch();
        }
    }

    /**
     * WB keeps the same CDN path when a seller replaces a product image. The card's
     * updatedAt value changes, so include it in the local URL identity to prevent the
     * persistent and in-memory image caches from serving the previous bytes.
     */
    static String versionPhotoUrl(String imageUrl, String updatedAt) {
        if (imageUrl == null || imageUrl.isBlank() || updatedAt == null || updatedAt.isBlank()) {
            return imageUrl;
        }
        String separator = imageUrl.contains("?") ? "&" : "?";
        return imageUrl + separator + "wcode_revision="
                + URLEncoder.encode(updatedAt.strip(), StandardCharsets.UTF_8);
    }

    private void addSizesBatch(PreparedStatement psSize, PreparedStatement psSku, int shopId, long nmId, List<WbProductCard.Size> sizes) throws SQLException {
        if (sizes == null || sizes.isEmpty()) {
            return;
        }
        for (WbProductCard.Size size : sizes) {
            long chrtId = safeLong(size.getChrtID());
            psSize.setInt(1, shopId);
            psSize.setLong(2, chrtId);
            psSize.setLong(3, nmId);
            psSize.setString(4, size.getTechSize());
            psSize.setString(5, size.getWbSize());
            psSize.addBatch();

            if (size.getSkus() != null) {
                for (String sku : size.getSkus()) {
                    psSku.setInt(1, shopId);
                    psSku.setLong(2, chrtId);
                    psSku.setString(3, sku);
                    psSku.addBatch();
                }
            }
        }
    }

    private void addCharacteristicsBatch(PreparedStatement ps, int shopId, long nmId, List<WbProductCard.Characteristic> characteristics) throws SQLException {
        if (characteristics == null || characteristics.isEmpty()) {
            return;
        }
        for (WbProductCard.Characteristic characteristic : characteristics) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setInt(3, characteristic.getId());
            ps.setString(4, characteristic.getName());
            ps.setString(5, WbJson.GSON.toJson(characteristic.getValue()));
            ps.addBatch();
        }
    }

    private void addTagsBatch(PreparedStatement ps, int shopId, long nmId, List<WbProductCard.Tag> tags) throws SQLException {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (WbProductCard.Tag tag : tags) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setInt(3, tag.getId());
            ps.setString(4, tag.getName());
            ps.setString(5, tag.getColor());
            ps.addBatch();
        }
    }
}
