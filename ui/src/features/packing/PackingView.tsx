import {
  AlertCircle,
  ArrowUpRight,
  Boxes,
  CalendarDays,
  CheckCircle2,
  CircleDotDashed,
  ImageIcon,
  PackageOpen,
  Search,
  ShieldCheck,
  Sparkles,
  Truck,
} from "lucide-react";
import { useCallback, useMemo, useRef, useState, type FormEvent } from "react";
import { InfiniteLoadTrigger } from "../../components/InfiniteLoadTrigger";
import { useBoundedInfinitePages } from "../../components/useBoundedInfinitePages";
import { commands } from "../../generated/commands";
import type {
  MutationPreview,
  PackingBoardResponse,
  PackingOrderItem,
  PackingSupplyItem,
  SupplyItem,
} from "../../generated/types";
import { interpolate } from "../../i18n";
import { SupplyDetailView } from "../supplies/SupplyDetailView";
import { PackingMutationDialog, type MutationDialog } from "./PackingMutationDialog";
import { defaultPackingCopy, type PackingCopy } from "./packingI18n";
import { isValidMutationPreview, isValidMutationReceipt } from "./packingMutationContract";

type PackingTab = "new" | "preparation" | "dispatch";
type PackingRow =
  | { kind: "order"; item: PackingOrderItem }
  | { kind: "supply"; item: PackingSupplyItem };
type MutationNotice = { action: "create" | "add" | "deliver"; supplyId: string };

const PAGE_SIZE = 20;

