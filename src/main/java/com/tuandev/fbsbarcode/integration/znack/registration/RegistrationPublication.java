package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import java.util.ArrayList;
import java.util.Set;

/** Strict write-back gate, separate from legacy permissive catalog display classification. */
record RegistrationPublication(long goodId, boolean published, boolean readyForKiz, boolean needsSignature, JsonObject card) {
    static RegistrationPublication parse(JsonElement response, String gtin, String participantInn) {
        if (participantInn == null || participantInn.isBlank()) throw new IllegalArgumentException("Missing Znack participant identity.");
        String normalized = GtinNormalizer.normalize(gtin);
        if (response == null || !response.isJsonObject() || !response.getAsJsonObject().has("result")
                || !response.getAsJsonObject().get("result").isJsonArray()) throw new IllegalArgumentException("Invalid National Catalog product response.");
        var matches = new ArrayList<JsonObject>();
        for (var item : response.getAsJsonObject().getAsJsonArray("result")) {
            if (!item.isJsonObject()) continue;
            var card = item.getAsJsonObject();
            var identifiers = card.get("identified_by");
            if (identifiers == null || !identifiers.isJsonArray()) continue;
            boolean match = false;
            for (var identifier : identifiers.getAsJsonArray()) {
                if (!identifier.isJsonObject()) continue;
                var id = identifier.getAsJsonObject();
                if (!"gtin".equals(text(id, "type"))) continue;
                try { match |= normalized.equals(GtinNormalizer.normalize(text(id, "value"))); }
                catch (IllegalArgumentException ignored) { }
            }
            if (match) matches.add(card);
        }
        if (matches.size() != 1) throw new IllegalArgumentException("National Catalog GTIN is missing or ambiguous: " + normalized);
        var card = matches.getFirst();
        if (!participantInn.equals(text(card, "producer_inn"))) throw new IllegalArgumentException("National Catalog product owner does not match this shop.");
        var statuses = new java.util.HashSet<String>();
        statuses.add(text(card, "good_status"));
        var detailed = card.get("good_detailed_status");
        if (detailed != null && detailed.isJsonArray()) detailed.getAsJsonArray().forEach(e -> {
            if (e.isJsonPrimitive()) statuses.add(e.getAsString());
        });
        boolean published = bool(card, "good_signed") && "published".equals(text(card, "good_status"))
                && java.util.Collections.disjoint(statuses, Set.of("archived", "errors", "draft", "moderation", "notsigned"));
        long goodId = card.has("good_id") ? card.get("good_id").getAsLong() : 0;
        if (goodId <= 0) throw new IllegalArgumentException("Missing National Catalog good_id.");
        return new RegistrationPublication(goodId, published,
                published && bool(card, "good_mark_flag") && bool(card, "good_turn_flag"), statuses.contains("notsigned"), card.deepCopy());
    }
    static boolean bool(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    static String text(JsonObject object, String key) {
        var value = object.get(key); return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
