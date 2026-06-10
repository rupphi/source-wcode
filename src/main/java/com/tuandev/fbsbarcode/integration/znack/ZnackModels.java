package com.tuandev.fbsbarcode.integration.znack;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

public final class ZnackModels {
    public static final String PRODUCTION_TRUE_API = "https://markirovka.crpt.ru/api/v3/true-api";
    public static final String PRODUCTION_SUZ = "https://suzgrid.crpt.ru";

    private ZnackModels() {
    }

    public enum OrderStatus {
        DRAFT, SUBMITTED, WAITING_CODES, CODES_READY, CODES_DOWNLOADED, PDF_GENERATED,
        INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, INTRODUCTION_SKIPPED_MISSING_METADATA,
        INTRO_SENT, INTRODUCED, FAILED, CANCELLED
    }

    public enum KizInventoryStatus {
        AVAILABLE, RESERVED, CONSUMED
    }

    public enum KizLegalStatus {
        RECEIVED, PRINTED, INTRO_SENT, IN_CIRCULATION
    }

    public enum PurchaseStage {
        VALIDATING, CREATING_ORDER, POLLING_ORDER, DOWNLOADING_CODES,
        INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, INTRODUCTION_SKIPPED_MISSING_METADATA,
        SUBMITTING_INTRODUCTION, POLLING_INTRODUCTION, INTRODUCED, COMPLETED, FAILED
    }

    public record ShopContext(int shopId, String shopName) {
        public ShopContext {
            if (shopId <= 0) throw new IllegalArgumentException("A persisted shop is required.");
            shopName = shopName == null ? "" : shopName;
        }
    }

    public record Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                           String participantInn, String producerInn, String ownerInn, String signerExecutable,
                           String signerCertificate, String signerArgumentsJson, String documentNumber,
                           String documentDate, String pdfFolder, boolean autoIntroduction,
                           String certificateListExecutable, String certificateListArgumentsJson,
                           String certificateMetadataJson, Instant signerTestedAt,
                           String certmgrPath, String cryptcpPath, String csptestPath, int cryptoProTimeoutSeconds,
                           String documentExpiryDate) {
        private static final DateTimeFormatter GOODS_DOCUMENT_DATE =
                DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, "", "[]", "", null, "", "", "", 60, "");
        }

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction,
                        String certificateListExecutable, String certificateListArgumentsJson,
                        String certificateMetadataJson, Instant signerTestedAt) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, certificateListExecutable, certificateListArgumentsJson,
                    certificateMetadataJson, signerTestedAt, "", "", "", 60, "");
        }

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction,
                        String certificateListExecutable, String certificateListArgumentsJson,
                        String certificateMetadataJson, Instant signerTestedAt,
                        String certmgrPath, String cryptcpPath, String csptestPath, int cryptoProTimeoutSeconds) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, certificateListExecutable, certificateListArgumentsJson,
                    certificateMetadataJson, signerTestedAt, certmgrPath, cryptcpPath, csptestPath,
                    cryptoProTimeoutSeconds, "");
        }

        public static Settings empty() {
            return new Settings("", "", "", "", "", "", "", "", "", "[]", "", "", "", false,
                    "", "[]", "", null, "", "", "", 60, "");
        }

        public String resolvedTrueApiBaseUrl() {
            return trueApiBaseUrl == null || trueApiBaseUrl.isBlank() ? PRODUCTION_TRUE_API : trueApiBaseUrl.trim();
        }

        public String resolvedSuzBaseUrl() {
            return suzBaseUrl == null || suzBaseUrl.isBlank() ? PRODUCTION_SUZ : suzBaseUrl.trim();
        }

        public int resolvedCryptoProTimeoutSeconds() {
            return cryptoProTimeoutSeconds <= 0 ? 60 : Math.min(cryptoProTimeoutSeconds, 600);
        }

        public boolean hasDefaultGoodsDocument() {
            return !blank(documentNumber) && !blank(documentDate) && !blank(documentExpiryDate);
        }

        public void validateGoodsDocumentDates() {
            validateDate(documentDate, "Document issue date");
            validateDate(documentExpiryDate, "Document expiry date");
            if (!blank(documentDate) && !blank(documentExpiryDate)
                    && parseDate(documentExpiryDate).isBefore(parseDate(documentDate))) {
                throw new IllegalArgumentException("Document expiry date must not be before the issue date.");
            }
        }

        private static void validateDate(String value, String field) {
            if (blank(value)) return;
            try { parseDate(value); }
            catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + " must use dd.MM.yyyy format.");
            }
        }

        private static LocalDate parseDate(String value) {
            return LocalDate.parse(value.trim(), GOODS_DOCUMENT_DATE);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record Product(String gtin, String productName, String tnVed, String certificateType,
                          String certificateNumber, String certificateDate, String productionDate) {
    }

    public record KizOrder(long id, String externalOrderId, String gtin, int quantity, String remoteStatus,
                           OrderStatus localStatus, String errorMessage, Instant createdAt, Instant updatedAt) {
    }

    public record KizCode(long id, long orderId, String rawCode, String displayCode, String gtin, String blockId,
                          String pdfPath, Long documentId, KizInventoryStatus inventoryStatus,
                          KizLegalStatus legalStatus) {
    }

    public record Document(long id, long orderId, String payloadJson, String externalDocumentId, String status) {
    }

    public record OperationLog(long id, int shopId, String shopName, String action, String entityReference,
                               String severity, String message, Integer httpStatus, Instant createdAt) {
    }

    public record BufferStatus(String remoteStatus, int availableCodes, boolean rejected, String rejectionReason) {
        public OrderStatus localStatus() {
            if (rejected || "DECLINED".equalsIgnoreCase(remoteStatus) || "REJECTED".equalsIgnoreCase(remoteStatus)) {
                return OrderStatus.FAILED;
            }
            if ("READY".equalsIgnoreCase(remoteStatus) || availableCodes > 0) {
                return OrderStatus.CODES_READY;
            }
            return OrderStatus.WAITING_CODES;
        }
    }

    public record DownloadedCodes(List<String> codes, String blockId) {
        public DownloadedCodes {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }
}