export function PackingView({
  shopId,
  licenseAllowed = false,
  copy = defaultPackingCopy,
  locale = "ru-RU",
}: {
  shopId: number;
  licenseAllowed?: boolean;
  copy?: PackingCopy;
  locale?: string;
}) {
  const [tab, setTab] = useState<PackingTab>("new");
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [retryKey, setRetryKey] = useState(0);
  const [selectedSupply, setSelectedSupply] = useState<SupplyItem | null>(null);
  const [selectedOrderIds, setSelectedOrderIds] = useState<string[]>([]);
  const [mutationDialog, setMutationDialog] = useState<MutationDialog | null>(null);
  const [shipmentName, setShipmentName] = useState("");
  const [selectedTargetSupply, setSelectedTargetSupply] = useState("");
  const [targetSupplyQuery, setTargetSupplyQuery] = useState("");
  const [mutationBusy, setMutationBusy] = useState(false);
  const [mutationError, setMutationError] = useState(false);
  const [mutationNotice, setMutationNotice] = useState<MutationNotice | null>(null);
  const targetRequestSequence = useRef(0);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const moneyFormat = useMemo(() => new Intl.NumberFormat(locale, { style: "currency", currency: "RUB", maximumFractionDigits: 2 }), [locale]);
  const dateTimeFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }), [locale]);

  const loadPage = useCallback(async (page: number) => {
    const response = await commands.packing.board({
      shopId,
      tab,
      query,
      categories,
      page,
      pageSize: PAGE_SIZE,
    });
    if (!matchesRequest(response, shopId, tab, query, categories, page)) {
      throw new Error("Unexpected packing board response");
    }
    const items: PackingRow[] = tab === "new"
      ? response.orders.map((item) => ({ kind: "order", item }))
      : response.supplies.map((item) => ({ kind: "supply", item }));
    return {
      items,
      hasMore: page < response.totalPages,
      summary: response,
    };
  }, [categories, query, shopId, tab]);
  const pages = useBoundedInfinitePages<PackingRow, PackingBoardResponse>({
    resetKey: JSON.stringify([shopId, tab, query, categories, retryKey]),
    loadPage,
    getId: packingRowId,
  });
  const data = pages.summary ?? null;
  const orderItems = pages.items.flatMap((row) => row.kind === "order" ? [row.item] : []);
  const supplyItems = pages.items.flatMap((row) => row.kind === "supply" ? [row.item] : []);

  const selectTab = (nextTab: PackingTab) => {
    setSelectedSupply(null);
    setTab(nextTab);
    setCategories([]);
    if (nextTab !== "new") setSelectedOrderIds([]);
  };

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalized = draftQuery.trim();
    if (normalized === query) {
      setRetryKey((value) => value + 1);
    }
    setQuery(normalized);
  };

  const toggleCategory = (category: string) => {
    setCategories((current) => current.includes(category)
      ? current.filter((value) => value !== category)
      : [...current, category]);
  };

  const toggleOrder = (orderId: string) => {
    setSelectedOrderIds((current) => current.includes(orderId)
      ? current.filter((value) => value !== orderId)
      : current.length >= 1_000 ? current : [...current, orderId]);
  };

  const openCreate = () => {
    const now = new Date();
    setShipmentName(`${copy.shipmentPrefix} ${String(now.getDate()).padStart(2, "0")}.${String(now.getMonth() + 1).padStart(2, "0")}.${now.getFullYear()}`);
    setMutationError(false);
    setMutationDialog({ kind: "create" });
  };

  const openAdd = () => {
    setSelectedTargetSupply("");
    setTargetSupplyQuery("");
    setMutationError(false);
    setMutationDialog({ kind: "add", status: "loading", supplies: [] });
    loadAddSupplies("");
  };

  const loadAddSupplies = (candidate: string) => {
    const normalized = candidate.trim();
    const requestId = ++targetRequestSequence.current;
    setMutationDialog({ kind: "add", status: "loading", supplies: [] });
    void commands.packing.board({
      shopId,
      tab: "preparation",
      query: normalized,
      categories: [],
      page: 1,
      pageSize: 100,
    }).then((response) => {
      if (targetRequestSequence.current !== requestId) return;
      if (!matchesRequest(response, shopId, "preparation", normalized, [], 1, 100)) {
        throw new Error("invalid supply response");
      }
      setMutationDialog((current) => current?.kind === "add"
        ? { kind: "add", status: "ready", supplies: response.supplies }
        : current);
    }).catch(() => {
      if (targetRequestSequence.current !== requestId) return;
      setMutationDialog((current) => current?.kind === "add"
        ? { kind: "add", status: "error", supplies: [] }
        : current);
    });
  };

  const prepareCreate = async () => {
    if (shipmentName.trim().length === 0 || selectedOrderIds.length === 0) return;
    await prepareMutation("create", undefined, () => commands.packing.prepareCreate({
      shopId,
      name: shipmentName.trim(),
      orderIds: selectedOrderIds,
    }));
  };

  const prepareAdd = async () => {
    if (!selectedTargetSupply || selectedOrderIds.length === 0) return;
    await prepareMutation("add", selectedTargetSupply, () => commands.packing.prepareAdd({
      shopId,
      supplyId: selectedTargetSupply,
      orderIds: selectedOrderIds,
    }));
  };

  const prepareDeliver = async (supply: PackingSupplyItem) => {
    await prepareMutation("deliver", supply.id, () => commands.packing.prepareDeliver({ shopId, supplyId: supply.id }));
  };

  const prepareMutation = async (
    expectedAction: string,
    expectedSupplyId: string | undefined,
    operation: () => Promise<MutationPreview>,
  ) => {
    setMutationBusy(true);
    setMutationError(false);
    try {
      const preview = await operation();
      if (!isValidMutationPreview(preview, shopId, expectedAction, expectedSupplyId)) {
        throw new Error("invalid preview");
      }
      setMutationDialog({ kind: "preview", preview });
    } catch {
      setMutationError(true);
    } finally {
      setMutationBusy(false);
    }
  };

  const executeMutation = async (preview: MutationPreview) => {
    setMutationBusy(true);
    setMutationError(false);
    try {
      const receipt = await commands.packing.execute({
        shopId,
        previewId: preview.previewId,
        confirmed: true,
      });
      if (!isValidMutationReceipt(receipt, preview)) throw new Error("invalid receipt");
      if (receipt.action !== "create" && receipt.action !== "add" && receipt.action !== "deliver") {
        throw new Error("invalid receipt action");
      }
      setMutationNotice({ action: receipt.action, supplyId: receipt.supplyId });
      setSelectedOrderIds([]);
      setMutationDialog(null);
      setRetryKey((value) => value + 1);
    } catch {
      setMutationError(true);
    } finally {
      setMutationBusy(false);
    }
  };

  if (selectedSupply !== null) {
    return (
      <SupplyDetailView
        shopId={shopId}
        summary={selectedSupply}
        onBack={() => setSelectedSupply(null)}
        onSupplyRefreshed={() => setRetryKey((value) => value + 1)}
        licenseAllowed={licenseAllowed}
        copy={copy.supply}
        locale={locale}
      />
    );
  }

  return (
    <div className="grid gap-3">
      <section className="overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
        <div className="flex flex-col gap-3 border-b border-[var(--border-subtle)] bg-[var(--accent-soft)] p-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--sidebar)] text-white">
              <Boxes aria-hidden="true" size={20} />
            </span>
            <div>
              <h3 className="font-semibold tracking-[-0.01em]">{copy.header.title}</h3>
              <p className="mt-1 max-w-2xl text-sm leading-5 text-[var(--text-secondary)]">
                {copy.header.description}
              </p>
            </div>
          </div>
          <span className="inline-flex w-fit items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800">
            <ShieldCheck aria-hidden="true" size={15} />
            {copy.header.guarded}
          </span>
        </div>

        <div className="grid grid-cols-1 gap-2 p-3 sm:grid-cols-3" role="tablist" aria-label={copy.tabs.label}>
          <TabButton
            active={tab === "new"}
            icon={Sparkles}
            label={copy.tabs.new}
            count={data?.newOrderCount}
            onClick={() => selectTab("new")}
            numberFormat={numberFormat}
          />
          <TabButton
            active={tab === "preparation"}
            icon={CircleDotDashed}
            label={copy.tabs.preparation}
            count={data?.preparationCount}
            onClick={() => selectTab("preparation")}
            numberFormat={numberFormat}
          />
          <TabButton
            active={tab === "dispatch"}
            icon={CheckCircle2}
            label={copy.tabs.dispatch}
            count={data?.dispatchCount}
            onClick={() => selectTab("dispatch")}
            numberFormat={numberFormat}
          />
        </div>
      </section>

      {mutationNotice && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-900" role="status">
          {interpolate(mutationNotice.action === "create"
            ? copy.notices.created
            : mutationNotice.action === "add"
              ? copy.notices.added
              : copy.notices.delivered, { id: mutationNotice.supplyId })}
        </div>
      )}

      {tab === "new" && data && (
        <section className="flex flex-col gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] sm:flex-row sm:items-center sm:justify-between" aria-label={copy.selection.label}>
          <p className="text-sm text-[var(--text-secondary)]">
            {copy.selection.selected} <strong className="text-[var(--text-primary)]">{numberFormat.format(selectedOrderIds.length)}</strong>
          </p>
          <div className="flex flex-col gap-2 sm:flex-row">
            <button className="rounded-xl border border-[var(--border-strong)] px-4 py-2.5 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-45" type="button" disabled={selectedOrderIds.length === 0} onClick={openAdd}>
              {copy.selection.add}
            </button>
            <button className="rounded-xl bg-[var(--button-primary)] px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-45" type="button" disabled={selectedOrderIds.length === 0} onClick={openCreate}>
              {copy.selection.create}
            </button>
          </div>
        </section>
      )}

      <section className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)] md:p-4">
        <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.search.label}</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              className="h-9 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-3 pl-10 text-xs shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={tab === "new" ? copy.search.orderPlaceholder : copy.search.supplyPlaceholder}
              aria-label={copy.search.label}
            />
          </label>
          <button className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg bg-[var(--button-primary)] px-4 text-xs font-semibold text-white transition hover:brightness-110" type="submit">
            <Search aria-hidden="true" size={16} />
            {copy.search.submit}
          </button>
        </form>

        {tab === "new" && (data?.availableCategories.length ?? 0) > 0 && (
          <div className="mt-4 flex flex-wrap items-center gap-2" aria-label={copy.search.categoriesLabel}>
            <span className="mr-1 text-xs font-semibold tracking-[0.04em] text-[var(--text-muted)] uppercase">{copy.search.categories}</span>
            {data?.availableCategories.map((category) => {
              const active = categories.includes(category);
              return (
                <button
                  className={`rounded-lg px-3 py-2 text-xs font-semibold transition ${active ? "bg-[var(--button-primary)] text-white" : "bg-[var(--surface-muted)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"}`}
                  key={category}
                  type="button"
                  aria-pressed={active}
                  onClick={() => toggleCategory(category)}
                >
                  {category}
                </button>
              );
            })}
          </div>
        )}
      </section>

      {pages.status === "loading" && pages.items.length === 0 ? (
        <PackingLoading copy={copy} />
      ) : pages.status === "error" && pages.items.length === 0 ? (
        <PackingError copy={copy} onRetry={pages.retry} />
      ) : pages.items.length === 0 ? (
        <PackingEmpty tab={tab} filtered={query.length > 0 || categories.length > 0} copy={copy} />
      ) : tab === "new" ? (
        <OrderGrid items={orderItems} selected={selectedOrderIds} onToggle={toggleOrder} copy={copy} moneyFormat={moneyFormat} />
      ) : (
        <PackingSupplyTable
          items={supplyItems}
          onOpen={(item) => setSelectedSupply(item)}
          onDeliver={prepareDeliver}
          copy={copy}
          numberFormat={numberFormat}
          dateTimeFormat={dateTimeFormat}
        />
      )}

      {pages.items.length > 0 && (
        <InfiniteLoadTrigger
          status={pages.status}
          hasMore={pages.hasMore}
          copy={{ loading: copy.pagination.loading, loadMore: copy.pagination.loadMore, loadError: copy.pagination.loadError, retry: copy.error.retry, end: copy.pagination.end }}
          announcement={pages.addedCount > 0 ? interpolate(copy.pagination.added, { count: numberFormat.format(pages.addedCount) }) : ""}
          onLoadMore={pages.loadMore}
          onRetry={pages.retry}
        />
      )}

      {mutationDialog && (
        <PackingMutationDialog
          dialog={mutationDialog}
          shipmentName={shipmentName}
          selectedTargetSupply={selectedTargetSupply}
          targetSupplyQuery={targetSupplyQuery}
          busy={mutationBusy}
          error={mutationError}
          onShipmentName={setShipmentName}
          onTargetSupply={setSelectedTargetSupply}
          onTargetSupplyQuery={setTargetSupplyQuery}
          onSearchSupplies={() => loadAddSupplies(targetSupplyQuery)}
          onClose={() => !mutationBusy && setMutationDialog(null)}
          onPrepareCreate={prepareCreate}
          onPrepareAdd={prepareAdd}
          onExecute={executeMutation}
          copy={copy.mutation}
          locale={locale}
        />
      )}
    </div>
  );
}

