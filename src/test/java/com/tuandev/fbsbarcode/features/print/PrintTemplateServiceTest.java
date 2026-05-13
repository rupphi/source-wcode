package com.tuandev.fbsbarcode.features.print;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintTemplateServiceTest {
    private final PrintTemplateService service = new PrintTemplateService();

    @Test
    void shouldCreateDefaultTemplateShape() {
        PrintTemplate template = service.createSystemDefaultTemplate("Test");

        assertEquals("Test", template.getName());
        assertEquals(PrintTemplateService.PAGE_WIDTH, template.getPageWidth());
        assertEquals(PrintTemplateService.PAGE_HEIGHT, template.getPageHeight());
        assertTrue(template.getElements().stream().anyMatch(item -> item.getType() == PrintElementType.KIZ_DATAMATRIX));
        assertTrue(template.getElements().stream().anyMatch(item -> item.getType() == PrintElementType.BARCODE_CODE128));
        assertTrue(template.getElements().stream().anyMatch(item -> item.getType() == PrintElementType.STICKER_TAIL));
        assertEquals("Цвет", template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.COLOR).findFirst().orElseThrow().getPrefix());
        assertEquals("Арт", template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.ARTICLE).findFirst().orElseThrow().getPrefix());
        assertEquals("Раз", template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.SIZE).findFirst().orElseThrow().getPrefix());
    }

    @Test
    void shouldRoundTripTemplateJson() {
        PrintTemplate source = service.createSystemDefaultTemplate("Roundtrip");
        String json = service.toJson(source);

        PrintTemplate restored = service.fromJson(json);

        assertEquals(source.getElements().size(), restored.getElements().size());
        assertEquals(PrintTemplateService.PAGE_WIDTH, restored.getPageWidth());
        assertEquals(PrintTemplateService.PAGE_HEIGHT, restored.getPageHeight());
        assertNotNull(restored.getElements().getFirst().getLabel());
    }

    @Test
    void shouldCreatePaletteElementWithExpectedDefaults() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.type() == PrintElementType.BARCODE_CODE128)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 3);

        assertEquals(PrintElementType.BARCODE_CODE128, element.getType());
        assertEquals(3, element.getZIndex());
        assertTrue(element.getWidth() > 0);
        assertTrue(element.getHeight() > 0);
    }

    @Test
    void shouldCreateTextPaletteElementWithDefaultPrefix() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.fieldKey() == PrintFieldKey.SIZE)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 4);

        assertEquals("Раз", element.getPrefix());
    }

    @Test
    void shouldCreateStaticTextPaletteElementWithEditableContent() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.type() == PrintElementType.STATIC_TEXT)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 5);

        assertEquals(PrintElementType.STATIC_TEXT, element.getType());
        assertEquals("Текст", element.getContent());
        assertEquals(5, element.getZIndex());
    }
}
