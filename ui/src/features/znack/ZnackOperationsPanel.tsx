import { AlertTriangle, CheckCircle2, Clock3, RefreshCw, RotateCcw, ScrollText, ShoppingCart, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { InfiniteLoadTrigger } from "../../components/InfiniteLoadTrigger";
import { useBoundedInfinitePages, type InfinitePagesStatus } from "../../components/useBoundedInfinitePages";
import { useModalFocus } from "../../components/useModalFocus";
import { commands } from "../../generated/commands";
import type { LogItem, LogsResponse, PurchaseItem, PurchasesResponse } from "../../generated/types";
import { interpolate } from "../../i18n";
import { getZnackLoadCopy, type ZnackCopy } from "./znackI18n";

type OperationsCopy = ZnackCopy["operations"];

const PAGE_SIZE = 50;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PURCHASE_STAGES = new Set([
  "validating", "creating_order", "polling_order", "downloading_codes",
  "waiting_introduction_readiness", "submitting_introduction", "polling_introduction",
  "introduction_failed", "introduction_skipped_missing_documents",
  "introduction_skipped_missing_metadata", "introduced", "completed", "failed",
]);
const PURCHASE_STATES = new Set(["running", "completed", "attention", "failed", "manual_review"]);
const ERROR_KINDS = new Set([
  "", "order_creation_ambiguous", "introduction_failed", "missing_documents", "missing_metadata",
  "authentication_failed", "rate_limited", "timeout", "certificate_unavailable", "upstream_error",
]);
const LOG_ACTIONS = new Set(["buy_kiz", "download_codes", "purchase_pipeline", "introduction", "product_sync", "operation"]);
const LOG_SEVERITIES = new Set(["info", "warning", "error"]);
const LOG_MESSAGES = new Set([
  "completed", "attention", "missing_documents", "missing_metadata", "authentication_failed",
  "rate_limited", "timeout", "upstream_error",
]);
const HTTP_CLASSES = new Set(["", "1xx", "2xx", "3xx", "4xx", "5xx"]);

function validPurchase(item: PurchaseItem) {
  return item !== null
    && UUID.test(item.purchaseId)
    && /^\d{14}$/.test(item.gtin)
    && typeof item.productName === "string"
    && item.productName.length <= 160
    && !/[\p{Cc}\p{Cf}]/u.test(item.productName)
    && item.quantity > 0
    && item.quantity <= 10_000
    && item.downloadedCodes >= 0
    && item.downloadedCodes <= item.quantity
    && item.progress >= 0
    && item.progress <= 100
    && PURCHASE_STAGES.has(item.stage)
    && PURCHASE_STATES.has(item.state)
    && ERROR_KINDS.has(item.errorKind)
    && typeof item.createdAt === "string"
    && typeof item.updatedAt === "string"
    && !Number.isNaN(Date.parse(item.createdAt))
    && !Number.isNaN(Date.parse(item.updatedAt));
}

function validPurchases(response: PurchasesResponse, shopId: number, page: number) {
  return response !== null
    && response.shopId === shopId
    && response.page === page
    && response.pageSize === PAGE_SIZE
    && Array.isArray(response.items)
    && response.items.length <= PAGE_SIZE
    && response.items.every(validPurchase)
    && new Set(response.items.map((item) => item.purchaseId)).size === response.items.length;
}

function statusCopy(copy: OperationsCopy, item: PurchaseItem) {
  if (item.stage === "introduction_failed") {
    return { title: copy.status.introductionAttention, tone: "status-warning" };
  }
  if (item.stage === "creating_order") {
    return { title: copy.status.manualReview, tone: "status-warning" };
  }
  if (item.stage === "introduction_skipped_missing_documents") {
    return { title: copy.status.documentsRequired, tone: "status-warning" };
  }
  if (item.stage === "introduction_skipped_missing_metadata") {
    return { title: copy.status.metadataRequired, tone: "status-warning" };
  }
  if (item.state === "completed") return { title: copy.status.completed, tone: "status-success" };
  if (item.state === "failed") return { title: copy.status.failed, tone: "status-danger" };
  return { title: copy.status.running, tone: "status-info" };
}

function stageCopy(copy: OperationsCopy, stage: string) {
  return copy.stages[stage as keyof OperationsCopy["stages"]] ?? copy.stages.fallback;
}

function errorCopy(copy: OperationsCopy, kind: string) {
  return copy.errors[kind as keyof OperationsCopy["errors"]] ?? "";
}

export function ZnackPurchasesPanel({
  copy,
  locale,
  shopId,
  settingsVersion,
  canMutate,
  refreshToken,
}: {
  copy: OperationsCopy;
  locale: string;
  shopId: number;
  settingsVersion: string;
  canMutate: boolean;
  refreshToken: number;
}) {
  const [retrying, setRetrying] = useState<PurchaseItem | null>(null);
  const [submittingRetry, setSubmittingRetry] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [purchaseUpdates, setPurchaseUpdates] = useState<{ resetKey: string | number; items: Map<string, PurchaseItem> }>(() => ({ resetKey: "", items: new Map() }));
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const dateFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" }), [locale]);

  const loadPage = useCallback(async (page: number) => {
    const response = await commands.znack.purchases({ shopId, page, pageSize: PAGE_SIZE });
    if (!validPurchases(response, shopId, page)) throw new Error("Unexpected Znack purchases response");
    return { items: response.items, hasMore: response.hasMore, summary: response };
  }, [shopId]);
  const pages = useBoundedInfinitePages<PurchaseItem, PurchasesResponse>({
    resetKey: JSON.stringify([shopId, refreshToken, reload]),
    loadPage,
    getId: (item) => item.purchaseId,
  });
  const visibleUpdates = Object.is(purchaseUpdates.resetKey, pages.resetKey)
    ? purchaseUpdates.items
    : new Map<string, PurchaseItem>();
  const items = pages.items.map((item) => visibleUpdates.get(item.purchaseId) ?? item);

  const activeIds = items.filter((item) => item.state === "running").map((item) => item.purchaseId).join(",");

  useEffect(() => {
    if (!activeIds) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = () => {
      timer = setTimeout(() => {
        const ids = activeIds.split(",");
        void Promise.all(ids.map((purchaseId) => commands.znack.purchaseStatus({ shopId, purchaseId }))).then(
          (items) => {
            if (!active || items.some((item) => !validPurchase(item))) return;
            const byId = new Map(items.map((item) => [item.purchaseId, item]));
            setPurchaseUpdates((current) => {
              const next = Object.is(current.resetKey, pages.resetKey) ? new Map(current.items) : new Map<string, PurchaseItem>();
              for (const [purchaseId, item] of byId) next.set(purchaseId, item);
              return { resetKey: pages.resetKey, items: next };
            });
            if (items.some((item) => item.state === "running")) poll();
          },
          () => {
            if (active) setError(copy.purchases.pollError);
          },
        );
      }, 300);
    };
    poll();
    return () => {
      active = false;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [activeIds, copy, pages.resetKey, shopId]);

  const confirmRetry = async () => {
    if (!retrying || !canMutate || submittingRetry) return;
    setSubmittingRetry(true);
    setError("");
    try {
      const updated = await commands.znack.retryIntroduction({
        shopId,
        purchaseId: retrying.purchaseId,
        version: settingsVersion,
        confirmed: true,
      });
      if (!validPurchase(updated) || updated.purchaseId !== retrying.purchaseId) throw new Error("Unexpected retry response");
      setPurchaseUpdates((current) => {
        const next = Object.is(current.resetKey, pages.resetKey) ? new Map(current.items) : new Map<string, PurchaseItem>();
        next.set(updated.purchaseId, updated);
        return { resetKey: pages.resetKey, items: next };
      });
      setRetrying(null);
    } catch {
      setError(copy.purchases.retryError);
    } finally {
      setSubmittingRetry(false);
    }
  };

  const reloadPage = () => {
    setError("");
    setReload((value) => value + 1);
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h4 className="text-base font-semibold">{copy.purchases.title}</h4>
          <p className="mt-1 text-xs leading-5 text-[var(--text-muted)]">{copy.purchases.description}</p>
        </div>
        <button className="secondary-button" type="button" onClick={reloadPage} aria-label={copy.purchases.refreshAria}>
          <RefreshCw aria-hidden="true" size={16} /> {copy.purchases.refresh}
        </button>
      </div>
      {error ? <div className="notice-error" role="alert"><span>{error}</span><button type="button" onClick={reloadPage}>{copy.common.retry}</button></div> : null}
      {pages.status === "loading" && items.length === 0 ? <PanelState copy={copy} loading label={copy.purchases.loading} /> : null}
      {pages.status === "error" && items.length === 0 ? <PanelState copy={copy} label={copy.purchases.loadError} onRetry={pages.retry} /> : null}
      {pages.status === "ready" && items.length === 0 ? (
        <div className="grid min-h-64 place-items-center rounded-xl border border-dashed border-[var(--border-subtle)] bg-[var(--surface-muted)] p-8 text-center">
          <div><ShoppingCart aria-hidden="true" className="mx-auto text-[var(--text-muted)]" size={28} /><h5 className="mt-3 font-semibold">{copy.purchases.empty}</h5><p className="mt-1 text-sm text-[var(--text-muted)]">{copy.purchases.emptyHint}</p></div>
        </div>
      ) : null}
      {items.length > 0 ? (
        <ul className="space-y-3">
          {items.map((item) => {
            const status = statusCopy(copy, item);
            const detail = errorCopy(copy, item.errorKind);
            return (
              <li key={item.purchaseId} className="rounded-xl [content-visibility:auto] [contain-intrinsic-size:auto_11rem] border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3">
                <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className={`status-pill ${status.tone}`}>{status.title}</span>
                      <code className="text-xs font-semibold">{item.gtin}</code>
                    </div>
                    <p className="mt-2 truncate text-sm font-semibold">{item.productName || copy.purchases.unnamed}</p>
                    <p className="mt-1 text-xs text-[var(--text-muted)]">{stageCopy(copy, item.stage)} · {interpolate(copy.purchases.ordered, { count: numberFormat.format(item.quantity) })}</p>
                  </div>
                  <div className="flex shrink-0 flex-col items-start gap-2 lg:items-end">
                    <p className="text-xs font-medium text-[var(--text-secondary)]">{interpolate(copy.purchases.downloaded, { downloaded: numberFormat.format(item.downloadedCodes), total: numberFormat.format(item.quantity) })}</p>
                    {item.canRetryIntroduction ? (
                      <button className="secondary-button" type="button" disabled={!canMutate} onClick={() => setRetrying(item)} aria-label={interpolate(copy.purchases.retryAria, { gtin: item.gtin })}>
                        <RotateCcw aria-hidden="true" size={15} /> {copy.purchases.retryOnly}
                      </button>
                    ) : null}
                  </div>
                </div>
                <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-[var(--surface-muted)]" aria-label={interpolate(copy.purchases.progress, { percent: numberFormat.format(item.progress) })} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={item.progress}>
                  <div className="h-full rounded-full bg-[var(--accent)] transition-[width]" style={{ width: `${item.progress}%` }} />
                </div>
                {detail ? <p className="mt-3 flex items-start gap-2 text-xs leading-5 text-[var(--text-secondary)]"><AlertTriangle aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--warning)]" size={14} />{detail}</p> : null}
                <p className="mt-3 flex items-center gap-1.5 text-[11px] text-[var(--text-muted)]"><Clock3 aria-hidden="true" size={13} />{interpolate(copy.purchases.updated, { date: dateFormat.format(new Date(item.updatedAt)) })}</p>
              </li>
            );
          })}
        </ul>
      ) : null}
      {items.length > 0 ? <OperationLoadTrigger copy={copy} locale={locale} status={pages.status} hasMore={pages.hasMore} addedCount={pages.addedCount} numberFormat={numberFormat} onLoadMore={pages.loadMore} onRetry={pages.retry} /> : null}
      {retrying ? <RetryIntroductionDialog copy={copy} gtin={retrying.gtin} busy={submittingRetry} canConfirm={canMutate} onClose={() => setRetrying(null)} onConfirm={() => void confirmRetry()} /> : null}
    </div>
  );
}

function RetryIntroductionDialog({ copy, gtin, busy, canConfirm, onClose, onConfirm }: { copy: OperationsCopy; gtin: string; busy: boolean; canConfirm: boolean; onClose: () => void; onConfirm: () => void }) {
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLDivElement>(busy, onClose);
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/45 p-4" role="dialog" aria-modal="true" aria-labelledby="retry-introduction-title">
      <div ref={dialogRef} className="w-full max-w-lg rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl">
        <div className="flex items-start justify-between gap-4"><div><h4 id="retry-introduction-title" className="text-lg font-semibold">{copy.purchases.confirmTitle}</h4><p className="mt-1 text-sm text-[var(--text-muted)]">GTIN {gtin}</p></div><button ref={initialFocusRef} className="icon-button" type="button" aria-label={copy.purchases.closeConfirm} disabled={busy} onClick={onClose}><X aria-hidden="true" size={18} /></button></div>
        <p className="mt-4 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-3 text-sm leading-6"><strong className="block">{copy.purchases.alreadyBought}</strong><span className="text-[var(--text-secondary)]">{copy.purchases.onlyIntroduction}</span></p>
        <div className="mt-5 flex justify-end gap-2"><button className="secondary-button" type="button" disabled={busy} onClick={onClose}>{copy.purchases.cancel}</button><button className="primary-button" type="button" disabled={!canConfirm || busy} onClick={onConfirm}>{busy ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} /> : <CheckCircle2 aria-hidden="true" size={16} />}{busy ? copy.purchases.starting : copy.purchases.confirm}</button></div>
      </div>
    </div>
  );
}