function packingRowId(row: PackingRow) {
  return `${row.kind}:${row.kind === "order" ? row.item.orderId : row.item.id}`;
}

function TabButton({ active, icon: Icon, label, count, onClick, numberFormat }: {
  active: boolean;
  icon: typeof Sparkles;
  label: string;
  count: number | undefined;
  onClick: () => void;
  numberFormat: Intl.NumberFormat;
}) {
  return (
    <button
      className={`flex items-center gap-3 rounded-xl border px-4 py-3 text-left transition ${active ? "border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent-strong)]" : "border-transparent hover:border-[var(--border-subtle)] hover:bg-[var(--surface-muted)]"}`}
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
    >
      <Icon aria-hidden="true" size={18} />
      <span className="min-w-0 flex-1 text-sm font-semibold">{label}</span>
      <span className="rounded-lg bg-white/75 px-2 py-1 text-xs font-bold tabular-nums">{count === undefined ? "…" : numberFormat.format(count)}</span>
    </button>
  );
}

function OrderGrid({ items, selected, onToggle, copy, moneyFormat }: {
  items: PackingOrderItem[];
  selected: string[];
  onToggle: (orderId: string) => void;
  copy: PackingCopy;
  moneyFormat: Intl.NumberFormat;
}) {
  return (
    <section className="grid gap-3 sm:grid-cols-2 2xl:grid-cols-3" aria-label={copy.orders.label}>
      {items.map((item) => (
        <article className="relative flex min-w-0 gap-4 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]" key={item.orderId}>
          <input
            className="absolute top-3 right-3 size-4 accent-[var(--button-primary)]"
            type="checkbox"
            aria-label={interpolate(copy.orders.select, { id: item.orderId })}
            checked={selected.includes(item.orderId)}
            onChange={() => onToggle(item.orderId)}
          />
          <div className="grid size-20 shrink-0 place-items-center overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)]">
            {item.imagePath ? (
              <img className="size-full object-cover" src={item.imagePath} alt="" />
            ) : (
              <ImageIcon aria-hidden="true" size={22} className="text-[var(--text-muted)]" />
            )}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{item.name}</p>
                <p className="mt-1 truncate text-xs text-[var(--text-secondary)]">{[item.brand, item.subject].filter(Boolean).join(" · ") || copy.orders.uncategorized}</p>
              </div>
              <p className="shrink-0 text-sm font-bold tabular-nums">{moneyFormat.format(item.priceKopecks / 100)}</p>
            </div>
            <div className="mt-3 flex flex-wrap gap-1.5 text-[0.7rem] font-medium text-[var(--text-secondary)]">
              {item.article && <span className="rounded-md bg-[var(--surface-muted)] px-2 py-1">{item.article}</span>}
              {(item.russianSize || item.size) && <span className="rounded-md bg-[var(--surface-muted)] px-2 py-1">{interpolate(copy.orders.size, { value: item.russianSize || item.size })}</span>}
              {item.color && <span className="rounded-md bg-[var(--surface-muted)] px-2 py-1">{item.color}</span>}
            </div>
            <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-[var(--border-subtle)] pt-3">
              <span className="font-mono text-[0.7rem] text-[var(--text-muted)]">#{item.orderId}</span>
              {item.requiresKiz && <span className="rounded-full bg-violet-50 px-2 py-1 text-[0.68rem] font-semibold text-violet-800">{copy.orders.requiresKiz}</span>}
            </div>
          </div>
        </article>
      ))}
    </section>
  );
}

