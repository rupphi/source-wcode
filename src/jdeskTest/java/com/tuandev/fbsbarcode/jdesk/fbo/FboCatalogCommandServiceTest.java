package com.tuandev.fbsbarcode.jdesk.fbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FboCatalogCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-not-cross-the-fbo-bridge";

    @Test
    void returnsABoundedPageWithStringIdsAndOpaqueCachedImages() {
        List<FboProductSku> source = new ArrayList<>();
        for (int index = 1; index <= 11; index++) {
            source.add(product(9_007_199_254_740_990L + index, "SKU-" + index, "Subject " + index));
        }
        AtomicReference<FboCatalogCommandService.ProductQuery> query = new AtomicReference<>();
        AtomicReference<List<String>> imageUrls = new AtomicReference<>();
        FboCatalogCommandService service = new FboCatalogCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> List.of(" Обувь ", "Сумки", "обувь"),
                request -> {
                    query.set(request);
                    return source;
                },
                products -> {
                    imageUrls.set(products.stream().map(FboProductSku::imageUrl).toList());
                    Map<String, byte[]> images = new LinkedHashMap<>();
                    products.forEach(product -> images.put(
                            product.imageUrl(), new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
                    return images;
                },
                new OrderImageAssetService());

        FboCatalogCommandService.FboCatalogResponse response = service
                .load(new FboCatalogCommandService.FboCatalogRequest(
                        7, " sku ", List.of(" Обувь ", "обувь"), 2, 10), null)
                .toCompletableFuture()
                .join();

        assertEquals(new FboCatalogCommandService.ProductQuery(
                7, "sku", List.of("Обувь"), 11, 10), query.get());
        assertEquals(List.of("Обувь", "Сумки"), response.availableSubjects());
        assertEquals(10, response.items().size());
        assertTrue(response.hasMore());
        assertEquals("9007199254740991", response.items().getFirst().nmId());
        assertEquals("Product 1", response.items().getFirst().title());
        assertTrue(response.items().getFirst().imagePath().startsWith("jdesk://app/order-images/"));
        assertEquals(10, imageUrls.get().size());
        assertFalse(response.toString().contains("https://"));
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void rejectsInvalidAndUnownedRequestsBeforeReadingProducts() {
        AtomicInteger subjectReads = new AtomicInteger();
        AtomicInteger productReads = new AtomicInteger();
        FboCatalogCommandService service = new FboCatalogCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> {
                    subjectReads.incrementAndGet();
                    return List.of();
                },
                request -> {
                    productReads.incrementAndGet();
                    return List.of();
                },
                products -> Map.of(),
                new OrderImageAssetService());

        List<FboCatalogCommandService.FboCatalogRequest> invalid = List.of(
                new FboCatalogCommandService.FboCatalogRequest(0, "", List.of(), 1, 10),
                new FboCatalogCommandService.FboCatalogRequest(9, "", List.of(), 1, 10),
                new FboCatalogCommandService.FboCatalogRequest(7, "bad\nquery", List.of(), 1, 10),
                new FboCatalogCommandService.FboCatalogRequest(7, "", null, 1, 10),
                new FboCatalogCommandService.FboCatalogRequest(7, "", List.of(""), 1, 10),
                new FboCatalogCommandService.FboCatalogRequest(7, "", List.of("bad\nsubject"), 1, 10),
                new FboCatalogCommandService.FboCatalogRequest(7, "", List.of(), 0, 10),
                new FboCatalogCommandService.FboCatalogRequest(7, "", List.of(), 1, 101));

        for (FboCatalogCommandService.FboCatalogRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.load(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, subjectReads.get());
        assertEquals(0, productReads.get());
    }

    @Test
    void redactsReaderFailuresAndRejectsOversizedPages() {
        FboCatalogCommandService failing = new FboCatalogCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> List.of("Subject"),
                request -> {
                    throw new IllegalStateException("sqlite failure " + SECRET);
                },
                products -> Map.of(),
                new OrderImageAssetService());

        JDeskException failure = assertThrows(JDeskException.class, () -> failing.load(request(), null));
        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
        assertFalse(failure.publicMessage().contains(SECRET));
        assertNull(failure.details());
        assertNull(failure.getCause());

        FboCatalogCommandService oversized = new FboCatalogCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> List.of(),
                request -> java.util.Collections.nCopies(12, product(1, "SKU", "Subject")),
                products -> Map.of(),
                new OrderImageAssetService());
        JDeskException oversizedFailure = assertThrows(
                JDeskException.class, () -> oversized.load(request(), null));
        assertEquals(ErrorCode.INTERNAL_ERROR, oversizedFailure.code());
        assertFalse(oversizedFailure.publicMessage().contains(SECRET));
    }

    @Test
    void bridgeCodecRoundTripsTheAllowlistedContract() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        FboCatalogCommandService.FboCatalogRequest request = codec.decode(
                """
                {"shopId":7,"query":"","subjects":[],"page":1,"pageSize":10}
                """,
                FboCatalogCommandService.FboCatalogRequest.class);
        FboCatalogCommandService service = new FboCatalogCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shopId -> List.of("Subject"),
                ignored -> List.of(product(101, "SKU-1", "Subject")),
                products -> Map.of(),
                new OrderImageAssetService());

        String json = codec.encode(service.load(request, null).toCompletableFuture().join());

        assertTrue(json.contains("\"nmId\":\"101\""));
        assertTrue(json.contains("\"availableSubjects\":[\"Subject\"]"));
        assertFalse(json.contains("imageUrl"));
        assertFalse(json.contains(SECRET));
    }

    private static FboCatalogCommandService.FboCatalogRequest request() {
        return new FboCatalogCommandService.FboCatalogRequest(7, "", List.of(), 1, 10);
    }

    private static FboProductSku product(long nmId, String sku, String subject) {
        return new FboProductSku(
                nmId,
                "ART-" + sku,
                subject,
                " Brand ",
                " Product " + sku.substring(sku.lastIndexOf('-') + 1) + " ",
                " Blue ",
                " M ",
                " 44 ",
                sku,
                "https://untrusted.example/" + sku + ".png?secret=" + SECRET,
                true);
    }
}
