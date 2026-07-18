import { ChevronDown, Settings2, Store } from "lucide-react";
import type { Ref } from "react";
import type { ShopSummary } from "../generated/types";
import type { AppCopy } from "../i18n";

export function ShopPicker({
  shops,
  selectedId,
  onSelect,
  onManage,
  manageButtonRef,
  busy = false,
  error = "",
  copy,
}: {
  shops: ShopSummary[];
  selectedId: number | null;
  onSelect: (shopId: number) => void;
  onManage: () => void;
  manageButtonRef?: Ref<HTMLButtonElement>;
  busy?: boolean;
  error?: string;
  copy: AppCopy["shop"];
}) {
  return (
    <div className="grid min-w-64 gap-1.5">
      <span className="text-xs font-semibold text-[var(--text-secondary)]">{copy.label}</span>
      <div className="flex items-center gap-2">
        <label className="relative min-w-0 flex-1">
          <span className="sr-only">{copy.label}</span>
          <Store className="pointer-events-none absolute top-3 left-3 text-[var(--text-muted)]" size={17} />
          <select
            className="h-11 w-full appearance-none rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-10 pl-10 text-sm font-medium text-[var(--text-primary)] shadow-[var(--shadow-control)] outline-none transition hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
            value={selectedId ?? ""}
            onChange={(event) => onSelect(Number(event.target.value))}
            disabled={shops.length === 0 || busy}
          >
            {shops.length === 0 && <option value="">{copy.empty}</option>}
            {shops.map((shop) => (
              <option key={shop.id} value={shop.id}>{shop.name}</option>
            ))}
          </select>
          <ChevronDown className="pointer-events-none absolute top-3 right-3 text-[var(--text-muted)]" size={17} />
        </label>
        <button ref={manageButtonRef} className="icon-button size-11 shrink-0" type="button" aria-label={copy.manage} onClick={onManage}>
          <Settings2 aria-hidden="true" size={18} />
        </button>
      </div>
      {error ? <p className="max-w-80 text-xs text-[var(--danger)]" role="alert">{error}</p> : null}
    </div>
  );
}
