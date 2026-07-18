package com.tuandev.fbsbarcode.jdesk.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.features.print.PrintJobOptions;
import com.tuandev.fbsbarcode.features.print.PrintPageOrder;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PrintCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-never-cross-the-bridge";

    @Test
    void loadsSavedOptionsAndSanitizedTemplateSummariesWithoutSecrets() {
        PrintCommandService service = service(
                List.of(template(9, " Main\u0000 label ", true), template(12, "Compact", false)),
                new PrintJobOptions(PrintPageOrder.STICKER_THEN_BARCODE, 3),
                ignored -> {});

        PrintCommandService.PrintSetupResponse response = service
                .loadSetup(new PrintCommandService.PrintSetupRequest(7), null)
                .toCompletableFuture()
                .join();

        assertEquals(7, response.shopId());
        assertEquals("sticker_then_barcode", response.pageOrder());
        assertEquals(3, response.barcodeCopies());
        assertEquals(9, response.defaultTemplateId());
        assertEquals(58d, response.pageWidthMm());
        assertEquals(40d, response.pageHeightMm());
        assertEquals(
                List.of(
                        new PrintCommandService.PrintTemplateSummary(9, "Main label", true),
                        new PrintCommandService.PrintTemplateSummary(12, "Compact", false)),
                response.templates());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void savesCanonicalOptionsAndReturnsTheUpdatedSetup() {
        List<PrintJobOptions> saved = new ArrayList<>();
        PrintCommandService service = service(
                List.of(template(9, "Default", true)),
                PrintJobOptions.defaults(),
                saved::add);

        PrintCommandService.PrintSetupResponse response = service
                .saveOptions(new PrintCommandService.SavePrintOptionsRequest(
                        7, "sticker_then_barcode", 4), null)
                .toCompletableFuture()
                .join();

        assertEquals(
                List.of(new PrintJobOptions(PrintPageOrder.STICKER_THEN_BARCODE, 4)),
                saved);
        assertEquals("sticker_then_barcode", response.pageOrder());
        assertEquals(4, response.barcodeCopies());
    }

    @Test
    void rejectsUnknownShopMalformedOrderAndUnsafeCopyCountsBeforePersistence() {
        AtomicInteger saves = new AtomicInteger();
        PrintCommandService service = service(
                List.of(template(9, "Default", true)),
                PrintJobOptions.defaults(),
                ignored -> saves.incrementAndGet());

        assertInvalid(() -> service.loadSetup(new PrintCommandService.PrintSetupRequest(8), null));
        assertInvalid(() -> service.saveOptions(
                new PrintCommandService.SavePrintOptionsRequest(7, "unknown", 1), null));
        assertInvalid(() -> service.saveOptions(
                new PrintCommandService.SavePrintOptionsRequest(7, "barcode_then_sticker", 0), null));
        assertInvalid(() -> service.saveOptions(
                new PrintCommandService.SavePrintOptionsRequest(7, "barcode_then_sticker", 101), null));

        assertEquals(0, saves.get());
    }

    @Test
    void rejectsMissingOrAmbiguousDefaultTemplate() {
        PrintCommandService noDefault = service(
                List.of(template(9, "Only", false)),
                PrintJobOptions.defaults(),
                ignored -> {});
        PrintCommandService twoDefaults = service(
                List.of(template(9, "First", true), template(10, "Second", true)),
                PrintJobOptions.defaults(),
                ignored -> {});

        assertInternal(() -> noDefault.loadSetup(new PrintCommandService.PrintSetupRequest(7), null));
        assertInternal(() -> twoDefaults.loadSetup(new PrintCommandService.PrintSetupRequest(7), null));
    }

    private static PrintCommandService service(
            List<PrintTemplate> templates,
            PrintJobOptions loaded,
            PrintCommandService.OptionsWriter writer) {
        return new PrintCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                () -> templates,
                () -> loaded,
                writer);
    }

    private static PrintTemplate template(int id, String name, boolean isDefault) {
        PrintTemplate template = new PrintTemplate();
        template.setId(id);
        template.setName(name);
        template.setPageWidth(PrintTemplateService.PAGE_WIDTH);
        template.setPageHeight(PrintTemplateService.PAGE_HEIGHT);
        template.setDefaultTemplate(isDefault);
        return template;
    }

    private static void assertInvalid(StageCall call) {
        JDeskException error = assertThrows(JDeskException.class, call::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }

    private static void assertInternal(StageCall call) {
        JDeskException error = assertThrows(JDeskException.class, call::run);
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
    }

    @FunctionalInterface
    private interface StageCall {
        java.util.concurrent.CompletionStage<?> run();
    }
}
