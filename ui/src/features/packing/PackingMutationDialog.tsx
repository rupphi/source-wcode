import { useEffect, useRef } from "react";
import type { MutationPreview, PackingSupplyItem } from "../../generated/types";

export type MutationDialog =
  | { kind: "create" }
  | { kind: "add"; status: "loading" | "ready" | "error"; supplies: PackingSupplyItem[] }
  | { kind: "preview"; preview: MutationPreview };

const numberFormat = new Intl.NumberFormat("ru-RU");

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
}) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const behavior = useRef({ busy, onClose });
  useEffect(() => {
    behavior.current = { busy, onClose };
  }, [busy, onClose]);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !behavior.current.busy) behavior.current.onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      previous?.focus();
    };
  }, []);

  const preview = dialog.kind === "preview" ? dialog.preview : null;
  const title = dialogTitle(dialog, preview);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="w-full max-w-lg rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl" role="dialog" aria-modal="true" aria-labelledby="packing-mutation-title">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold" id="packing-mutation-title">{title}</h2>
            <p className="mt-1 text-sm text-[var(--text-secondary)]">Wildberries изменится только после финального подтверждения.</p>
          </div>
          <button ref={closeButton} className="rounded-lg px-2 py-1 text-sm text-[var(--text-muted)]" type="button" onClick={onClose} disabled={busy} aria-label="Закрыть">×</button>
        </div>

        {dialog.kind === "create" && (
          <label className="mt-5 grid gap-2 text-sm font-semibold">
            Название поставки
            <input className="h-11 rounded-xl border border-[var(--border-strong)] bg-transparent px-3 font-normal outline-none focus:border-[var(--accent)]" maxLength={160} value={shipmentName} onChange={(event) => onShipmentName(event.target.value)} />
          </label>
        )}

        {dialog.kind === "add" && <SupplyChooser dialog={dialog} selected={selectedTargetSupply} query={targetSupplyQuery} onQuery={onTargetSupplyQuery} onSearch={onSearchSupplies} onSelect={onTargetSupply} />}
        {preview && <PreviewSummary preview={preview} />}
        {error && <p className="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800" role="alert">Операция не выполнена. Обновите данные и повторите проверку.</p>}

        <div className="mt-6 flex justify-end gap-2">
          <button className="rounded-xl border border-[var(--border-strong)] px-4 py-2.5 text-sm font-semibold" type="button" onClick={onClose} disabled={busy}>Отмена</button>
          {dialog.kind === "create" && <button className="rounded-xl bg-[var(--button-primary)] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-45" type="button" disabled={busy || shipmentName.trim().length === 0} onClick={() => void onPrepareCreate()}>{busy ? "Проверка…" : "Проверить"}</button>}
          {dialog.kind === "add" && <button className="rounded-xl bg-[var(--button-primary)] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-45" type="button" disabled={busy || dialog.status !== "ready" || !selectedTargetSupply} onClick={() => void onPrepareAdd()}>{busy ? "Проверка…" : "Проверить"}</button>}
          {preview?.ready && <button className="rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-45" type="button" disabled={busy} onClick={() => void onExecute(preview)}>{busy ? "Выполнение…" : actionLabel(preview.action)}</button>}
        </div>
      </section>
    </div>
  );
}

function SupplyChooser({ dialog, selected, query, onQuery, onSearch, onSelect }: {
  dialog: Extract<MutationDialog, { kind: "add" }>;
  selected: string;
  query: string;
  onQuery: (value: string) => void;
  onSearch: () => void;
  onSelect: (value: string) => void;
}) {
  return (
    <div className="mt-5 grid gap-3">
      <form className="flex gap-2" onSubmit={(event) => { event.preventDefault(); onSearch(); }} role="search">
        <input className="h-10 min-w-0 flex-1 rounded-xl border border-[var(--border-strong)] bg-transparent px-3 text-sm outline-none focus:border-[var(--accent)]" type="search" aria-label="Поиск поставки" maxLength={120} value={query} onChange={(event) => onQuery(event.target.value)} placeholder="ID или название" />
        <button className="rounded-xl border border-[var(--border-strong)] px-3 text-sm font-semibold" type="submit">Найти</button>
      </form>
      <div className="grid max-h-60 gap-2 overflow-y-auto" aria-live="polite">
      {dialog.status === "loading" ? (
        <p className="text-sm text-[var(--text-secondary)]">Загрузка открытых поставок…</p>
      ) : dialog.status === "error" ? (
        <p className="text-sm text-red-700">Не удалось загрузить открытые поставки.</p>
      ) : dialog.supplies.length === 0 ? (
        <p className="text-sm text-[var(--text-secondary)]">Открытых поставок нет.</p>
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

function PreviewSummary({ preview }: { preview: MutationPreview }) {
  return (
    <div className="mt-5 grid gap-3">
      <div className="rounded-xl bg-[var(--surface-muted)] p-4 text-sm">
        <p><strong>{preview.supplyName || preview.supplyId}</strong></p>
        <p className="mt-1 text-[var(--text-secondary)]">Заказов: {numberFormat.format(preview.itemCount)}</p>
        {preview.kizCount > 0 && preview.action !== "deliver" && <p className="mt-1 font-semibold text-violet-800">{numberFormat.format(preview.kizCount)} заказ требует KIZ</p>}
      </div>
      {preview.blockers.includes("labels_missing") && <p className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">Сначала распечатайте этикетки поставки</p>}
      {preview.blockers.includes("kiz_missing") && <p className="rounded-xl border border-violet-200 bg-violet-50 p-3 text-sm text-violet-900">Не все обязательные KIZ прикреплены</p>}
      {preview.blockers.includes("supply_not_ready") && <p className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-900">Поставка пуста, закрыта или уже передана</p>}
    </div>
  );
}

function dialogTitle(dialog: MutationDialog, preview: MutationPreview | null) {
  if (dialog.kind === "create") return "Новая поставка";
  if (dialog.kind === "add") return "Выберите поставку";
  if (!preview?.ready) return "Поставка не готова к передаче";
  if (preview.action === "create") return "Подтвердить создание поставки";
  if (preview.action === "add") return "Подтвердить добавление заказов";
  return "Подтвердить передачу поставки";
}

function actionLabel(action: string) {
  if (action === "create") return "Создать в Wildberries";
  if (action === "add") return "Добавить заказы";
  return "Передать в доставку";
}
