package com.tuandev.fbsbarcode.jdesk.kizmapping;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinMappingRule;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.OrderStatus;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class KizMappingCommandService {
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_CATEGORY_COUNT = 30;
    private static final int MAX_AVAILABLE_CATEGORIES = 100;
    private static final int MAX_SUBJECTS = 500;
    private static final int MAX_GENDERS_PER_SUBJECT = 100;
    private static final int MAX_SELECTIONS = 500;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_LABEL_LENGTH = 160;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Set<String> ORDER_STATUSES = enumNames(OrderStatus.class);
    private static final Set<String> PIPELINE_STAGES = enumNames(PurchaseStage.class);

    private final Supplier<List<Shop>> shops;
    private final MappingDataSource source;
    private final Object mutationLock = new Object();

    public KizMappingCommandService() {
        KizMappingRepository repository = new KizMappingRepository();
        this.shops = new ShopRepository()::findAll;
        this.source = new LegacyMappingDataSource(repository);
    }

    KizMappingCommandService(Supplier<List<Shop>> shops, MappingDataSource source) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.source = Objects.requireNonNull(source, "source");
    }

    @DesktopCommand("kizMapping.catalog")
    @RequiresCapability("kiz-mapping:read")
    public CompletionStage<CatalogResponse> catalog(CatalogRequest request, InvocationContext context) {
        ValidatedCatalog validated = validateCatalog(request);
        return SafeCommandExecutor.execute(() -> {
            requireShop(validated.shopId());
            List<String> availableCategories = sanitizeLabels(
                    source.categories(validated.shopId()), MAX_AVAILABLE_CATEGORIES, false, false, "category catalog");
            CatalogQuery query = new CatalogQuery(
                    validated.shopId(),
                    validated.query(),
                    validated.categories(),
                    validated.pageSize() + 1,
                    Math.multiplyExact(validated.page() - 1, validated.pageSize()));
            List<ZnackGtinInventorySummary> loaded = List.copyOf(
                    Objects.requireNonNull(source.summaries(query), "GTIN summary page"));
            if (loaded.size() > query.limit() || loaded.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("GTIN summary page is invalid");
            }
            boolean hasMore = loaded.size() > validated.pageSize();
            List<GtinItem> items = loaded.subList(0, Math.min(loaded.size(), validated.pageSize()))
                    .stream()
                    .map(KizMappingCommandService::toItem)
                    .toList();
            requireUniqueGtins(items);
            return new CatalogResponse(
                    validated.shopId(),
                    validated.query(),
                    validated.categories(),
                    validated.page(),
                    validated.pageSize(),
                    hasMore,
                    availableCategories,
                    items);
        });
    }

    @DesktopCommand("kizMapping.editor")
    @RequiresCapability("kiz-mapping:read")
    public CompletionStage<EditorResponse> editor(EditorRequest request, InvocationContext context) {
        ValidatedTarget target = validateTarget(request == null ? 0 : request.shopId(),
                request == null ? null : request.gtin());
        return SafeCommandExecutor.execute(() -> {
            requireShop(target.shopId());
            return toEditorResponse(target, requireEditor(target));
        });
    }

    @DesktopCommand("kizMapping.save")
    @RequiresCapability("kiz-mapping:write")
    public CompletionStage<EditorResponse> save(SaveRequest request, InvocationContext context) {
        ValidatedSave validated = validateSave(request);
        return SafeCommandExecutor.execute(() -> {
            requireShop(validated.target().shopId());
            synchronized (mutationLock) {
                EditorModel current = editorModel(validated.target(), requireEditor(validated.target()));
                List<ZnackGtinMappingSelection> selections = validateSelections(validated, current);
                try {
                    source.replaceRules(validated.target().shopId(), validated.target().gtin(), selections);
                } catch (KizMappingRepository.MappingConflictException conflict) {
                    throw invalid("The mapping is already owned by another GTIN.");
                }
                return toEditorResponse(validated.target(), requireEditor(validated.target()));
            }
        });
    }

    private EditorData requireEditor(ValidatedTarget target) {
        EditorData data = Objects.requireNonNull(
                source.editor(target.shopId(), target.gtin()), "GTIN editor data");
        if (!data.productExists()) {
            throw invalid("The GTIN is not registered for the selected shop.");
        }
        return data;
    }

    private EditorResponse toEditorResponse(ValidatedTarget target, EditorData data) {
        EditorModel model = editorModel(target, data);
        List<SubjectOption> subjects = model.subjects().stream().map(subject -> {
            Map<String, String> owners = model.owners().getOrDefault(subject, Map.of());
            String wildcardOwner = owners.getOrDefault(KizMappingRepository.WILDCARD_GENDER, "");
            Set<String> selected = model.selectedExact().getOrDefault(subject, Set.of());
            List<GenderOption> genders = model.genders().getOrDefault(subject, List.of()).stream()
                    .map(gender -> new GenderOption(
                            gender,
                            selected.contains(gender),
                            effectiveOwner(owners, gender)))
                    .toList();
            boolean wildcardSelected = model.selectedWildcard().contains(subject);
            return new SubjectOption(
                    subject,
                    wildcardSelected || !selected.isEmpty(),
                    wildcardSelected,
                    wildcardOwner,
                    genders);
        }).toList();
        return new EditorResponse(target.shopId(), target.gtin(), subjects);
    }

    private static EditorModel editorModel(ValidatedTarget target, EditorData data) {
        List<String> subjects = sanitizeLabels(data.subjects(), MAX_SUBJECTS, true, true, "subject catalog");
        Map<String, List<String>> genders = new LinkedHashMap<>();
        Map<String, Map<String, String>> owners = new LinkedHashMap<>();
        for (String subject : subjects) {
            List<String> sourceGenders = data.gendersBySubject().get(subject);
            List<String> safeGenders = sanitizeLabels(
                    sourceGenders == null ? List.of() : sourceGenders,
                    MAX_GENDERS_PER_SUBJECT,
                    true,
                    true,
                    "gender catalog");
            genders.put(subject, safeGenders);
            Map<String, String> sourceOwners = data.ownersBySubject().getOrDefault(subject, Map.of());
            Map<String, String> safeOwners = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : sourceOwners.entrySet()) {
                String key = entry.getKey();
                if (!KizMappingRepository.WILDCARD_GENDER.equals(key) && !safeGenders.contains(key)) {
                    throw new IllegalStateException("GTIN owner data is invalid");
                }
                String owner = normalizeSourceGtin(entry.getValue());
                if (safeOwners.putIfAbsent(key, owner) != null) {
                    throw new IllegalStateException("GTIN owner data is duplicated");
                }
            }
            owners.put(subject, Map.copyOf(safeOwners));
        }

        Set<String> wildcard = new LinkedHashSet<>();
        Map<String, Set<String>> selectedExact = new LinkedHashMap<>();
        List<ZnackGtinMappingRule> rules = List.copyOf(
                Objects.requireNonNull(data.currentRules(), "current GTIN rules"));
        if (rules.size() > MAX_SELECTIONS || rules.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Current GTIN rules are invalid");
        }
        for (ZnackGtinMappingRule rule : rules) {
            String ruleGtin = normalizeSourceGtin(rule.gtin());
            if (rule.shopId() != target.shopId()
                    || !ruleGtin.equals(target.gtin())
                    || !subjects.contains(rule.subjectName())) {
                throw new IllegalStateException("Current GTIN rule is invalid");
            }
            if (rule.wildcardGender()) {
                if (!wildcard.add(rule.subjectName())) {
                    throw new IllegalStateException("Current GTIN rule is duplicated");
                }
            } else {
                if (!genders.get(rule.subjectName()).contains(rule.genderValue())
                        || !selectedExact.computeIfAbsent(rule.subjectName(), ignored -> new LinkedHashSet<>())
                                .add(rule.genderValue())) {
                    throw new IllegalStateException("Current GTIN rule is invalid");
                }
            }
        }
        if (wildcard.stream().anyMatch(selectedExact::containsKey)) {
            throw new IllegalStateException("Current GTIN rules mix wildcard and exact values");
        }
        return new EditorModel(subjects, Map.copyOf(genders), Map.copyOf(owners),
                Set.copyOf(wildcard), immutableSets(selectedExact));
    }

    private static List<ZnackGtinMappingSelection> validateSelections(
            ValidatedSave validated, EditorModel current) {
        List<ZnackGtinMappingSelection> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Boolean> modes = new LinkedHashMap<>();
        for (SelectionRequest selection : validated.selections()) {
            String subject = writeLabel(selection.subjectName(), "The mapping subject is invalid.");
            if (!current.subjects().contains(subject)) {
                throw invalid("The mapping subject is no longer available.");
            }
            Map<String, String> owners = current.owners().getOrDefault(subject, Map.of());
            if (selection.wildcardGender()) {
                if (selection.genderValue() != null && !selection.genderValue().isBlank()) {
                    throw invalid("A wildcard mapping cannot include a gender value.");
                }
                if (owners.values().stream().anyMatch(owner -> !owner.equals(validated.target().gtin()))) {
                    throw invalid("The mapping is already owned by another GTIN.");
                }
                requireConsistentMode(modes, subject, true);
                requireUniqueSelection(keys, subject + "\u0000*");
                result.add(new ZnackGtinMappingSelection(subject, null, true));
            } else {
                String gender = writeLabel(selection.genderValue(), "The mapping gender is invalid.");
                if (!current.genders().getOrDefault(subject, List.of()).contains(gender)) {
                    throw invalid("The mapping gender is no longer available.");
                }
                String owner = effectiveOwner(owners, gender);
                if (!owner.isEmpty() && !owner.equals(validated.target().gtin())) {
                    throw invalid("The mapping is already owned by another GTIN.");
                }
                requireConsistentMode(modes, subject, false);
                requireUniqueSelection(keys, subject + "\u0000" + gender);
                result.add(new ZnackGtinMappingSelection(subject, gender, false));
            }
        }
        return List.copyOf(result);
    }

    private static void requireConsistentMode(Map<String, Boolean> modes, String subject, boolean wildcard) {
        Boolean previous = modes.putIfAbsent(subject, wildcard);
        if (previous != null && previous != wildcard) {
            throw invalid("Wildcard and exact gender mappings cannot be mixed.");
        }
    }

    private static void requireUniqueSelection(Set<String> keys, String key) {
        if (!keys.add(key)) throw invalid("The mapping selections contain duplicates.");
    }

    private void requireShop(int shopId) {
        List<Shop> available = List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
        if (available.stream().noneMatch(shop -> shop != null && shop.getId() == shopId)) {
            throw invalid("The selected shop is not available.");
        }
    }

    private static ValidatedCatalog validateCatalog(CatalogRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw invalid("A positive shop id is required.");
        }
        String query = validateQuery(request.query());
        List<String> categories = sanitizeLabels(
                request.categories(), MAX_CATEGORY_COUNT, true, false, "category filter");
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw invalid("The requested GTIN page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw invalid("The requested GTIN page size is invalid.");
        }
        return new ValidatedCatalog(
                request.shopId(), query, categories, request.page(), request.pageSize());
    }

    private static ValidatedTarget validateTarget(int shopId, String gtin) {
        if (shopId <= 0) throw invalid("A positive shop id is required.");
        try {
            return new ValidatedTarget(shopId, GtinNormalizer.requireProductionOrderable(gtin));
        } catch (IllegalArgumentException error) {
            throw invalid("A production GTIN is required.");
        }
    }

    private static ValidatedSave validateSave(SaveRequest request) {
        ValidatedTarget target = validateTarget(
                request == null ? 0 : request.shopId(), request == null ? null : request.gtin());
        if (request.selections() == null
                || request.selections().size() > MAX_SELECTIONS
                || request.selections().stream().anyMatch(Objects::isNull)) {
            throw invalid("The mapping selections are invalid.");
        }
        return new ValidatedSave(target, List.copyOf(request.selections()));
    }

    private static String validateQuery(String value) {
        if (value == null || value.length() > MAX_QUERY_LENGTH || hasControls(value)) {
            throw invalid("The GTIN search query is invalid.");
        }
        return value.strip();
    }

    private static List<String> sanitizeLabels(
            List<String> values, int maxCount, boolean strict, boolean rejectDuplicates, String field) {
        if (values == null || values.size() > maxCount) {
            throw strict ? invalid("The " + field + " is invalid.")
                    : new IllegalStateException("The " + field + " is invalid");
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > MAX_LABEL_LENGTH || hasControls(value)) {
                if (strict) throw invalid("The " + field + " is invalid.");
                continue;
            }
            String label = cleanText(value, MAX_LABEL_LENGTH);
            unique.putIfAbsent(label.toLowerCase(Locale.ROOT), label);
        }
        if (rejectDuplicates && unique.size() != values.size()) {
            throw invalid("The " + field + " contains duplicates.");
        }
        return List.copyOf(unique.values());
    }

    private static String writeLabel(String value, String error) {
        if (value == null || value.isBlank() || value.length() > MAX_LABEL_LENGTH || hasControls(value)) {
            throw invalid(error);
        }
        return value.strip();
    }

    private static GtinItem toItem(ZnackGtinInventorySummary summary) {
        String gtin = normalizeSourceGtin(summary.gtin());
        if (summary.available() < 0 || summary.reserved() < 0 || summary.consumed() < 0
                || summary.mappingRuleCount() < 0) {
            throw new IllegalStateException("GTIN inventory counts are invalid");
        }
        String error = cleanText(
                ZnackSanitizer.message(ZnackErrorMessages.display(summary.latestError())),
                MAX_ERROR_LENGTH);
        return new GtinItem(
                gtin,
                cleanText(summary.productName(), MAX_LABEL_LENGTH),
                cleanText(summary.category(), MAX_LABEL_LENGTH),
                summary.available(),
                summary.reserved(),
                summary.consumed(),
                summary.mappingRuleCount(),
                safeStatus(summary.latestOrderStatus(), ORDER_STATUSES),
                safeStatus(summary.latestPipelineStage(), PIPELINE_STAGES),
                error,
                summary.syncedAt() == null ? "" : summary.syncedAt().toString());
    }

    private static String safeStatus(String value, Set<String> allowed) {
        if (value == null) return "";
        String status = value.strip().toUpperCase(Locale.ROOT);
        return allowed.contains(status) ? status : "";
    }

    private static String normalizeSourceGtin(String value) {
        try {
            return GtinNormalizer.normalize(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("GTIN source data is invalid");
        }
    }

    private static String effectiveOwner(Map<String, String> owners, String gender) {
        String exact = owners.get(gender);
        return exact == null || exact.isBlank()
                ? owners.getOrDefault(KizMappingRepository.WILDCARD_GENDER, "")
                : exact;
    }

    private static String cleanText(String value, int maxLength) {
        if (value == null) return "";
        String bounded = value.length() <= maxLength * 4 ? value : value.substring(0, maxLength * 4);
        String clean = bounded.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).strip();
    }

    private static boolean hasControls(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static void requireUniqueGtins(List<GtinItem> items) {
        Set<String> gtins = new LinkedHashSet<>();
        if (items.stream().anyMatch(item -> !gtins.add(item.gtin()))) {
            throw new IllegalStateException("GTIN summary page contains duplicates");
        }
    }

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> type) {
        Set<String> names = new LinkedHashSet<>();
        EnumSet.allOf(type).forEach(value -> names.add(value.name()));
        return Set.copyOf(names);
    }

    private static Map<String, Set<String>> immutableSets(Map<String, Set<String>> values) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    private static dev.jdesk.api.JDeskException invalid(String message) {
        return SafeCommandExecutor.invalidRequest(message);
    }

    public record CatalogRequest(
            int shopId, String query, List<String> categories, int page, int pageSize) {
        public CatalogRequest {
            categories = categories == null ? null : java.util.Collections.unmodifiableList(new ArrayList<>(categories));
        }
    }

    public record CatalogResponse(
            int shopId,
            String query,
            List<String> categories,
            int page,
            int pageSize,
            boolean hasMore,
            List<String> availableCategories,
            List<GtinItem> items) {
    }

    public record GtinItem(
            String gtin,
            String productName,
            String category,
            int available,
            int reserved,
            int consumed,
            int mappingRuleCount,
            String orderStatus,
            String pipelineStage,
            String errorMessage,
            String syncedAt) {
    }

    public record EditorRequest(int shopId, String gtin) {
    }

    public record SaveRequest(int shopId, String gtin, List<SelectionRequest> selections) {
        public SaveRequest {
            selections = selections == null
                    ? null
                    : java.util.Collections.unmodifiableList(new ArrayList<>(selections));
        }
    }

    public record SelectionRequest(String subjectName, String genderValue, boolean wildcardGender) {
    }

    public record EditorResponse(int shopId, String gtin, List<SubjectOption> subjects) {
    }

    public record SubjectOption(
            String subjectName,
            boolean selected,
            boolean wildcardSelected,
            String wildcardOwnerGtin,
            List<GenderOption> genders) {
    }

    public record GenderOption(String value, boolean selected, String ownerGtin) {
    }

    record CatalogQuery(int shopId, String query, List<String> categories, int limit, int offset) {
    }

    record EditorData(
            boolean productExists,
            List<String> subjects,
            Map<String, List<String>> gendersBySubject,
            List<ZnackGtinMappingRule> currentRules,
            Map<String, Map<String, String>> ownersBySubject) {
    }

    interface MappingDataSource {
        List<String> categories(int shopId);

        List<ZnackGtinInventorySummary> summaries(CatalogQuery query);

        EditorData editor(int shopId, String gtin);

        void replaceRules(int shopId, String gtin, List<ZnackGtinMappingSelection> selections);
    }

    @FunctionalInterface
    interface SummaryReader {
        List<ZnackGtinInventorySummary> read(CatalogQuery query);
    }

    private static final class LegacyMappingDataSource implements MappingDataSource {
        private final KizMappingRepository repository;

        private LegacyMappingDataSource(KizMappingRepository repository) {
            this.repository = repository;
        }

        @Override
        public List<String> categories(int shopId) {
            return repository.findGtinCategories(shopId);
        }

        @Override
        public List<ZnackGtinInventorySummary> summaries(CatalogQuery query) {
            return repository.findGtinSummariesPage(
                    query.shopId(), query.query(), query.categories(), query.limit(), query.offset());
        }

        @Override
        public EditorData editor(int shopId, String gtin) {
            List<String> subjects = repository.findSubjects(shopId);
            Map<String, List<String>> genders = new LinkedHashMap<>();
            Map<String, Map<String, String>> owners = new LinkedHashMap<>();
            for (String subject : subjects) {
                genders.put(subject, repository.findGendersForSubject(shopId, subject));
                owners.put(subject, repository.findOwnersForSubject(shopId, subject));
            }
            return new EditorData(
                    repository.hasGtinProduct(shopId, gtin),
                    subjects,
                    Map.copyOf(genders),
                    repository.findRulesForGtin(shopId, gtin),
                    Map.copyOf(owners));
        }

        @Override
        public void replaceRules(
                int shopId, String gtin, List<ZnackGtinMappingSelection> selections) {
            repository.replaceRulesForGtin(shopId, gtin, selections);
        }
    }

    private record ValidatedCatalog(
            int shopId, String query, List<String> categories, int page, int pageSize) {
    }

    private record ValidatedTarget(int shopId, String gtin) {
    }

    private record ValidatedSave(ValidatedTarget target, List<SelectionRequest> selections) {
    }

    private record EditorModel(
            List<String> subjects,
            Map<String, List<String>> genders,
            Map<String, Map<String, String>> owners,
            Set<String> selectedWildcard,
            Map<String, Set<String>> selectedExact) {
    }
}
