import type { MutationPreview, PackingSupplyItem } from "../../generated/types";
import { useModalFocus } from "../../components/useModalFocus";
import { interpolate } from "../../i18n";
import { defaultPackingMutationCopy, type PackingMutationCopy } from "./PackingMutationCopy";

export type MutationDialog =
  | { kind: "create" }
  | { kind: "add"; status: "loading" | "ready" | "error"; supplies: PackingSupplyItem[] }
  | { kind: "preview"; preview: MutationPreview };

export function PackingMutationDialog({
  dialog,
  shipmentName,
  selectedTargetSupply,
  targetSupplyQuery,
  busy,
  error,
  onShipmentName,
  onTargetSupply,
  onTargetSupplyQuery,
  onSearchSupplies,
  onClose,
  onPrepareCreate,
  onPrepareAdd,
  onExecute,
  copy = defaultPackingMutationCopy,
  locale = "ru-RU",
}: {
  dialog: MutationDialog;
  shipmentName: string;
  selectedTargetSupply: string;
  targetSupplyQuery: string;
  busy: boolean;
  error: boolean;
  onShipmentName: (value: string) => void;
  onTargetSupply: (value: string) => void;
  onTargetSupplyQuery: (value: string) => void;
  onSearchSupplies: () => void;
  onClose: () => void;
  onPrepareCreate: () => Promise<void>;
  onPrepareAdd: () => Promise<void>;
  onExecute: (preview: MutationPreview) => Promise<void>;
  copy?: PackingMutationCopy;
  locale?: string;
}) {
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLElement>(busy, onClose);

  const preview = dialog.kind === "preview" ? dialog.preview : null;
  const title = dialogTitle(dialog, preview, copy);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section ref={dialogRef} className="w-full max-w-lg rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl" role="dialog" aria-modal="true" aria-labelledby="packing-mutation-title">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold" id="packing-mutation-title">{title}</h2>
            <p className="mt-1 text-sm text-[var(--text-secondary)]">{copy.changedAfterConfirmation}</p>
          </div>
          <button ref={initialFocusRef} className="rounded-lg px-2 py-1 text-sm text-[var(--text-muted)]" type="button" onClick={onClose} disabled={busy} aria-label={copy.close}>×</button>
        </div>

        {dialog.kind === "create" && (
          <label className="mt-5 grid gap-2 text-sm font-semibold">
            {copy.supplyName}
            <input className="h-11 rounded-xl border border-[var(--border-strong)] bg-transparent px-3 font-normal outline-none focus:border-[var(--accent)]" maxLength={160} value={shipmentName} onChange={(event) => onShipmentName(event.target.value)} />
          </label>
        )}

        {dialog.kind === "add" && <SupplyChooser dialog={dialog} selected={selectedTargetSupply} query={targetSupplyQuery} onQuery={onTargetSupplyQuery} onSearch={onSearchSupplies} onSelect={onTargetSupply} copy={copy} />}
        {preview && <PreviewSummary preview={preview} copy={copy} locale={locale} />}
        {error && <p className="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800" role="alert">{copy.operationError}</p>}

        <div className="mt-6 flex justify-end gap-2">
          <button className="rounded-xl border border-[var(--border-strong)] px-4 py-2.5 text-sm font-semibold" type="button" onClick={onClose} disabled={busy}>{copy.cancel}</button>
          {dialog.kind === "create" && <button className="rounded-xl bg-[var(--button-primary)] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-45" type="button" disabled={busy || shipmentName.trim().length === 0} onClick={() => void onPrepareCreate()}>{busy ? copy.checking : copy.check}</button>}
          {dialog.kind === "add" && <button className="rounded-xl bg-[var(--button-primary)] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-45" type="button" disabled={busy || dialog.status !== "ready" || !selectedTargetSupply} onClick={() => void onPrepareAdd()}>{busy ? copy.checking : copy.check}</button>}
          {preview?.ready && <button className="rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-45" type="button" disabled={busy} onClick={() => void onExecute(preview)}>{busy ? copy.executing : actionLabel(preview.action, copy)}</button>}
        </div>
      </section>
    </div>
  );
}

function ignore() {}

function ignoreValue() {}

async function ignoreAsync() {}

