import { AlertTriangle, CheckCircle2, KeyRound, Pencil, Plus, Store, Trash2, X } from "lucide-react";
import { JDeskError } from "jdesk-client";
import { useState } from "react";
import { useModalFocus } from "../../components/useModalFocus";
import { commands } from "../../generated/commands";
import type { ManagedShopSummary, ShopState } from "../../generated/types";
import { interpolate } from "../../i18n";
import type { AppCopy } from "../../i18n";
import { validShopState } from "./shopState";

const MAX_SHOPS = 500;
const MAX_NAME = 120;
const MAX_TOKEN = 16 * 1024;
const ERROR_KINDS = new Set(["invalid_name", "invalid_token", "shop_busy"]);

type ShopItem = Pick<ManagedShopSummary, "id" | "name" | "tokenConfigured">;
type Mode =
  | { kind: "list" }
  | { kind: "create" }
  | { kind: "edit"; shop: ShopItem }
  | { kind: "delete"; shop: ShopItem };

function validName(value: unknown): value is string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > MAX_NAME) return false;
  for (const character of value) {
    const point = character.codePointAt(0);
    if (point !== undefined && (point < 32 || (point >= 127 && point <= 159))) return false;
  }
  return true;
}

function errorMessage(error: unknown, copy: AppCopy["shop"]) {
  if (!(error instanceof JDeskError) || typeof error.data !== "object" || error.data === null) {
    return copy.errors.unavailable;
  }
  const kind = (error.data as { kind?: unknown }).kind;
  if (typeof kind !== "string" || !ERROR_KINDS.has(kind)) return copy.errors.unavailable;
  if (kind === "shop_busy") return copy.errors.busy;
  return copy.errors.invalid;
}

