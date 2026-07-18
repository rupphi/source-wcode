package com.tuandev.fbsbarcode.jdesk.fbo;

import com.tuandev.fbsbarcode.features.fbo.FboProductRepository;
import com.tuandev.fbsbarcode.features.fbo.FboProductSearchCriteria;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.features.print.history.ImageCacheRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.jdesk.supply.OrderImageAssetService;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class FboCatalogCommandService {
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_SUBJECT_COUNT = 50;
    private static final int MAX_AVAILABLE_SUBJECTS = 100;
    private static final int MAX_SUBJECT_LENGTH = 120;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SKU_LENGTH = 128;

    private final Supplier<List<Shop>> shops;
    private final SubjectReader subjects;
    private final ProductPageReader products;
    private final CachedImageReader cachedImages;
    private final OrderImageAssetService imageAssets;

    public FboCatalogCommandService(OrderImageAssetService imageAssets) {
        ShopRepository shopRepository = new ShopRepository();
        FboProductRepository productRepository = new FboProductRepository();
        ImageCacheRepository imageRepository = new ImageCacheRepository();
        this.shops = shopRepository::findAll;
        this.subjects = productRepository::findSubjects;
        this.products = query -> productRepository.search(new FboProductSearchCriteria(
                query.shopId(), query.query(), query.subjects(), query.limit(), query.offset()));
        this.cachedImages = page -> readCachedImages(page, imageRepository);
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
    }

    FboCatalogCommandService(
            Supplier<List<Shop>> shops,
            SubjectReader subjects,
            ProductPageReader products,
            CachedImageReader cachedImages,
            OrderImageAssetService imageAssets) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
        this.products = Objects.requireNonNull(products, "products");
        this.cachedImages = Objects.requireNonNull(cachedImages, "cachedImages");
        this.imageAssets = Objects.requireNonNull(imageAssets, "imageAssets");
    }

    @DesktopCommand("fbo.catalog")
    @RequiresCapability("fbo:read")
    public CompletionStage<FboCatalogResponse> load(
            FboCatalogRequest request, InvocationContext context) {
        ValidatedRequest validated = validate(request);
        return SafeCommandExecutor.execute(() -> {
            if (requireShops().stream().noneMatch(shop -> shop.getId() == validated.shopId())) {
                throw SafeCommandExecutor.invalidRequest("The selected shop is not available.");
            }
            List<String> availableSubjects = sanitizeSubjects(
                    subjects.read(validated.shopId()), MAX_AVAILABLE_SUBJECTS, false);
            ProductQuery query = new ProductQuery(
                    validated.shopId(),
                    validated.query(),
                    validated.subjects(),
                    validated.pageSize() + 1,
                    Math.multiplyExact(validated.page() - 1, validated.pageSize()));
            List<FboProductSku> loaded = List.copyOf(Objects.requireNonNull(products.read(query), "FBO product page"));
            if (loaded.size() > query.limit() || loaded.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("FBO product page is invalid");
            }
            boolean hasMore = loaded.size() > validated.pageSize();
            List<FboProductSku> page = new ArrayList<>(loaded.subList(0, Math.min(loaded.size(), validated.pageSize())));
            requireUniqueSkus(page);
            Map<String, byte[]> images = page.isEmpty()
                    ? Map.of()
                    : Map.copyOf(Objects.requireNonNull(cachedImages.read(page), "FBO cached images"));
            List<FboProductItem> items = page.stream()
                    .map(product -> toItem(product, images.get(product.imageUrl())))
                    .toList();
            return new FboCatalogResponse(
                    validated.shopId(),
                    validated.query(),
                    validated.subjects(),
                    validated.page(),
                    validated.pageSize(),
                    hasMore,
                    availableSubjects,
                    items);
        });
    }

    private List<Shop> requireShops() {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
    }

    private FboProductItem toItem(FboProductSku product, byte[] image) {
        if (product.nmId() <= 0) {
            throw new IllegalStateException("FBO product id is invalid");
        }
        String sku = requireSku(product.sku());
        String imagePath = "";
        String cacheScope = PrintHistoryService.imageCacheKey(product.imageUrl());
        if (cacheScope != null && image != null && image.length > 0) {
            imagePath = imageAssets.registerProduct(product.nmId(), cacheScope, image);
        }
        return new FboProductItem(
                Long.toString(product.nmId()),
                text(product.vendorCode(), 120),
                text(product.subjectName(), MAX_SUBJECT_LENGTH),
                text(product.brand(), 120),
                text(product.title(), 180),
                text(product.color(), 80),
                text(product.size(), 80),
                text(product.ruSize(), 80),
                sku,
                product.requiresKiz(),
                imagePath);
    }

    private static ValidatedRequest validate(FboCatalogRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The FBO search query is invalid.");
        }
        List<String> selectedSubjects = sanitizeSubjects(request.subjects(), MAX_SUBJECT_COUNT, true);
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw SafeCommandExecutor.invalidRequest("The requested FBO page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw SafeCommandExecutor.invalidRequest("The FBO page size is invalid.");
        }
        return new ValidatedRequest(
                request.shopId(), request.query().strip(), selectedSubjects, request.page(), request.pageSize());
    }

    private static List<String> sanitizeSubjects(List<String> values, int maxCount, boolean strict) {
        if (values == null || (strict && values.size() > maxCount)) {
            throw SafeCommandExecutor.invalidRequest("The FBO subjects are invalid.");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null
                    || value.isBlank()
                    || value.length() > MAX_SUBJECT_LENGTH
                    || value.chars().anyMatch(Character::isISOControl)) {
                if (strict) {
                    throw SafeCommandExecutor.invalidRequest("The FBO subjects are invalid.");
                }
                continue;
            }
            String subject = text(value, MAX_SUBJECT_LENGTH);
            normalized.putIfAbsent(subject.toLowerCase(Locale.ROOT), subject);
            if (normalized.size() >= maxCount) {
                break;
            }
        }
        return List.copyOf(normalized.values());
    }

    private static void requireUniqueSkus(List<FboProductSku> page) {
        Set<String> skus = new LinkedHashSet<>();
        for (FboProductSku product : page) {
            if (!skus.add(requireSku(product.sku()).toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("FBO product page contains duplicate SKUs");
            }
        }
    }

    private static String requireSku(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_SKU_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("FBO product SKU is invalid");
        }
        return value.strip();
    }

    private static String text(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    private static Map<String, byte[]> readCachedImages(
            List<FboProductSku> products, ImageCacheRepository imageRepository) {
        Map<String, String> keysByUrl = new LinkedHashMap<>();
        for (FboProductSku product : products) {
            String key = PrintHistoryService.imageCacheKey(product.imageUrl());
            if (key != null) {
                keysByUrl.putIfAbsent(product.imageUrl(), key);
            }
        }
        Map<String, byte[]> cached = imageRepository.findImages(keysByUrl.values());
        Map<String, byte[]> images = new LinkedHashMap<>();
        keysByUrl.forEach((url, key) -> {
            byte[] image = cached.get(key);
            if (image != null && image.length > 0) {
                images.put(url, image);
            }
        });
        return images;
    }

    @FunctionalInterface
    interface SubjectReader {
        List<String> read(int shopId);
    }

    @FunctionalInterface
    interface ProductPageReader {
        List<FboProductSku> read(ProductQuery query);
    }

    @FunctionalInterface
    interface CachedImageReader {
        Map<String, byte[]> read(List<FboProductSku> products);
    }

    public record ProductQuery(
            int shopId, String query, List<String> subjects, int limit, int offset) {
        public ProductQuery {
            subjects = List.copyOf(subjects);
        }
    }

    private record ValidatedRequest(
            int shopId, String query, List<String> subjects, int page, int pageSize) {
    }

    public record FboCatalogRequest(
            int shopId, String query, List<String> subjects, int page, int pageSize) {
    }

    public record FboProductItem(
            String nmId,
            String vendorCode,
            String subject,
            String brand,
            String title,
            String color,
            String size,
            String russianSize,
            String sku,
            boolean requiresKiz,
            String imagePath) {
    }

    public record FboCatalogResponse(
            int shopId,
            String query,
            List<String> subjects,
            int page,
            int pageSize,
            boolean hasMore,
            List<String> availableSubjects,
            List<FboProductItem> items) {
        public FboCatalogResponse {
            subjects = List.copyOf(subjects);
            availableSubjects = List.copyOf(availableSubjects);
            items = List.copyOf(items);
        }
    }
}