export function PackingPreviewDialog({
  preview,
  busy,
  error,
  onClose,
  onExecute,
  copy = defaultPackingMutationCopy,
  locale = "ru-RU",
}: {
  preview: MutationPreview;
  busy: boolean;
  error: boolean;
  onClose: () => void;
  onExecute: (preview: MutationPreview) => Promise<void>;
  copy?: PackingMutationCopy;
  locale?: string;
}) {
  return (
    <PackingMutationDialog
      dialog={{ kind: "preview", preview }}
      shipmentName=""
      selectedTargetSupply=""
      targetSupplyQuery=""
      busy={busy}
      error={error}
      onShipmentName={ignoreValue}
      onTargetSupply={ignoreValue}
      onTargetSupplyQuery={ignoreValue}
      onSearchSupplies={ignore}
      onClose={onClose}
      onPrepareCreate={ignoreAsync}
      onPrepareAdd={ignoreAsync}
      onExecute={onExecute}
      copy={copy}
      locale={locale}
    />
  );
}

function SupplyChooser({ dialog, selected, query, onQuery, onSearch, onSelect, copy }: {
  dialog: Extract<MutationDialog, { kind: "add" }>;
  selected: string;
  query: string;
  onQuery: (value: string) => void;
  onSearch: () => void;
  onSelect: (value: string) => void;
  copy: PackingMutationCopy;
}) {
  return (
    <div className="mt-5 grid gap-3">
      <form className="flex gap-2" onSubmit={(event) => { event.preventDefault(); onSearch(); }} role="search">
        <input className="h-10 min-w-0 flex-1 rounded-xl border border-[var(--border-strong)] bg-transparent px-3 text-sm outline-none focus:border-[var(--accent)]" type="search" aria-label={copy.searchSupply} maxLength={120} value={query} onChange={(event) => onQuery(event.target.value)} placeholder={copy.searchPlaceholder} />
        <button className="rounded-xl border border-[var(--border-strong)] px-3 text-sm font-semibold" type="submit">{copy.search}</button>
      </form>
      <div className="grid max-h-60 gap-2 overflow-y-auto" aria-live="polite">
      {dialog.status === "loading" ? (
        <p className="text-sm text-[var(--text-secondary)]">{copy.loadingOpenSupplies}</p>
      ) : dialog.status === "error" ? (
        <p className="text-sm text-red-700">{copy.loadOpenSuppliesError}</p>
      ) : dialog.supplies.length === 0 ? (
        <p className="text-sm text-[var(--text-secondary)]">{copy.noOpenSupplies}</p>
      ) : dialog.supplies.map((supply) => (
        <label className="flex cursor-pointer items-center gap-3 rounded-xl border border-[var(--border-subtle)] p-3" key={supply.id}>
          <input type="radio" name="target-supply" value={supply.id} checked={selected === supply.id} onChange={() => onSelect(supply.id)} aria-label={`${supply.name} · ${supply.id}`} />
          <span className="min-w-0 text-sm"><strong className="block truncate">{supply.name}</strong><span className="font-mono text-xs text-[var(--text-muted)]">{supply.id}</span></span>
        </label>
      ))}
      </div>
    </div>
  );
}

function PreviewSummary({ preview, copy, locale }: { preview: MutationPreview; copy: PackingMutationCopy; locale: string }) {
  const numberFormat = new Intl.NumberFormat(locale);
  return (
    <div className="mt-5 grid gap-3">
      <div className="rounded-xl bg-[var(--surface-muted)] p-4 text-sm">
        <p><strong>{preview.supplyName || preview.supplyId}</strong></p>
        <p className="mt-1 text-[var(--text-secondary)]">{interpolate(copy.orderCount, { count: numberFormat.format(preview.itemCount) })}</p>
        {preview.kizCount > 0 && preview.action !== "deliver" && <p className="mt-1 font-semibold text-violet-800">{interpolate(copy.kizOrderCount, { count: numberFormat.format(preview.kizCount) })}</p>}
      </div>
      {preview.blockers.includes("labels_missing") && <p className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">{copy.labelsMissing}</p>}
      {preview.blockers.includes("kiz_missing") && <p className="rounded-xl border border-violet-200 bg-violet-50 p-3 text-sm text-violet-900">{copy.kizMissing}</p>}
      {preview.blockers.includes("supply_not_ready") && <p className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-900">{copy.supplyNotReady}</p>}
    </div>
  );
}

function dialogTitle(dialog: MutationDialog, preview: MutationPreview | null, copy: PackingMutationCopy) {
  if (dialog.kind === "create") return copy.titles.create;
  if (dialog.kind === "add") return copy.titles.add;
  if (!preview?.ready) return copy.titles.blocked;
  if (preview.action === "create") return copy.titles.confirmCreate;
  if (preview.action === "add") return copy.titles.confirmAdd;
  return copy.titles.confirmDeliver;
}

function actionLabel(action: string, copy: PackingMutationCopy) {
  if (action === "create") return copy.actions.create;
  if (action === "add") return copy.actions.add;
  return copy.actions.deliver;
}
