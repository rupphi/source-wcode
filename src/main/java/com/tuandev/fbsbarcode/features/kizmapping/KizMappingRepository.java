package com.tuandev.fbsbarcode.features.kizmapping;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public class KizMappingRepository {
    private static final int SQL_PARAM_BATCH_SIZE = 900;
    private static final int GENDER_CHARACTERISTIC_ID = 204557;

    public List<String> findSubjects(int shopId) {
        String sql = """
                SELECT DISTINCT subject_name
                FROM wb_product_cards
                WHERE shop_id = ?
                  AND subject_name IS NOT NULL
                  AND TRIM(subject_name) <> ''
                  AND (COALESCE(need_kiz, 0) = 1 OR COALESCE(kiz_marked, 0) = 1)
                ORDER BY subject_name COLLATE NOCASE
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> subjects = new ArrayList<>();
                while (rs.next()) {
                    subjects.add(rs.getString("subject_name"));
                }
                return subjects;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> findGenders(int shopId) {
        String sql = """
                SELECT DISTINCT COALESCE(json_extract(ch.value_json, '$[0]'), json_extract(ch.value_json, '$')) AS gender_value
                FROM wb_product_cards c
                JOIN wb_product_characteristics ch ON ch.shop_id = c.shop_id AND ch.nm_id = c.nm_id
                WHERE c.shop_id = ?
                  AND ch.characteristic_id = ?
                  AND (COALESCE(c.need_kiz, 0) = 1 OR COALESCE(c.kiz_marked, 0) = 1)
                  AND gender_value IS NOT NULL
                  AND TRIM(gender_value) <> ''
                ORDER BY gender_value COLLATE NOCASE
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, GENDER_CHARACTERISTIC_ID);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> genders = new ArrayList<>();
                while (rs.next()) {
                    genders.add(rs.getString("gender_value"));
                }
                return genders;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<KizMappingProduct> search(KizMappingSearchCriteria criteria) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT c.nm_id, c.title, c.subject_name, c.vendor_code, c.brand,
                       (SELECT GROUP_CONCAT(ps.tech_size, '/') FROM wb_product_sizes ps WHERE ps.shop_id = c.shop_id AND ps.nm_id = c.nm_id) AS sizes_value,
                       m.kiz_category_id,
                       p.c246x328_url, p.square_url, p.big_url, p.hq_url, p.tm_url
                       ,(SELECT COALESCE(json_extract(ch.value_json, '$[0]'), json_extract(ch.value_json, '$'))
                         FROM wb_product_characteristics ch
                         WHERE ch.shop_id = c.shop_id
                           AND ch.nm_id = c.nm_id
                           AND ch.characteristic_id = """ + GENDER_CHARACTERISTIC_ID + """
                         LIMIT 1) AS gender_value
                FROM wb_product_cards c
                LEFT JOIN wb_product_kiz_mappings m ON m.shop_id = c.shop_id AND m.nm_id = c.nm_id
                LEFT JOIN wb_product_photos p ON p.shop_id = c.shop_id AND p.nm_id = c.nm_id AND p.photo_index = 0
                """);
        appendProductFilters(sql, params, criteria);

        sql.append("""
                ORDER BY CASE WHEN m.kiz_category_id IS NULL THEN 0 ELSE 1 END,
                         c.title COLLATE NOCASE,
                         c.nm_id
                LIMIT ? OFFSET ?
                """);
        params.add(Math.max(1, criteria.limit()));
        params.add(Math.max(0, criteria.offset()));

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<KizMappingProduct> products = new ArrayList<>();
                while (rs.next()) {
                    products.add(toProduct(rs));
                }
                return products;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<KizMappingProduct> findAllForExport(int shopId) {
        return search(new KizMappingSearchCriteria(shopId, "", List.of(), List.of(), Integer.MAX_VALUE, 0));
    }

    public Map<Long, Integer> findMappings(int shopId, List<Long> nmIds) {
        if (nmIds == null || nmIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> mappings = new HashMap<>();
        try (Connection conn = Database.getConnection()) {
            for (int start = 0; start < nmIds.size(); start += SQL_PARAM_BATCH_SIZE) {
                List<Long> batch = nmIds.subList(start, Math.min(start + SQL_PARAM_BATCH_SIZE, nmIds.size()));
                String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
                String sql = "SELECT nm_id, kiz_category_id FROM wb_product_kiz_mappings WHERE shop_id = ? AND kiz_category_id IS NOT NULL AND nm_id IN (" + placeholders + ")";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, shopId);
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setLong(i + 2, batch.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            mappings.put(rs.getLong("nm_id"), rs.getInt("kiz_category_id"));
                        }
                    }
                }
            }
            return mappings;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<Long> findKizRequiredNmIds(int shopId, List<Long> nmIds) {
        if (nmIds == null || nmIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> requiredNmIds = new LinkedHashSet<>();
        try (Connection conn = Database.getConnection()) {
            for (int start = 0; start < nmIds.size(); start += SQL_PARAM_BATCH_SIZE) {
                List<Long> batch = nmIds.subList(start, Math.min(start + SQL_PARAM_BATCH_SIZE, nmIds.size()));
                String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
                String sql = """
                        SELECT nm_id
                        FROM wb_product_cards
                        WHERE shop_id = ?
                          AND nm_id IN (
                        """ + placeholders + """
                          )
                          AND (COALESCE(need_kiz, 0) = 1 OR COALESCE(kiz_marked, 0) = 1)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, shopId);
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setLong(i + 2, batch.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            requiredNmIds.add(rs.getLong("nm_id"));
                        }
                    }
                }
            }
            return requiredNmIds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveMapping(int shopId, long nmId, Integer kizCategoryId) {
        try (Connection conn = Database.getConnection()) {
            if (kizCategoryId == null) {
                deleteMapping(conn, shopId, nmId);
                return;
            }
            if (!productExists(conn, shopId, nmId)) {
                throw new IllegalArgumentException("nmId không thuộc shop hiện tại: " + nmId);
            }
            if (!categoryExists(conn, kizCategoryId)) {
                throw new IllegalArgumentException("Không tồn tại KIZ Category ID: " + kizCategoryId);
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO wb_product_kiz_mappings (shop_id, nm_id, kiz_category_id, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(shop_id, nm_id) DO UPDATE SET
                        kiz_category_id = excluded.kiz_category_id,
                        updated_at = excluded.updated_at
                    """)) {
                ps.setInt(1, shopId);
                ps.setLong(2, nmId);
                ps.setInt(3, kizCategoryId);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int saveMappingForFilter(KizMappingSearchCriteria criteria, int categoryId) {
        List<Object> params = new ArrayList<>();
        params.add(categoryId);
        params.add(Instant.now().toString());
        StringBuilder sql = new StringBuilder("""
                INSERT INTO wb_product_kiz_mappings (shop_id, nm_id, kiz_category_id, updated_at)
                SELECT c.shop_id, c.nm_id, ?, ?
                FROM wb_product_cards c
                """);
        appendProductFilters(sql, params, criteria);
        sql.append("""
                ON CONFLICT(shop_id, nm_id) DO UPDATE SET
                    kiz_category_id = excluded.kiz_category_id,
                    updated_at = excluded.updated_at
                """);

        try (Connection conn = Database.getConnection()) {
            if (!categoryExists(conn, categoryId)) {
                throw new IllegalArgumentException("Không tồn tại KIZ Category ID: " + categoryId);
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public KizMappingImportResult replaceMappingsFromImport(int shopId, Map<Long, Integer> importedMappings) {
        Map<Long, Integer> safeMappings = importedMappings == null ? Map.of() : importedMappings;
        List<String> errors = validateImport(safeMappings);
        if (!errors.isEmpty()) {
            return new KizMappingImportResult(0, 0, errors);
        }
        int updated = 0;
        int cleared = 0;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<Long, Integer> entry : safeMappings.entrySet()) {
                    if (entry.getValue() == null) {
                        cleared += deleteMapping(conn, shopId, entry.getKey());
                    } else {
                        upsertMapping(conn, shopId, entry.getKey(), entry.getValue());
                        updated++;
                    }
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
            return new KizMappingImportResult(updated, cleared, List.of());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> validateImport(Map<Long, Integer> mappings) {
        Set<Integer> missingCategoryIds = new LinkedHashSet<>();
        try (Connection conn = Database.getConnection()) {
            Set<Integer> categoryIds = new LinkedHashSet<>();
            for (Integer categoryId : mappings.values()) {
                if (categoryId != null) {
                    categoryIds.add(categoryId);
                }
            }
            for (Integer categoryId : categoryIds) {
                if (!categoryExists(conn, categoryId)) {
                    missingCategoryIds.add(categoryId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        List<String> errors = new ArrayList<>();
        for (Integer categoryId : missingCategoryIds) {
            errors.add("Không tồn tại KIZ Category ID: " + categoryId);
        }
        return errors;
    }

    private KizMappingProduct toProduct(ResultSet rs) throws SQLException {
        int categoryId = rs.getInt("kiz_category_id");
        boolean categoryNull = rs.wasNull();
        return new KizMappingProduct(
                rs.getLong("nm_id"),
                firstNonBlank(
                        rs.getString("c246x328_url"),
                        rs.getString("square_url"),
                        rs.getString("big_url"),
                        rs.getString("hq_url"),
                        rs.getString("tm_url")
                ),
                rs.getString("title"),
                rs.getString("subject_name"),
                rs.getString("gender_value"),
                rs.getString("vendor_code"),
                categoryNull ? null : categoryId,
                rs.getString("brand"),
                rs.getString("sizes_value")
        );
    }

    private void appendProductFilters(StringBuilder sql, List<Object> params, KizMappingSearchCriteria criteria) {
        sql.append("""
                WHERE c.shop_id = ?
                  AND (COALESCE(c.need_kiz, 0) = 1 OR COALESCE(c.kiz_marked, 0) = 1)
                """);
        params.add(criteria.shopId());

        String query = criteria.query() == null ? "" : criteria.query().trim();
        if (!query.isBlank()) {
            sql.append("""
                    AND (
                        CAST(c.nm_id AS TEXT) LIKE ?
                        OR LOWER(c.vendor_code) LIKE ?
                        OR LOWER(c.title) LIKE ?
                    )
                    """);
            String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        List<String> subjects = normalizeFilterValues(criteria.subjectNames());
        if (!subjects.isEmpty()) {
            sql.append(" AND c.subject_name IN (")
                    .append(String.join(", ", Collections.nCopies(subjects.size(), "?")))
                    .append(")");
            params.addAll(subjects);
        }

        List<String> genders = normalizeFilterValues(criteria.genderValues());
        if (!genders.isEmpty()) {
            sql.append("""
                    AND EXISTS (
                        SELECT 1
                        FROM wb_product_characteristics ch
                        WHERE ch.shop_id = c.shop_id
                          AND ch.nm_id = c.nm_id
                          AND ch.characteristic_id = ?
                          AND COALESCE(json_extract(ch.value_json, '$[0]'), json_extract(ch.value_json, '$')) IN (
                    """);
            sql.append(String.join(", ", Collections.nCopies(genders.size(), "?")));
            sql.append("))");
            params.add(GENDER_CHARACTERISTIC_ID);
            params.addAll(genders);
        }
    }

    private List<String> normalizeFilterValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private void upsertMapping(Connection conn, int shopId, long nmId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO wb_product_kiz_mappings (shop_id, nm_id, kiz_category_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(shop_id, nm_id) DO UPDATE SET
                    kiz_category_id = excluded.kiz_category_id,
                    updated_at = excluded.updated_at
                """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setInt(3, categoryId);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private int deleteMapping(Connection conn, int shopId, long nmId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM wb_product_kiz_mappings WHERE shop_id = ? AND nm_id = ?")) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            return ps.executeUpdate();
        }
    }

    private boolean productExists(Connection conn, int shopId, long nmId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1
                FROM wb_product_cards
                WHERE shop_id = ?
                  AND nm_id = ?
                  AND (COALESCE(need_kiz, 0) = 1 OR COALESCE(kiz_marked, 0) = 1)
                LIMIT 1
                """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean categoryExists(Connection conn, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM categories WHERE id = ? LIMIT 1")) {
            ps.setInt(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
