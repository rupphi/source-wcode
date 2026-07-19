import type { CatalogResponse, GtinItem } from "../../generated/types";

const ORDER_STATUSES = new Set([
  "", "DRAFT", "SUBMITTED", "WAITING_CODES", "CODES_READY", "CODES_DOWNLOADED", "PDF_GENERATED",
  "INTRODUCTION_SKIPPED_MISSING_DOCUMENTS", "INTRODUCTION_SKIPPED_MISSING_METADATA",
  "WAITING_INTRODUCTION_READINESS", "INTRO_SENT", "INTRODUCED", "INTRODUCTION_FAILED", "FAILED", "CANCELLED",
]);
const PIPELINE_STAGES = new Set([
  "", "VALIDATING", "CREATING_ORDER", "POLLING_ORDER", "DOWNLOADING_CODES",
  "INTRODUCTION_SKIPPED_MISSING_DOCUMENTS", "INTRODUCTION_SKIPPED_MISSING_METADATA",
  "WAITING_INTRODUCTION_READINESS", "SUBMITTING_INTRODUCTION", "POLLING_INTRODUCTION",
  "INTRODUCTION_FAILED", "INTRODUCED", "COMPLETED", "FAILED",
]);
const SAFE_TEXT = /^[^\p{Cc}\p{Cf}]*$/u;

export function matchesCatalogResponse(response: CatalogResponse, shopId: number, query: string, categories: string[], page: number, pageSize: number) {
  return response !== null
    && response.shopId === shopId
    && response.query === query
    && response.page === page
    && response.pageSize === pageSize
    && typeof response.hasMore === "boolean"
    && Array.isArray(response.categories)
    && response.categories.length === categories.length
    && response.categories.every((category, index) => category === categories[index])
    && Array.isArray(response.availableCategories)
    && response.availableCategories.length <= 100
    && response.availableCategories.every((category) => safeText(category, 160))
    && new Set(response.availableCategories).size === response.availableCategories.length
    && Array.isArray(response.items)
    && response.items.length <= pageSize
    && response.items.every(validGtinItem)
    && new Set(response.items.map((item) => item.gtin)).size === response.items.length;
}

function validGtinItem(item: GtinItem) {
  return item !== null
    && /^\d{14}$/.test(item.gtin)
    && safeText(item.productName, 160)
    && safeText(item.category, 160)
    && safeCount(item.available)
    && safeCount(item.reserved)
    && safeCount(item.consumed)
    && safeCount(item.mappingRuleCount)
    && ORDER_STATUSES.has(item.orderStatus)
    && PIPELINE_STAGES.has(item.pipelineStage)
    && safeText(item.errorMessage, 500)
    && (item.syncedAt === "" || !Number.isNaN(Date.parse(item.syncedAt)));
}

function safeText(value: string, maxLength: number) {
  return typeof value === "string" && value.length <= maxLength && SAFE_TEXT.test(value);
}

function safeCount(value: number) {
  return Number.isSafeInteger(value) && value >= 0 && value <= 1_000_000;
}
