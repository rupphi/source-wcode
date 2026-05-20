package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class KizAttachmentCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(KizAttachmentCoordinator.class);
    private static final KizAttachmentCoordinator INSTANCE = new KizAttachmentCoordinator();

    private final Map<String, KizAttachmentProgress> activeJobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<KizAttachmentProgress>> listeners = new CopyOnWriteArrayList<>();

    private KizAttachmentCoordinator() {
    }

    public static KizAttachmentCoordinator getInstance() {
        return INSTANCE;
    }

    public void addListener(Consumer<KizAttachmentProgress> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<KizAttachmentProgress> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public boolean hasActiveJobs() {
        return !activeJobs.isEmpty();
    }

    public boolean hasActiveJobForShop(int shopId) {
        return activeJobs.values().stream().anyMatch(progress -> progress.shopId() == shopId && progress.active());
    }

    public boolean hasActiveJobForSupply(int shopId, String supplyId) {
        return findActiveJobForSupply(shopId, supplyId).isPresent();
    }

    public Optional<KizAttachmentProgress> findActiveJobForSupply(int shopId, String supplyId) {
        if (supplyId == null || supplyId.isBlank()) {
            return Optional.empty();
        }
        return activeJobs.values().stream()
                .filter(progress -> progress.shopId() == shopId)
                .filter(progress -> Objects.equals(progress.supplyId(), supplyId))
                .filter(KizAttachmentProgress::active)
                .findFirst();
    }

    public void enqueue(Shop shop,
                        String supplyId,
                        String supplyName,
                        List<OrderExportWorkflow.KizAttachmentAssignment> assignments) {
        if (shop == null || assignments == null || assignments.isEmpty()) {
            return;
        }

        String safeSupplyId = supplyId == null ? "" : supplyId;
        String safeSupplyName = supplyName == null ? "" : supplyName;
        String key = buildKey(shop.getId(), safeSupplyId);
        KizAttachmentProgress initialProgress = new KizAttachmentProgress(
                shop.getId(),
                shop.getName(),
                safeSupplyId,
                safeSupplyName,
                0,
                assignments.size(),
                true,
                "Отправка KIZ в WB 0/" + assignments.size(),
                List.of(),
                List.of()
        );
        activeJobs.put(key, initialProgress);
        notifyListeners(initialProgress);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                runAttachmentJob(key, shop, safeSupplyId, safeSupplyName, assignments);
                return null;
            }
        };
        AppTaskExecutor.execute(task);
    }

    private void runAttachmentJob(String key,
                                  Shop shop,
                                  String supplyId,
                                  String supplyName,
                                  List<OrderExportWorkflow.KizAttachmentAssignment> assignments) throws IOException {
        List<Kiz> successfulKizs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int completed = 0;

        for (OrderExportWorkflow.KizAttachmentAssignment assignment : assignments) {
            if (assignment == null || assignment.orderId() == null || assignment.orderId() <= 0) {
                continue;
            }
            KizService.AttachCodeResult result = KizService.addDataMatrixCodeToOrder(
                    shop.getApiKey(),
                    assignment.orderId(),
                    assignment.kizCode()
            );

            if (result.success()) {
                successfulKizs.add(assignment.sourceKiz());
                completed++;
                notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                        "Отправка KIZ в WB " + completed + "/" + assignments.size(), failures, successfulKizs);
                continue;
            }

            String message;
            if (result.statusCode() == 429) {
                message = "WB ограничил отправку KIZ для order " + assignment.orderId() + " (HTTP 429)";
            } else {
                message = "Не удалось отправить KIZ для order " + assignment.orderId() + " (HTTP " + result.statusCode() + ")";
            }
            if (result.responseBody() != null && !result.responseBody().isBlank()) {
                message += ": " + result.responseBody();
            }
            failures.add(message);
            LOGGER.warn("Background attach KIZ failed for shop {}, supply {}, order {}: {}",
                    shop.getId(), supplyId, assignment.orderId(), message);
            notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                    "Отправка KIZ в WB " + completed + "/" + assignments.size(), failures, successfulKizs);
        }

        if (!successfulKizs.isEmpty()) {
            KizService.deleteKizs(successfulKizs);
        }

        String finalMessage = failures.isEmpty()
                ? "KIZ отправлены в WB: " + completed + "/" + assignments.size()
                : "KIZ отправлены частично: " + completed + "/" + assignments.size();
        KizAttachmentProgress completedProgress = new KizAttachmentProgress(
                shop.getId(),
                shop.getName(),
                supplyId,
                supplyName,
                completed,
                assignments.size(),
                false,
                finalMessage,
                List.copyOf(failures),
                successfulKizs.stream().map(Kiz::getCode).filter(Objects::nonNull).toList()
        );
        activeJobs.remove(key);
        notifyListeners(completedProgress);
    }

    private void notifyProgress(String key,
                                Shop shop,
                                String supplyId,
                                String supplyName,
                                int completed,
                                int total,
                                boolean active,
                                String message,
                                List<String> failures,
                                List<Kiz> successfulKizs) {
        KizAttachmentProgress progress = new KizAttachmentProgress(
                shop.getId(),
                shop.getName(),
                supplyId,
                supplyName,
                completed,
                total,
                active,
                message,
                List.copyOf(failures),
                successfulKizs.stream().map(Kiz::getCode).filter(Objects::nonNull).toList()
        );
        activeJobs.put(key, progress);
        notifyListeners(progress);
    }

    private void notifyListeners(KizAttachmentProgress progress) {
        for (Consumer<KizAttachmentProgress> listener : listeners) {
            try {
                listener.accept(progress);
            } catch (RuntimeException ex) {
                LOGGER.warn("KIZ attachment progress listener failed", ex);
            }
        }
    }

    private static String buildKey(int shopId, String supplyId) {
        return shopId + ":" + (supplyId == null ? "" : supplyId);
    }

    public record KizAttachmentProgress(
            int shopId,
            String shopName,
            String supplyId,
            String supplyName,
            int completed,
            int total,
            boolean active,
            String message,
            List<String> failures,
            List<String> successfulKizCodes
    ) {
    }
}