function validLogs(response: LogsResponse, shopId: number, page: number) {
  return response !== null && response.shopId === shopId && response.page === page && response.pageSize === PAGE_SIZE
    && Array.isArray(response.items) && response.items.length <= PAGE_SIZE && response.items.every((item) =>
      item !== null
      && LOG_ACTIONS.has(item.action)
      && LOG_SEVERITIES.has(item.severity)
      && LOG_MESSAGES.has(item.messageKind)
      && HTTP_CLASSES.has(item.httpClass)
      && (item.entityGtin === "" || /^\d{14}$/.test(item.entityGtin))
      && typeof item.createdAt === "string"
      && !Number.isNaN(Date.parse(item.createdAt)));
}

export function ZnackLogsPanel({ copy, locale, shopId }: { copy: OperationsCopy; locale: string; shopId: number }) {
  const [reload, setReload] = useState(0);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const dateFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" }), [locale]);
  const loadPage = useCallback(async (page: number) => {
    const response = await commands.znack.operationLogs({ shopId, page, pageSize: PAGE_SIZE });
    if (!validLogs(response, shopId, page)) throw new Error("Unexpected Znack logs response");
    return { items: response.items, hasMore: response.hasMore, summary: response };
  }, [shopId]);
  const pages = useBoundedInfinitePages<LogItem, LogsResponse>({
    resetKey: JSON.stringify([shopId, reload]),
    loadPage,
    getId: logItemId,
  });
  const reloadPage = () => {
    setReload((value) => value + 1);
  };
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-base font-semibold">{copy.logs.title}</h4>
          <p className="mt-1 text-xs text-[var(--text-muted)]">{copy.logs.description}</p>
        </div>
        <button className="secondary-button" type="button" onClick={reloadPage} aria-label={copy.logs.refreshAria}>
          <RefreshCw aria-hidden="true" size={16} />{copy.logs.refresh}
        </button>
      </div>
      {pages.status === "loading" && pages.items.length === 0 ? <PanelState copy={copy} loading label={copy.logs.loading} /> : null}
      {pages.status === "error" && pages.items.length === 0 ? <PanelState copy={copy} label={copy.logs.loadError} onRetry={pages.retry} /> : null}
      {pages.status === "ready" && pages.items.length === 0 ? <div className="grid min-h-64 place-items-center rounded-xl border border-dashed border-[var(--border-subtle)] bg-[var(--surface-muted)] text-center"><div><ScrollText aria-hidden="true" className="mx-auto text-[var(--text-muted)]" size={28} /><h5 className="mt-3 font-semibold">{copy.logs.empty}</h5></div></div> : null}
      {pages.items.length > 0 ? <ul className="divide-y divide-[var(--border-subtle)] overflow-hidden rounded-xl border border-[var(--border-subtle)]">{pages.items.map((item: LogItem) => <li key={logItemId(item)} className="grid [content-visibility:auto] [contain-intrinsic-size:auto_6rem] gap-3 bg-[var(--surface-elevated)] px-3 py-3 md:grid-cols-[minmax(10rem,.7fr)_minmax(12rem,1fr)_auto] md:items-center"><div><p className="text-sm font-semibold">{copy.logs.actions[item.action as keyof OperationsCopy["logs"]["actions"]] ?? copy.logs.fallbackAction}</p><p className="mt-1 text-xs text-[var(--text-muted)]">{item.entityGtin || copy.logs.system}</p></div><div><p className="text-sm text-[var(--text-secondary)]">{copy.logs.messages[item.messageKind as keyof OperationsCopy["logs"]["messages"]] ?? copy.logs.fallbackMessage}</p><p className="mt-1 text-xs text-[var(--text-muted)]">{dateFormat.format(new Date(item.createdAt))}</p></div><div className="flex items-center gap-2"><span className={`status-pill ${item.severity === "error" ? "status-danger" : item.severity === "warning" ? "status-warning" : "status-success"}`}>{item.severity === "error" ? copy.logs.severityError : item.severity === "warning" ? copy.logs.severityWarning : copy.logs.severityInfo}</span></div></li>)}</ul> : null}
      {pages.items.length > 0 ? <OperationLoadTrigger copy={copy} locale={locale} status={pages.status} hasMore={pages.hasMore} addedCount={pages.addedCount} numberFormat={numberFormat} onLoadMore={pages.loadMore} onRetry={pages.retry} /> : null}
    </div>
  );
}