function PackingSupplyTable({ items, onOpen, onDeliver, copy, numberFormat, dateTimeFormat }: {
  items: PackingSupplyItem[];
  onOpen: (item: PackingSupplyItem) => void;
  onDeliver: (item: PackingSupplyItem) => void;
  copy: PackingCopy;
  numberFormat: Intl.NumberFormat;
  dateTimeFormat: Intl.DateTimeFormat;
}) {
  return (
    <section className="overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full table-fixed border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-3 py-2.5" scope="col">{copy.supplies.columns.supply}</th>
              <th className="hidden w-20 px-3 py-2.5 lg:table-cell" scope="col">{copy.supplies.columns.mode}</th>
              <th className="hidden w-40 px-3 py-2.5 xl:table-cell" scope="col">{copy.supplies.columns.created}</th>
              <th className="hidden w-20 px-3 py-2.5 text-right sm:table-cell" scope="col">{copy.supplies.columns.orders}</th>
              <th className="w-32 px-2 py-2.5 text-right" scope="col">{copy.supplies.columns.action}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.id}>
                <td className="min-w-0 px-3 py-3">
                  <p className="max-w-md truncate text-sm font-semibold">{item.name}</p>
                  <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{item.id}</p>
                </td>
                <td className="hidden px-3 py-3 text-xs text-[var(--text-secondary)] lg:table-cell">{item.mode === "b2b" ? "B2B" : item.mode === "consumer" ? "B2C" : copy.supplies.modeUnknown}</td>
                <td className="hidden px-3 py-3 text-xs text-[var(--text-secondary)] xl:table-cell">
                  <span className="inline-flex items-center gap-2 whitespace-nowrap"><CalendarDays aria-hidden="true" size={15} />{formatCreatedAt(item.createdAt, dateTimeFormat)}</span>
                </td>
                <td className="hidden px-3 py-3 text-right text-xs font-semibold tabular-nums sm:table-cell">{numberFormat.format(item.itemCount)}</td>
                <td className="px-2 py-3 text-right">
                  <div className="flex justify-end gap-1.5">
                    <button className="inline-flex h-9 items-center gap-1 rounded-lg border border-[var(--border-strong)] px-2 text-xs font-semibold" type="button" aria-label={interpolate(copy.supplies.prepareDelivery, { name: item.name })} onClick={() => onDeliver(item)}>
                      <Truck aria-hidden="true" size={14} />{copy.supplies.deliver}
                    </button>
                    <button className="icon-button" aria-label={interpolate(copy.supplies.openLabel, { name: item.name })} title={copy.supplies.open} type="button" onClick={() => onOpen(item)}>
                      <ArrowUpRight aria-hidden="true" size={16} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function PackingLoading({ copy }: { copy: PackingCopy }) {
  return (
    <section className="grid gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)]" aria-label={copy.loading}>
      {[0, 1, 2, 3].map((row) => <span className="h-20 animate-pulse rounded-xl bg-[var(--surface-muted)]" key={row} />)}
    </section>
  );
}

function PackingError({ copy, onRetry }: { copy: PackingCopy; onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div>
        <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} />
        <h3 className="font-semibold text-red-950">{copy.error.title}</h3>
        <p className="mt-2 text-sm text-red-800">{copy.error.description}</p>
        <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>{copy.error.retry}</button>
      </div>
    </section>
  );
}

function PackingEmpty({ tab, filtered, copy }: { tab: PackingTab; filtered: boolean; copy: PackingCopy }) {
  const emptyCopy = tab === "new" ? copy.empty.new : tab === "preparation" ? copy.empty.preparation : copy.empty.dispatch;
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        <h3 className="font-semibold">{filtered ? copy.empty.filtered : emptyCopy}</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{filtered ? copy.empty.filteredDescription : copy.empty.description}</p>
      </div>
    </section>
  );
}

function matchesRequest(
  response: PackingBoardResponse,
  shopId: number,
  tab: PackingTab,
  query: string,
  categories: string[],
  page: number,
  pageSize = PAGE_SIZE,
): boolean {
  return response.shopId === shopId
    && response.tab === tab
    && response.query === query
    && response.page === page
    && response.pageSize === pageSize
    && response.categories.length === categories.length
    && response.categories.every((value, index) => value === categories[index]);
}

function formatCreatedAt(value: string, dateTimeFormat: Intl.DateTimeFormat): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : dateTimeFormat.format(date);
}
