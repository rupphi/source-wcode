package com.tuandev.fbsbarcode.jdesk.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.print.PrintElementType;
import com.tuandev.fbsbarcode.features.print.PrintFieldKey;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateElement;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintTextAlign;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TemplateDesignerCommandServiceTest {
    private static final String SECRET = "template-repository-secret-that-must-not-cross-the-bridge";

    @Test
    void mapsTheAllowlistedCatalogToMillimetersAndStringIds() {
        AtomicReference<String> requestedMode = new AtomicReference<>();
        TemplateDesignerCommandService service = new TemplateDesignerCommandService(mode -> {
            requestedMode.set(mode);
            return catalog(template(17, "По умолчанию", true, element(" element-1 ")));
        });

        TemplateDesignerCommandService.TemplateDesignerResponse response = service
                .load(new TemplateDesignerCommandService.TemplateDesignerRequest("fbs"), null)
                .toCompletableFuture()
                .join();

        assertEquals("fbs", requestedMode.get());
        assertEquals("fbs", response.mode());
        assertEquals(58d, response.pageWidthMm());
        assertEquals(40d, response.pageHeightMm());
        assertEquals("17", response.templates().getFirst().id());
        assertEquals("element-1", response.templates().getFirst().elements().getFirst().id());
        assertEquals("text_field", response.templates().getFirst().elements().getFirst().type());
        assertEquals("article", response.templates().getFirst().elements().getFirst().fieldKey());
        assertEquals(2d, response.templates().getFirst().elements().getFirst().xMm(), 0.0001d);
        assertEquals(3d, response.templates().getFirst().elements().getFirst().yMm(), 0.0001d);
        assertEquals(20d, response.templates().getFirst().elements().getFirst().widthMm(), 0.0001d);
        assertEquals(5d, response.templates().getFirst().elements().getFirst().heightMm(), 0.0001d);
        assertEquals("left", response.templates().getFirst().elements().getFirst().align());
        assertEquals("text_field:article", response.palette().getFirst().key());
    }

    @Test
    void routesFboToItsOwnCatalogSource() {
        AtomicReference<String> requestedMode = new AtomicReference<>();
        TemplateDesignerCommandService service = new TemplateDesignerCommandService(mode -> {
            requestedMode.set(mode);
            return catalog(template(3, "FBO default", true, element("element-1")));
        });

        TemplateDesignerCommandService.TemplateDesignerResponse response = service
                .load(new TemplateDesignerCommandService.TemplateDesignerRequest("fbo"), null)
                .toCompletableFuture()
                .join();

        assertEquals("fbo", requestedMode.get());
        assertEquals("FBO default", response.templates().getFirst().name());
    }

    @Test
    void rejectsInvalidModesBeforeReadingTheCatalog() {
        AtomicInteger reads = new AtomicInteger();
        TemplateDesignerCommandService service = new TemplateDesignerCommandService(mode -> {
            reads.incrementAndGet();
            return catalog();
        });

        for (TemplateDesignerCommandService.TemplateDesignerRequest request : List.of(
                new TemplateDesignerCommandService.TemplateDesignerRequest(""),
                new TemplateDesignerCommandService.TemplateDesignerRequest("FBS"),
                new TemplateDesignerCommandService.TemplateDesignerRequest("unknown"))) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.load(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        JDeskException nullError = assertThrows(JDeskException.class, () -> service.load(null, null));
        assertEquals(ErrorCode.INVALID_REQUEST, nullError.code());
        assertEquals(0, reads.get());
    }

    @Test
    void mapsQuotaMalformedAndRepositoryFailuresWithoutLeakingDetails() {
        List<PrintTemplate> tooMany = new ArrayList<>();
        for (int index = 1; index <= 101; index++) {
            tooMany.add(template(index, "Template " + index, index == 1, element("element-" + index)));
        }
        PrintTemplate malformed = template(1, "Malformed", true, element("element-1"));
        malformed.getElements().getFirst().setX(Double.NaN);
        List<TemplateDesignerCommandService> services = List.of(
                new TemplateDesignerCommandService(mode -> new TemplateDesignerCommandService.CatalogData(
                        tooMany, palette())),
                new TemplateDesignerCommandService(mode -> catalog(malformed)),
                new TemplateDesignerCommandService(mode -> {
                    throw new IllegalStateException("sqlite " + SECRET);
                }));

        for (TemplateDesignerCommandService service : services) {
            JDeskException error = assertThrows(
                    JDeskException.class,
                    () -> service.load(new TemplateDesignerCommandService.TemplateDesignerRequest("fbs"), null));
            assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
            assertFalse(error.publicMessage().contains(SECRET));
            assertNull(error.details());
            assertNull(error.getCause());
        }
    }

    @Test
    void bridgeCodecContainsNoRawLayoutJson() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        TemplateDesignerCommandService.TemplateDesignerResponse response = new TemplateDesignerCommandService(
                        mode -> catalog(template(1, "Default", true, element("element-1"))))
                .load(new TemplateDesignerCommandService.TemplateDesignerRequest("fbs"), null)
                .toCompletableFuture()
                .join();

        String json = codec.encode(response);

        assertTrue(json.contains("\"pageWidthMm\":58.0"));
        assertTrue(json.contains("\"id\":\"1\""));
        assertFalse(json.contains("layoutJson"));
        assertFalse(json.contains("layout_json"));
    }

    @Test
    void readsBothConfiguredLiveTemplateCatalogsOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue("1".equals(System.getenv("WCODE_LIVE_READ_SMOKE")));
        try (AppDataLock ignored = AppDataLock.acquire(AppPaths.appDataDir(), "jdesk-live-template-test")) {
            Database.initDatabase();
            TemplateDesignerCommandService service = new TemplateDesignerCommandService();

            for (String mode : List.of("fbs", "fbo")) {
                TemplateDesignerCommandService.TemplateDesignerResponse response = service
                        .load(new TemplateDesignerCommandService.TemplateDesignerRequest(mode), null)
                        .toCompletableFuture()
                        .join();

                assertEquals(mode, response.mode());
                assertTrue(response.templates().size() <= 100);
                assertTrue(response.templates().stream().allMatch(template -> template.elements().size() <= 100));
                assertFalse(response.toString().contains("layout_json"));
            }
        }
    }

    private static TemplateDesignerCommandService.CatalogData catalog(PrintTemplate... templates) {
        return new TemplateDesignerCommandService.CatalogData(List.of(templates), palette());
    }

    private static List<PrintTemplateService.ElementPaletteItem> palette() {
        return List.of(new PrintTemplateService.ElementPaletteItem(
                "Артикул", PrintElementType.TEXT_FIELD, PrintFieldKey.ARTICLE));
    }

    private static PrintTemplate template(
            int id, String name, boolean defaultTemplate, PrintTemplateElement... elements) {
        PrintTemplate template = new PrintTemplate();
        template.setId(id);
        template.setName(name);
        template.setDefaultTemplate(defaultTemplate);
        template.setPageWidth(PrintTemplateService.PAGE_WIDTH);
        template.setPageHeight(PrintTemplateService.PAGE_HEIGHT);
        template.setElements(List.of(elements));
        return template;
    }

    private static PrintTemplateElement element(String id) {
        PrintTemplateElement element = PrintTemplateElement.create(
                PrintElementType.TEXT_FIELD,
                "Артикул",
                2 * PrintTemplateService.POINTS_PER_MM,
                3 * PrintTemplateService.POINTS_PER_MM,
                20 * PrintTemplateService.POINTS_PER_MM,
                5 * PrintTemplateService.POINTS_PER_MM);
        element.setId(id);
        element.setFieldKey(PrintFieldKey.ARTICLE);
        element.setPrefix("Арт");
        element.setZIndex(1);
        element.setFontSize(8f);
        element.setAlign(PrintTextAlign.LEFT);
        return element;
    }
}
