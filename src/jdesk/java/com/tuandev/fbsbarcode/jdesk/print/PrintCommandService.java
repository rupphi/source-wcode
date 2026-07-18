package com.tuandev.fbsbarcode.jdesk.print;

import com.tuandev.fbsbarcode.features.print.PrintJobOptions;
import com.tuandev.fbsbarcode.features.print.PrintPageOrder;
import com.tuandev.fbsbarcode.features.print.PrintPreferenceService;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class PrintCommandService {
    private static final int MAX_BARCODE_COPIES = 100;
    private static final int MAX_TEMPLATES = 100;

    private final Supplier<List<Shop>> shops;
    private final TemplateReader templates;
    private final OptionsReader optionsReader;
    private final OptionsWriter optionsWriter;

    public PrintCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        PrintTemplateService templateService = new PrintTemplateService();
        PrintPreferenceService preferenceService = new PrintPreferenceService();
        this.shops = shopRepository::findAll;
        this.templates = templateService::loadTemplates;
        this.optionsReader = preferenceService::load;
        this.optionsWriter = preferenceService::save;
    }

    PrintCommandService(
            Supplier<List<Shop>> shops,
            TemplateReader templates,
            OptionsReader optionsReader,
            OptionsWriter optionsWriter) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.optionsReader = Objects.requireNonNull(optionsReader, "optionsReader");
        this.optionsWriter = Objects.requireNonNull(optionsWriter, "optionsWriter");
    }

    @DesktopCommand("printing.setup")
    @RequiresCapability("printing:read")
    public CompletionStage<PrintSetupResponse> loadSetup(
            PrintSetupRequest request, InvocationContext context) {
        int shopId = validateShopRequest(request == null ? 0 : request.shopId());
        requireShop(shopId);
        return SafeCommandExecutor.execute(() -> buildSetup(
                shopId,
                Objects.requireNonNull(optionsReader.load(), "print options")));
    }

    @DesktopCommand("printing.saveOptions")
    @RequiresCapability("printing:configure")
    public CompletionStage<PrintSetupResponse> saveOptions(
            SavePrintOptionsRequest request, InvocationContext context) {
        ValidatedOptions validated = validateOptions(request);
        requireShop(validated.shopId());
        PrintJobOptions options = new PrintJobOptions(validated.pageOrder(), validated.barcodeCopies());
        return SafeCommandExecutor.execute(() -> {
            optionsWriter.save(options);
            return buildSetup(validated.shopId(), options);
        });
    }

    private PrintSetupResponse buildSetup(int shopId, PrintJobOptions rawOptions) {
        PrintJobOptions options = rawOptions.normalized();
        List<PrintTemplate> loaded = List.copyOf(Objects.requireNonNull(templates.load(), "print templates"));
        List<PrintTemplate> defaults = loaded.stream()
                .filter(Objects::nonNull)
                .filter(PrintTemplate::isDefaultTemplate)
                .toList();
        if (defaults.size() != 1 || defaults.getFirst().getId() == null || defaults.getFirst().getId() <= 0) {
            throw new IllegalStateException("Exactly one valid default print template is required.");
        }
        List<PrintTemplateSummary> summaries = loaded.stream()
                .filter(Objects::nonNull)
                .limit(MAX_TEMPLATES)
                .map(PrintCommandService::toSummary)
                .toList();
        return new PrintSetupResponse(
                shopId,
                toWireValue(options.pageOrder()),
                Math.min(options.barcodeCopies(), MAX_BARCODE_COPIES),
                defaults.getFirst().getId(),
                PrintTemplateService.PAGE_WIDTH_MM,
                PrintTemplateService.PAGE_HEIGHT_MM,
                summaries);
    }

    private static PrintTemplateSummary toSummary(PrintTemplate template) {
        Integer id = template.getId();
        if (id == null || id <= 0) {
            throw new IllegalStateException("Print template id is invalid.");
        }
        String name = sanitize(template.getName(), 120);
        if (name.isBlank()) {
            name = "Template " + id;
        }
        return new PrintTemplateSummary(id, name, template.isDefaultTemplate());
    }

    private void requireShop(int shopId) {
        List<Shop> available = List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
        if (available.stream().noneMatch(shop -> shop != null && shop.getId() == shopId)) {
            throw SafeCommandExecutor.invalidRequest("The selected shop is not available.");
        }
    }

    private static int validateShopRequest(int shopId) {
        if (shopId <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        return shopId;
    }

    private static ValidatedOptions validateOptions(SavePrintOptionsRequest request) {
        if (request == null) {
            throw SafeCommandExecutor.invalidRequest("Print options are required.");
        }
        int shopId = validateShopRequest(request.shopId());
        if (request.pageOrder() == null) {
            throw SafeCommandExecutor.invalidRequest("The print page order is invalid.");
        }
        PrintPageOrder pageOrder = switch (request.pageOrder()) {
            case "barcode_then_sticker" -> PrintPageOrder.BARCODE_THEN_STICKER;
            case "sticker_then_barcode" -> PrintPageOrder.STICKER_THEN_BARCODE;
            default -> throw SafeCommandExecutor.invalidRequest("The print page order is invalid.");
        };
        if (request.barcodeCopies() < 1 || request.barcodeCopies() > MAX_BARCODE_COPIES) {
            throw SafeCommandExecutor.invalidRequest("Barcode copies must be between 1 and 100.");
        }
        return new ValidatedOptions(shopId, pageOrder, request.barcodeCopies());
    }

    private static String toWireValue(PrintPageOrder order) {
        return order == PrintPageOrder.STICKER_THEN_BARCODE
                ? "sticker_then_barcode"
                : "barcode_then_sticker";
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxLength));
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length() && safe.length() < maxLength; index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                if (!previousWhitespace && safe.length() > 0) {
                    safe.append(' ');
                    previousWhitespace = true;
                }
            } else {
                safe.append(character);
                previousWhitespace = false;
            }
        }
        return safe.toString().strip();
    }

    @FunctionalInterface
    interface TemplateReader {
        List<PrintTemplate> load();
    }

    @FunctionalInterface
    interface OptionsReader {
        PrintJobOptions load();
    }

    @FunctionalInterface
    interface OptionsWriter {
        void save(PrintJobOptions options);
    }

    private record ValidatedOptions(
            int shopId, PrintPageOrder pageOrder, int barcodeCopies) {
    }

    public record PrintSetupRequest(int shopId) {
    }

    public record SavePrintOptionsRequest(
            int shopId, String pageOrder, int barcodeCopies) {
    }

    public record PrintTemplateSummary(
            int id, String name, boolean defaultTemplate) {
    }

    public record PrintSetupResponse(
            int shopId,
            String pageOrder,
            int barcodeCopies,
            int defaultTemplateId,
            double pageWidthMm,
            double pageHeightMm,
            List<PrintTemplateSummary> templates) {
    }
}
