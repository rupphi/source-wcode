package com.tuandev.fbsbarcode.jdesk.template;

import com.tuandev.fbsbarcode.features.fbo.FboPrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintElementType;
import com.tuandev.fbsbarcode.features.print.PrintFieldKey;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateElement;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintTextAlign;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class TemplateDesignerMutationCommandService {
    private static final int MAX_TEMPLATES = 100;
    private static final int MAX_ELEMENTS = 100;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final double PAGE_EPSILON_MM = 0.001d;

    private final OperationsSource operationsSource;
    private final Object mutationLock = new Object();

    public TemplateDesignerMutationCommandService() {
        this(mode -> new LegacyTemplateOperations(mode.equals("fbo")
                ? new FboPrintTemplateService()
                : new PrintTemplateService()));
    }

    TemplateDesignerMutationCommandService(OperationsSource operationsSource) {
        this.operationsSource = Objects.requireNonNull(operationsSource, "operationsSource");
    }

    @DesktopCommand("templates.create")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> create(
            TemplateNameRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        String name = writeText(request.name(), MAX_NAME_LENGTH, true, "The template name is invalid.");
        return executeLocked(mode, operations -> {
            TemplateDesignerCommandService.CatalogData before = requireCatalog(operations);
            requireCreateCapacity(before);
            requireAvailableName(before, name, null);
            PrintTemplate created = operations.create(name);
            return response(mode, operations, requireTemplateId(created));
        });
    }

    @DesktopCommand("templates.duplicate")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> duplicate(
            TemplateDuplicateRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        int templateId = parseTemplateId(request.templateId());
        String name = writeText(request.name(), MAX_NAME_LENGTH, true, "The template name is invalid.");
        return executeLocked(mode, operations -> {
            TemplateDesignerCommandService.CatalogData before = requireCatalog(operations);
            requireCreateCapacity(before);
            requireTarget(operations, templateId);
            requireAvailableName(before, name, null);
            PrintTemplate duplicate = operations.duplicate(templateId, name);
            return response(mode, operations, requireTemplateId(duplicate));
        });
    }

    @DesktopCommand("templates.rename")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> rename(
            TemplateRenameRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        int templateId = parseTemplateId(request.templateId());
        String name = writeText(request.name(), MAX_NAME_LENGTH, true, "The template name is invalid.");
        return executeLocked(mode, operations -> {
            TemplateDesignerCommandService.CatalogData before = requireCatalog(operations);
            requireTarget(operations, templateId);
            requireAvailableName(before, name, templateId);
            operations.rename(templateId, name);
            return response(mode, operations, templateId);
        });
    }

    @DesktopCommand("templates.delete")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> delete(
            TemplateTargetRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        int templateId = parseTemplateId(request.templateId());
        return executeLocked(mode, operations -> {
            TemplateDesignerCommandService.CatalogData before = requireCatalog(operations);
            requireTarget(operations, templateId);
            if (before.templates().size() <= 1) {
                throw invalid("At least one template must be kept.");
            }
            operations.delete(templateId);
            TemplateDesignerCommandService.CatalogData after = requireCatalog(operations);
            PrintTemplate selected = after.templates().stream()
                    .filter(PrintTemplate::isDefaultTemplate)
                    .findFirst()
                    .orElse(after.templates().getFirst());
            return response(mode, after, requireTemplateId(selected));
        });
    }

    @DesktopCommand("templates.setDefault")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> setDefault(
            TemplateTargetRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        int templateId = parseTemplateId(request.templateId());
        return executeLocked(mode, operations -> {
            requireTarget(operations, templateId);
            operations.setDefault(templateId);
            return response(mode, operations, templateId);
        });
    }

    @DesktopCommand("templates.reset")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> reset(
            TemplateTargetRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        int templateId = parseTemplateId(request.templateId());
        return executeLocked(mode, operations -> {
            requireTarget(operations, templateId);
            operations.reset(templateId);
            return response(mode, operations, templateId);
        });
    }

    @DesktopCommand("templates.save")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateMutationResponse> save(
            TemplateSaveRequest request, InvocationContext context) {
        ValidatedTemplate validated = validateTemplateRequest(request);
        return executeLocked(validated.mode(), operations -> {
            PrintTemplate current = requireTarget(operations, validated.templateId());
            TemplateDesignerCommandService.CatalogData before = requireCatalog(operations);
            requireAvailableName(before, validated.name(), validated.templateId());
            PrintTemplate template = validated.toTemplate(current.isDefaultTemplate());
            operations.save(template);
            return response(validated.mode(), operations, validated.templateId());
        });
    }

    @DesktopCommand("templates.createElement")
    @RequiresCapability("templates:write")
    public CompletionStage<TemplateDesignerCommandService.TemplateElementItem> createElement(
            TemplateElementRequest request, InvocationContext context) {
        String mode = validateMode(request == null ? null : request.mode());
        String paletteKey = identifier(request.paletteKey(), "The palette item is invalid.");
        if (request.zIndex() < 1 || request.zIndex() > MAX_ELEMENTS) {
            throw invalid("The element layer is invalid.");
        }
        return executeLocked(mode, operations -> {
            TemplateDesignerCommandService.CatalogData catalog = requireCatalog(operations);
            PrintTemplateService.ElementPaletteItem paletteItem = catalog.palette().stream()
                    .filter(item -> paletteKey(item).equals(paletteKey))
                    .findFirst()
                    .orElseThrow(() -> invalid("The palette item is invalid."));
            PrintTemplateElement element = operations.createElement(paletteItem, request.zIndex());
            return TemplateDesignerCommandService.toItem(element);
        });
    }

    private <T> CompletionStage<T> executeLocked(
            String mode, Function<TemplateOperations, T> operation) {
        return SafeCommandExecutor.execute(() -> {
            synchronized (mutationLock) {
                TemplateOperations operations = Objects.requireNonNull(
                        operationsSource.open(mode), "template operations");
                return operation.apply(operations);
            }
        });
    }

    private static TemplateMutationResponse response(
            String mode, TemplateOperations operations, int selectedTemplateId) {
        return response(mode, requireCatalog(operations), selectedTemplateId);
    }

    private static TemplateMutationResponse response(
            String mode,
            TemplateDesignerCommandService.CatalogData catalog,
            int selectedTemplateId) {
        TemplateDesignerCommandService.TemplateDesignerResponse designer =
                TemplateDesignerCommandService.toResponse(mode, catalog);
        String selected = Integer.toString(selectedTemplateId);
        if (designer.templates().stream().noneMatch(template -> template.id().equals(selected))) {
            throw new IllegalStateException("Selected template is missing after mutation");
        }
        return new TemplateMutationResponse(designer, selected);
    }

    private static TemplateDesignerCommandService.CatalogData requireCatalog(
            TemplateOperations operations) {
        TemplateDesignerCommandService.CatalogData catalog = Objects.requireNonNull(
                operations.load(), "template catalog");
        List<PrintTemplate> templates = List.copyOf(
                Objects.requireNonNull(catalog.templates(), "templates"));
        List<PrintTemplateService.ElementPaletteItem> palette = List.copyOf(
                Objects.requireNonNull(catalog.palette(), "palette"));
        if (templates.size() > MAX_TEMPLATES || palette.size() > MAX_ELEMENTS) {
            throw new IllegalStateException("Template catalog quota exceeded");
        }
        return new TemplateDesignerCommandService.CatalogData(templates, palette);
    }

    private static void requireCreateCapacity(
            TemplateDesignerCommandService.CatalogData catalog) {
        if (catalog.templates().size() >= MAX_TEMPLATES) {
            throw invalid("The template limit has been reached.");
        }
    }

    private static PrintTemplate requireTarget(TemplateOperations operations, int templateId) {
        return operations.find(templateId)
                .orElseThrow(() -> invalid("The selected template is not available."));
    }

    private static void requireAvailableName(
            TemplateDesignerCommandService.CatalogData catalog, String name, Integer currentId) {
        boolean exists = catalog.templates().stream().anyMatch(template ->
                name.equalsIgnoreCase(template.getName())
                        && (currentId == null || !currentId.equals(template.getId())));
        if (exists) {
            throw invalid("A template with this name already exists.");
        }
    }

    private static int requireTemplateId(PrintTemplate template) {
        Objects.requireNonNull(template, "template");
        Integer id = template.getId();
        if (id == null || id <= 0) {
            throw new IllegalStateException("Created template id is invalid");
        }
        return id;
    }

    private static ValidatedTemplate validateTemplateRequest(TemplateSaveRequest request) {
        String mode = validateMode(request == null ? null : request.mode());
        if (request.template() == null) {
            throw invalid("The template draft is required.");
        }
        TemplateDraft draft = request.template();
        int templateId = parseTemplateId(draft.id());
        String name = writeText(draft.name(), MAX_NAME_LENGTH, true, "The template name is invalid.");
        List<TemplateElementDraft> rawElements = draft.elements() == null
                ? List.of()
                : List.copyOf(draft.elements());
        if (rawElements.isEmpty() || rawElements.size() > MAX_ELEMENTS) {
            throw invalid("A template must contain between 1 and 100 elements.");
        }

        Set<String> ids = new HashSet<>();
        boolean hasKiz = false;
        boolean hasBarcode = false;
        boolean hasStickerTail = false;
        List<ValidatedElement> elements = new ArrayList<>(rawElements.size());
        for (TemplateElementDraft element : rawElements) {
            ValidatedElement validated = validateElement(element);
            if (!ids.add(validated.id())) {
                throw invalid("Template element ids must be unique.");
            }
            hasKiz |= validated.type() == PrintElementType.KIZ_DATAMATRIX;
            hasBarcode |= validated.type() == PrintElementType.BARCODE_CODE128;
            hasStickerTail |= validated.type() == PrintElementType.STICKER_TAIL;
            elements.add(validated);
        }
        if (!hasKiz || !hasBarcode || !hasStickerTail) {
            throw invalid("KIZ, Code128 and sticker-tail elements are required.");
        }
        return new ValidatedTemplate(mode, templateId, name, List.copyOf(elements));
    }

    private static ValidatedElement validateElement(TemplateElementDraft draft) {
        if (draft == null) {
            throw invalid("The template element is invalid.");
        }
        String id = identifier(draft.id(), "The template element id is invalid.");
        PrintElementType type = enumValue(
                PrintElementType.class, draft.type(), "The template element type is invalid.");
        PrintFieldKey fieldKey = null;
        String rawFieldKey = draft.fieldKey() == null ? "" : draft.fieldKey();
        if (type == PrintElementType.TEXT_FIELD) {
            fieldKey = enumValue(
                    PrintFieldKey.class, rawFieldKey, "The template field is invalid.");
        } else if (!rawFieldKey.isBlank()) {
            throw invalid("Only text fields may have a field key.");
        }
        String label = writeText(
                draft.label(), MAX_NAME_LENGTH, true, "The template element label is invalid.");
        String prefix = writeText(
                draft.prefix(), MAX_NAME_LENGTH, false, "The template element prefix is invalid.");
        String content = writeText(
                draft.content(), MAX_TEXT_LENGTH, false, "The template element content is invalid.");
        if (type == PrintElementType.STATIC_TEXT && content.isBlank()) {
            throw invalid("Static text content is required.");
        }
        PrintTextAlign align = enumValue(
                PrintTextAlign.class, draft.align(), "The template alignment is invalid.");
        requireGeometry(draft);
        if (!Double.isFinite(draft.fontSizePt())
                || draft.fontSizePt() < 0d
                || draft.fontSizePt() > 200d
                || draft.zIndex() < 0
                || draft.zIndex() > MAX_ELEMENTS) {
            throw invalid("The template element style is invalid.");
        }
        return new ValidatedElement(
                id,
                type,
                fieldKey,
                label,
                prefix,
                content,
                draft.xMm(),
                draft.yMm(),
                draft.widthMm(),
                draft.heightMm(),
                draft.visible(),
                draft.zIndex(),
                draft.fontSizePt(),
                draft.bold(),
                align,
                draft.humanReadable());
    }

    private static void requireGeometry(TemplateElementDraft draft) {
        double[] values = {draft.xMm(), draft.yMm(), draft.widthMm(), draft.heightMm()};
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0d) {
                throw invalid("The template element geometry is invalid.");
            }
        }
        if (draft.xMm() + draft.widthMm()
                        > PrintTemplateService.PAGE_WIDTH_MM + PAGE_EPSILON_MM
                || draft.yMm() + draft.heightMm()
                        > PrintTemplateService.PAGE_HEIGHT_MM + PAGE_EPSILON_MM) {
            throw invalid("The template element is outside the page.");
        }
    }

    private static String validateMode(String mode) {
        if (!("fbs".equals(mode) || "fbo".equals(mode))) {
            throw invalid("The template mode is invalid.");
        }
        return mode;
    }

    private static int parseTemplateId(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 10
                || value.chars().anyMatch(character -> !Character.isDigit(character))) {
            throw invalid("The template id is invalid.");
        }
        try {
            int id = Integer.parseInt(value);
            if (id <= 0) {
                throw invalid("The template id is invalid.");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw invalid("The template id is invalid.");
        }
    }

    private static String identifier(String value, String errorMessage) {
        if (value == null) {
            throw invalid(errorMessage);
        }
        String normalized = value.strip();
        if (normalized.isBlank()
                || normalized.length() > MAX_IDENTIFIER_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid(errorMessage);
        }
        return normalized;
    }

    private static String writeText(
            String value, int maxLength, boolean required, String errorMessage) {
        String normalized = value == null ? "" : value.strip();
        if ((required && normalized.isBlank())
                || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid(errorMessage);
        }
        return normalized;
    }

    private static <T extends Enum<T>> T enumValue(
            Class<T> type, String value, String errorMessage) {
        if (value == null || !value.matches("[a-z0-9_]+")) {
            throw invalid(errorMessage);
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(errorMessage);
        }
    }

    private static String paletteKey(PrintTemplateService.ElementPaletteItem item) {
        String type = item.type().name().toLowerCase(Locale.ROOT);
        return item.fieldKey() == null
                ? type
                : type + ":" + item.fieldKey().name().toLowerCase(Locale.ROOT);
    }

    private static dev.jdesk.api.JDeskException invalid(String message) {
        return SafeCommandExecutor.invalidRequest(message);
    }

    @FunctionalInterface
    interface OperationsSource {
        TemplateOperations open(String mode);
    }

    interface TemplateOperations {
        TemplateDesignerCommandService.CatalogData load();

        Optional<PrintTemplate> find(int id);

        PrintTemplate create(String name);

        PrintTemplate duplicate(int id, String name);

        void rename(int id, String name);

        void delete(int id);

        void setDefault(int id);

        void reset(int id);

        void save(PrintTemplate template);

        PrintTemplateElement createElement(
                PrintTemplateService.ElementPaletteItem item, int zIndex);
    }

    private static final class LegacyTemplateOperations implements TemplateOperations {
        private final PrintTemplateService service;

        private LegacyTemplateOperations(PrintTemplateService service) {
            this.service = service;
        }

        @Override
        public TemplateDesignerCommandService.CatalogData load() {
            return new TemplateDesignerCommandService.CatalogData(
                    service.loadTemplates(), service.getPaletteItems());
        }

        @Override
        public Optional<PrintTemplate> find(int id) {
            return service.findById(id);
        }

        @Override
        public PrintTemplate create(String name) {
            return service.createTemplate(name);
        }

        @Override
        public PrintTemplate duplicate(int id, String name) {
            return service.duplicateTemplate(id, name);
        }

        @Override
        public void rename(int id, String name) {
            service.renameTemplate(id, name);
        }

        @Override
        public void delete(int id) {
            service.deleteTemplate(id);
        }

        @Override
        public void setDefault(int id) {
            service.setDefaultTemplate(id);
        }

        @Override
        public void reset(int id) {
            service.resetTemplateToSystemDefault(id);
        }

        @Override
        public void save(PrintTemplate template) {
            service.saveTemplate(template);
        }

        @Override
        public PrintTemplateElement createElement(
                PrintTemplateService.ElementPaletteItem item, int zIndex) {
            return service.createElementFromPalette(item, zIndex);
        }
    }

    private record ValidatedTemplate(
            String mode, int templateId, String name, List<ValidatedElement> elements) {
        private PrintTemplate toTemplate(boolean defaultTemplate) {
            PrintTemplate template = new PrintTemplate();
            template.setId(templateId);
            template.setName(name);
            template.setPageWidth(PrintTemplateService.PAGE_WIDTH);
            template.setPageHeight(PrintTemplateService.PAGE_HEIGHT);
            template.setDefaultTemplate(defaultTemplate);
            template.setElements(elements.stream().map(ValidatedElement::toElement).toList());
            return template;
        }
    }

    private record ValidatedElement(
            String id,
            PrintElementType type,
            PrintFieldKey fieldKey,
            String label,
            String prefix,
            String content,
            double xMm,
            double yMm,
            double widthMm,
            double heightMm,
            boolean visible,
            int zIndex,
            double fontSizePt,
            boolean bold,
            PrintTextAlign align,
            boolean humanReadable) {
        private PrintTemplateElement toElement() {
            PrintTemplateElement element = PrintTemplateElement.create(
                    type,
                    label,
                    xMm * PrintTemplateService.POINTS_PER_MM,
                    yMm * PrintTemplateService.POINTS_PER_MM,
                    widthMm * PrintTemplateService.POINTS_PER_MM,
                    heightMm * PrintTemplateService.POINTS_PER_MM);
            element.setId(id);
            element.setFieldKey(fieldKey);
            element.setPrefix(prefix.isBlank() ? null : prefix);
            element.setContent(content.isBlank() ? null : content);
            element.setVisible(visible);
            element.setZIndex(zIndex);
            element.setFontSize((float) fontSizePt);
            element.setBold(bold);
            element.setAlign(align);
            element.setShowHumanReadable(humanReadable);
            return element;
        }
    }

    public record TemplateNameRequest(String mode, String name) {
    }

    public record TemplateDuplicateRequest(
            String mode, String templateId, String name) {
    }

    public record TemplateRenameRequest(
            String mode, String templateId, String name) {
    }

    public record TemplateTargetRequest(String mode, String templateId) {
    }

    public record TemplateElementRequest(String mode, String paletteKey, int zIndex) {
    }

    public record TemplateSaveRequest(String mode, TemplateDraft template) {
    }

    public record TemplateDraft(
            String id, String name, List<TemplateElementDraft> elements) {
        public TemplateDraft {
            elements = elements == null ? null : List.copyOf(elements);
        }
    }

    public record TemplateElementDraft(
            String id,
            String type,
            String fieldKey,
            String label,
            String prefix,
            String content,
            double xMm,
            double yMm,
            double widthMm,
            double heightMm,
            boolean visible,
            int zIndex,
            double fontSizePt,
            boolean bold,
            String align,
            boolean humanReadable) {
    }

    public record TemplateMutationResponse(
            TemplateDesignerCommandService.TemplateDesignerResponse designer,
            String selectedTemplateId) {
    }
}
