package com.tuandev.fbsbarcode.integration.znack.registration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Status;

/** UI selection is keyed by real WB size, never article or row position. */
public final class RegistrationSelection {
    private int shopId;
    private String filter;
    private final LinkedHashMap<Long, Sku> selected = new LinkedHashMap<>();
    public void reset(int shopId, String filter) {
        if (this.shopId != shopId || !Objects.equals(this.filter, filter)) selected.clear();
        this.shopId = shopId;
        this.filter = filter;
    }
    public static boolean eligible(Sku sku) {
        return sku != null && sku.status() == Status.NOT_CREATED
                && (sku.gtin() == null || sku.gtin().isBlank())
                && (sku.feedId() == null || sku.feedId().isBlank());
    }
    public boolean contains(Sku sku) { return selected.containsKey(sku.chrtId()); }
    public void set(Sku sku, boolean checked) {
        if (checked && eligible(sku)) selected.put(sku.chrtId(), sku);
        else selected.remove(sku.chrtId());
    }
    public void selectAll(List<Sku> values) { values.forEach(sku -> set(sku, true)); }
    public void retainEligible(List<Sku> values) {
        var eligible = values.stream().filter(RegistrationSelection::eligible)
                .map(Sku::chrtId).collect(java.util.stream.Collectors.toSet());
        selected.keySet().retainAll(eligible);
    }
    public List<Sku> snapshot() { return List.copyOf(selected.values()); }
    public void clear() { selected.clear(); }
}
