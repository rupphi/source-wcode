package com.tuandev.fbsbarcode.integration.znack.signature;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CryptoProCertificateInfo(String selector, String thumbprint, String subject, String issuer, String inn,
                                       Instant validFrom, Instant validTo, boolean hasPrivateKey, String provider,
                                       String rawSummary) {
    private static final Pattern COMMON_NAME = Pattern.compile("(?:^|,)\\s*CN\\s*=\\s*\"?([^,\"]+)", Pattern.CASE_INSENSITIVE);

    public boolean expired(Instant now) {
        return validTo != null && validTo.isBefore(now);
    }

    public boolean usable(Instant now) {
        return selector != null && !selector.isBlank() && !expired(now);
    }

    public String displayName() {
        String owner = ownerName();
        StringBuilder result = new StringBuilder(owner);
        if (inn != null && !inn.isBlank()) result.append(" / INN ").append(inn);
        if (validTo != null) result.append(" / ").append(validToDate());
        return result.toString();
    }

    public LocalDate validToDate() {
        return validTo == null ? null : validTo.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public String ownerName() {
        Matcher matcher = COMMON_NAME.matcher(subject == null ? "" : subject);
        String owner = matcher.find() ? matcher.group(1).trim() : subject;
        if (owner == null || owner.isBlank()) owner = selector;
        return owner;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
