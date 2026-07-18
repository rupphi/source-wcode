import { ChevronDown, Store } from "lucide-react";
import type { ShopSummary } from "../generated/types";

export function ShopPicker({
  shops,
  selectedId,
  onSelect,
}: {
  shops: ShopSummary[];
  selectedId: number | null;
  onSelect: (shopId: number) => void;
}) {
  return (
    <label className="relative grid min-w-64 gap-1.5 text-xs font-semibold text-[var(--text-secondary)]">
      Магазин
      <Store className="pointer-events-none absolute bottom-3 left-3 text-[var(--text-muted)]" size={17} />
      <select
        className="h-11 appearance-none rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-10 pl-10 text-sm font-medium text-[var(--text-primary)] shadow-[var(--shadow-control)] outline-none transition hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
        value={selectedId ?? ""}
        onChange={(event) => onSelect(Number(event.target.value))}
        disabled={shops.length === 0}
      >
        {shops.length === 0 && <option value="">Нет магазинов</option>}
        {shops.map((shop) => (
          <option key={shop.id} value={shop.id}>
            {shop.name}
          </option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute right-3 bottom-3 text-[var(--text-muted)]" size={17} />
    </label>
  );
}
