import { AlertCircle, ArrowLeft, Check, PackageOpen, RefreshCw, Search, SlidersHorizontal, Truck } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type { MutationPreview, OrderSortRequest, SupplyDetailResponse, SupplyItem } from "../../generated/types";
import { interpolate } from "../../i18n";
import { PackingPreviewDialog } from "../packing/PackingMutationDialog";
import { isValidMutationPreview, isValidMutationReceipt } from "../packing/packingMutationContract";
import { PrintSetupDialog } from "../printing/PrintSetupDialog";
import { OrderTable, OrderTableLoading } from "./OrderTable";
import { ExcelImportPanel } from "./ExcelImportPanel";
import { Pagination } from "./SupplyTable";
import { SupplyGtinInventory } from "./SupplyGtinInventory";
import { defaultSupplyCopy, type SupplyCopy } from "./supplyI18n";
import { useSupplyRefresh, type SupplyRefreshState } from "./useSupplyRefresh";

type DetailState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | { status: "ready"; requestKey: string; data: SupplyDetailResponse };

const PAGE_SIZE = 25;
const DEFAULT_SORT: OrderSortRequest = {
  bySubject: true,
  byArticle: true,
  byColor: true,
  bySize: true,
};

export function SupplyDetailView({
  shopId,
  summary,
  onBack,
  onSupplyRefreshed,
  licenseAllowed = false,
  copy = defaultSupplyCopy,
  locale = "ru-RU",
}: {
  shopId: number;
  summary: SupplyItem;
  onBack: () => void;
  onSupplyRefreshed: () => void;
  licenseAllowed?: boolean;
  copy?: SupplyCopy;
  locale?: string;
}) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState<OrderSortRequest>(DEFAULT_SORT);
  const [retryKey, setRetryKey] = useState(0);
  const [showImportedOrders, setShowImportedOrders] = useState(false);
  const [state, setState] = useState<DetailState>({ status: "loading", requestKey: "" });
  const [deliveryPreview, setDeliveryPreview] = useState<MutationPreview | null>(null);
  const [deliveryBusy, setDeliveryBusy] = useState(false);
  const [deliveryError, setDeliveryError] = useState(false);
  const [deliveryNotice, setDeliveryNotice] = useState("");
  const requestSequence = useRef(0);
  const requestKey = JSON.stringify([shopId, summary.id, query, page, sort, retryKey]);
  const reloadLocal = useCallback(async () => {
    setRetryKey((key) => key + 1);
    onSupplyRefreshed();
  }, [onSupplyRefreshed]);
  const refresh = useSupplyRefresh(shopId, summary.id, reloadLocal);

  useEffect(() => {
    const requestId = ++requestSequence.current;
    let active = true;
    void commands.supplies.detail({
      shopId,
      supplyId: summary.id,
      query,
      page,
      pageSize: PAGE_SIZE,
      sort,
    }).then(
      (response) => {
        if (!active || requestSequence.current !== requestId) return;
        if (!matchesRequest(response, summary.id, query, page, sort)) {
          setState({ status: "error", requestKey });
          return;
        }
        const lastPage = Math.max(1, response.totalPages);
        if (page > lastPage) {
          setPage(lastPage);
          return;
        }
        setState({ status: "ready", requestKey, data: response });
      },
      () => {
        if (active && requestSequence.current === requestId) {
          setState({ status: "error", requestKey });
        }
      },
    );
    return () => {
      active = false;
    };
  }, [page, query, requestKey, shopId, sort, summary.id]);

  const visibleState: DetailState = state.requestKey === requestKey
    ? state
    : { status: "loading", requestKey };
  const supply = visibleState.status === "ready" ? visibleState.data.supply : summary;
  const printableOrderCount = visibleState.status === "ready" ? visibleState.data.totalItems : supply.itemCount;

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalizedQuery = draftQuery.trim();
    if (normalizedQuery === query && page === 1) {
      setRetryKey((key) => key + 1);
    }
    setQuery(normalizedQuery);
    setPage(1);
  };

  const toggleSort = (field: keyof OrderSortRequest) => {
    setSort((current) => ({ ...current, [field]: !current[field] }));
    setPage(1);
  };

  const prepareDelivery = async () => {
    if (supply.status !== "open" || deliveryBusy) return;
    setDeliveryBusy(true);
    setDeliveryError(false);
    setDeliveryNotice("");
    try {
      const preview = await commands.packing.prepareDeliver({ shopId, supplyId: supply.id });
      if (!isValidMutationPreview(preview, shopId, "deliver", supply.id)) {
        throw new Error("invalid preview");
      }
      setDeliveryPreview(preview);
    } catch {
      setDeliveryPreview(null);
      setDeliveryError(true);
    } finally {
      setDeliveryBusy(false);
    }
  };

  const executeDelivery = async (preview: MutationPreview) => {
    if (!preview.ready || deliveryBusy) return;
    setDeliveryBusy(true);
    setDeliveryError(false);
    try {
      const receipt = await commands.packing.execute({
        shopId,
        previewId: preview.previewId,
        confirmed: true,
      });
      if (!isValidMutationReceipt(receipt, preview)) throw new Error("invalid receipt");
      setDeliveryPreview(null);
      setDeliveryNotice(interpolate(copy.detail.delivered, { id: receipt.supplyId }));
      await reloadLocal();
    } catch {
      setDeliveryError(true);
    } finally {
      setDeliveryBusy(false);
    }
  };

  const refreshBusy = ["starting", "running", "cancelling"].includes(refresh.state.status);

  return (
    <div className="grid gap-5">
      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)] md:p-6">
        <button
          className="mb-5 inline-flex items-center gap-2 text-sm font-semibold text-[var(--text-secondary)] transition hover:text-[var(--accent-strong)]"
          type="button"
          onClick={onBack}
        >
          <ArrowLeft aria-hidden="true" size={17} />
          {copy.detail.back}
        </button>
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
          <div className="min-w-0">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <SupplyStatus status={supply.status} copy={copy} />
              <span className="rounded-full bg-[var(--surface-muted)] px-2.5 py-1 text-xs font-semibold text-[var(--text-secondary)]">
                {supply.mode === "b2b" ? "B2B" : supply.mode === "consumer" ? "B2C" : copy.detail.modeUnknown}
              </span>
            </div>
            <h3 className="truncate text-2xl font-semibold tracking-[-0.03em]">{supply.name}</h3>
            <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{supply.id}</p>
          </div>
          <div className="flex shrink-0 flex-col items-stretch gap-2 sm:items-end">
            <div className="rounded-xl bg-[var(--surface-muted)] px-4 py-3 text-right">
              <p className="text-xs font-semibold text-[var(--text-secondary)]">{copy.detail.orderCount}</p>
              <p className="mt-1 text-2xl font-semibold tabular-nums">{supply.itemCount}</p>
            </div>
            <button
              className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] hover:text-[var(--accent-strong)] disabled:cursor-wait disabled:opacity-55"
              disabled={refresh.state.status === "cancelling"}
              onClick={() => void (["starting", "running"].includes(refresh.state.status) ? refresh.cancel() : refresh.start())}
              type="button"
            >
              <RefreshCw
                aria-hidden="true"
                className={["starting", "running", "cancelling"].includes(refresh.state.status) ? "animate-spin" : ""}
                size={16}
              />
              {refresh.state.status === "starting" || refresh.state.status === "running"
                ? copy.detail.cancelRefresh
                : refresh.state.status === "cancelling"
                  ? copy.detail.cancelling
                  : copy.detail.refresh}
            </button>
            {!showImportedOrders && <PrintSetupDialog
              shopId={shopId}
              supplyId={summary.id}
              query={query}
              sort={sort}
              orderCount={printableOrderCount}
              copy={copy.print}
              locale={locale}
            />}
            {supply.status === "open" && !showImportedOrders && (
              <button
                className="inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-red-700 px-4 text-sm font-semibold text-white transition hover:bg-red-800 disabled:cursor-wait disabled:opacity-55"
                disabled={deliveryBusy || refreshBusy}
                onClick={() => void prepareDelivery()}
                type="button"
                aria-label={interpolate(copy.detail.prepareDelivery, { name: supply.name })}
              >
                <Truck aria-hidden="true" size={16} />
                {deliveryBusy && deliveryPreview === null ? copy.detail.checking : copy.detail.deliver}
              </button>
            )}
          </div>
        </div>
      </section>

      <RefreshNotice state={refresh.state} copy={copy} />

      {deliveryNotice && (
        <section className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-900" role="status">
          {deliveryNotice}
        </section>
      )}
      {deliveryError && deliveryPreview === null && (
        <section className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert">
          {copy.detail.mutationError}
        </section>
      )}

      <ExcelImportPanel key={shopId} shopId={shopId} onActiveChange={setShowImportedOrders} copy={copy} locale={locale} />

      <SupplyGtinInventory shopId={shopId} licenseAllowed={licenseAllowed} copy={copy} locale={locale} />

      {!showImportedOrders && <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.detail.searchLabel}</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={copy.detail.searchPlaceholder}
              aria-label={copy.detail.searchLabel}
            />
          </label>
          <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329]" type="submit">
            <Search aria-hidden="true" size={16} />
            {copy.detail.search}
          </button>
        </form>
        <div className="mt-4 flex flex-wrap items-center gap-2" aria-label={copy.detail.sortLabel}>
          <span className="mr-1 inline-flex items-center gap-2 text-xs font-semibold text-[var(--text-secondary)]">
            <SlidersHorizontal aria-hidden="true" size={15} />
            {copy.detail.sortBy}
          </span>
          <SortToggle label={copy.detail.subject} checked={sort.bySubject} onChange={() => toggleSort("bySubject")} />
          <SortToggle label={copy.detail.article} checked={sort.byArticle} onChange={() => toggleSort("byArticle")} />
          <SortToggle label={copy.detail.color} checked={sort.byColor} onChange={() => toggleSort("byColor")} />
          <SortToggle label={copy.detail.size} checked={sort.bySize} onChange={() => toggleSort("bySize")} />
        </div>
      </section>}

      {!showImportedOrders && (visibleState.status === "loading" ? (
        <OrderTableLoading copy={copy} />
      ) : visibleState.status === "error" ? (
        <DetailError copy={copy} onRetry={() => setRetryKey((key) => key + 1)} />
      ) : visibleState.data.items.length === 0 ? (
        <DetailEmpty copy={copy} hasQuery={query.length > 0} />
      ) : (
        <OrderTable items={visibleState.data.items} copy={copy} locale={locale} />
      ))}

      {!showImportedOrders && visibleState.status === "ready" && visibleState.data.items.length > 0 && (
        <Pagination
          page={visibleState.data.page}
          totalPages={visibleState.data.totalPages}
          totalItems={visibleState.data.totalItems}
          onPage={setPage}
          ariaLabel={copy.detail.pagination}
          previousLabel={copy.detail.previousPage}
          nextLabel={copy.detail.nextPage}
          copy={copy}
          locale={locale}
        />
      )}

      {deliveryPreview && (
        <PackingPreviewDialog
          preview={deliveryPreview}
          busy={deliveryBusy}
          error={deliveryError}
          onClose={() => !deliveryBusy && setDeliveryPreview(null)}
          onExecute={executeDelivery}
          copy={copy.mutation}
          locale={locale}
        />
      )}
    </div>
  );
}

