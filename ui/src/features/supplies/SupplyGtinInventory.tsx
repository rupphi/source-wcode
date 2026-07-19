import { AlertCircle, ChevronLeft, ChevronRight, PackageCheck, Search, ShoppingCart } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type { CatalogResponse, GtinItem, SettingsResponse } from "../../generated/types";
import { interpolate } from "../../i18n";
import { matchesCatalogResponse } from "../kizmapping/kizCatalogContract";
import { ZnackPurchaseDialog } from "../znack/ZnackPurchaseDialog";
import { defaultSupplyCopy, type SupplyCopy } from "./supplyI18n";

type CatalogState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | { status: "ready"; requestKey: string; data: CatalogResponse };
type SettingsState =
  | { status: "loading" | "error" }
  | { status: "ready"; data: SettingsResponse };

const PAGE_SIZE = 10;
const TERMINAL_STAGES = new Set([
  "", "COMPLETED", "INTRODUCED", "FAILED", "INTRODUCTION_FAILED",
  "INTRODUCTION_SKIPPED_MISSING_DOCUMENTS", "INTRODUCTION_SKIPPED_MISSING_METADATA",
]);

export function SupplyGtinInventory({ shopId, licenseAllowed, copy = defaultSupplyCopy, locale = "ru-RU" }: { shopId: number; licenseAllowed: boolean; copy?: SupplyCopy; locale?: string }) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [retryKey, setRetryKey] = useState(0);
  const [settingsRetry, setSettingsRetry] = useState(0);
  const [catalog, setCatalog] = useState<CatalogState>({ status: "loading", requestKey: "" });
  const [settings, setSettings] = useState<SettingsState>({ status: "loading" });
  const [purchaseTarget, setPurchaseTarget] = useState<GtinItem | null>(null);
  const [notice, setNotice] = useState("");
  const catalogRequest = useRef(0);
  const settingsRequest = useRef(0);
  const requestKey = JSON.stringify([shopId, query, page, retryKey]);
  const localizedNumberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);

  useEffect(() => {
    const requestId = ++catalogRequest.current;
    let active = true;
    void commands.kizMapping.catalog({ shopId, query, categories: [], page, pageSize: PAGE_SIZE }).then(
      (response) => {
        if (!active || requestId !== catalogRequest.current) return;
        setCatalog(matchesCatalogResponse(response, shopId, query, [], page, PAGE_SIZE)
          ? { status: "ready", requestKey, data: response }
          : { status: "error", requestKey });
      },
      () => {
        if (active && requestId === catalogRequest.current) setCatalog({ status: "error", requestKey });
      },
    );
    return () => { active = false; };
  }, [page, query, requestKey, shopId]);

  useEffect(() => {
    const requestId = ++settingsRequest.current;
    let active = true;
    void commands.znack.settings({ shopId }).then(
      (response) => {
        if (!active || requestId !== settingsRequest.current) return;
        setSettings(validSettings(response, shopId) ? { status: "ready", data: response } : { status: "error" });
      },
      () => {
        if (active && requestId === settingsRequest.current) setSettings({ status: "error" });
      },
    );
    return () => { active = false; };
  }, [settingsRetry, shopId]);

  const visibleCatalog: CatalogState = catalog.requestKey === requestKey
    ? catalog
    : { status: "loading", requestKey };
  const canPurchase = licenseAllowed && settings.status === "ready" && settings.data.signatureStatus === "VERIFIED";

  const submitSearch = (event: FormEvent) => {
    event.preventDefault();
    const next = draftQuery.trim();
    setPage(1);
    if (next === query) setRetryKey((value) => value + 1);
    else setQuery(next);
  };

  return (
    <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5" aria-labelledby="supply-gtin-title">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h4 id="supply-gtin-title" className="font-semibold">{copy.inventory.title}</h4>
          <p className="mt-1 text-xs text-[var(--text-muted)]">{copy.inventory.description}</p>
        </div>
        <form className="flex gap-2" role="search" onSubmit={submitSearch}>
          <label className="relative min-w-0 flex-1 sm:w-72">
            <span className="sr-only">{copy.inventory.searchLabel}</span>
            <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--text-muted)]" size={16} />
            <input className="text-input w-full pl-9" type="search" aria-label={copy.inventory.searchLabel} maxLength={120} value={draftQuery} onChange={(event) => setDraftQuery(event.target.value)} placeholder={copy.inventory.searchPlaceholder} />
          </label>
          <button className="secondary-button" type="submit">{copy.inventory.search}</button>
        </form>
      </div>

      {notice ? <p className="notice-success mt-4" role="status">{notice}</p> : null}
      {!licenseAllowed ? <p className="mt-4 text-xs text-[var(--warning)]">{copy.inventory.licenseRequired}</p> : settings.status === "error" ? <div className="notice-error mt-4" role="alert"><span>{copy.inventory.settingsError}</span><button type="button" onClick={() => { setSettings({ status: "loading" }); setSettingsRetry((value) => value + 1); }}>{copy.inventory.retry}</button></div> : null}
      {visibleCatalog.status === "loading" ? <p className="mt-5 text-sm text-[var(--text-muted)]" role="status">{copy.inventory.loading}</p> : null}
      {visibleCatalog.status === "error" ? <div className="notice-error mt-5" role="alert"><AlertCircle aria-hidden="true" size={16} /><span>{copy.inventory.loadError}</span><button type="button" onClick={() => setRetryKey((value) => value + 1)}>{copy.inventory.retry}</button></div> : null}
      {visibleCatalog.status === "ready" && visibleCatalog.data.items.length === 0 ? <p className="mt-5 rounded-xl border border-dashed border-[var(--border-subtle)] p-5 text-center text-sm text-[var(--text-muted)]">{copy.inventory.empty}</p> : null}
      {visibleCatalog.status === "ready" && visibleCatalog.data.items.length > 0 ? (
        <>
          <ul className="mt-5 divide-y divide-[var(--border-subtle)] overflow-hidden rounded-xl border border-[var(--border-subtle)]">
            {visibleCatalog.data.items.map((item) => (
              <li className="grid gap-3 px-4 py-4 sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:items-center" key={item.gtin}>
                <div className="min-w-0"><code className="text-xs font-semibold">{item.gtin}</code><p className="mt-1 truncate text-sm font-semibold">{item.productName || copy.inventory.unnamed}</p><p className="mt-1 text-xs text-[var(--text-muted)]">{item.category || copy.inventory.uncategorized} · {interpolate(copy.inventory.rules, { count: localizedNumberFormat.format(item.mappingRuleCount) })}</p></div>
                <span className="inline-flex w-fit items-center gap-2 rounded-full bg-[var(--accent-soft)] px-3 py-1.5 text-xs font-semibold text-[var(--accent-strong)]"><PackageCheck aria-hidden="true" size={14} />{interpolate(copy.inventory.available, { count: localizedNumberFormat.format(item.available) })}</span>
                <button className="secondary-button justify-center" type="button" disabled={!canPurchase || item.gtin.startsWith("029") || !TERMINAL_STAGES.has(item.pipelineStage)} onClick={() => setPurchaseTarget(item)} aria-label={interpolate(copy.inventory.buyLabel, { gtin: item.gtin })}><ShoppingCart aria-hidden="true" size={15} />{copy.inventory.buy}</button>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex items-center justify-between gap-3">
            <button className="secondary-button" type="button" disabled={page <= 1} onClick={() => setPage((value) => Math.max(1, value - 1))} aria-label={copy.inventory.previousPage}><ChevronLeft aria-hidden="true" size={16} />{copy.inventory.back}</button>
            <span className="text-sm text-[var(--text-secondary)]">{interpolate(copy.inventory.page, { page })}</span>
            <button className="secondary-button" type="button" disabled={!visibleCatalog.data.hasMore || page >= 100_000} onClick={() => setPage((value) => Math.min(100_000, value + 1))} aria-label={copy.inventory.nextPage}>{copy.inventory.next}<ChevronRight aria-hidden="true" size={16} /></button>
          </div>
        </>
      ) : null}

      {purchaseTarget && settings.status === "ready" ? (
        <ZnackPurchaseDialog
          shopId={shopId}
          product={purchaseTarget}
          settingsVersion={settings.data.version}
          canPurchase={canPurchase}
          copy={copy.purchase}
          onClose={() => setPurchaseTarget(null)}
          onStarted={() => {
            setPurchaseTarget(null);
            setNotice(interpolate(copy.inventory.started, { gtin: purchaseTarget.gtin }));
            setRetryKey((value) => value + 1);
          }}
        />
      ) : null}
    </section>
  );
}

function validSettings(response: SettingsResponse, shopId: number) {
  return response !== null
    && response.shopId === shopId
    && typeof response.version === "string"
    && /^[0-9a-f]{64}$/.test(response.version)
    && typeof response.signatureStatus === "string"
    && ["UNCONFIGURED", "NOT_VERIFIED", "VERIFIED", "EXPIRED"].includes(response.signatureStatus);
}
