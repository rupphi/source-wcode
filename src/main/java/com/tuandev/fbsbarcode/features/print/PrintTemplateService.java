package com.tuandev.fbsbarcode.features.print;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PrintTemplateService {
    public static final double POINTS_PER_MM = 72d / 25.4d;
    public static final double PAGE_WIDTH_MM = 58d;
    public static final double PAGE_HEIGHT_MM = 40d;
    public static final double PAGE_WIDTH = PAGE_WIDTH_MM * POINTS_PER_MM;
    public static final double PAGE_HEIGHT = PAGE_HEIGHT_MM * POINTS_PER_MM;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final PrintTemplateRepository repository = new PrintTemplateRepository();

    public void ensureDefaultTemplateExists() {
        repository.normalizePageSize(PAGE_WIDTH, PAGE_HEIGHT);
        if (repository.count() == 0) {
            PrintTemplate template = createSystemDefaultTemplate("Mặc định");
            saveTemplate(template);
            repository.setDefault(template.getId());
        }
    }

    public List<PrintTemplate> loadTemplates() {
        ensureDefaultTemplateExists();
        List<PrintTemplate> result = new ArrayList<>();
        for (PrintTemplateRepository.TemplateRecord record : repository.findAll()) {
            result.add(fromRecord(record));
        }
        return result;
    }

    public PrintTemplate getDefaultTemplate() {
        ensureDefaultTemplateExists();
        return repository.findDefault()
                .map(this::fromRecord)
                .orElseGet(() -> loadTemplates().getFirst());
    }

    public Optional<PrintTemplate> findById(int id) {
        ensureDefaultTemplateExists();
        return repository.findById(id).map(this::fromRecord);
    }

    public PrintTemplate createTemplate(String name) {
        validateName(name, null);
        PrintTemplate template = createSystemDefaultTemplate(name);
        saveTemplate(template);
        return template;
    }

    public PrintTemplate duplicateTemplate(int templateId, String name) {
        validateName(name, templateId);
        PrintTemplate source = findById(templateId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy template"));
        PrintTemplate duplicate = fromJson(toJson(source));
        duplicate.setId(null);
        duplicate.setName(name);
        duplicate.setDefaultTemplate(false);
        saveTemplate(duplicate);
        return duplicate;
    }

    public void renameTemplate(int templateId, String name) {
        validateName(name, templateId);
        repository.rename(templateId, name.trim());
    }

    public void deleteTemplate(int templateId) {
        List<PrintTemplate> templates = loadTemplates();
        if (templates.size() <= 1) {
            throw new IllegalStateException("Phải giữ lại ít nhất một template");
        }
        boolean deletingDefault = templates.stream().anyMatch(t -> t.getId() == templateId && t.isDefaultTemplate());
        repository.delete(templateId);
        if (deletingDefault) {
            repository.findAll().stream().findFirst().ifPresent(record -> repository.setDefault(record.id()));
        }
    }

    public void setDefaultTemplate(int templateId) {
        repository.setDefault(templateId);
    }

    public void resetTemplateToSystemDefault(int templateId) {
        PrintTemplate current = findById(templateId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy template"));
        PrintTemplate reset = createSystemDefaultTemplate(current.getName());
        reset.setId(templateId);
        reset.setDefaultTemplate(current.isDefaultTemplate());
        saveTemplate(reset);
    }

    public void saveTemplate(PrintTemplate template) {
        validateTemplate(template);
        String json = toJson(template);
        if (template.getId() == null) {
            int id = repository.insert(template.getName().trim(), PAGE_WIDTH, PAGE_HEIGHT, template.isDefaultTemplate(), json);
            template.setId(id);
        } else {
            repository.update(template.getId(), template.getName().trim(), PAGE_WIDTH, PAGE_HEIGHT, template.isDefaultTemplate(), json);
        }
    }

    public PrintTemplate createSystemDefaultTemplate(String name) {
        PrintTemplate template = new PrintTemplate();
        template.setName(name);
        template.setPageWidth(PAGE_WIDTH);
        template.setPageHeight(PAGE_HEIGHT);
        List<PrintTemplateElement> elements = new ArrayList<>();

        PrintTemplateElement kiz = PrintTemplateElement.create(PrintElementType.KIZ_DATAMATRIX, "KIZ", 10, 10, 52, 52);
        kiz.setZIndex(1);
        elements.add(kiz);

        PrintTemplateElement brand = textField("Brand", PrintFieldKey.BRAND, 70, 8, 84, 10, 9, true, PrintTextAlign.CENTER, 2);
        elements.add(brand);
        elements.add(textField("Tên sản phẩm", PrintFieldKey.NAME, 70, 20, 84, 14, 8, false, PrintTextAlign.LEFT, 3));
        elements.add(textField("Màu", PrintFieldKey.COLOR, "Цвет", 70, 35, 84, 10, 8, false, PrintTextAlign.LEFT, 4));
        elements.add(textField("Article", PrintFieldKey.ARTICLE, "Арт", 70, 47, 84, 10, 8, false, PrintTextAlign.LEFT, 5));
        elements.add(textField("Size", PrintFieldKey.SIZE, "Раз", 70, 59, 84, 10, 9, false, PrintTextAlign.LEFT, 6));

        PrintTemplateElement separator = PrintTemplateElement.create(PrintElementType.SEPARATOR_LINE, "Đường phân cách", 10, 67, 144, 1);
        separator.setZIndex(7);
        elements.add(separator);

        PrintTemplateElement barcode = PrintTemplateElement.create(PrintElementType.BARCODE_CODE128, "Barcode", 12, 72, 140, 25);
        barcode.setShowHumanReadable(false);
        barcode.setZIndex(8);
        elements.add(barcode);

        elements.add(textField("Mã barcode", PrintFieldKey.BARCODE, null, 8, 99, 120, 8, 8, false, PrintTextAlign.CENTER, 9));

        PrintTemplateElement stickerTail = PrintTemplateElement.create(PrintElementType.STICKER_TAIL, "Sticker tail", 134, 99, 20, 8);
        stickerTail.setFontSize(8);
        stickerTail.setAlign(PrintTextAlign.RIGHT);
        stickerTail.setZIndex(10);
        elements.add(stickerTail);

        template.setElements(elements);
        return template;
    }

    public List<ElementPaletteItem> getPaletteItems() {
        return List.of(
                new ElementPaletteItem("KIZ", PrintElementType.KIZ_DATAMATRIX, null),
                new ElementPaletteItem("Barcode", PrintElementType.BARCODE_CODE128, null),
                new ElementPaletteItem("Brand", PrintElementType.TEXT_FIELD, PrintFieldKey.BRAND),
                new ElementPaletteItem("Tên sản phẩm", PrintElementType.TEXT_FIELD, PrintFieldKey.NAME),
                new ElementPaletteItem("Danh mục", PrintElementType.TEXT_FIELD, PrintFieldKey.SUBJECT_NAME),
                new ElementPaletteItem("Màu", PrintElementType.TEXT_FIELD, PrintFieldKey.COLOR),
                new ElementPaletteItem("Article", PrintElementType.TEXT_FIELD, PrintFieldKey.ARTICLE),
                new ElementPaletteItem("Size", PrintElementType.TEXT_FIELD, PrintFieldKey.SIZE),
                new ElementPaletteItem("Text cố định", PrintElementType.STATIC_TEXT, null),
                new ElementPaletteItem("Mã barcode", PrintElementType.TEXT_FIELD, PrintFieldKey.BARCODE),
                new ElementPaletteItem("Sticker tail", PrintElementType.STICKER_TAIL, null),
                new ElementPaletteItem("Đường phân cách", PrintElementType.SEPARATOR_LINE, null)
        );
    }

    public PrintTemplateElement createElementFromPalette(ElementPaletteItem item, int zIndex) {
        if (item.type() == PrintElementType.KIZ_DATAMATRIX) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 10, 10, 52, 52);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.BARCODE_CODE128) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 12, 72, 140, 25);
            element.setShowHumanReadable(false);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.SEPARATOR_LINE) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 10, 67, 144, 1);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.STICKER_TAIL) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 134, 99, 20, 8);
            element.setFontSize(8);
            element.setAlign(PrintTextAlign.RIGHT);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.STATIC_TEXT) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 10, 10, 84, 10);
            element.setContent("Текст");
            element.setFontSize(8);
            element.setAlign(PrintTextAlign.LEFT);
            element.setZIndex(zIndex);
            return element;
        }
        PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 10, 10, 84, 10);
        element.setFieldKey(item.fieldKey());
        element.setPrefix(defaultPrefix(item.fieldKey()));
        element.setFontSize(8);
        element.setAlign(PrintTextAlign.LEFT);
        element.setZIndex(zIndex);
        return element;
    }

    public String toJson(PrintTemplate template) {
        PrintTemplate copy = new PrintTemplate();
        copy.setName(template.getName());
        copy.setPageWidth(PAGE_WIDTH);
        copy.setPageHeight(PAGE_HEIGHT);
        copy.setDefaultTemplate(template.isDefaultTemplate());
        copy.setElements(sortedElements(template.getElements()));
        return GSON.toJson(copy);
    }

    public PrintTemplate fromJson(String json) {
        PrintTemplate template = GSON.fromJson(json, PrintTemplate.class);
        if (template.getElements() == null) {
            template.setElements(List.of());
        }
        template.setPageWidth(PAGE_WIDTH);
        template.setPageHeight(PAGE_HEIGHT);
        return template;
    }

    private PrintTemplate fromRecord(PrintTemplateRepository.TemplateRecord record) {
        PrintTemplate template = fromJson(record.layoutJson());
        template.setId(record.id());
        template.setName(record.name());
        template.setPageWidth(PAGE_WIDTH);
        template.setPageHeight(PAGE_HEIGHT);
        template.setDefaultTemplate(record.isDefault());
        return template;
    }

    private void validateName(String name, Integer currentId) {
        String safeName = name == null ? "" : name.trim();
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("Tên template không được để trống");
        }
        boolean exists = repository.findAll().stream()
                .anyMatch(template -> safeName.equalsIgnoreCase(template.name())
                        && (currentId == null || template.id() != currentId));
        if (exists) {
            throw new IllegalArgumentException("Tên template đã tồn tại");
        }
    }

    private void validateTemplate(PrintTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("Template không hợp lệ");
        }
        validateName(template.getName(), template.getId());
        if (template.getElements() == null || template.getElements().isEmpty()) {
            throw new IllegalArgumentException("Template phải có ít nhất một phần tử");
        }

        boolean hasKiz = false;
        boolean hasBarcode = false;
        boolean hasStickerTail = false;

        List<PrintTemplateElement> elements = sortedElements(template.getElements());
        for (int index = 0; index < elements.size(); index++) {
            PrintTemplateElement element = elements.get(index);
            if (element.getId() == null || element.getId().isBlank()) {
                throw new IllegalArgumentException("Mỗi phần tử phải có id");
            }
            clampToPage(element);
            element.setZIndex(index + 1);
            if (element.getType() == PrintElementType.KIZ_DATAMATRIX) {
                hasKiz = true;
            } else if (element.getType() == PrintElementType.BARCODE_CODE128) {
                hasBarcode = true;
            } else if (element.getType() == PrintElementType.STICKER_TAIL) {
                hasStickerTail = true;
            } else if (element.getType() == PrintElementType.TEXT_FIELD && element.getFieldKey() == null) {
                throw new IllegalArgumentException("Text field phải có fieldKey");
            } else if (element.getType() == PrintElementType.STATIC_TEXT && safeTrim(element.getContent()).isBlank()) {
                throw new IllegalArgumentException("Text cố định không được để trống");
            }
            if (element.getPrefix() != null) {
                element.setPrefix(element.getPrefix().trim());
            }
            if (element.getContent() != null) {
                element.setContent(element.getContent().trim());
            }
        }
        if (!hasKiz || !hasBarcode || !hasStickerTail) {
            throw new IllegalArgumentException("Template bắt buộc phải có KIZ, barcode và sticker tail");
        }
    }

    public void clampToPage(PrintTemplateElement element) {
        element.setWidth(Math.max(0, Math.min(PAGE_WIDTH, element.getWidth())));
        element.setHeight(Math.max(0, Math.min(PAGE_HEIGHT, element.getHeight())));
        element.setX(Math.max(0, Math.min(PAGE_WIDTH - element.getWidth(), element.getX())));
        element.setY(Math.max(0, Math.min(PAGE_HEIGHT - element.getHeight(), element.getY())));
        if (element.getFontSize() < 0f) {
            element.setFontSize(0f);
        }
    }

    private static List<PrintTemplateElement> sortedElements(List<PrintTemplateElement> elements) {
        List<PrintTemplateElement> copy = new ArrayList<>(elements);
        copy.sort(Comparator.comparingInt(PrintTemplateElement::getZIndex).thenComparing(PrintTemplateElement::getLabel, Comparator.nullsLast(String::compareToIgnoreCase)));
        return copy;
    }

    private static PrintTemplateElement textField(String label, PrintFieldKey fieldKey, String prefix, double x, double y, double width, double height,
                                                  float fontSize, boolean bold, PrintTextAlign align, int zIndex) {
        PrintTemplateElement element = PrintTemplateElement.create(PrintElementType.TEXT_FIELD, label, x, y, width, height);
        element.setFieldKey(fieldKey);
        element.setPrefix(prefix == null ? defaultPrefix(fieldKey) : prefix);
        element.setFontSize(fontSize);
        element.setBold(bold);
        element.setAlign(align);
        element.setZIndex(zIndex);
        return element;
    }

    private static PrintTemplateElement textField(String label, PrintFieldKey fieldKey, double x, double y, double width, double height,
                                                  float fontSize, boolean bold, PrintTextAlign align, int zIndex) {
        return textField(label, fieldKey, null, x, y, width, height, fontSize, bold, align, zIndex);
    }

    private static String defaultPrefix(PrintFieldKey fieldKey) {
        if (fieldKey == null) {
            return null;
        }
        return switch (fieldKey) {
            case COLOR -> "Цвет";
            case ARTICLE -> "Арт";
            case SIZE -> "Раз";
            case SUBJECT_NAME -> "Кат";
            default -> null;
        };
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ElementPaletteItem(String label, PrintElementType type, PrintFieldKey fieldKey) {
        @Override
        public String toString() {
            return label;
        }
    }
}
