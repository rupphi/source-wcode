package com.tuandev.fbsbarcode.jdesk.supply;

import com.tuandev.fbsbarcode.models.Order;
import dev.jdesk.api.AssetRoute;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OrderImageAssetService implements AssetRoute {
    private static final String ROUTE_PREFIX = "jdesk://app/order-images/";
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}\\.png";
    private static final int DEFAULT_MAX_ENTRIES = 500;
    private static final int DEFAULT_MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int DEFAULT_MAX_CACHE_BYTES = 64 * 1024 * 1024;

    private final byte[] secret;
    private final int maxEntries;
    private final int maxImageBytes;
    private final int maxCacheBytes;
    private final Map<String, byte[]> images;
    private long cacheBytes;

    public OrderImageAssetService() {
        this(randomSecret(), DEFAULT_MAX_ENTRIES, DEFAULT_MAX_IMAGE_BYTES, DEFAULT_MAX_CACHE_BYTES);
    }

    OrderImageAssetService(byte[] secret, int maxEntries, int maxImageBytes) {
        this(secret, maxEntries, maxImageBytes, defaultCacheBytes(maxEntries, maxImageBytes));
    }

    OrderImageAssetService(byte[] secret, int maxEntries, int maxImageBytes, int maxCacheBytes) {
        if (secret == null
                || secret.length < 32
                || maxEntries <= 0
                || maxImageBytes <= 0
                || maxCacheBytes < maxImageBytes) {
            throw new IllegalArgumentException("Invalid order image asset configuration");
        }
        this.secret = secret.clone();
        this.maxEntries = maxEntries;
        this.maxImageBytes = maxImageBytes;
        this.maxCacheBytes = maxCacheBytes;
        this.images = new LinkedHashMap<>(64, 0.75f, true);
    }

    public String register(Order order) {
        if (order == null
                || order.getId() == null
                || order.getId() <= 0
                || order.getImageUrl() == null
                || order.getImageUrl().isBlank()
                || order.getImage() == null
                || order.getImage().length == 0
                || order.getImage().length > maxImageBytes) {
            return "";
        }
        String token = token(order.getId(), order.getImageUrl());
        byte[] image = order.getImage().clone();
        synchronized (images) {
            byte[] previous = images.put(token, image);
            cacheBytes += image.length - (previous == null ? 0 : previous.length);
            evictLeastRecentlyUsed();
        }
        return ROUTE_PREFIX + token + ".png";
    }

    @Override
    public Optional<Response> serve(Request request) {
        if (request == null
                || !("GET".equals(request.method()) || "HEAD".equals(request.method()))
                || !request.path().matches(TOKEN_PATTERN)) {
            return Optional.empty();
        }
        String token = request.path().substring(0, request.path().length() - ".png".length());
        byte[] image;
        synchronized (images) {
            image = images.get(token);
        }
        if (image == null) {
            return Optional.empty();
        }
        byte[] response = image.clone();
        return Optional.of(new Response(
                "image/png",
                response.length,
                () -> new ByteArrayInputStream(response),
                Map.of("Cache-Control", "private, max-age=3600")));
    }

    private String token(long orderId, String imageUrl) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(orderId).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(imageUrl.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Order image token generation is unavailable");
        }
    }

    private void evictLeastRecentlyUsed() {
        Iterator<Map.Entry<String, byte[]>> entries = images.entrySet().iterator();
        while ((images.size() > maxEntries || cacheBytes > maxCacheBytes) && entries.hasNext()) {
            Map.Entry<String, byte[]> entry = entries.next();
            cacheBytes -= entry.getValue().length;
            entries.remove();
        }
    }

    private static int defaultCacheBytes(int maxEntries, int maxImageBytes) {
        if (maxEntries <= 0 || maxImageBytes <= 0) {
            return 1;
        }
        return (int) Math.min(DEFAULT_MAX_CACHE_BYTES, (long) maxEntries * maxImageBytes);
    }

    private static byte[] randomSecret() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return value;
    }
}
