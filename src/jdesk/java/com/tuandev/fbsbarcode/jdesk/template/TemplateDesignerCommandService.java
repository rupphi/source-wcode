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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public final class TemplateDesignerCommandService {
    private static final int MAX_TEMPLATES = 100;
    private static final int MAX_ELEMENTS = 100;
    private static final int MAX_PALETTE_ITEMS = 100;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final double PAGE_EPSILON_POINTS = 0.01d;

    private final CatalogSource catalogs;

    public TemplateDesignerCommandService() {
        this(mode -> {
            PrintTemplateService service = mode.equals("fbo")
                    ? new FboPrintTemplateService()
                    : new PrintTemplateService();
            return new CatalogData(service.loadTemplates(), service.getPaletteItems());
        });
    }

    TemplateDesignerCommandService(CatalogSource catalogs) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    @DesktopCommand("templates.loadDesigner")
    @RequiresCapability("templates:read")
    public CompletionStage<TemplateDesignerResponse> load(
            TemplateDesignerRequest request, InvocationContext context) {
        String mode = validateMode(request);
        return SafeCommandExecutor.execute(() -> toResponse(mode, catalogs.load(mode)));
    }

    private static String validateMode(TemplateDesignerRequest request) {
        if (request == null || !("fbs".equals(request.mode()) || "fbo".equals(request.mode()))) {
            throw SafeCommandExecutor.invalidRequest("The template mode is invalid.");
        }
        return request.mode();
    }

    static TemplateDesignerResponse toResponse(String mode, CatalogData catalog) {
        Objects.requireNonNull(catalog, "template catalog");
        List<PrintTemplate> templates = List.copyOf(
                Objects.requireNonNull(catalog.templates(), "templates"));
        List<PrintTemplateService.ElementPaletteItem> palette = List.copyOf(
                Objects.requireNonNull(catalog.palette(), "template palette"));
        if (templates.size() > MAX_TEMPLATES || palette.size() > MAX_PALETTE_ITEMS) {
            throw new IllegalStateException("Template catalog quota exceeded");
        }

        Set<Integer> templateIds = new HashSet<>();
        int defaultCount = 0;
        List<TemplateSummary> mappedTemplates = templates.stream()
                .map(template -> mapTemplate(template, templateIds))
                .toList();
        for (PrintTemplate template : templates) {
            if (template.isDefaultTemplate()) {
                defaultCount++;
            }
        }
        if (defaultCount > 1) {
            throw new IllegalStateException("Template catalog has multiple defaults");
        }

        Set<String> paletteKeys = new HashSet<>();
        List<TemplatePaletteItem> mappedPalette = palette.stream()
                .map(item -> mapPaletteItem(item, paletteKeys))
                .toList();
        return new TemplateDesignerResponse(
                mode,
                PrintTemplateService.PAGE_WIDTH_MM,
                PrintTemplateService.PAGE_HEIGHT_MM,
                MAX_TEMPLATES,
                MAX_ELEMENTS,
                mappedTemplates,
                mappedPalette);
    }

    private static TemplateSummary mapTemplate(PrintTemplate template, Set<Integer> templateIds) {
        Objects.requireNonNull(template, "template");
        Integer id = template.getId();
        if (id == null || id <= 0 || !templateIds.add(id)) {
            throw new IllegalStateException("Template id is invalid");
        }
        String name = text(template.getName(), MAX_NAME_LENGTH);
        if (name.isBlank()) {
            throw new IllegalStateException("Template name is invalid");
        }
        List<PrintTemplateElement> elements = List.copyOf(
                Objects.requireNonNull(template.getElements(), "template elements"));
        if (elements.size() > MAX_ELEMENTS) {
            throw new IllegalStateException("Template element quota exceeded");
        }
        Set<String> elementIds = new HashSet<>();
        List<TemplateElementItem> mappedElements = elements.stream()
                .map(element -> mapElement(element, elementIds))
                .toList();
        return new TemplateSummary(
                Integer.toString(id), name, template.isDefaultTemplate(), mappedElements);
    }

    private static TemplateElementItem mapElement(
            PrintTemplateElement element, Set<String> elementIds) {
        Objects.requireNonNull(element, "template element");
        String id = identifier(element.getId());
        if (!elementIds.add(id)) {
            throw new IllegalStateException("Template element id is duplicated");
        }
        PrintElementType type = Objects.requireNonNull(element.getType(), "template element type");
        PrintFieldKey fieldKey = element.getFieldKey();
        if (type == PrintElementType.TEXT_FIELD && fieldKey == null) {
            throw new IllegalStateException("Template text field key is missing");
        }
        PrintTextAlign align = Objects.requireNonNull(element.getAlign(), "template element align");
        requireGeometry(element);
        if (!Float.isFinite(element.getFontSize())
                || element.getFontSize() < 0f
                || element.getFontSize() > 200f
                || element.getZIndex() < 0
                || element.getZIndex() > MAX_ELEMENTS) {
            throw new IllegalStateException("Template element style is invalid");
        }
        String label = text(element.getLabel(), MAX_NAME_LENGTH);
        if (label.isBlank()) {
            throw new IllegalStateException("Template element label is invalid");
        }
        return new TemplateElementItem(
                id,
                enumKey(type),
                fieldKey == null ? "" : enumKey(fieldKey),
                label,
                text(element.getPrefix(), MAX_NAME_LENGTH),
                text(element.getContent(), MAX_TEXT_LENGTH),
                toMillimeters(element.getX()),
                toMillimeters(element.getY()),
                toMillimeters(element.getWidth()),
                toMillimeters(element.getHeight()),
                element.isVisible(),
                element.getZIndex(),
                element.getFontSize(),
                element.isBold(),
                enumKey(align),
                element.isShowHumanReadable());
    }

    static TemplateElementItem toItem(PrintTemplateElement element) {
        return mapElement(element, new HashSet<>());
    }

    private static TemplatePaletteItem mapPaletteItem(
            PrintTemplateService.ElementPaletteItem item, Set<String> paletteKeys) {
        Objects.requireNonNull(item, "template palette item");
        PrintElementType type = Objects.requireNonNull(item.type(), "template palette type");
        PrintFieldKey fieldKey = item.fieldKey();
        if (type == PrintElementType.TEXT_FIELD && fieldKey == null) {
            throw new IllegalStateException("Template palette field key is missing");
        }
        String typeKey = enumKey(type);
        String key = typeKey + (fieldKey == null ? "" : ":" + enumKey(fieldKey));
        String label = text(item.label(), MAX_NAME_LENGTH);
        if (label.isBlank() || !paletteKeys.add(key)) {
            throw new IllegalStateException("Template palette item is invalid");
        }
        return new TemplatePaletteItem(
                key, label, typeKey, fieldKey == null ? "" : enumKey(fieldKey));
    }

    private static void requireGeometry(PrintTemplateElement element) {
        double[] values = {
            element.getX(), element.getY(), element.getWidth(), element.getHeight()
        };
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0d) {
                throw new IllegalStateException("Template element geometry is invalid");
            }
        }
        if (element.getX() + element.getWidth()
                        > PrintTemplateService.PAGE_WIDTH + PAGE_EPSILON_POINTS
                || element.getY() + element.getHeight()
                        > PrintTemplateService.PAGE_HEIGHT + PAGE_EPSILON_POINTS) {
            throw new IllegalStateException("Template element is outside the page");
        }
    }

    private static double toMillimeters(double points) {
        return points / PrintTemplateService.POINTS_PER_MM;
    }

    private static String identifier(String value) {
        if (value == null) {
            throw new IllegalStateException("Template element id is invalid");
        }
        String normalized = value.strip();
        if (normalized.isBlank()
                || normalized.length() > MAX_IDENTIFIER_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Template element id is invalid");
        }
        return normalized;
    }

    private static String text(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    private static String enumKey(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface CatalogSource {
        CatalogData load(String mode);
    }

    record CatalogData(
            List<PrintTemplate> templates,
            List<PrintTemplateService.ElementPaletteItem> palette) {
    }

    public record TemplateDesignerRequest(String mode) {
    }

    public record TemplateElementItem(
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

    public record TemplateSummary(
            String id,
            String name,
            boolean defaultTemplate,
            List<TemplateElementItem> elements) {
        public TemplateSummary {
            elements = List.copyOf(elements);
        }
    }

    public record TemplatePaletteItem(
            String key, String label, String type, String fieldKey) {
    }

    public record TemplateDesignerResponse(
            String mode,
            double pageWidthMm,
            double pageHeightMm,
            int maxTemplates,
            int maxElements,
            List<TemplateSummary> templates,
            List<TemplatePaletteItem> palette) {
        public TemplateDesignerResponse {
            templates = List.copyOf(templates);
            palette = List.copyOf(palette);
        }
    }
}