function RefreshNotice({ state, copy }: { state: SupplyRefreshState; copy: SupplyCopy }) {
  if (state.status === "idle") return null;
  if (state.status === "starting" || state.status === "running" || state.status === "cancelling") {
    return (
      <section className="rounded-xl border border-sky-200 bg-sky-50 px-4 py-3 text-sm text-sky-900" role="status" aria-live="polite">
        {state.status === "cancelling"
          ? copy.detail.refreshStopping
          : copy.detail.refreshing}
      </section>
    );
  }
  if (state.status === "completed") {
    return (
      <section className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900" role="status" aria-live="polite">
        <span className="font-semibold">{copy.detail.refreshed}</span>.{" "}
        {interpolate(copy.detail.localOrders, { count: state.result.localOrders })}
      </section>
    );
  }
  if (state.status === "cancelled") {
    return (
      <section className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900" role="status" aria-live="polite">
        {copy.detail.cancelled}
      </section>
    );
  }
  return (
    <section className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert">
      <span className="font-semibold">{copy.detail.refreshFailed}</span>{" "}
      {refreshErrorMessage(state.errorKind, state.retryable, copy)}
    </section>
  );
}

function refreshErrorMessage(errorKind: string, retryable: boolean, copy: SupplyCopy): string {
  if (errorKind === "token_invalid") {
    return copy.detail.tokenInvalid;
  }
  if (errorKind === "rate_limited") {
    return copy.detail.rateLimited;
  }
  if (errorKind === "shop_busy") {
    return copy.detail.shopBusy;
  }
  return retryable
    ? copy.detail.retryableError
    : copy.detail.fatalError;
}

