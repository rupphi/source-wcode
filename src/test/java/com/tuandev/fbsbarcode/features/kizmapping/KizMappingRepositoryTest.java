package com.tuandev.fbsbarcode.features.kizmapping;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KizMappingRepositoryTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldSearchDistinctNmProductsAndSaveMapping() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();

        List<KizMappingProduct> products = repository.search(new KizMappingSearchCriteria(1, "vendor-a", List.of(), 20, 0));
        assertEquals(1, products.size());
        assertEquals(1001L, products.getFirst().nmId());

        repository.saveMapping(1, 1001L, 10);

        Map<Long, Integer> mappings = repository.findMappings(1, List.of(1001L, 1002L));
        assertEquals(10, mappings.get(1001L));
    }

    @Test
    void shouldClearMappingWhenCategoryIsBlankInImport() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();
        repository.saveMapping(1, 1001L, 10);

        Map<Long, Integer> imported = new LinkedHashMap<>();
        imported.put(1001L, null);
        KizMappingImportResult result = repository.replaceMappingsFromImport(1, imported);

        assertTrue(result.success());
        assertEquals(1, result.clearedCount());
        assertTrue(repository.findMappings(1, List.of(1001L)).isEmpty());
    }

    @Test
    void shouldRejectImportAndKeepExistingMappingWhenCategoryDoesNotExist() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();
        repository.saveMapping(1, 1001L, 10);

        KizMappingImportResult result = repository.replaceMappingsFromImport(1, Map.of(1001L, 999));

        assertFalse(result.success());
        assertEquals(10, repository.findMappings(1, List.of(1001L)).get(1001L));
    }

    @Test
    void shouldShowUnmappedProductsFirst() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();
        repository.saveMapping(1, 1001L, 10);

        List<KizMappingProduct> products = repository.search(new KizMappingSearchCriteria(1, "", List.of(), 20, 0));

        assertEquals(1002L, products.getFirst().nmId());
    }

    @Test
    void shouldShowOnlyProductsThatNeedKiz() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();

        List<KizMappingProduct> products = repository.search(new KizMappingSearchCriteria(1, "", List.of(), 20, 0));

        assertEquals(List.of(1001L, 1002L), products.stream().map(KizMappingProduct::nmId).sorted().toList());
    }

    @Test
    void shouldFindKizRequiredNmIdsFromProductFlags() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();

        Set<Long> requiredNmIds = repository.findKizRequiredNmIds(1, List.of(1001L, 1002L, 1003L, 9999L));

        assertEquals(Set.of(1001L, 1002L), requiredNmIds);
    }

    @Test
    void shouldLoadDistinctGenderValuesForKizProducts() throws Exception {
        initializeFixture();
        insertGender(1, 1001L, "Men");
        insertGender(1, 1002L, "Women");
        insertGender(1, 1003L, "Kids");
        KizMappingRepository repository = new KizMappingRepository();

        List<String> genders = repository.findGenders(1);

        assertEquals(List.of("Men", "Women"), genders);
    }

    @Test
    void shouldFilterProductsByGender() throws Exception {
        initializeFixture();
        insertGender(1, 1001L, "Men");
        insertGender(1, 1002L, "Women");
        KizMappingRepository repository = new KizMappingRepository();

        List<KizMappingProduct> products = repository.search(
                new KizMappingSearchCriteria(1, "", List.of(), List.of("Women"), 20, 0));

        assertEquals(List.of(1002L), products.stream().map(KizMappingProduct::nmId).toList());
    }

    @Test
    void shouldCombineSearchSubjectAndGenderFilters() throws Exception {
        initializeFixture();
        insertGender(1, 1001L, "Men");
        insertGender(1, 1002L, "Women");
        insertProduct(1, 1004L, "Other Product", "Shoes WB", "vendor-other", true);
        insertGender(1, 1004L, "Women");
        KizMappingRepository repository = new KizMappingRepository();

        List<KizMappingProduct> products = repository.search(
                new KizMappingSearchCriteria(1, "vendor", List.of("Shoes WB"), List.of("Women"), 20, 0));

        assertEquals(List.of(1004L), products.stream().map(KizMappingProduct::nmId).toList());
    }

    @Test
    void shouldBulkSaveMappingForAllFilteredProductsIgnoringPagination() throws Exception {
        initializeFixture();
        insertGender(1, 1001L, "Women");
        insertGender(1, 1002L, "Women");
        KizMappingRepository repository = new KizMappingRepository();

        int updated = repository.saveMappingForFilter(
                new KizMappingSearchCriteria(1, "", List.of(), List.of("Women"), 1, 0), 10);

        assertEquals(2, updated);
        assertEquals(Map.of(1001L, 10, 1002L, 10), repository.findMappings(1, List.of(1001L, 1002L)));
    }

    @Test
    void shouldKeepRowSaveSingleProductWhenNoFilterIsUsed() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();

        repository.saveMapping(1, 1001L, 10);

        assertEquals(Map.of(1001L, 10), repository.findMappings(1, List.of(1001L, 1002L)));
    }

    @Test
    void shouldReportMissingCategoryOnlyOnceForImport() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();
        Map<Long, Integer> imported = new LinkedHashMap<>();
        imported.put(1001L, 999);
        imported.put(1002L, 999);

        KizMappingImportResult result = repository.replaceMappingsFromImport(1, imported);

        assertFalse(result.success());
        assertEquals(List.of("Không tồn tại KIZ Category ID: 999"), result.errors());
    }

    @Test
    void shouldImportMoreThanOneThousandMappingsWithoutProductConstraint() throws Exception {
        initializeFixture();
        KizMappingRepository repository = new KizMappingRepository();
        Map<Long, Integer> imported = new LinkedHashMap<>();
        List<Long> nmIds = new ArrayList<>();
        for (long nmId = 10_000; nmId <= 11_050; nmId++) {
            imported.put(nmId, 10);
            nmIds.add(nmId);
        }

        KizMappingImportResult result = repository.replaceMappingsFromImport(1, imported);

        assertTrue(result.success());
        assertEquals(1051, result.updatedCount());
        assertEquals(1051, repository.findMappings(1, nmIds).size());
    }

    private void initializeFixture() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO categories(id, name) VALUES (10, 'Shoes')");
        }
        insertProduct(1, 1001L, "Product A", "Shoes WB", "vendor-a", true);
        insertProduct(1, 1002L, "Product B", "Bags WB", "vendor-b", true);
        insertProduct(1, 1003L, "Product C", "Hats WB", "vendor-c", false);
    }

    private void insertProduct(int shopId, long nmId, String title, String subject, String vendorCode, boolean needKiz) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO wb_product_cards (shop_id, nm_id, title, subject_name, vendor_code, need_kiz, synced_at)
                     VALUES (?, ?, ?, ?, ?, ?, '2026-05-23T00:00:00Z')
                     """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setString(3, title);
            ps.setString(4, subject);
            ps.setString(5, vendorCode);
            ps.setInt(6, needKiz ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void insertGender(int shopId, long nmId, String gender) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO wb_product_characteristics (shop_id, nm_id, characteristic_id, name, value_json)
                     VALUES (?, ?, 204557, 'Пол', ?)
                     """)) {
            ps.setInt(1, shopId);
            ps.setLong(2, nmId);
            ps.setString(3, "[\"" + gender + "\"]");
            ps.executeUpdate();
        }
    }
}
