package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.features.print.history.ImageCacheRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.models.Sticker;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class WbSupplyWorkflow {
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(6, runnable -> {
        Thread thread = new Thread(runnable, "wb-image-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final int IMAGE_CACHE_LIMIT = 500;
    private static final Map<String, byte[]> IMAGE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > IMAGE_CACHE_LIMIT;
                }
            }
    );
    private static final Set<String> FAILED_IMAGE_URLS = ConcurrentHashMap.newKeySet();

    private final WbSupplyRepository supplyRepository = new WbSupplyRepository();
    private final WbOrderRepository orderRepository = new WbOrderRepository();
    private final WbProductSyncService productSyncService = new WbProductSyncService();
    private final WbStickerService stickerService = new WbStickerService();
    private final ImageCacheRepository imageCacheRepository = new ImageCacheRepository();

    public List<WbSupplySummary> getSupplies(int shopId) {
        return supplyRepository.getSupplySummaries(shopId);
    }

    public List<Order> loadOrdersForSupplyLocal(Shop shop, String supplyId) {
        return orderRepository.getOrdersForSupply(shop.getId(), supplyId);
    }

    public List<Order> loadOrdersForSupply(Shop shop, String supplyId) throws IOException {
        if (orderRepository.hasMissingProductsForSupply(shop.getId(), supplyId)) {
            productSyncService.sync(shop);
        }
        List<Order> orders = orderRepository.getOrdersForSupply(shop.getId(), supplyId);
        if (orders.isEmpty()) {
            return orders;
        }

        enrichOrders(shop, orders);
        return orders;
    }

    public void enrichOrders(Shop shop, List<Order> orders) throws IOException {
        enrichOrderStickers(shop, orders);
    }

    public void enrichOrderStickers(Shop shop, List<Order> orders) throws IOException {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<Sticker> stickers = stickerService.getStickers(shop.getApiKey(), orderIds);
        Map<Long, Sticker> stickerMap = stickers.stream().collect(Collectors.toMap(Sticker::getOrderId, value -> value));

        for (Order order : orders) {
            Sticker sticker = stickerMap.get(order.getId());
            if (sticker == null) {
                continue;
            }
            order.setSticker(sticker.getPartA() + " " + sticker.getPartB());
            order.setStickerCode(sticker.getBarcode());
        }
    }

    public void ensureOrderImages(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        populateOrderImages(orders);
    }

    private void populateOrderImages(List<Order> orders) {
        List<Order> missingImages = orders.stream()
                .filter(order -> order.getImage() == null)
                .filter(order -> order.getImageUrl() != null && !order.getImageUrl().isBlank())
                .toList();

        if (missingImages.isEmpty()) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(missingImages.size());
        for (Order order : missingImages) {
            IMAGE_EXECUTOR.submit(() -> {
                try {
                    populateSingleOrderImage(order);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void populateSingleOrderImage(Order order) {
        String imageUrl = order.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }
        String cacheKey = PrintHistoryService.imageCacheKey(imageUrl);
        if (cacheKey == null) {
            return;
        }

        byte[] image = IMAGE_CACHE.get(cacheKey);
        if (image == null) {
            image = imageCacheRepository.findImage(cacheKey);
            if (image != null) {
                IMAGE_CACHE.put(cacheKey, image);
            }
        }
        if (image == null && !FAILED_IMAGE_URLS.contains(cacheKey)) {
            image = downloadProductImage(imageUrl);
            if (image != null) {
                IMAGE_CACHE.put(cacheKey, image);
                imageCacheRepository.saveImage(cacheKey, imageUrl, image, "image/png");
            } else {
                FAILED_IMAGE_URLS.add(cacheKey);
            }
        }

        if (image != null) {
            order.setImage(image);
        }
    }

    private byte[] downloadProductImage(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            byte[] body = response.body().bytes();
            try (ByteArrayInputStream input = new ByteArrayInputStream(body);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                BufferedImage image = ImageIO.read(input);
                if (image == null) {
                    return null;
                }
                ImageIO.write(image, "png", output);
                return output.toByteArray();
            }
        } catch (IOException ex) {
            return null;
        }
    }

    public static void clearImageCache() {
        IMAGE_CACHE.clear();
        FAILED_IMAGE_URLS.clear();
    }
}