function matchesRequest(
  response: SupplyDetailResponse,
  supplyId: string,
  query: string,
  page: number,
  sort: OrderSortRequest,
): boolean {
  return response.supply.id === supplyId
    && response.query === query
    && response.page === page
    && response.pageSize === PAGE_SIZE
    && response.sort.bySubject === sort.bySubject
    && response.sort.byArticle === sort.byArticle
    && response.sort.byColor === sort.byColor
    && response.sort.bySize === sort.bySize;
}

function SortToggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: () => void }) {
  return (
    <label className={`inline-flex cursor-pointer items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-semibold transition ${checked ? "bg-[var(--accent-soft)] text-[var(--accent-strong)]" : "bg-[var(--surface-muted)] text-[var(--text-secondary)]"}`}>
      <input className="sr-only" type="checkbox" checked={checked} onChange={onChange} />
      <span className={`grid size-4 place-items-center rounded border ${checked ? "border-[var(--button-primary)] bg-[var(--button-primary)] text-white" : "border-[var(--border-strong)] bg-[var(--surface-elevated)]"}`}>
        {checked && <Check aria-hidden="true" size={11} strokeWidth={3} />}
      </span>
      {label}
    </label>
  );
}

function SupplyStatus({ status, copy }: { status: string; copy: SupplyCopy }) {
  const open = status === "open";
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${open ? "bg-amber-50 text-amber-800" : "bg-emerald-50 text-emerald-800"}`}>
      <span className={`size-1.5 rounded-full ${open ? "bg-amber-500" : "bg-emerald-500"}`} />
      {open ? copy.detail.open : copy.detail.closed}
    </span>
  );
}

function DetailError({ copy, onRetry }: { copy: SupplyCopy; onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div>
        <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} />
        <h4 className="font-semibold text-red-950">{copy.detail.loadError}</h4>
        <p className="mt-2 text-sm text-red-800">{copy.detail.unchanged}</p>
        <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>{copy.detail.retry}</button>
      </div>
    </section>
  );
}

function DetailEmpty({ copy, hasQuery }: { copy: SupplyCopy; hasQuery: boolean }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        <h4 className="font-semibold">{hasQuery ? copy.detail.emptySearchTitle : copy.detail.emptyTitle}</h4>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{hasQuery ? copy.detail.emptySearchDescription : copy.detail.emptyDescription}</p>
      </div>
    </section>
  );
}