function OperationLoadTrigger({ copy, locale, status, hasMore, addedCount, numberFormat, onLoadMore, onRetry }: { copy: OperationsCopy; locale: string; status: InfinitePagesStatus; hasMore: boolean; addedCount: number; numberFormat: Intl.NumberFormat; onLoadMore: () => void; onRetry: () => void }) {
  const labels = getZnackLoadCopy(locale);
  return <InfiniteLoadTrigger status={status} hasMore={hasMore} copy={{ ...labels, retry: copy.common.retry }} announcement={addedCount > 0 ? interpolate(labels.added, { count: numberFormat.format(addedCount) }) : ""} onLoadMore={onLoadMore} onRetry={onRetry} />;
}

function logItemId(item: LogItem) {
  return [item.createdAt, item.action, item.entityGtin, item.severity, item.messageKind, item.httpClass].join(":");
}

function PanelState({ copy, loading = false, label, onRetry }: { copy: OperationsCopy; loading?: boolean; label: string; onRetry?: () => void }) {
  return <div className="grid min-h-56 place-items-center text-center" role={loading ? "status" : "alert"} aria-label={label}><div>{loading ? <RefreshCw aria-hidden="true" className="mx-auto animate-spin text-[var(--accent)]" size={24} /> : <><p className="font-semibold">{label}</p><button className="secondary-button mt-4" type="button" onClick={onRetry}><RefreshCw aria-hidden="true" size={16} />{copy.common.retry}</button></>}</div></div>;
}
