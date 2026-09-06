package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Category;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Gs1Status;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** National Catalog card workflow primitives. All methods are blocking and must run off the FX thread. */
public final class ZnackNationalCatalogService {
    public static final long DECLARATION_ATTRIBUTE_ID = 23_557L;
    public static final long CERTIFICATE_ATTRIBUTE_ID = 23_561L;

    private final ZnackApiClient api;
    private final ZnackAuthService auth;
    private final ZnackSignatureProvider signer;
    private final ZnackModels.Settings settings;

    public ZnackNationalCatalogService(ZnackApiClient api, ZnackAuthService auth,
                                       ZnackSignatureProvider signer, ZnackModels.Settings settings) {
        this.api = api;
        this.auth = auth;
        this.signer = signer;
        this.settings = settings;
    }

    public Preflight preflight(String tnved) throws Exception {
        String normalized = digits(tnved);
        if (normalized.length() < 4 || normalized.length() > 10) {
            throw new IllegalArgumentException("TN VED must contain 4 to 10 digits.");
        }
        String token = auth.trueApiToken(settings);
        Gs1Status gs1 = parseGs1(api.generatedGtins(settings.resolvedTrueApiBaseUrl(), token));
        if (gs1.quotaKnown() && !gs1.canGenerate()) {
            throw new IllegalStateException("GS1/GTIN quota is unavailable or exhausted (" + gs1.usage()
                    + "/" + gs1.limit() + "). Check the active GS1 RUS membership in National Catalog.");
        }
        List<Category> categories = parseCategories(api.nationalCatalogCategories(
                settings.resolvedTrueApiBaseUrl(), token, normalized));
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("No active National Catalog category was found for TN VED "
                    + normalized + ".");
        }
        return new Preflight(normalized, gs1, categories, token);
    }

    public List<Attribute> requiredAttributes(long categoryId, String token) throws Exception {
        List<Attribute> attributes = parseAttributes(api.nationalCatalogAttributes(
                settings.resolvedTrueApiBaseUrl(), token, categoryId));
        if (attributes.isEmpty()) {
            throw new IllegalStateException("National Catalog returned no mandatory attribute model for category "
                    + categoryId + ". Check that the category is active and try again.");
        }
        return attributes;
    }

    public String generateOne(String token) throws Exception {
        JsonObject result = resultObject(api.generateGtins(settings.resolvedTrueApiBaseUrl(), token, 1));
        JsonArray drafts = array(result.get("drafts"));
        if (drafts.isEmpty()) throw new IllegalStateException("National Catalog did not return a generated GTIN.");
        String gtin = string(drafts.get(0).getAsJsonObject(), "gtin");
        if (gtin.isBlank()) throw new IllegalStateException("Generated GTIN is empty.");
        return gtin;
    }

    public String submit(String token, JsonObject payload) throws Exception {
        JsonObject result = resultObject(api.submitNationalCatalogFeed(
                settings.resolvedTrueApiBaseUrl(), token, payload));
        String feedId = string(result, "feed_id");
        if (feedId.isBlank()) throw new IllegalStateException("National Catalog did not return feed_id.");
        return feedId;
    }

    public FeedProgress progress(String token, String feedId, String gtin) throws Exception {
        JsonObject result = resultObject(api.nationalCatalogFeedStatus(
                settings.resolvedTrueApiBaseUrl(), token, feedId));
        String status = string(result, "status");
        List<String> errors = new ArrayList<>();
        Long goodId = null;
        for (JsonElement element : array(result.get("item"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String itemGtin = string(item, "gtin");
            if (!itemGtin.isBlank() && !itemGtin.equals(gtin)) continue;
            if (item.has("good_id") && !item.get("good_id").isJsonNull()) goodId = item.get("good_id").getAsLong();
            String message = first(string(item, "message"), string(item, "status_message"));
            int code = item.has("status_code") && !item.get("status_code").isJsonNull()
                    ? item.get("status_code").getAsInt() : 0;
            if (code != 0 && !message.isBlank()) errors.add(message);
        }
        JsonObject details = object(result.get("error_details"));
        for (JsonElement element : array(details.get("items"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            for (JsonElement error : array(item.get("errors"))) {
                if (error.isJsonObject()) {
                    String text = string(error.getAsJsonObject(), "text");
                    if (!text.isBlank()) errors.add(text);
                }
            }
        }
        return new FeedProgress(status, goodId, List.copyOf(errors));
    }

    public long sign(String token, String gtin) throws Exception {
        JsonObject request = new JsonObject();
        JsonArray gtins = new JsonArray();
        gtins.add(gtin);
        request.add("gtins", gtins);
        request.addProperty("publicationAgreement", true);
        JsonObject document = resultObject(api.nationalCatalogSigningDocument(
                settings.resolvedTrueApiBaseUrl(), token, request));
        JsonArray xmls = array(document.get("xmls"));
        if (xmls.isEmpty()) {
            String error = firstError(document);
            throw new IllegalStateException(error.isBlank() ? "The card is not ready for signing." : error);
        }
        JsonObject xmlItem = xmls.get(0).getAsJsonObject();
        long goodId = xmlItem.get("goodId").getAsLong();
        String xml = string(xmlItem, "xml");
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(xmlBytes, ZnackSignatureContext.TRUE_API_DOCUMENT).cms();
        JsonObject signed = new JsonObject();
        signed.addProperty("goodId", goodId);
        signed.addProperty("base64Xml", Base64.getEncoder().encodeToString(xmlBytes));
        signed.addProperty("signature", Base64.getEncoder().encodeToString(signature));
        JsonArray batch = new JsonArray();
        batch.add(signed);
        JsonObject response = resultObject(api.signNationalCatalogProduct(
                settings.resolvedTrueApiBaseUrl(), token, batch));
        for (JsonElement id : array(response.get("signed"))) if (id.getAsLong() == goodId) return goodId;
        String error = firstError(response);
        throw new IllegalStateException(error.isBlank() ? "National Catalog did not confirm the signature." : error);
    }

    public static JsonObject buildPayload(String gtin, Draft draft, String imageUrl) {
        JsonObject payload = new JsonObject();
        payload.addProperty("gtin", gtin);
        payload.addProperty("tnved", draft.tnved());
        payload.addProperty("moderation", 1);
        payload.addProperty("brand", draft.brand());
        payload.addProperty("good_name", draft.goodName());

        JsonObject identity = new JsonObject();
        identity.addProperty("value", gtin);
        identity.addProperty("type", "gtin");
        identity.addProperty("multiplier", 1);
        identity.addProperty("level", "trade-unit");
        identity.addProperty("unit", "шт");
        JsonArray identities = new JsonArray();
        identities.add(identity);
        payload.add("identified_by", identities);

        JsonArray categories = new JsonArray();
        categories.add(draft.categoryId());
        payload.add("categories", categories);

        if (imageUrl != null && !imageUrl.isBlank()) {
            JsonObject image = new JsonObject();
            image.addProperty("photo_type", "default");
            image.addProperty("photo_url", imageUrl);
            image.addProperty("identifier", gtin);
            image.addProperty("identifier_type", "gtin");
            JsonArray images = new JsonArray();
            images.add(image);
            payload.add("good_images", images);
        }

        JsonArray attributes = new JsonArray();
        draft.attributes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() == null || entry.getValue().isBlank()) return;
            JsonObject attribute = new JsonObject();
            attribute.addProperty("attr_id", entry.getKey());
            attribute.addProperty("attr_value", entry.getValue().trim());
            attributes.add(attribute);
        });
        payload.add("good_attrs", attributes);
        return payload;
    }

    static Gs1Status parseGs1(JsonElement response) {
        JsonObject result = resultObject(response);
        JsonObject monthly = object(result.get("monthly-limit"));
        long limit = number(monthly, "limit");
        long usage = number(monthly, "usage");
        boolean quotaKnown = monthly.has("limit") && monthly.has("usage");
        return new Gs1Status(limit, usage, array(result.get("drafts")).size(), quotaKnown);
    }

    static List<Category> parseCategories(JsonElement response) {
        List<Category> active = new ArrayList<>();
        List<Category> all = new ArrayList<>();
        for (JsonElement element : resultArray(response)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = number(value, "cat_id");
            if (id <= 0) continue;
            Category category = new Category(id, string(value, "cat_name"));
            all.add(category);
            if (!value.has("category_active") || value.get("category_active").getAsBoolean()) active.add(category);
        }
        return active.isEmpty() ? all : active;
    }

    static List<Attribute> parseAttributes(JsonElement response) {
        List<Attribute> values = new ArrayList<>();
        for (JsonElement element : resultArray(response)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = number(value, "attr_id");
            if (id <= 0) continue;
            List<String> presets = new ArrayList<>();
            for (JsonElement preset : array(value.get("attr_preset"))) {
                if (preset.isJsonPrimitive()) presets.add(preset.getAsString());
            }
            values.add(new Attribute(id, string(value, "attr_name"), string(value, "attr_field_type"),
                    bool(value, "attr_preset_only"), bool(value, "attr_multiplicity"),
                    bool(value, "first_layer"), bool(value, "second_layer"), presets));
        }
        return List.copyOf(values);
    }

    private static JsonObject resultObject(JsonElement response) {
        JsonElement result = unwrap(response);
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray resultArray(JsonElement response) {
        JsonElement result = unwrap(response);
        return result != null && result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
    }

    private static JsonElement unwrap(JsonElement response) {
        if (response == null || response.isJsonNull()) return null;
        if (response.isJsonObject() && response.getAsJsonObject().has("result")) return response.getAsJsonObject().get("result");
        return response;
    }

    private static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private static long number(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return 0L;
        try { return value.get(key).getAsLong(); } catch (RuntimeException ignored) { return 0L; }
    }

    private static boolean bool(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean();
    }

    private static String firstError(JsonObject result) {
        for (JsonElement value : array(result.get("errors"))) {
            if (value.isJsonObject()) {
                String message = string(value.getAsJsonObject(), "message");
                if (!message.isBlank()) return message;
            }
        }
        return "";
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public record Preflight(String tnved, Gs1Status gs1, List<Category> categories, String token) { }
    public record FeedProgress(String status, Long goodId, List<String> errors) {
        public boolean failed() { return "Rejected".equalsIgnoreCase(status) || !errors.isEmpty(); }
        public boolean readyToSign() { return "Moderated".equalsIgnoreCase(status) && errors.isEmpty(); }
        public boolean signed() { return "Signed".equalsIgnoreCase(status); }
        public String errorMessage() { return String.join("; ", errors); }
    }
}
