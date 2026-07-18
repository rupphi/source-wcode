package com.tuandev.fbsbarcode.jdesk.kizmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinMappingRule;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KizMappingCommandServiceTest {
    private static final String GTIN = "04601234567890";
    private static final String OTHER_GTIN = "04601234567891";
    private static final String SECRET = "znack-secret-that-must-not-cross-the-bridge";

    @Test
    void returnsABoundedCatalogPageWithSafeInventoryAndErrorFields() {
        AtomicReference<KizMappingCommandService.CatalogQuery> query = new AtomicReference<>();
        List<ZnackGtinInventorySummary> summaries = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            summaries.add(summary(
                    String.format("046012345678%02d", index),
                    index == 0 ? " Product \n One " : "Product " + index,
                    index == 0 ? " Shoes " : "Clothes",
                    index == 0 ? "COMPLETED" : "UNKNOWN_STAGE",
                    index == 0
                            ? "Znack API request failed (HTTP 401): {\"token\":\"" + SECRET
                                    + "\",\"message\":\"Authorization Bearer " + SECRET + "\"}"
                            : ""));
        }
        FakeSource source = new FakeSource();
        source.categories = List.of(" Shoes ", "Clothes", "shoes");
        source.summaries = request -> {
            query.set(request);
            return summaries;
        };
        KizMappingCommandService service = service(source);

        KizMappingCommandService.CatalogResponse response = service.catalog(
                        new KizMappingCommandService.CatalogRequest(
                                7, " product ", List.of(" Shoes ", "shoes"), 2, 10),
                        null)
                .toCompletableFuture()
                .join();

        assertEquals(new KizMappingCommandService.CatalogQuery(
                7, "product", List.of("Shoes"), 11, 10), query.get());
        assertEquals(List.of("Shoes", "Clothes"), response.availableCategories());
        assertEquals(10, response.items().size());
        assertTrue(response.hasMore());
        KizMappingCommandService.GtinItem first = response.items().getFirst();
        assertEquals("04601234567800", first.gtin());
        assertEquals("Product One", first.productName());
        assertEquals("Shoes", first.category());
        assertEquals("COMPLETED", first.pipelineStage());
        assertEquals("", response.items().get(1).pipelineStage());
        assertTrue(first.errorMessage().contains("HTTP 401"));
        assertFalse(first.errorMessage().contains(SECRET));
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void editorPreservesCurrentRulesAndShowsForeignOwners() {
        FakeSource source = new FakeSource();
        source.editor = editorData(true);

        KizMappingCommandService.EditorResponse response = service(source)
                .editor(new KizMappingCommandService.EditorRequest(7, GTIN), null)
                .toCompletableFuture()
                .join();

        assertEquals(GTIN, response.gtin());
        assertEquals(3, response.subjects().size());
        KizMappingCommandService.SubjectOption jackets = response.subjects().getFirst();
        assertEquals("Jackets", jackets.subjectName());
        assertTrue(jackets.selected());
        assertTrue(jackets.wildcardSelected());
        assertEquals(GTIN, jackets.wildcardOwnerGtin());
        KizMappingCommandService.SubjectOption split = response.subjects().get(1);
        assertTrue(split.selected());
        assertFalse(split.wildcardSelected());
        assertTrue(split.genders().getFirst().selected());
        assertEquals(OTHER_GTIN, split.genders().get(1).ownerGtin());
        KizMappingCommandService.SubjectOption blocked = response.subjects().get(2);
        assertFalse(blocked.selected());
        assertEquals(OTHER_GTIN, blocked.wildcardOwnerGtin());
    }

    @Test
    void savesValidatedRulesThenReturnsTheFreshEditor() {
        FakeSource source = new FakeSource();
        source.editor = editorData(true);
        AtomicReference<List<ZnackGtinMappingSelection>> saved = new AtomicReference<>();
        source.save = selections -> {
            saved.set(selections);
            source.editor = new KizMappingCommandService.EditorData(
                    true,
                    source.editor.subjects(),
                    source.editor.gendersBySubject(),
                    List.of(new ZnackGtinMappingRule(
                            7, GTIN, "Split", "Female", false, Instant.parse("2026-07-18T00:00:00Z"))),
                    source.editor.ownersBySubject());
        };

        KizMappingCommandService.EditorResponse response = service(source)
                .save(new KizMappingCommandService.SaveRequest(
                                7,
                                GTIN,
                                List.of(new KizMappingCommandService.SelectionRequest(
                                        "Split", "Female", false))),
                        null)
                .toCompletableFuture()
                .join();

        assertEquals(
                List.of(new ZnackGtinMappingSelection("Split", "Female", false)),
                saved.get());
        assertTrue(response.subjects().get(1).genders().getFirst().selected());
        assertEquals(1, source.saves.get());
    }

    @Test
    void rejectsInvalidStaleAndConflictingRulesBeforeMutation() {
        FakeSource source = new FakeSource();
        source.editor = editorData(true);
        KizMappingCommandService service = service(source);
        List<KizMappingCommandService.SaveRequest> invalid = List.of(
                new KizMappingCommandService.SaveRequest(
                        7, "02900699308808", List.of()),
                new KizMappingCommandService.SaveRequest(
                        7, GTIN, List.of(new KizMappingCommandService.SelectionRequest(
                                "Missing", null, true))),
                new KizMappingCommandService.SaveRequest(
                        7, GTIN, List.of(new KizMappingCommandService.SelectionRequest(
                                "Split", "Missing", false))),
                new KizMappingCommandService.SaveRequest(
                        7, GTIN, List.of(new KizMappingCommandService.SelectionRequest(
                                "Split", "Male", false))),
                new KizMappingCommandService.SaveRequest(
                        7, GTIN, List.of(new KizMappingCommandService.SelectionRequest(
                                "Blocked", null, true))),
                new KizMappingCommandService.SaveRequest(
                        7, GTIN, List.of(
                                new KizMappingCommandService.SelectionRequest("Split", "Female", false),
                                new KizMappingCommandService.SelectionRequest("Split", "Female", false))));

        for (KizMappingCommandService.SaveRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.save(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, source.saves.get());

        source.editor = editorData(false);
        JDeskException missing = assertThrows(
                JDeskException.class,
                () -> service.editor(new KizMappingCommandService.EditorRequest(7, GTIN), null));
        assertEquals(ErrorCode.INVALID_REQUEST, missing.code());
    }

    @Test
    void rejectsMalformedAndUnownedRequestsBeforeCatalogReads() {
        FakeSource source = new FakeSource();
        KizMappingCommandService service = service(source);
        List<KizMappingCommandService.CatalogRequest> invalid = List.of(
                new KizMappingCommandService.CatalogRequest(0, "", List.of(), 1, 10),
                new KizMappingCommandService.CatalogRequest(9, "", List.of(), 1, 10),
                new KizMappingCommandService.CatalogRequest(7, "bad\nquery", List.of(), 1, 10),
                new KizMappingCommandService.CatalogRequest(7, "", null, 1, 10),
                new KizMappingCommandService.CatalogRequest(7, "", List.of(""), 1, 10),
                new KizMappingCommandService.CatalogRequest(7, "", List.of(), 0, 10),
                new KizMappingCommandService.CatalogRequest(7, "", List.of(), 1, 101));

        for (KizMappingCommandService.CatalogRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.catalog(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, source.catalogReads.get());
    }

    @Test
    void mapsSourceFailuresToAnOpaqueErrorEnvelope() {
        FakeSource source = new FakeSource();
        source.summaries = ignored -> {
            throw new IllegalStateException("sqlite failed with " + SECRET);
        };

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service(source).catalog(catalogRequest(), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.details());
        assertNull(error.getCause());
    }

    @Test
    void mapsAConcurrentOwnerRaceToASafeConflictWithoutLeakingTheOwner() {
        FakeSource source = new FakeSource();
        source.editor = editorData(true);
        source.save = ignored -> {
            throw new KizMappingRepository.MappingConflictException();
        };

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service(source).save(new KizMappingCommandService.SaveRequest(
                        7,
                        GTIN,
                        List.of(new KizMappingCommandService.SelectionRequest(
                                "Split", "Female", false))), null));

        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertFalse(error.publicMessage().contains(OTHER_GTIN));
        assertEquals(1, source.saves.get());
    }

    @Test
    void bridgeCodecRoundTripsOnlyTheTypedAllowlist() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        KizMappingCommandService.SaveRequest request = codec.decode(
                """
                {"shopId":7,"gtin":"04601234567890","selections":[]}
                """,
                KizMappingCommandService.SaveRequest.class);
        FakeSource source = new FakeSource();
        source.editor = editorData(true);

        String json = codec.encode(service(source).save(request, null).toCompletableFuture().join());

        assertTrue(json.contains("\"gtin\":\"" + GTIN + "\""));
        assertTrue(json.contains("\"subjects\""));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains(SECRET));
    }

    private static KizMappingCommandService service(FakeSource source) {
        return new KizMappingCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)), source);
    }

    private static KizMappingCommandService.CatalogRequest catalogRequest() {
        return new KizMappingCommandService.CatalogRequest(7, "", List.of(), 1, 10);
    }

    private static ZnackGtinInventorySummary summary(
            String gtin, String name, String category, String pipeline, String error) {
        return new ZnackGtinInventorySummary(
                gtin,
                name,
                category,
                7,
                2,
                3,
                4,
                "CODES_READY",
                pipeline,
                error,
                Instant.parse("2026-07-18T00:00:00Z"));
    }

    private static KizMappingCommandService.EditorData editorData(boolean productExists) {
        Map<String, List<String>> genders = new LinkedHashMap<>();
        genders.put("Jackets", List.of("Female", "Male"));
        genders.put("Split", List.of("Female", "Male"));
        genders.put("Blocked", List.of("Female", "Male"));
        Map<String, Map<String, String>> owners = new LinkedHashMap<>();
        owners.put("Jackets", Map.of(KizMappingRepository.WILDCARD_GENDER, GTIN));
        owners.put("Split", Map.of("Male", OTHER_GTIN));
        owners.put("Blocked", Map.of(KizMappingRepository.WILDCARD_GENDER, OTHER_GTIN));
        return new KizMappingCommandService.EditorData(
                productExists,
                List.of("Jackets", "Split", "Blocked"),
                genders,
                List.of(
                        new ZnackGtinMappingRule(
                                7, GTIN, "Jackets", KizMappingRepository.WILDCARD_GENDER,
                                true, Instant.parse("2026-07-18T00:00:00Z")),
                        new ZnackGtinMappingRule(
                                7, GTIN, "Split", "Female", false,
                                Instant.parse("2026-07-18T00:00:00Z"))),
                owners);
    }

    private static final class FakeSource implements KizMappingCommandService.MappingDataSource {
        List<String> categories = List.of();
        KizMappingCommandService.SummaryReader summaries = ignored -> List.of();
        KizMappingCommandService.EditorData editor = editorData(true);
        java.util.function.Consumer<List<ZnackGtinMappingSelection>> save = ignored -> {
        };
        AtomicInteger catalogReads = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();

        @Override
        public List<String> categories(int shopId) {
            return categories;
        }

        @Override
        public List<ZnackGtinInventorySummary> summaries(KizMappingCommandService.CatalogQuery query) {
            catalogReads.incrementAndGet();
            return summaries.read(query);
        }

        @Override
        public KizMappingCommandService.EditorData editor(int shopId, String gtin) {
            return editor;
        }

        @Override
        public void replaceRules(
                int shopId, String gtin, List<ZnackGtinMappingSelection> selections) {
            saves.incrementAndGet();
            save.accept(selections);
        }
    }
}
