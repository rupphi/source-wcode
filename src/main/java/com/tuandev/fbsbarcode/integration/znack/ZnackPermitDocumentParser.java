package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.GoodsDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses permit documents using the National Catalog v5.62 response contract. */
final class ZnackPermitDocumentParser {
    private static final Map<String, String> TYPES = Map.of(
            "23557", "CONFORMITY_DECLARATION",
            "23561", "CONFORMITY_CERTIFICATE",
            "23765", "STATE_REGISTRATION_CERTIFICATE");
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "действует",
            "подписан и действует",
            "возобновлен",
            "возобновлён",
            "продлен",
            "продлён",
            "ожидает проверки оператора реестра");

    private ZnackPermitDocumentParser() {
    }

    static List<GoodsDocument> fromProductCard(JsonObject card) {
        JsonArray attributes = array(card, "good_attrs");
        if (attributes == null) attributes = array(card, "goodAttrs");
        if (attributes == null) return List.of();
        LinkedHashSet<GoodsDocument> documents = new LinkedHashSet<>();
        for (JsonElement element : attributes) {
            if (!element.isJsonObject()) continue;
            JsonObject attribute = element.getAsJsonObject();
            String type = TYPES.get(text(attribute, "attr_id", "attrId"));
            if (type == null) continue;
            String number = text(attribute, "certificate_number", "certificateNumber");
            String date = text(attribute, "certificate_issued_date", "certificateIssuedDate");
            if (number.isBlank() || date.isBlank()) {
                String[] legacy = text(attribute, "attr_value", "attrValue").split(":::", 2);
                if (legacy.length == 2) {
                    if (number.isBlank()) number = legacy[0].trim();
                    if (date.isBlank()) date = legacy[1].trim();
                }
            }
            addComplete(documents, type, number, date);
        }
        return List.copyOf(documents);
    }

    static List<GoodsDocument> activeFromRegistry(JsonElement response) {
        JsonArray entries = registryDocuments(response);
        if (entries == null) return List.of();
        LinkedHashSet<GoodsDocument> documents = new LinkedHashSet<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject document = element.getAsJsonObject();
            if (!active(document)) continue;
            String type = TYPES.get(text(document, "attr_id", "attrId"));
            if (type == null) continue;
            addComplete(documents, type, text(document, "number", "certificate_number"),
                    text(document, "from_date", "certificate_issued_date"));
        }
        return List.copyOf(documents);
    }

    private static JsonArray registryDocuments(JsonElement response) {
        if (response == null || response.isJsonNull()) return null;
        if (response.isJsonArray()) return response.getAsJsonArray();
        JsonObject root = response.getAsJsonObject();
        JsonObject result = object(root, "result");
        JsonArray documents = array(result == null ? root : result, "documents");
        return documents == null ? array(root, "documents") : documents;
    }

    private static boolean active(JsonObject document) {
        JsonElement group = value(document, "status_group", "statusGroup");
        Integer groupValue = null;
        if (group != null) {
            try {
                groupValue = group.getAsInt();
                if (groupValue != 1) return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        JsonElement active = value(document, "active");
        if (active != null && active.isJsonPrimitive() && active.getAsJsonPrimitive().isBoolean()) {
            if (!active.getAsBoolean()) return false;
            if (groupValue == null) return true;
        }
        if (groupValue != null) return true;
        return ACTIVE_STATUSES.contains(text(document, "status").strip().toLowerCase(Locale.ROOT));
    }

    private static void addComplete(Set<GoodsDocument> target, String type, String number, String date) {
        GoodsDocument document = new GoodsDocument(type, number == null ? "" : number.trim(),
                date == null ? "" : date.trim());
        if (document.complete()) target.add(document);
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static JsonElement value(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) return object.get(key);
        }
        return null;
    }

    private static String text(JsonObject object, String... keys) {
        JsonElement value = value(object, keys);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
