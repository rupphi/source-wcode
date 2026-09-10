package com.tuandev.fbsbarcode.integration.znack.registration;

import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.GoodsDocument;
import java.util.List;
import java.util.HashMap;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;

/** One preflight session per batch; no GTIN allocation or submission during validation. */
public final class RegistrationDraftPreparer {
    public record KindGroup(long subjectId, String subjectName, String tnved, long categoryId,
                            String categoryName, Attribute attribute) { }
    public record Prepared(Sku sku, Draft draft, List<String> missing, KindGroup kindGroup) {
        public Prepared(Sku sku, Draft draft, List<String> missing) { this(sku, draft, missing, null); }
        public List<String> kindOptions() {
            if (kindGroup == null) return List.of();
            return kindGroup.attribute().presets().stream().filter(v -> v != null && !v.isBlank()
                    && ZnackWbAttributeMapper.resolveValueType(kindGroup.attribute(), v, sku.wbSize()) != null).distinct().toList();
        }
        public Prepared chooseKind(String value) {
            if (kindGroup == null || draft == null || value == null || value.isBlank()
                    || !kindOptions().contains(value))
                throw new IllegalArgumentException("Invalid Znack goods-kind selection.");
            String type = ZnackWbAttributeMapper.resolveValueType(kindGroup.attribute(), value, sku.wbSize());
            if (type == null) throw new IllegalArgumentException("Unsupported Znack goods-kind value type.");
            var values = new HashMap<>(draft.attributes()); values.put(12L, value);
            var types = new HashMap<>(draft.attributeTypes()); types.put(12L, type);
            return new Prepared(sku, new Draft(draft.tnved(), draft.feedTnved(), draft.categoryId(),
                    draft.goodName(), draft.brand(), values, types), missing, kindGroup);
        }
    }
    private final Shop shop;
    private final ZnackCardRegistrationRepository repository;
    private final List<GoodsDocument> documents;
    private final ZnackNationalCatalogService catalog;
    private final HashMap<String, ZnackNationalCatalogService.Preflight> preflights = new HashMap<>();
    private final HashMap<Long, List<Attribute>> schemas = new HashMap<>();
    public RegistrationDraftPreparer(Shop shop, ZnackCardRegistrationRepository repository) {
        this.shop = shop; this.repository = repository;
        var settings = RegistrationRunner.settings(shop);
        documents = RegistrationDocuments.load(shop.getId(), settings);
        if (documents.isEmpty()) throw new IllegalArgumentException("znack.registration.missing_document_config");
        catalog = RegistrationRunner.session(shop, settings).catalog();
    }
    public Prepared prepare(Sku sku) throws Exception {
        var characteristics = repository.characteristics(shop.getId(), sku.nmId());
        String tnved = ZnackWbAttributeMapper.findTnved(characteristics);
        if (tnved.isBlank()) return new Prepared(sku, null, List.of("TN VED"));
        var preflight = preflights.get(tnved);
        if (preflight == null) { preflight = catalog.preflight(tnved); preflights.put(tnved, preflight); }
        var category = ZnackNationalCatalogService.selectLightIndustryCategory(preflight.categories(), sku.subjectName());
        var schema = schemas.get(category.id());
        if (schema == null) { schema = catalog.requiredAttributes(category.id(), preflight.token()); schemas.put(category.id(), schema); }
        var kind = schema.stream().filter(a -> a.id() == 12L).findFirst().orElse(null);
        var automaticSchema = schema.stream().filter(a -> a.id() != 12L).toList();
        var mapped = new ZnackWbAttributeMapper().mapDocuments(sku, characteristics, automaticSchema,
                preflight.tnved(), preflight.categoryTnved(), documents);
        return new Prepared(sku, new Draft(preflight.tnved(), preflight.categoryTnved(), category.id(),
                mapped.goodName(), mapped.brand(), mapped.attributes(), mapped.attributeTypes()), mapped.missingFields(),
                kind == null ? null : new KindGroup(sku.subjectId(), sku.subjectName(), tnved, category.id(), category.name(), kind));
    }
}
