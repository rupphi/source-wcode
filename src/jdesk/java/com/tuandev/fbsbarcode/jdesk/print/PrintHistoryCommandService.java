package com.tuandev.fbsbarcode.jdesk.print;

import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class PrintHistoryCommandService {
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_PAGE = 100_000;
    private static final int MIN_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REPRINT_ITEMS = 5_000;

    private final Supplier<List<Shop>> shops;
    private final JobReader jobs;

    public PrintHistoryCommandService() {
        ShopRepository shopRepository = new ShopRepository();
        PrintHistoryService historyService = new PrintHistoryService();
        this.shops = shopRepository::findAll;
        this.jobs = historyService::getJobs;
    }

    PrintHistoryCommandService(Supplier<List<Shop>> shops, JobReader jobs) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    @DesktopCommand("printing.history")
    @RequiresCapability("printing:read")
    public CompletionStage<PrintHistoryResponse> list(
            PrintHistoryRequest request, InvocationContext context) {
        ValidatedRequest validated = validate(request);
        return SafeCommandExecutor.execute(() -> {
            if (requireShops().stream().noneMatch(shop -> shop.getId() == validated.shopId())) {
                throw SafeCommandExecutor.invalidRequest("The selected shop is not available.");
            }
            List<PrintHistoryJobSummary> all = List.copyOf(
                    Objects.requireNonNull(jobs.read(validated.shopId()), "print history jobs"));
            int successfulItems = 0;
            int failedItems = 0;
            for (PrintHistoryJobSummary job : all) {
                requireJob(job, validated.shopId());
                if (job.canReprint()) {
                    successfulItems++;
                } else {
                    failedItems++;
                }
            }
            List<PrintHistoryJobSummary> matching = all.stream()
                    .filter(job -> validated.status().equals("all")
                            || normalizedStatus(job).equals(validated.status()))
                    .filter(job -> matches(job, validated.query()))
                    .toList();
            int totalItems = matching.size();
            int totalPages = totalItems == 0
                    ? 0
                    : (int) (((long) totalItems + validated.pageSize() - 1) / validated.pageSize());
            List<PrintHistoryItem> items = page(matching, validated.page(), validated.pageSize()).stream()
                    .map(PrintHistoryCommandService::toItem)
                    .toList();
            return new PrintHistoryResponse(
                    validated.shopId(),
                    validated.query(),
                    validated.status(),
                    validated.page(),
                    validated.pageSize(),
                    totalItems,
                    totalPages,
                    successfulItems,
                    failedItems,
                    items);
        });
    }

    private List<Shop> requireShops() {
        return List.copyOf(Objects.requireNonNull(shops.get(), "shops"));
    }

    private static ValidatedRequest validate(PrintHistoryRequest request) {
        if (request == null || request.shopId() <= 0) {
            throw SafeCommandExecutor.invalidRequest("A positive shop id is required.");
        }
        if (request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw SafeCommandExecutor.invalidRequest("The print history query is invalid.");
        }
        if (!("all".equals(request.status())
                || "success".equals(request.status())
                || "failed".equals(request.status()))) {
            throw SafeCommandExecutor.invalidRequest("The print history status is invalid.");
        }
        if (request.page() < 1 || request.page() > MAX_PAGE) {
            throw SafeCommandExecutor.invalidRequest("The requested print history page is invalid.");
        }
        if (request.pageSize() < MIN_PAGE_SIZE || request.pageSize() > MAX_PAGE_SIZE) {
            throw SafeCommandExecutor.invalidRequest("The print history page size is invalid.");
        }
        return new ValidatedRequest(
                request.shopId(), request.query().strip(), request.status(), request.page(), request.pageSize());
    }

    private static void requireJob(PrintHistoryJobSummary job, int shopId) {
        if (job == null || job.id() <= 0 || job.shopId() != shopId || job.itemCount() < 0) {
            throw new IllegalStateException("Print history job is invalid");
        }
        normalizedStatus(job);
    }

    private static String normalizedStatus(PrintHistoryJobSummary job) {
        if ("success".equalsIgnoreCase(job.status())) {
            return "success";
        }
        if ("failed".equalsIgnoreCase(job.status())) {
            return "failed";
        }
        throw new IllegalStateException("Print history status is invalid");
    }

    private static boolean matches(PrintHistoryJobSummary job, String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(job.id(), needle)
                || contains(job.supplyId(), needle)
                || contains(job.supplyName(), needle)
                || contains(job.printedAt(), needle)
                || contains(job.templateName(), needle)
                || contains(job.status(), needle);
    }

    private static boolean contains(Object value, String needle) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static List<PrintHistoryJobSummary> page(
            List<PrintHistoryJobSummary> items, int page, int pageSize) {
        int offset = Math.multiplyExact(page - 1, pageSize);
        int fromIndex = Math.min(offset, items.size());
        int toIndex = Math.min(fromIndex + pageSize, items.size());
        return new ArrayList<>(items.subList(fromIndex, toIndex));
    }

    private static PrintHistoryItem toItem(PrintHistoryJobSummary job) {
        String supplyId = text(job.supplyId(), 128);
        String supplyName = text(job.supplyName(), 160);
        if (supplyName.isBlank()) {
            supplyName = supplyId.isBlank() ? "Print job " + job.id() : supplyId;
        }
        String status = normalizedStatus(job);
        boolean canReprint = status.equals("success")
                && job.itemCount() > 0
                && job.itemCount() <= MAX_REPRINT_ITEMS;
        return new PrintHistoryItem(
                Long.toString(job.id()),
                supplyId,
                supplyName,
                text(job.printedAt(), 64),
                job.itemCount(),
                text(job.templateName(), 160),
                status,
                canReprint);
    }

    private static String text(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    @FunctionalInterface
    interface JobReader {
        List<PrintHistoryJobSummary> read(int shopId);
    }

    private record ValidatedRequest(int shopId, String query, String status, int page, int pageSize) {
    }

    public record PrintHistoryRequest(int shopId, String query, String status, int page, int pageSize) {
    }

    public record PrintHistoryItem(
            String jobId,
            String supplyId,
            String supplyName,
            String printedAt,
            int itemCount,
            String templateName,
            String status,
            boolean canReprint) {
    }

    public record PrintHistoryResponse(
            int shopId,
            String query,
            String status,
            int page,
            int pageSize,
            int totalItems,
            int totalPages,
            int successfulItems,
            int failedItems,
            List<PrintHistoryItem> items) {
        public PrintHistoryResponse {
            items = List.copyOf(items);
        }
    }
}