export function ShopManagementDialog({
  shops,
  selectedId,
  onClose,
  onState,
  copy,
}: {
  shops: ShopItem[];
  selectedId: number | null;
  onClose: () => void;
  onState: (state: ShopState) => void;
  copy: AppCopy["shop"];
}) {
  const [mode, setMode] = useState<Mode>({ kind: "list" });
  const [name, setName] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [dismissed, setDismissed] = useState(false);

  const close = () => {
    setApiKey("");
    setDismissed(true);
    onClose();
  };
  const escape = () => {
    if (mode.kind === "list") {
      close();
    } else {
      setApiKey("");
      setError("");
      setMode({ kind: "list" });
    }
  };
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLDivElement>(busy, escape);

  const beginCreate = () => {
    setName("");
    setApiKey("");
    setError("");
    setMode({ kind: "create" });
  };

  const beginEdit = (shop: ShopItem) => {
    setName(shop.name);
    setApiKey("");
    setError("");
    setMode({ kind: "edit", shop });
  };

  const back = () => {
    setApiKey("");
    setError("");
    setMode({ kind: "list" });
  };

  const accept = (response: unknown) => {
    if (!validShopState(response)) throw new Error("Invalid shop response");
    setApiKey("");
    onState(response);
    setDismissed(true);
    onClose();
  };

  const save = async () => {
    if (busy) return;
    const normalizedName = name.trim();
    const normalizedToken = apiKey.trim();
    if (!validName(normalizedName)
      || normalizedToken.length > MAX_TOKEN
      || (mode.kind === "create" && normalizedToken.length === 0)) {
      setError(copy.errors.invalid);
      return;
    }
    setBusy(true);
    setError("");
    try {
      const response = mode.kind === "create"
        ? await commands.shops.create({ name: normalizedName, apiKey: normalizedToken })
        : mode.kind === "edit"
          ? await commands.shops.update({ shopId: mode.shop.id, name: normalizedName, apiKey: normalizedToken })
          : null;
      accept(response);
    } catch (failure) {
      setApiKey("");
      setError(errorMessage(failure, copy));
    } finally {
      setBusy(false);
    }
  };

  const deleteShop = async () => {
    if (busy || mode.kind !== "delete") return;
    setBusy(true);
    setError("");
    try {
      accept(await commands.shops.delete({ shopId: mode.shop.id, confirmed: true }));
    } catch (failure) {
      setError(errorMessage(failure, copy));
    } finally {
      setBusy(false);
    }
  };

  if (dismissed) return null;
  const form = mode.kind === "create" || mode.kind === "edit";

  return (
    <div
      ref={dialogRef}
      className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="shop-dialog-title"
    >
      <div className="max-h-[min(46rem,calc(100vh-2rem))] w-full max-w-2xl overflow-y-auto rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-popover)]">
        <header className="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-5 py-4">
          <div>
            <p className="text-xs font-semibold tracking-[0.12em] text-[var(--accent-strong)] uppercase">WCode</p>
            <h2 id="shop-dialog-title" className="mt-1 text-xl font-semibold">{copy.dialogTitle}</h2>
            <p className="mt-1 text-sm text-[var(--text-muted)]">{copy.dialogDescription}</p>
          </div>
          <button ref={initialFocusRef} className="icon-button shrink-0" type="button" aria-label={copy.close} disabled={busy} onClick={close}>
            <X aria-hidden="true" size={19} />
          </button>
        </header>

        <div className="p-5">
          {mode.kind === "list" ? (
            <>
              <div className="mb-4 flex items-center justify-between gap-3">
                <p className="text-sm text-[var(--text-secondary)]">{shops.length} / {MAX_SHOPS}</p>
                <button className="primary-button" type="button" onClick={beginCreate}>
                  <Plus aria-hidden="true" size={16} /> {copy.add}
                </button>
              </div>
              {shops.length === 0 ? (
                <div className="grid min-h-44 place-items-center rounded-xl border border-dashed border-[var(--border-strong)] text-center">
                  <div><Store className="mx-auto text-[var(--text-muted)]" aria-hidden="true" /><p className="mt-3 text-sm text-[var(--text-secondary)]">{copy.empty}</p></div>
                </div>
              ) : (
                <ul className="grid gap-2" aria-label={copy.label}>
                  {shops.map((shop) => (
                    <li key={shop.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-3">
                      <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]"><Store aria-hidden="true" size={18} /></div>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <p className="truncate text-sm font-semibold">{shop.name}</p>
                          {shop.id === selectedId ? <span className="rounded-full bg-[var(--accent-soft)] px-2 py-0.5 text-[0.68rem] font-semibold text-[var(--accent-strong)]">{copy.selected}</span> : null}
                        </div>
                        <p className="mt-1 inline-flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
                          {shop.tokenConfigured ? <CheckCircle2 aria-hidden="true" size={13} /> : <KeyRound aria-hidden="true" size={13} />}
                          {shop.tokenConfigured ? copy.tokenConfigured : copy.tokenMissing}
                        </p>
                      </div>
                      <button className="secondary-button" type="button" aria-label={copy.edit} onClick={() => beginEdit(shop)}><Pencil aria-hidden="true" size={15} /><span className="hidden sm:inline">{copy.edit}</span></button>
                      <button className="danger-button" type="button" aria-label={copy.remove} onClick={() => { setError(""); setMode({ kind: "delete", shop }); }}><Trash2 aria-hidden="true" size={15} /><span className="hidden sm:inline">{copy.remove}</span></button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          ) : form ? (
            <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
              <button className="secondary-button mb-5" type="button" disabled={busy} onClick={back}>{copy.back}</button>
              <div className="grid gap-5 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
                <label className="field-label" htmlFor="shop-name">
                  <span>{copy.name}</span>
                  <input id="shop-name" className="text-input" value={name} maxLength={MAX_NAME} autoComplete="off" disabled={busy} onChange={(event) => setName(event.target.value)} />
                </label>
                <div className="field-label">
                  <label htmlFor="shop-api-key">{copy.token}</label>
                  <input id="shop-api-key" aria-describedby="shop-api-key-hint" className="text-input" type="password" value={apiKey} maxLength={MAX_TOKEN} autoComplete="new-password" disabled={busy} onChange={(event) => setApiKey(event.target.value)} />
                  <span id="shop-api-key-hint" className="font-normal leading-5 text-[var(--text-muted)]">{mode.kind === "create" ? copy.tokenCreateHint : copy.tokenEditHint}</span>
                </div>
              </div>
              {error ? <p className="notice-error mt-4" role="alert">{error}</p> : null}
              <button className="primary-button mt-5 w-full" type="submit" disabled={busy}>
                {busy ? copy.saving : mode.kind === "create" ? copy.saveCreate : copy.saveEdit}
              </button>
            </form>
          ) : (
            <div>
              <div className="rounded-xl border border-[var(--danger)] bg-[var(--danger-soft)] p-5">
                <AlertTriangle className="text-[var(--danger)]" aria-hidden="true" size={24} />
                <h3 className="mt-3 text-lg font-semibold">{interpolate(copy.deleteTitle, { name: mode.shop.name })}</h3>
                <p className="mt-2 text-sm leading-6 text-[var(--text-secondary)]">{copy.deleteDescription}</p>
              </div>
              {error ? <p className="notice-error mt-4" role="alert">{error}</p> : null}
              <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                <button className="secondary-button" type="button" disabled={busy} onClick={back}>{copy.back}</button>
                <button className="danger-button" type="button" disabled={busy} onClick={() => void deleteShop()}><Trash2 aria-hidden="true" size={15} />{copy.deleteConfirm}</button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
