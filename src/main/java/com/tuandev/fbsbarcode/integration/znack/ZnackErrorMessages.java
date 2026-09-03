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
    private static final String WAITING_INTRODUCTION_READINESS = "WAITING_INTRODUCTION_READINESS";
    private static final String WAITING_INTRODUCTION_DOCUMENTS = "WAITING_INTRODUCTION_DOCUMENTS";
    private static final String LEGACY_MISSING_DOCUMENTS = "INTRODUCTION_SKIPPED_MISSING_DOCUMENTS";
    private static final String READINESS_PROGRESS_PREFIX = "True API readiness:";
    private static final Pattern HTTP_STATUS = Pattern.compile("\\(HTTP (\\d{3})\\)");
    private static final Pattern ERROR_CODE = Pattern.compile("\"?errorCode\"?\\s*[:=]\\s*\"?(\\d+)\"?");
    private static final Pattern INSUFFICIENT_FUNDS_CODE = Pattern.compile(
            "(?i)(?:HTTP\\s*400\\)?\\s*:\\s*|(?:error[_\\s-]?code|code)\"?\\s*[:=]\\s*\"?)3590(?!\\d)");
    private static final Pattern EMISSION_TYPE_BLOCKED_CODE = Pattern.compile(
            "(?i)(?:HTTP\\s*400\\)?\\s*:\\s*|(?:error[_\\s-]?code|code)\"?\\s*[:=]\\s*\"?)3055(?!\\d)");
    private static final Set<String> MESSAGE_KEYS = Set.of(
            "error_message", "errormessage", "message", "description", "error_description",
            "detail", "reason", "globalerrors", "fielderrors", "errors");

    private ZnackErrorMessages() {
    }

    /** Mã lỗi Znack (nếu có), ví dụ "1110"; rỗng nếu không tìm thấy. */
    public static String errorCode(String raw) {
        if (raw == null) return "";
        Matcher matcher = ERROR_CODE.matcher(raw);
        return matcher.find() ? matcher.group(1) : "";
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
        if (messages.isEmpty()) {
            // Message-less payload ("{}" and friends): show the wrapper text without the JSON blob.
            String prefix = raw.substring(0, json).trim();
            if (prefix.endsWith(":")) prefix = prefix.substring(0, prefix.length() - 1).trim();
            return prefix.isBlank() ? raw.trim() : prefix;
        }
        Matcher status = HTTP_STATUS.matcher(raw);
        String prefix = status.find() ? "HTTP " + status.group(1) + ": " : "";
        return prefix + String.join("; ", messages);
    }

    /**
     * User-facing pipeline detail. Expected readiness progress and missing-document waits are
     * represented by their localized status/dialog instead of a long diagnostic under every GTIN.
     * The raw diagnostic remains stored for audit and retry decisions.
     */
    public static String displayForPipeline(String stage, String raw) {
        if (isExpectedReadinessProgress(stage, raw) || isMissingDocumentsStage(stage)) return "";
        return display(raw);
    }

    /** True when KIZ introduction is paused until the GTIN has an active catalog document. */
    public static boolean isMissingDocumentsStage(String stage) {
        return WAITING_INTRODUCTION_DOCUMENTS.equalsIgnoreCase(stage)
                || LEGACY_MISSING_DOCUMENTS.equalsIgnoreCase(stage);
    }

    /** True only for Znack's insufficient-account-balance response. */
    public static boolean isInsufficientFunds(String raw) {
        if (raw == null || raw.isBlank()) return false;
        return INSUFFICIENT_FUNDS_CODE.matcher(raw).find()
                || raw.toLowerCase(java.util.Locale.ROOT).contains("notenoughmoneyexception");
    }

    /**
     * True when Honest Sign has blocked the participant from using the selected emission type.
     * Error 3055 requires the participant to sign the operator's updated agreement; repeatedly
     * submitting the same KIZ order cannot resolve it.
     */
    public static boolean requiresOperatorTermsSignature(String raw) {
        return raw != null && !raw.isBlank() && EMISSION_TYPE_BLOCKED_CODE.matcher(raw).find();
    }

    private static boolean isExpectedReadinessProgress(String stage, String raw) {
        if (!WAITING_INTRODUCTION_READINESS.equalsIgnoreCase(stage) || raw == null) return false;
        String detail = raw.stripLeading();
        return detail.regionMatches(true, 0, READINESS_PROGRESS_PREFIX, 0,
                READINESS_PROGRESS_PREFIX.length());
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
            // Entries with their own message-like keys (e.g. fieldErrors items carrying
            // fieldName + errors) contribute only those keys; plain field->message maps
            // contribute every value.
            var object = element.getAsJsonObject();
            boolean hasMessageKey = object.keySet().stream()
                    .anyMatch(key -> MESSAGE_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT)));
            if (hasMessageKey) {
                collect(object, out);
            } else {
                for (var entry : object.entrySet()) collectValues(entry.getValue(), out);
            }
        }
    }
}
