package com.tuandev.fbsbarcode.jdesk.supply;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.models.Order;
import dev.jdesk.api.AssetRoute;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderImageAssetServiceTest {
    private static final String REMOTE_URL = "https://untrusted.example/private-product.png";

    @Test
    void servesCachedPngThroughAnOpaqueSameOriginPath() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        OrderImageAssetService service = new OrderImageAssetService(new byte[32], 4, 1024);
        Order order = order(9_007_199_254_740_993L, REMOTE_URL, png);

        String path = service.register(order);
        String routePath = path.substring("jdesk://app/order-images/".length());
        AssetRoute.Response response = service
                .serve(new AssetRoute.Request(routePath, "GET", new byte[0], Map.of()))
                .orElseThrow();

        assertTrue(path.matches("jdesk://app/order-images/[A-Za-z0-9_-]{43}\\.png"));
        assertFalse(path.contains(REMOTE_URL));
        assertFalse(path.contains(order.getId().toString()));
        assertEquals("image/png", response.contentType());
        assertEquals(png.length, response.contentLength());
        assertEquals("private, max-age=3600", response.headers().get("Cache-Control"));
        try (var body = response.body().get()) {
            assertArrayEquals(png, body.readAllBytes());
        }
        assertEquals(path, service.register(order));
    }

    @Test
    void rejectsTamperedWritesMissingAndOversizedImages() {
        OrderImageAssetService service = new OrderImageAssetService(new byte[32], 2, 8);
        Order valid = order(1, REMOTE_URL, new byte[] {1, 2, 3});
        String routePath = service.register(valid).substring("jdesk://app/order-images/".length());

        assertTrue(service.serve(new AssetRoute.Request(routePath + "x", "GET", new byte[0], Map.of())).isEmpty());
        assertTrue(service.serve(new AssetRoute.Request(routePath, "POST", new byte[0], Map.of())).isEmpty());
        assertTrue(service.serve(new AssetRoute.Request("missing.png", "GET", new byte[0], Map.of())).isEmpty());
        assertEquals("", service.register(order(2, REMOTE_URL, new byte[9])));
        assertEquals("", service.register(order(3, "", new byte[] {1})));
        assertEquals("", service.register(order(4, REMOTE_URL, null)));
    }

    @Test
    void evictsLeastRecentlyUsedImagesWhenTheTotalByteBudgetIsExceeded() {
        OrderImageAssetService service = new OrderImageAssetService(new byte[32], 4, 8, 10);
        String first = routePath(service.register(order(1, REMOTE_URL + "/1", new byte[] {1, 2, 3, 4})));
        String second = routePath(service.register(order(2, REMOTE_URL + "/2", new byte[] {5, 6, 7, 8})));

        assertTrue(service.serve(request(first)).isPresent());
        String third = routePath(service.register(order(3, REMOTE_URL + "/3", new byte[] {9, 10, 11, 12})));

        assertTrue(service.serve(request(first)).isPresent());
        assertTrue(service.serve(request(second)).isEmpty());
        assertTrue(service.serve(request(third)).isPresent());
    }

    private AssetRoute.Request request(String path) {
        return new AssetRoute.Request(path, "GET", new byte[0], Map.of());
    }

    private String routePath(String path) {
        return path.substring("jdesk://app/order-images/".length());
    }

    private Order order(long id, String imageUrl, byte[] image) {
        Order order = new Order();
        order.setId(id);
        order.setImageUrl(imageUrl);
        order.setImage(image);
        return order;
    }
}
