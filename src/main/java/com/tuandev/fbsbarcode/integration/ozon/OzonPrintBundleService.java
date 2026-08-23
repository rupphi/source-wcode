package com.tuandev.fbsbarcode.integration.ozon;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AtomicFilePublisher;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Idempotent Ozon print orchestration: prepare marks, preserve official pages, append physical KIZ
 * labels and create a separate picking list. Printing never ships the posting.
 */
public final class OzonPrintBundleService {
    private final OzonPostingRepository postings;
    private final OzonExemplarJobRepository jobs;
    private final Preparation preparation;
    private final OfficialLabelDownloader labels;
    private final OzonKizLabelAppender kizLabels;
    private final OzonPickingListPdfExporter pickingLists;

    public OzonPrintBundleService() {
        this(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                new OzonExemplarService()::prepare,
                new OzonLabelService()::downloadOfficialPdf);
    }

    OzonPrintBundleService(
            OzonPostingRepository postings,
            OzonExemplarJobRepository jobs,
            Preparation preparation,
            OfficialLabelDownloader labels) {
        this(postings, jobs, preparation, labels, new OzonKizLabelAppender(), new OzonPickingListPdfExporter());
    }

    OzonPrintBundleService(
            OzonPostingRepository postings,
            OzonExemplarJobRepository jobs,
            Preparation preparation,
            OfficialLabelDownloader labels,
            OzonKizLabelAppender kizLabels,
            OzonPickingListPdfExporter pickingLists) {
        this.postings = Objects.requireNonNull(postings, "postings");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.kizLabels = Objects.requireNonNull(kizLabels, "kizLabels");
        this.pickingLists = Objects.requireNonNull(pickingLists, "pickingLists");
    }

    public ExportResult export(Shop shop, String postingNumber, File labelTarget, File pickingTarget)
            throws IOException {
        MarketplaceGuard.requireOzon(shop);
        requirePdfTarget(labelTarget, "label bundle");
        requirePdfTarget(pickingTarget, "picking list");
        if (labelTarget.toPath().toAbsolutePath().normalize()
                .equals(pickingTarget.toPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Ozon label bundle and picking list must use different files.");
        }

        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        OzonPostingDto posting = postings.find(shop.getId(), safePosting);
        if (posting == null) throw new IOException("The selected Ozon posting is not available locally. Refresh first.");

        OzonExemplarJob job = jobs.find(shop.getId(), safePosting);
        if (markingRequested(posting) && !accepted(job)) {
            OzonPreparationResult result = preparation.prepare(shop, safePosting);
            if ("NOT_REQUIRED".equals(result.stage())
                    && !posting.requirements().mandatoryMarkProductIds().isEmpty()) {
                throw new IOException("A mandatory Ozon KIZ cannot be omitted from the print bundle.");
            }
            if (!"ACCEPTED".equals(result.stage()) && !"NOT_REQUIRED".equals(result.stage())) {
                throw new IOException("Ozon KIZ is not accepted yet (stage " + safeStage(result.stage()) + ").");
            }
            posting = Objects.requireNonNullElse(postings.find(shop.getId(), safePosting), posting);
            job = jobs.find(shop.getId(), safePosting);
            if ("ACCEPTED".equals(result.stage()) && !accepted(job)) {
                throw new IOException("Ozon reported accepted KIZ but the durable local job is incomplete.");
            }
        }

        List<OzonExemplarJobRepository.KizBinding> bindings = accepted(job)
                ? jobs.bindings(job.id()) : List.of();
        List<OzonExemplarJobRepository.ExemplarSummary> summaries = accepted(job)
                ? jobs.summaries(job.id()) : List.of();
        validateAcceptedJob(job, bindings, summaries);

        File officialStaging = null;
        File labelStaging = null;
        File pickingStaging = null;
        try {
            officialStaging = AtomicFilePublisher.stagingFile(labelTarget, ".official.pdf");
            labelStaging = AtomicFilePublisher.stagingFile(labelTarget, ".bundle.pdf");
            pickingStaging = AtomicFilePublisher.stagingFile(pickingTarget, ".picking.pdf");
            labels.download(shop, safePosting, officialStaging);
            int officialPages = compose(officialStaging, labelStaging, shop, posting, bindings);
            pickingLists.export(pickingStaging, shop, posting);
            AtomicFilePublisher.publish(labelStaging, labelTarget);
            labelStaging = null;
            AtomicFilePublisher.publish(pickingStaging, pickingTarget);
            pickingStaging = null;
            return new ExportResult(
                    labelTarget, pickingTarget, officialPages, bindings.size(), officialPages + bindings.size());
        } finally {
            AtomicFilePublisher.deleteQuietly(officialStaging);
            AtomicFilePublisher.deleteQuietly(labelStaging);
            AtomicFilePublisher.deleteQuietly(pickingStaging);
        }
    }

    private int compose(
            File official,
            File target,
            Shop shop,
            OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        if (!Files.isRegularFile(official.toPath())) {
            throw new IOException("Ozon did not provide an official shipping label PDF.");
        }
        try (PdfDocument source = new PdfDocument(new PdfReader(official));
                PdfDocument destination = new PdfDocument(new PdfWriter(target))) {
            int officialPages = source.getNumberOfPages();
            if (officialPages < 1) throw new IOException("The official Ozon shipping label PDF has no pages.");
            // iText copies each source page with its original media box and content stream; no
            // Ozon barcode is regenerated or scaled.
            source.copyPagesTo(1, officialPages, destination);
            int appended = kizLabels.append(destination, shop, posting, bindings);
            if (appended != bindings.size()) {
                throw new IOException("The Ozon KIZ label page count is incomplete.");
            }
            return officialPages;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("The Ozon print bundle could not be composed.", exception);
        }
    }

    private static void validateAcceptedJob(
            OzonExemplarJob job,
            List<OzonExemplarJobRepository.KizBinding> bindings,
            List<OzonExemplarJobRepository.ExemplarSummary> summaries) throws IOException {
        if (!accepted(job)) return;
        if (bindings.isEmpty() || summaries.size() != bindings.size()) {
            throw new IOException("The accepted Ozon KIZ job is incomplete and cannot be printed.");
        }
        boolean allPassed = summaries.stream().allMatch(value ->
                value.kizId() != null && "passed".equalsIgnoreCase(value.checkStatus()));
        if (!allPassed) throw new IOException("Only Ozon KIZ exemplars with passed status can be printed.");
    }

    private static boolean markingRequested(OzonPostingDto posting) {
        return !posting.requirements().mandatoryMarkProductIds().isEmpty()
                || !posting.requirements().optionalMarkProductIds().isEmpty();
    }

    private static boolean accepted(OzonExemplarJob job) {
        return job != null && job.stage() == OzonExemplarJobStage.ACCEPTED;
    }

    private static void requirePdfTarget(File target, String label) {
        if (target == null || !target.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Ozon " + label + " target must be a PDF file.");
        }
    }

    private static String safeStage(String value) {
        return value != null && value.matches("[A-Z_]{1,64}") ? value : "UNKNOWN";
    }

    @FunctionalInterface
    interface Preparation {
        OzonPreparationResult prepare(Shop shop, String postingNumber) throws IOException;
    }

    @FunctionalInterface
    interface OfficialLabelDownloader {
        File download(Shop shop, String postingNumber, File target) throws IOException;
    }

    public record ExportResult(
            File labelFile,
            File pickingFile,
            int officialPages,
            int kizPages,
            int totalPages) {
    }
}
