import type { ShopState } from "../../generated/types";

const MAX_SHOPS = 500;
const MAX_NAME = 120;

function validName(value: unknown): value is string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > MAX_NAME) return false;
  for (const character of value) {
    const point = character.codePointAt(0);
    if (point !== undefined && (point < 32 || (point >= 127 && point <= 159))) return false;
  }
  return true;
}

export function validShopState(value: unknown): value is ShopState {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as ShopState;
  if (!Array.isArray(candidate.shops) || candidate.shops.length > MAX_SHOPS
    || typeof candidate.hasSelectedShop !== "boolean"
    || !Number.isInteger(candidate.selectedShopId) || candidate.selectedShopId < 0) return false;
  const ids = new Set<number>();
  for (const shop of candidate.shops) {
    if (typeof shop !== "object" || shop === null || !Number.isInteger(shop.id) || shop.id <= 0
      || ids.has(shop.id) || !validName(shop.name) || typeof shop.tokenConfigured !== "boolean") return false;
    ids.add(shop.id);
  }
  return candidate.hasSelectedShop
    ? ids.has(candidate.selectedShopId)
    : candidate.selectedShopId === 0;
}
