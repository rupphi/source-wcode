package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;

final class WbRegisteredGtinWriter {
    static JsonObject append(JsonObject original, long nmId, long chrtId, String gtin) {
        gtin = GtinNormalizer.normalize(gtin);
        if (original == null || !original.has("nmID") || original.get("nmID").getAsLong() != nmId)
            throw new IllegalArgumentException("WB card identity does not match registration.");
        for (String required : new String[]{"vendorCode", "brand", "title", "description", "dimensions", "characteristics", "sizes", "kizMarked"}) {
            if (!original.has(required) || original.get(required).isJsonNull())
                throw new IllegalArgumentException("WB returned a partial card; refusing to overwrite missing field: " + required);
        }
        JsonObject result = new JsonObject();
        for (String key : new String[]{"nmID", "vendorCode", "brand", "title", "description", "dimensions",
                "characteristics", "sizes", "kizMarked", "wholesale", "isSwatchTryOn"}) {
            if (original.has(key)) result.add(key, original.get(key).deepCopy());
        }
        if (!result.has("sizes") || !result.get("sizes").isJsonArray()) throw new IllegalArgumentException("WB card has no sizes.");
        int matched = 0;
        var seen = new java.util.HashSet<Long>();
        for (var element : result.getAsJsonArray("sizes")) {
            var size = element.getAsJsonObject();
            if (!size.has("chrtID") || !seen.add(size.get("chrtID").getAsLong())) throw new IllegalArgumentException("WB size identity is ambiguous.");
            if (!size.has("skus") || !size.get("skus").isJsonArray()) throw new IllegalArgumentException("WB size barcodes are missing.");
            if (size.get("chrtID").getAsLong() != chrtId) {
                if (size.getAsJsonArray("skus").contains(new JsonPrimitive(gtin)))
                    throw new IllegalArgumentException("GTIN is already assigned to another WB size.");
                continue;
            }
            matched++;
            if (!size.has("skus") || !size.get("skus").isJsonArray()) throw new IllegalArgumentException("WB size barcodes are missing.");
            var skus = size.getAsJsonArray("skus");
            if (!skus.contains(new JsonPrimitive(gtin))) skus.add(gtin);
        }
        if (matched != 1) throw new IllegalArgumentException("Registered WB size no longer exists.");
        // Name is a read-only field returned in characteristics, not part of the update contract.
        if (result.has("characteristics")) for (var a : result.getAsJsonArray("characteristics")) a.getAsJsonObject().remove("name");
        return result;
    }
}
