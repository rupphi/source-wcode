import type { MutationPreview, MutationReceipt } from "../../generated/types";

const actions = new Set(["create", "add", "deliver"]);
const blockerKinds = new Set(["supply_not_ready", "labels_missing", "kiz_missing"]);
const warningKinds = new Set(["kiz_required"]);

export function isValidMutationPreview(
  preview: MutationPreview,
  shopId: number,
  expectedAction?: string,
  expectedSupplyId?: string,
): boolean {
  return preview.shopId === shopId
    && (expectedAction === undefined || preview.action === expectedAction)
    && (expectedSupplyId === undefined || preview.supplyId === expectedSupplyId)
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(preview.previewId)
    && actions.has(preview.action)
    && typeof preview.supplyId === "string"
    && preview.supplyId.length <= 128
    && typeof preview.supplyName === "string"
    && preview.supplyName.length <= 160
    && Number.isSafeInteger(preview.itemCount)
    && preview.itemCount >= 0
    && preview.itemCount <= 1_000_000
    && Number.isSafeInteger(preview.kizCount)
    && preview.kizCount >= 0
    && preview.kizCount <= preview.itemCount
    && preview.blockers.length <= blockerKinds.size
    && new Set(preview.blockers).size === preview.blockers.length
    && preview.blockers.every((kind) => blockerKinds.has(kind))
    && preview.warnings.length <= warningKinds.size
    && new Set(preview.warnings).size === preview.warnings.length
    && preview.warnings.every((kind) => warningKinds.has(kind))
    && preview.ready === (preview.blockers.length === 0)
    && !Number.isNaN(Date.parse(preview.expiresAt));
}

export function isValidMutationReceipt(receipt: MutationReceipt, preview: MutationPreview): boolean {
  return receipt.accepted === true
    && receipt.action === preview.action
    && typeof receipt.supplyId === "string"
    && receipt.supplyId.length > 0
    && receipt.supplyId.length <= 128
    && Number.isSafeInteger(receipt.itemCount)
    && receipt.itemCount === preview.itemCount
    && (preview.action === "create" || receipt.supplyId === preview.supplyId);
}
