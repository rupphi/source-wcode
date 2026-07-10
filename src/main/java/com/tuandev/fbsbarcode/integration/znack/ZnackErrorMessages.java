package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns stored pipeline/order error strings into something readable for the UI. API failures are
 * persisted verbatim (e.g. {@code Znack API request failed (HTTP 400): {"error_message":"..."}})
 * so the audit log keeps everything; the panes should only show the human part of the payload.
 */
public final class ZnackErrorMessages {
    private static final Pattern HTTP_STATUS = Pattern.compile("\\(HTTP (\\d{3})\\)");
    private static final Set<String> MESSAGE_KEYS = Set.of(
            "error_message", "errormessage", "message", "description", "error_description",
            "detail", "reason", "globalerrors", "fielderrors", "errors");

    private ZnackErrorMessages() {
    }

    /** Human-readable form of a stored error; falls back to the raw text when nothing better is found. */
    public static String display(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int json = jsonStart(raw);
        if (json < 0) return raw.trim();
        List<String> messages = new ArrayList<>();
        try {
            collect(JsonParser.parseString(raw.substring(json)), messages);
        } catch (RuntimeException invalidJson) {
            return raw.trim();
        }
        if (messages.isEmpty()) return raw.trim();
        Matcher status = HTTP_STATUS.matcher(raw);
        String prefix = status.find() ? "HTTP " + status.group(1) + ": " : "";
        return prefix + String.join("; ", messages);
    }

    private static int jsonStart(String raw) {
        int object = raw.indexOf('{');
        int array = raw.indexOf('[');
        if (object < 0) return array;
        if (array < 0) return object;
        return Math.min(object, array);
    }

    private static void collect(JsonElement element, List<String> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (MESSAGE_KEYS.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    collectValues(entry.getValue(), out);
                } else {
                    collect(entry.getValue(), out);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) collect(item, out);
        }
    }

    private static void collectValues(JsonElement element, List<String> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            String value = element.getAsString().trim();
            if (!value.isBlank() && !out.contains(value)) out.add(value);
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) collectValues(item, out);
        } else if (element.isJsonObject()) {
            // Nested structures (e.g. fieldErrors entries) still pick only message-like keys,
            // so field names and other metadata stay out of the displayed text.
            collect(element, out);
        }
    }
}
