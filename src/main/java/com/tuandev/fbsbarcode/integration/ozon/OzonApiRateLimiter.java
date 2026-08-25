package com.tuandev.fbsbarcode.integration.ozon;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small per-shop/per-endpoint-family limiter; Ozon traffic never shares a WB limiter. */
public final class OzonApiRateLimiter {
    private final ConcurrentMap<Key, Long> nextAllowedNanos = new ConcurrentHashMap<>();
    private final long spacingNanos;

    public OzonApiRateLimiter() {
        this(Duration.ofMillis(120));
    }

    public OzonApiRateLimiter(Duration minimumSpacing) {
        if (minimumSpacing == null || minimumSpacing.isNegative()) {
            throw new IllegalArgumentException("minimumSpacing must not be negative");
        }
        spacingNanos = minimumSpacing.toNanos();
    }

    public void awaitTurn(int shopId, String family) throws InterruptedException {
        if (shopId <= 0 || family == null || !family.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("A valid shop and endpoint family are required");
        }
        Key key = new Key(shopId, family);
        while (true) {
            long now = System.nanoTime();
            long reserved = nextAllowedNanos.compute(key, (ignored, existing) -> {
                long start = existing == null ? now : Math.max(now, existing);
                return start + spacingNanos;
            }) - spacingNanos;
            long wait = reserved - now;
            if (wait <= 0) {
                return;
            }
            long millis = wait / 1_000_000L;
            int nanos = (int) (wait % 1_000_000L);
            Thread.sleep(millis, nanos);
            return;
        }
    }

    private record Key(int shopId, String family) {
        private Key {
            Objects.requireNonNull(family, "family");
        }
    }
}
