import {
  AlertCircle,
  Boxes,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  FileText,
  ImageIcon,
  Layers3,
  LoaderCircle,
  Minus,
  PackageOpen,
  Plus,
  Printer,
  Search,
  ShieldCheck,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type { FboCatalogResponse, FboExportResponse, FboProductItem } from "../../generated/types";
import { interpolate } from "../../i18n";
import { exportFboPdf } from "../printing/nativePrintCommands";
import { defaultFboCopy, formatFboPairs, type FboCopy } from "./fboI18n";

type CatalogState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | {
      status: "ready" | "loadingMore";
      requestKey: string;
      items: FboProductItem[];
      availableSubjects: string[];
      page: number;
      hasMore: boolean;
      loadMoreError: boolean;
    };

type ExportState =
  | { status: "idle" }
  | { status: "running" }
  | { status: "error" }
  | { status: "success"; data: FboExportResponse; opening: boolean; openError: boolean };

const PAGE_SIZE = 50;
const MAX_QUANTITY = 10_000;
const MAX_SELECTED_SKUS = 500;
const MAX_TOTAL_PAIRS = 10_000;

export function FboPackingView({ shopId, copy = defaultFboCopy, locale = "ru-RU" }: { shopId: number; copy?: FboCopy; locale?: string }) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [subjects, setSubjects] = useState<string[]>([]);
  const [subjectsOpen, setSubjectsOpen] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
  const [state, setState] = useState<CatalogState>({ status: "loading", requestKey: "" });
  const [quantities, setQuantities] = useState<Map<string, number>>(() => new Map());
  const [exportState, setExportState] = useState<ExportState>({ status: "idle" });
  const requestSequence = useRef(0);
  const requestKey = JSON.stringify([shopId, query, subjects, retryKey]);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const pairLabel = (value: number) => formatFboPairs(copy, locale, value);

  useEffect(() => {
    const requestId = ++requestSequence.current;
    let active = true;
    void commands.fbo.catalog({ shopId, query, subjects, page: 1, pageSize: PAGE_SIZE }).then(
      (response) => {
        if (!active || requestSequence.current !== requestId) return;
        if (!matchesCatalog(response, shopId, query, subjects, 1)) {
          setState({ status: "error", requestKey });
          return;
        }
        setState({
          status: "ready",
          requestKey,
          items: response.items,
          availableSubjects: response.availableSubjects,
          page: 1,
          hasMore: response.hasMore,
          loadMoreError: false,
        });
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
  }, [query, requestKey, shopId, subjects]);

  const visibleState: CatalogState = state.requestKey === requestKey
    ? state
    : { status: "loading", requestKey };
  const selectedSkuCount = quantities.size;
  const pairCount = useMemo(
    () => [...quantities.values()].reduce((total, quantity) => total + quantity, 0),
    [quantities],
  );
  const selectionValid = selectedSkuCount > 0
    && selectedSkuCount <= MAX_SELECTED_SKUS
    && pairCount <= MAX_TOTAL_PAIRS;
  const busy = exportState.status === "running";

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalized = draftQuery.trim();
    if (normalized === query) {
      setRetryKey((value) => value + 1);
    } else {
      setQuery(normalized);
    }
  };

  const toggleSubject = (subject: string) => {
    setSubjects((current) => current.includes(subject)
      ? current.filter((value) => value !== subject)
      : [...current, subject]);
  };

  const clearFilters = () => {
    setDraftQuery("");
    setQuery("");
    setSubjects([]);
    setSubjectsOpen(false);
    if (!query && subjects.length === 0) {
      setRetryKey((value) => value + 1);
    }
  };

  const setQuantity = (sku: string, quantity: number) => {
    const normalized = Number.isFinite(quantity)
      ? Math.min(MAX_QUANTITY, Math.max(0, Math.floor(quantity)))
      : 0;
    setQuantities((current) => {
      const next = new Map(current);
      if (normalized === 0) next.delete(sku);
      else next.set(sku, normalized);
      return next;
    });
  };

  const loadPage = async (nextPage: number) => {
    if (visibleState.status !== "ready"
      || nextPage < 1
      || (nextPage > visibleState.page && !visibleState.hasMore)) return;
    const current = visibleState;
    const requestId = requestSequence.current;
    setState({ ...current, status: "loadingMore", loadMoreError: false });
    try {
      const response = await commands.fbo.catalog({
        shopId,
        query,
        subjects,
        page: nextPage,
        pageSize: PAGE_SIZE,
      });
      if (requestSequence.current !== requestId || requestKey !== current.requestKey) return;
      if (!matchesCatalog(response, shopId, query, subjects, nextPage)) {
        setState({ ...current, status: "ready", loadMoreError: true });
        return;
      }
      setState({
        status: "ready",
        requestKey,
        items: response.items,
        availableSubjects: response.availableSubjects,
        page: nextPage,
        hasMore: response.hasMore,
        loadMoreError: false,
      });
    } catch {
      if (requestSequence.current === requestId) {
        setState({ ...current, status: "ready", loadMoreError: true });
      }
    }
  };

  const runExport = async (items: { sku: string; quantity: number }[], clearBatch: boolean) => {
    if (busy || items.length === 0) return;
    setExportState({ status: "running" });
    try {
      const response = await exportFboPdf({ shopId, items });
      if (response.cancelled) {
        setExportState({ status: "idle" });
        return;
      }
      if (!response.exportId || !response.fileName || response.pairCount < 1 || response.pageCount !== response.pairCount * 2) {
        setExportState({ status: "error" });
        return;
      }
      if (clearBatch) setQuantities(new Map());
      setExportState({ status: "success", data: response, opening: false, openError: false });
    } catch {
      setExportState({ status: "error" });
    }
  };

  const exportBatch = () => {
    if (!selectionValid) return;
    void runExport(
      [...quantities.entries()].map(([sku, quantity]) => ({ sku, quantity })),
      true,
    );
  };

  const openPdf = async () => {
    if (exportState.status !== "success" || exportState.opening) return;
    const { data } = exportState;
    setExportState({ ...exportState, opening: true, openError: false });
    try {
      await commands.fbo.openExport({ shopId, exportId: data.exportId });
      setExportState((current) => current.status === "success" && current.data.exportId === data.exportId
        ? { ...current, opening: false, openError: false }
        : current);
    } catch {
      setExportState((current) => current.status === "success" && current.data.exportId === data.exportId
        ? { ...current, opening: false, openError: true }
        : current);
    }
  };

  return (
    <div className="grid gap-5">
      <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
        <div className="flex flex-col gap-4 bg-[linear-gradient(120deg,var(--surface-elevated),var(--accent-soft))] p-5 lg:flex-row lg:items-center lg:justify-between">
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
      </section>

      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 lg:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.search.label}</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              aria-label={copy.search.label}
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={copy.search.placeholder}
              type="search"
              value={draftQuery}
            />
          </label>
          <div className="relative">
            <button
              aria-expanded={subjectsOpen}
              aria-label={copy.search.subjects}
              className="inline-flex h-11 w-full items-center justify-between gap-3 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] lg:w-52"
              onClick={() => setSubjectsOpen((value) => !value)}
              type="button"
            >
              <span>{subjects.length > 0 ? interpolate(copy.search.subjectCount, { count: numberFormat.format(subjects.length) }) : copy.search.subjects}</span>
              <ChevronDown aria-hidden="true" size={16} />
            </button>
            {subjectsOpen && visibleState.status !== "loading" && visibleState.status !== "error" && (
              <div className="absolute top-12 right-0 z-20 max-h-72 w-full min-w-64 overflow-y-auto rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-2 shadow-xl lg:right-auto lg:left-0">
                {visibleState.availableSubjects.length === 0 ? (
                  <p className="px-3 py-2 text-sm text-[var(--text-muted)]">{copy.search.noSubjects}</p>
                ) : visibleState.availableSubjects.map((subject) => (
                  <label className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-sm hover:bg-[var(--surface-muted)]" key={subject}>
                    <input
                      checked={subjects.includes(subject)}
                      className="size-4 accent-[var(--accent-strong)]"
                      onChange={() => toggleSubject(subject)}
                      type="checkbox"
                    />
                    <span>{subject}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
          <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329]" type="submit">
            <Search aria-hidden="true" size={16} />
            {copy.search.submit}
          </button>
        </form>

        {(query || subjects.length > 0) && (
          <div className="mt-4 flex flex-wrap items-center gap-2">
            {subjects.map((subject) => (
              <button className="inline-flex items-center gap-1 rounded-full bg-[var(--accent-soft)] px-3 py-1.5 text-xs font-semibold text-[var(--accent-strong)]" key={subject} onClick={() => toggleSubject(subject)} type="button">
                {subject}<X aria-hidden="true" size={13} />
              </button>
            ))}
            <button className="px-2 py-1.5 text-xs font-semibold text-[var(--text-secondary)] hover:text-[var(--text-primary)]" onClick={clearFilters} type="button">{copy.search.clear}</button>
          </div>
        )}
      </section>

      {selectedSkuCount > 0 && (
        <section className="flex flex-col gap-3 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-emerald-950 shadow-[var(--shadow-panel)] sm:flex-row sm:items-center sm:justify-between" aria-label={copy.selection.label}>
          <div className="flex items-center gap-3">
            <span className="grid size-10 place-items-center rounded-xl bg-emerald-700 text-white"><Layers3 aria-hidden="true" size={19} /></span>
            <div>
              <p className="font-semibold">{interpolate(copy.selection.summary, { pairs: pairLabel(pairCount), skus: numberFormat.format(selectedSkuCount) })}</p>
              <p className="mt-0.5 text-xs text-emerald-800">{copy.selection.clearedAfterSuccess}</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <button className="rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 py-2.5 text-sm font-semibold text-[var(--text-primary)]" onClick={() => setQuantities(new Map())} type="button">{copy.selection.clear}</button>
            <button
              aria-label={interpolate(copy.selection.createAria, { pairs: pairLabel(pairCount) })}
              className="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-800 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-emerald-900 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={!selectionValid || busy}
              onClick={exportBatch}
              type="button"
            >
              {busy ? <LoaderCircle className="animate-spin" aria-hidden="true" size={16} /> : <Printer aria-hidden="true" size={16} />}
              {busy ? copy.selection.creating : copy.selection.create}
            </button>
          </div>
          {!selectionValid && <p className="text-xs font-semibold text-red-800">{copy.selection.limit}</p>}
        </section>
      )}

      {exportState.status === "error" && (
        <div className="flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert">
          <AlertCircle className="mt-0.5 shrink-0 text-red-600" aria-hidden="true" size={18} />
          <p><strong>{copy.export.errorTitle}</strong> {copy.export.errorDescription}</p>
        </div>
      )}
      {exportState.status === "success" && (
        <section className="flex flex-col gap-3 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-emerald-950 sm:flex-row sm:items-center sm:justify-between" aria-live="polite">
          <div className="flex items-start gap-3">
            <CheckCircle2 className="mt-0.5 shrink-0 text-emerald-700" aria-hidden="true" size={20} />
            <div>
              <p className="font-semibold">{copy.export.success}</p>
              <p className="mt-0.5 text-sm"><span className="font-medium">{exportState.data.fileName}</span> · {pairLabel(exportState.data.pairCount)} · {interpolate(copy.export.pages, { count: numberFormat.format(exportState.data.pageCount) })}</p>
              {exportState.openError && <p className="mt-1 text-xs font-semibold text-red-800">{copy.export.openError}</p>}
            </div>
          </div>
          <button aria-label={copy.export.openAria} className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold shadow-[var(--shadow-control)] disabled:cursor-wait disabled:opacity-60" disabled={exportState.opening} onClick={() => void openPdf()} type="button">
            {exportState.opening ? <LoaderCircle className="animate-spin" aria-hidden="true" size={16} /> : <FileText aria-hidden="true" size={16} />}
            {exportState.opening ? copy.export.opening : copy.export.open}
          </button>
        </section>
      )}

      {visibleState.status === "loading" ? (
        <CatalogLoading copy={copy} />
      ) : visibleState.status === "error" ? (
        <CatalogError copy={copy} onRetry={() => setRetryKey((value) => value + 1)} />
      ) : visibleState.items.length === 0 ? (
        <CatalogEmpty copy={copy} filtered={Boolean(query || subjects.length > 0)} />
      ) : (
        <>
          <section className="grid gap-3 xl:grid-cols-2" aria-label={copy.catalog.label}>
            {visibleState.items.map((item) => (
              <ProductCard
                busy={busy}
                item={item}
                key={item.sku}
                onQuantity={(quantity) => setQuantity(item.sku, quantity)}
                onQuickPrint={() => void runExport([{ sku: item.sku, quantity: 1 }], false)}
                quantity={quantities.get(item.sku) ?? 0}
                copy={copy}
              />
            ))}
          </section>
          {visibleState.loadMoreError && (
            <p className="text-center text-sm font-medium text-red-700" role="alert">{copy.catalog.loadMoreError}</p>
          )}
          {(visibleState.page > 1 || visibleState.hasMore) && (
            <nav className="flex items-center justify-between gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)]" aria-label={copy.pagination.label}>
              <button aria-label={copy.pagination.previousAria} className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[var(--border-strong)] px-4 text-sm font-semibold transition hover:border-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-45" disabled={visibleState.page <= 1 || visibleState.status === "loadingMore"} onClick={() => void loadPage(visibleState.page - 1)} type="button"><ChevronLeft aria-hidden="true" size={16} /><span className="hidden sm:inline">{copy.pagination.previous}</span></button>
              <p className="text-sm font-semibold tabular-nums text-[var(--text-secondary)]">{visibleState.status === "loadingMore" ? copy.pagination.loading : interpolate(copy.pagination.page, { page: numberFormat.format(visibleState.page) })}</p>
              <button aria-label={copy.pagination.nextAria} className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[var(--border-strong)] px-4 text-sm font-semibold transition hover:border-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-45" disabled={!visibleState.hasMore || visibleState.status === "loadingMore"} onClick={() => void loadPage(visibleState.page + 1)} type="button"><span className="hidden sm:inline">{copy.pagination.next}</span><ChevronRight aria-hidden="true" size={16} /></button>
            </nav>
          )}
        </>
      )}
    </div>
  );
}

function ProductCard({ item, quantity, busy, onQuantity, onQuickPrint, copy }: {
  item: FboProductItem;
  quantity: number;
  busy: boolean;
  onQuantity: (quantity: number) => void;
  onQuickPrint: () => void;
  copy: FboCopy;
}) {
  const name = item.title || copy.product.unnamed;
  return (
    <article className="flex min-w-0 flex-col gap-4 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] sm:flex-row">
      <div className="grid h-32 w-full shrink-0 place-items-center overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] sm:h-32 sm:w-24">
        {item.imagePath ? (
          <img alt={interpolate(copy.product.photo, { name })} className="size-full object-cover" src={item.imagePath} />
        ) : (
          <ImageIcon aria-hidden="true" className="text-[var(--text-muted)]" size={24} />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="truncate text-sm font-semibold">{name}</h3>
            <p className="mt-1 truncate text-xs text-[var(--text-secondary)]">{[item.brand, item.subject].filter(Boolean).join(" · ") || copy.product.uncategorized}</p>
          </div>
          {item.requiresKiz && <span className="shrink-0 rounded-full bg-violet-50 px-2.5 py-1 text-[0.68rem] font-semibold text-violet-800">{copy.product.requiresKiz}</span>}
        </div>
        <div className="mt-3 grid gap-x-4 gap-y-1 text-xs text-[var(--text-secondary)] sm:grid-cols-2">
          <p><span className="text-[var(--text-muted)]">nmID</span> <span className="font-mono text-[var(--text-primary)]">{item.nmId}</span></p>
          <p><span className="text-[var(--text-muted)]">{copy.product.article}</span> <span className="font-medium text-[var(--text-primary)]">{item.vendorCode || "—"}</span></p>
          <p><span className="text-[var(--text-muted)]">SKU</span> <span className="font-mono text-[var(--text-primary)]">{item.sku}</span></p>
          <p><span className="text-[var(--text-muted)]">{copy.product.size}</span> <span className="font-medium text-[var(--text-primary)]">{item.russianSize || item.size || "—"}</span>{item.color ? ` · ${item.color}` : ""}</p>
        </div>
        <div className="mt-4 flex flex-col gap-2 border-t border-[var(--border-subtle)] pt-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="inline-flex h-10 w-fit items-center overflow-hidden rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] shadow-[var(--shadow-control)]">
            <button aria-label={interpolate(copy.product.decrease, { name })} className="grid size-10 place-items-center text-[var(--text-secondary)] hover:bg-[var(--surface-muted)] disabled:opacity-40" disabled={busy || quantity === 0} onClick={() => onQuantity(quantity - 1)} type="button"><Minus aria-hidden="true" size={15} /></button>
            <input
              aria-label={interpolate(copy.product.quantity, { name, sku: item.sku })}
              className="h-full w-16 border-x border-[var(--border-subtle)] text-center text-sm font-semibold tabular-nums outline-none"
              disabled={busy}
              max={MAX_QUANTITY}
              min={0}
              onChange={(event) => onQuantity(event.target.valueAsNumber)}
              type="number"
              value={quantity}
            />
            <button aria-label={interpolate(copy.product.increase, { name })} className="grid size-10 place-items-center text-[var(--text-secondary)] hover:bg-[var(--surface-muted)] disabled:opacity-40" disabled={busy || quantity >= MAX_QUANTITY} onClick={() => onQuantity(quantity + 1)} type="button"><Plus aria-hidden="true" size={15} /></button>
          </div>
          <button aria-label={interpolate(copy.product.quickPrintAria, { name })} className="inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-4 text-xs font-semibold text-white transition hover:bg-[#1c3329] disabled:cursor-wait disabled:opacity-55" disabled={busy} onClick={onQuickPrint} type="button"><Printer aria-hidden="true" size={15} />{copy.product.quickPrint}</button>
        </div>
      </div>
    </article>
  );
}

function CatalogLoading({ copy }: { copy: FboCopy }) {
  return <section aria-label={copy.loading} className="grid gap-3 xl:grid-cols-2">{[0, 1, 2, 3].map((item) => <span className="h-48 animate-pulse rounded-2xl bg-[var(--surface-muted)]" key={item} />)}</section>;
}

function CatalogError({ copy, onRetry }: { copy: FboCopy; onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div><AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} /><h3 className="font-semibold text-red-950">{copy.error.title}</h3><p className="mt-2 text-sm text-red-800">{copy.error.description}</p><button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" onClick={onRetry} type="button">{copy.error.retry}</button></div>
    </section>
  );
}

function CatalogEmpty({ copy, filtered }: { copy: FboCopy; filtered: boolean }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div><PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} /><h3 className="font-semibold">{filtered ? copy.empty.filteredTitle : copy.empty.title}</h3><p className="mt-2 text-sm text-[var(--text-secondary)]">{filtered ? copy.empty.filteredDescription : copy.empty.description}</p></div>
    </section>
  );
}

function matchesCatalog(response: FboCatalogResponse, shopId: number, query: string, subjects: string[], page: number) {
  return response.shopId === shopId
    && response.query === query
    && response.page === page
    && response.pageSize === PAGE_SIZE
    && response.subjects.length === subjects.length
    && response.subjects.every((value, index) => value === subjects[index]);
}
