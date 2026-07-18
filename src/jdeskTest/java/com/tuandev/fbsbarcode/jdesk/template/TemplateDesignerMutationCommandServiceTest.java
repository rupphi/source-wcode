package com.tuandev.fbsbarcode.jdesk.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.print.PrintElementType;
import com.tuandev.fbsbarcode.features.print.PrintFieldKey;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateElement;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintTextAlign;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateDesignerMutationCommandServiceTest {
    private static final double POINTS_PER_MM = PrintTemplateService.POINTS_PER_MM;

    @Test
    void createsInTheRequestedModeAndReturnsTheSelectedTypedCatalog() {
        AtomicReference<String> requestedMode = new AtomicReference<>();
        FakeOperations operations = new FakeOperations(template(1, "Default", true));
        TemplateDesignerMutationCommandService service =
                new TemplateDesignerMutationCommandService(mode -> {
                    requestedMode.set(mode);
                    return operations;
                });

        TemplateDesignerMutationCommandService.TemplateMutationResponse response = service
                .create(new TemplateDesignerMutationCommandService.TemplateNameRequest(
                        "fbo", "  Shipping label  "), null)
                .toCompletableFuture()
                .join();

        assertEquals("fbo", requestedMode.get());
        assertEquals("2", response.selectedTemplateId());
        assertEquals("Shipping label", response.designer().templates().get(1).name());
        assertEquals(2, operations.templates.size());
    }

    @Test
    void mapsTheCatalogAfterAStoreCreate() {
        FakeOperations operations = new FakeOperations(template(1, "Default", true));
        operations.create("Shipping label");
        for (PrintTemplate candidate : operations.templates) {
            for (PrintTemplateElement element : candidate.getElements()) {
                assertTrue(
                        element.getX() + element.getWidth() <= PrintTemplateService.PAGE_WIDTH + 0.01d
                                && element.getY() + element.getHeight() <= PrintTemplateService.PAGE_HEIGHT + 0.01d,
                        () -> candidate.getName() + ": " + element.getLabel()
                                + " at " + element.getX() + "," + element.getY()
                                + " size " + element.getWidth() + "x" + element.getHeight());
            }
        }

        TemplateDesignerCommandService.TemplateDesignerResponse response =
                TemplateDesignerCommandService.toResponse("fbo", operations.load());

        assertEquals(2, response.templates().size());
    }

    @Test
    void savesAnAllowlistedMillimeterDraftAndPreservesTheDefaultFlag() {
        PrintTemplate original = template(7, "Default", true);
        FakeOperations operations = new FakeOperations(original);
        TemplateDesignerMutationCommandService service =
                new TemplateDesignerMutationCommandService(mode -> operations);

        TemplateDesignerMutationCommandService.TemplateMutationResponse response = service
                .save(new TemplateDesignerMutationCommandService.TemplateSaveRequest(
                        "fbs",
                        new TemplateDesignerMutationCommandService.TemplateDraft(
                                "7", "Default", requiredDraftElements())), null)
                .toCompletableFuture()
                .join();

        PrintTemplate saved = operations.saved;
        assertTrue(saved.isDefaultTemplate());
        assertEquals(2d * POINTS_PER_MM, saved.getElements().getFirst().getX(), 0.0001d);
        assertEquals(18d * POINTS_PER_MM, saved.getElements().getFirst().getWidth(), 0.0001d);
        assertEquals(PrintElementType.KIZ_DATAMATRIX, saved.getElements().getFirst().getType());
        assertEquals("7", response.selectedTemplateId());
        assertEquals(2d, response.designer().templates().getFirst().elements().getFirst().xMm(), 0.0001d);
    }

    @Test
    void createsPaletteElementsFromLegacyDefaultsWithoutPersisting() {
        FakeOperations operations = new FakeOperations(template(1, "Default", true));
        TemplateDesignerMutationCommandService service =
                new TemplateDesignerMutationCommandService(mode -> operations);

        TemplateDesignerCommandService.TemplateElementItem item = service
                .createElement(new TemplateDesignerMutationCommandService.TemplateElementRequest(
                        "fbs", "text_field:article", 12), null)
                .toCompletableFuture()
                .join();

        assertEquals("text_field", item.type());
        assertEquals("article", item.fieldKey());
        assertEquals(12, item.zIndex());
        assertEquals(1, operations.templates.size());
        assertFalse(item.id().isBlank());
    }

    @Test
    void rejectsMalformedDraftsAndMissingTargetsBeforeMutation() {
        FakeOperations operations = new FakeOperations(template(1, "Default", true));
        TemplateDesignerMutationCommandService service =
                new TemplateDesignerMutationCommandService(mode -> operations);
        List<TemplateDesignerMutationCommandService.TemplateElementDraft> duplicateIds =
                new ArrayList<>(requiredDraftElements());
        duplicateIds.set(1, copyWithId(duplicateIds.get(1), duplicateIds.getFirst().id()));

        List<Runnable> invalidCalls = List.of(
                () -> service.rename(new TemplateDesignerMutationCommandService.TemplateRenameRequest(
                        "fbs", "999", "Missing"), null),
                () -> service.setDefault(new TemplateDesignerMutationCommandService.TemplateTargetRequest(
                        "fbo", "999"), null),
                () -> service.save(new TemplateDesignerMutationCommandService.TemplateSaveRequest(
                        "fbs", new TemplateDesignerMutationCommandService.TemplateDraft(
                                "1", "Default", duplicateIds)), null),
                () -> service.save(new TemplateDesignerMutationCommandService.TemplateSaveRequest(
                        "fbs", new TemplateDesignerMutationCommandService.TemplateDraft(
                                "1", "Default", List.of(requiredDraftElements().getFirst()))), null),
                () -> service.createElement(new TemplateDesignerMutationCommandService.TemplateElementRequest(
                        "fbs", "unknown", 2), null));

        for (Runnable call : invalidCalls) {
            JDeskException error = assertThrows(JDeskException.class, call::run);
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, operations.mutations);
    }

    @Test
    void rejectsUntrustedGeometryEnumsNamesAndCatalogQuotas() {
        FakeOperations operations = new FakeOperations(template(1, "Default", true));
        TemplateDesignerMutationCommandService service =
                new TemplateDesignerMutationCommandService(mode -> operations);
        List<TemplateDesignerMutationCommandService.TemplateElementDraft> nonFinite =
                replacingFirst(copyWithGeometry(
                        requiredDraftElements().getFirst(), Double.NaN, 3, 18, 18));
        List<TemplateDesignerMutationCommandService.TemplateElementDraft> outsidePage =
                replacingFirst(copyWithGeometry(
                        requiredDraftElements().getFirst(), 57, 3, 2, 18));
        List<TemplateDesignerMutationCommandService.TemplateElementDraft> unknownType =
                replacingFirst(copyWithType(
                        requiredDraftElements().getFirst(), "html_script"));

        for (List<TemplateDesignerMutationCommandService.TemplateElementDraft> elements :
                List.of(nonFinite, outsidePage, unknownType)) {
            JDeskException error = assertThrows(
                    JDeskException.class,
                    () -> service.save(new TemplateDesignerMutationCommandService.TemplateSaveRequest(
                            "fbs",
                            new TemplateDesignerMutationCommandService.TemplateDraft(
                                    "1", "Default", elements)), null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(
                        JDeskException.class,
                        () -> service.create(new TemplateDesignerMutationCommandService.TemplateNameRequest(
                                "fbs", "Default"), null))
                .code());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(
                        JDeskException.class,
                        () -> service.create(new TemplateDesignerMutationCommandService.TemplateNameRequest(
                                "fbs", "Bad\nname"), null))
                .code());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(
                        JDeskException.class,
                        () -> service.delete(new TemplateDesignerMutationCommandService.TemplateTargetRequest(
                                "fbs", "1"), null))
                .code());
        assertEquals(0, operations.mutations);

        PrintTemplate[] fullCatalog = IntStream.rangeClosed(1, 100)
                .mapToObj(id -> template(id, "Template " + id, id == 1))
                .toArray(PrintTemplate[]::new);
        FakeOperations fullOperations = new FakeOperations(fullCatalog);
        TemplateDesignerMutationCommandService fullService =
                new TemplateDesignerMutationCommandService(mode -> fullOperations);
        JDeskException quota = assertThrows(
                JDeskException.class,
                () -> fullService.create(new TemplateDesignerMutationCommandService.TemplateNameRequest(
                        "fbs", "Template 101"), null));
        assertEquals(ErrorCode.INVALID_REQUEST, quota.code());
        assertEquals(0, fullOperations.mutations);
    }

    @Test
    void mapsRepositoryFailuresWithoutLeakingDetails() {
        TemplateDesignerMutationCommandService service = new TemplateDesignerMutationCommandService(mode -> {
            throw new IllegalStateException("sqlite secret-write-detail");
        });

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.create(new TemplateDesignerMutationCommandService.TemplateNameRequest(
                        "fbs", "Safe name"), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains("secret-write-detail"));
    }

    @Test
    void persistsTheFullLifecycleInSeparateFbsAndFboTables(@TempDir Path appData) {
        String previousAppData = System.getProperty("wcode.appdata.dir");
        System.setProperty("wcode.appdata.dir", appData.toString());
        try {
            Database.initDatabase();
            TemplateDesignerMutationCommandService service =
                    new TemplateDesignerMutationCommandService();

            for (String mode : List.of("fbs", "fbo")) {
                TemplateDesignerMutationCommandService.TemplateMutationResponse created = service
                        .create(new TemplateDesignerMutationCommandService.TemplateNameRequest(
                                mode, "Custom " + mode), null)
                        .toCompletableFuture()
                        .join();
                String createdId = created.selectedTemplateId();
                TemplateDesignerMutationCommandService.TemplateMutationResponse renamed = service
                        .rename(new TemplateDesignerMutationCommandService.TemplateRenameRequest(
                                mode, createdId, "Renamed " + mode), null)
                        .toCompletableFuture()
                        .join();
                assertTrue(renamed.designer().templates().stream()
                        .anyMatch(template -> template.name().equals("Renamed " + mode)));

                TemplateDesignerMutationCommandService.TemplateMutationResponse duplicated = service
                        .duplicate(new TemplateDesignerMutationCommandService.TemplateDuplicateRequest(
                                mode, createdId, "Duplicate " + mode), null)
                        .toCompletableFuture()
                        .join();
                String duplicateId = duplicated.selectedTemplateId();
                TemplateDesignerMutationCommandService.TemplateMutationResponse madeDefault = service
                        .setDefault(new TemplateDesignerMutationCommandService.TemplateTargetRequest(
                                mode, duplicateId), null)
                        .toCompletableFuture()
                        .join();
                assertTrue(madeDefault.designer().templates().stream()
                        .filter(template -> template.id().equals(duplicateId))
                        .findFirst()
                        .orElseThrow()
                        .defaultTemplate());

                TemplateDesignerCommandService.TemplateSummary duplicate = madeDefault.designer()
                        .templates()
                        .stream()
                        .filter(template -> template.id().equals(duplicateId))
                        .findFirst()
                        .orElseThrow();
                TemplateDesignerMutationCommandService.TemplateMutationResponse saved = service
                        .save(new TemplateDesignerMutationCommandService.TemplateSaveRequest(
                                mode, toDraft(duplicate)), null)
                        .toCompletableFuture()
                        .join();
                assertEquals(duplicateId, saved.selectedTemplateId());

                TemplateDesignerMutationCommandService.TemplateMutationResponse reset = service
                        .reset(new TemplateDesignerMutationCommandService.TemplateTargetRequest(
                                mode, duplicateId), null)
                        .toCompletableFuture()
                        .join();
                assertEquals(10, reset.designer().templates().stream()
                        .filter(template -> template.id().equals(duplicateId))
                        .findFirst()
                        .orElseThrow()
                        .elements()
                        .size());

                TemplateDesignerMutationCommandService.TemplateMutationResponse deleted = service
                        .delete(new TemplateDesignerMutationCommandService.TemplateTargetRequest(
                                mode, createdId), null)
                        .toCompletableFuture()
                        .join();
                assertFalse(deleted.designer().templates().stream()
                        .anyMatch(template -> template.id().equals(createdId)));
            }
        } finally {
            if (previousAppData == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousAppData);
            }
        }
    }

    private static TemplateDesignerMutationCommandService.TemplateDraft toDraft(
            TemplateDesignerCommandService.TemplateSummary template) {
        List<TemplateDesignerMutationCommandService.TemplateElementDraft> elements = template.elements()
                .stream()
                .map(element -> new TemplateDesignerMutationCommandService.TemplateElementDraft(
                        element.id(),
                        element.type(),
                        element.fieldKey(),
                        element.label(),
                        element.prefix(),
                        element.content(),
                        element.xMm(),
                        element.yMm(),
                        element.widthMm(),
                        element.heightMm(),
                        element.visible(),
                        element.zIndex(),
                        element.fontSizePt(),
                        element.bold(),
                        element.align(),
                        element.humanReadable()))
                .toList();
        return new TemplateDesignerMutationCommandService.TemplateDraft(
                template.id(), template.name(), elements);
    }

    private static List<TemplateDesignerMutationCommandService.TemplateElementDraft>
            requiredDraftElements() {
        return List.of(
                draft("kiz", "kiz_datamatrix", "", 2, 3, 18, 18, 1),
                draft("barcode", "barcode_code128", "", 2, 24, 53, 8, 2),
                draft("tail", "sticker_tail", "", 47, 36, 8, 3, 3));
    }

    private static TemplateDesignerMutationCommandService.TemplateElementDraft draft(
            String id,
            String type,
            String fieldKey,
            double x,
            double y,
            double width,
            double height,
            int zIndex) {
        return new TemplateDesignerMutationCommandService.TemplateElementDraft(
                id,
                type,
                fieldKey,
                type,
                "",
                "",
                x,
                y,
                width,
                height,
                true,
                zIndex,
                8,
                false,
                "left",
                false);
    }

    private static TemplateDesignerMutationCommandService.TemplateElementDraft copyWithId(
            TemplateDesignerMutationCommandService.TemplateElementDraft source, String id) {
        return new TemplateDesignerMutationCommandService.TemplateElementDraft(
                id,
                source.type(),
                source.fieldKey(),
                source.label(),
                source.prefix(),
                source.content(),
                source.xMm(),
                source.yMm(),
                source.widthMm(),
                source.heightMm(),
                source.visible(),
                source.zIndex(),
                source.fontSizePt(),
                source.bold(),
                source.align(),
                source.humanReadable());
    }

    private static TemplateDesignerMutationCommandService.TemplateElementDraft copyWithGeometry(
            TemplateDesignerMutationCommandService.TemplateElementDraft source,
            double x,
            double y,
            double width,
            double height) {
        return new TemplateDesignerMutationCommandService.TemplateElementDraft(
                source.id(),
                source.type(),
                source.fieldKey(),
                source.label(),
                source.prefix(),
                source.content(),
                x,
                y,
                width,
                height,
                source.visible(),
                source.zIndex(),
                source.fontSizePt(),
                source.bold(),
                source.align(),
                source.humanReadable());
    }

    private static TemplateDesignerMutationCommandService.TemplateElementDraft copyWithType(
            TemplateDesignerMutationCommandService.TemplateElementDraft source, String type) {
        return new TemplateDesignerMutationCommandService.TemplateElementDraft(
                source.id(),
                type,
                source.fieldKey(),
                source.label(),
                source.prefix(),
                source.content(),
                source.xMm(),
                source.yMm(),
                source.widthMm(),
                source.heightMm(),
                source.visible(),
                source.zIndex(),
                source.fontSizePt(),
                source.bold(),
                source.align(),
                source.humanReadable());
    }

    private static List<TemplateDesignerMutationCommandService.TemplateElementDraft> replacingFirst(
            TemplateDesignerMutationCommandService.TemplateElementDraft replacement) {
        List<TemplateDesignerMutationCommandService.TemplateElementDraft> elements =
                new ArrayList<>(requiredDraftElements());
        elements.set(0, replacement);
        return List.copyOf(elements);
    }

    private static PrintTemplate template(int id, String name, boolean defaultTemplate) {
        PrintTemplateService service = new PrintTemplateService();
        PrintTemplate template = service.createSystemDefaultTemplate(name);
        template.getElements().forEach(service::clampToPage);
        template.setId(id);
        template.setDefaultTemplate(defaultTemplate);
        return template;
    }

    private static final class FakeOperations
            implements TemplateDesignerMutationCommandService.TemplateOperations {
        private final List<PrintTemplate> templates = new ArrayList<>();
        private PrintTemplate saved;
        private int mutations;

        private FakeOperations(PrintTemplate... templates) {
            this.templates.addAll(List.of(templates));
        }

        @Override
        public TemplateDesignerCommandService.CatalogData load() {
            return new TemplateDesignerCommandService.CatalogData(
                    List.copyOf(templates), palette());
        }

        @Override
        public Optional<PrintTemplate> find(int id) {
            return templates.stream().filter(template -> template.getId() == id).findFirst();
        }

        @Override
        public PrintTemplate create(String name) {
            mutations++;
            PrintTemplate created = template(templates.size() + 1, name.strip(), false);
            templates.add(created);
            return created;
        }

        @Override
        public PrintTemplate duplicate(int id, String name) {
            mutations++;
            PrintTemplate duplicate = template(templates.size() + 1, name.strip(), false);
            templates.add(duplicate);
            return duplicate;
        }

        @Override
        public void rename(int id, String name) {
            mutations++;
            find(id).orElseThrow().setName(name);
        }

        @Override
        public void delete(int id) {
            mutations++;
            templates.removeIf(template -> template.getId() == id);
        }

        @Override
        public void setDefault(int id) {
            mutations++;
            templates.forEach(template -> template.setDefaultTemplate(template.getId() == id));
        }

        @Override
        public void reset(int id) {
            mutations++;
        }

        @Override
        public void save(PrintTemplate template) {
            mutations++;
            saved = template;
            templates.replaceAll(current -> current.getId().equals(template.getId()) ? template : current);
        }

        @Override
        public PrintTemplateElement createElement(
                PrintTemplateService.ElementPaletteItem item, int zIndex) {
            PrintTemplateElement element = PrintTemplateElement.create(
                    item.type(), item.label(), 2 * POINTS_PER_MM, 2 * POINTS_PER_MM,
                    20 * POINTS_PER_MM, 5 * POINTS_PER_MM);
            element.setFieldKey(item.fieldKey());
            element.setAlign(PrintTextAlign.LEFT);
            element.setZIndex(zIndex);
            return element;
        }

        private static List<PrintTemplateService.ElementPaletteItem> palette() {
            return List.of(new PrintTemplateService.ElementPaletteItem(
                    "Article", PrintElementType.TEXT_FIELD, PrintFieldKey.ARTICLE));
        }
    }
}
