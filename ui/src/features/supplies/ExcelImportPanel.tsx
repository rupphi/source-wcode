import {
  AlertCircle,
  CheckCircle2,
  FileSpreadsheet,
  ImageIcon,
  RotateCcw,
  Search,
  ShieldCheck,
  X,
} from "lucide-react";
import { JDeskError } from "jdesk-client";
import { useMemo, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type { ImportedOrderItem, ImportedOrderPage } from "../../generated/types";
import { interpolate } from "../../i18n";
import { OrderThumbnail } from "./OrderTable";
import { Pagination } from "./SupplyTable";
import { defaultSupplyCopy, type SupplyCopy } from "./supplyI18n";

const PAGE_SIZE = 25;
const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SAFE_IMAGE_PATH = /^jdesk:\/\/app\/order-images\/[A-Za-z0-9_-]{43}\.(?:png|jpg)$/;

type ImportState =
  | { status: "idle" }
  | { status: "importing" }
  | { status: "error"; kind: ImportErrorKind }
  | { status: "ready"; data: ImportedOrderPage; pageLoading: boolean; pageError: boolean; actionError: boolean };

type ImportErrorKind = "invalid_file" | "token_invalid" | "rate_limited" | "unavailable";

export function ExcelImportPanel({
  shopId,
  onActiveChange,
  copy = defaultSupplyCopy,
  locale = "ru-RU",
}: {
  shopId: number;
  onActiveChange: (active: boolean) => void;
  copy?: SupplyCopy;
  locale?: string;
}) {
  const [state, setState] = useState<ImportState>({ status: "idle" });
  const [draftQuery, setDraftQuery] = useState("");
  const requestSequence = useRef(0);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);

  const importWorkbook = async () => {
    const previous = state.status === "ready" ? state : null;
    const requestId = ++requestSequence.current;
    setState({ status: "importing" });
    onActiveChange(false);
    try {
      const response = await commands.orders.importExcel({ shopId, pageSize: PAGE_SIZE });
      if (requestSequence.current !== requestId) return;
      if (response.cancelled) {
        setState(previous ?? { status: "idle" });
        onActiveChange(previous !== null);
        return;
      }
      if (!isSafePage(response)) {
        setState(previous === null ? { status: "error", kind: "invalid_file" } : { ...previous, actionError: true });
        onActiveChange(previous !== null);
        return;
      }
      setDraftQuery("");
      setState({ status: "ready", data: response, pageLoading: false, pageError: false, actionError: false });
      onActiveChange(true);
    } catch (error) {
      if (requestSequence.current === requestId) {
        setState(previous === null
          ? { status: "error", kind: excelImportErrorKind(error) }
          : { ...previous, actionError: true });
        onActiveChange(previous !== null);
      }
    }
  };

  const loadPage = async (query: string, page: number) => {
    if (state.status !== "ready") return;
    const sessionId = state.data.sessionId;
    const requestId = ++requestSequence.current;
    setState({ ...state, pageLoading: true, pageError: false });
    try {
      const response = await commands.orders.importedPage({
        shopId,
        sessionId,
        query,
        page,
        pageSize: PAGE_SIZE,
      });
      if (requestSequence.current !== requestId) return;
      if (!isSafePage(response) || response.sessionId !== sessionId || response.query !== query) {
        setState({ ...state, pageLoading: false, pageError: true });
        return;
      }
      setState({ status: "ready", data: response, pageLoading: false, pageError: false, actionError: false });
    } catch {
      if (requestSequence.current === requestId) {
        setState({ ...state, pageLoading: false, pageError: true });
      }
    }
  };

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void loadPage(draftQuery.trim(), 1);
  };

  const closeWorkspace = () => {
    requestSequence.current += 1;
    setState({ status: "idle" });
    setDraftQuery("");
    onActiveChange(false);
  };

  if (state.status !== "ready") {
    return (
      <section className="flex flex-col justify-between gap-4 rounded-2xl border border-emerald-200 bg-emerald-50/75 p-4 shadow-[var(--shadow-control)] sm:flex-row sm:items-center">
        <div className="flex min-w-0 items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-emerald-100 text-emerald-800">
            <FileSpreadsheet aria-hidden="true" size={20} />
          </span>
          <div>
            <h4 className="text-sm font-semibold text-emerald-950">{copy.excel.title}</h4>
            <p className="mt-1 max-w-2xl text-xs leading-5 text-emerald-800">
              {copy.excel.description}
            </p>
            {state.status === "error" && (
              <p className="mt-2 inline-flex items-center gap-1.5 text-xs font-semibold text-red-700" role="alert">
                <AlertCircle aria-hidden="true" size={14} />
                {excelImportErrorMessage(state.kind, copy)}
              </p>
            )}
          </div>
        </div>
        <button
          className="inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-xl bg-emerald-800 px-4 text-sm font-semibold text-white transition hover:bg-emerald-900 disabled:cursor-wait disabled:opacity-60"
          disabled={state.status === "importing"}
          onClick={() => void importWorkbook()}
          type="button"
        >
          {state.status === "importing" ? (
            <RotateCcw aria-hidden="true" className="animate-spin" size={16} />
          ) : (
            <FileSpreadsheet aria-hidden="true" size={16} />
          )}
          {state.status === "importing" ? copy.excel.reading : copy.excel.import}
        </button>
      </section>
    );
  }

  const { data } = state;
  return (
    <div className="grid gap-4">
      <section className="overflow-hidden rounded-2xl border border-emerald-200 bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
        <div className="flex flex-col justify-between gap-4 bg-emerald-950 px-5 py-5 text-white sm:flex-row sm:items-start">
          <div className="min-w-0">
            <p className="mb-1 inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-200">
              <ShieldCheck aria-hidden="true" size={14} />
              {copy.excel.verified}
            </p>
            <h3 className="truncate text-xl font-semibold tracking-[-0.02em]">{interpolate(copy.excel.ordersFrom, { file: data.fileName })}</h3>
          </div>
          <div className="flex gap-2">
            <button
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-white/20 px-3 text-xs font-semibold transition hover:bg-white/10"
              onClick={() => void importWorkbook()}
              type="button"
            >
              <FileSpreadsheet aria-hidden="true" size={14} />
              {copy.excel.anotherFile}
            </button>
            <button
              aria-label={copy.excel.close}
              className="grid size-9 place-items-center rounded-lg border border-white/20 transition hover:bg-white/10"
              onClick={closeWorkspace}
              type="button"
            >
              <X aria-hidden="true" size={16} />
            </button>
          </div>
        </div>
        <div className="grid gap-px bg-[var(--border-subtle)] sm:grid-cols-3">
          <ImportMetric label={copy.excel.fileOrders} value={numberFormat.format(data.importedItems)} />
          <ImportMetric label={copy.excel.stickers} value={interpolate(copy.excel.countOf, { count: numberFormat.format(data.stickerItems), total: numberFormat.format(data.importedItems) })} />
          <ImportMetric label={copy.excel.found} value={numberFormat.format(data.totalItems)} />
        </div>
        {state.actionError && (
          <p className="border-t border-red-200 bg-red-50 px-5 py-3 text-xs font-semibold text-red-800" role="alert">
            {copy.excel.actionError}
          </p>
        )}
      </section>

      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]">
        <form className="flex flex-col gap-3 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.excel.searchLabel}</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              aria-label={copy.excel.searchLabel}
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={copy.excel.searchPlaceholder}
              type="search"
              value={draftQuery}
            />
          </label>
          <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white" type="submit">
            <Search aria-hidden="true" size={16} />
            {copy.excel.search}
          </button>
        </form>
        {state.pageError && (
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3 rounded-xl bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">
            {copy.excel.pageError}
            <button className="font-semibold underline underline-offset-2" onClick={() => void loadPage(data.query, data.page)} type="button">
              {copy.excel.retry}
            </button>
          </div>
        )}
      </section>

      {state.pageLoading ? (
        <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-6 text-sm text-[var(--text-secondary)]" role="status">
          {copy.excel.loading}
        </section>
      ) : data.items.length === 0 ? (
        <section className="rounded-2xl border border-dashed border-[var(--border-strong)] p-8 text-center text-sm text-[var(--text-secondary)]">
          {copy.excel.empty}
        </section>
      ) : (
        <ImportedOrdersTable items={data.items} copy={copy} />
      )}

      {data.items.length > 0 && !state.pageLoading && (
        <Pagination
          ariaLabel={copy.excel.pagination}
          nextLabel={copy.excel.nextPage}
          onPage={(page) => void loadPage(data.query, page)}
          page={data.page}
          previousLabel={copy.excel.previousPage}
          totalItems={data.totalItems}
          totalPages={data.totalPages}
          copy={copy}
          locale={locale}
        />
      )}
    </div>
  );
}

function ImportMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-[var(--surface-elevated)] px-5 py-4">
      <p className="text-xs font-semibold text-[var(--text-secondary)]">{label}</p>
      <p className="mt-1 text-xl font-semibold tabular-nums">{value}</p>
    </div>
  );
}

function ImportedOrdersTable({ items, copy }: { items: ImportedOrderItem[]; copy: SupplyCopy }) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[58rem] border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-5 py-3.5" scope="col">{copy.excel.columns.order}</th>
              <th className="px-4 py-3.5" scope="col">{copy.excel.columns.product}</th>
              <th className="px-4 py-3.5" scope="col">{copy.excel.columns.articleBarcode}</th>
              <th className="px-4 py-3.5" scope="col">{copy.excel.columns.variant}</th>
              <th className="px-5 py-3.5" scope="col">{copy.excel.columns.sticker}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.orderId}>
                <td className="px-5 py-4 align-top font-mono text-xs font-semibold">{item.orderId}</td>
                <td className="px-4 py-4 align-top">
                  <div className="flex min-w-0 items-start gap-3">
                    {SAFE_IMAGE_PATH.test(item.imagePath) ? (
                      <OrderThumbnail name={item.name} path={item.imagePath} copy={copy} />
                    ) : (
                      <span className="grid size-12 shrink-0 place-items-center rounded-xl bg-[var(--surface-muted)] text-[var(--text-muted)]">
                        <ImageIcon aria-hidden="true" size={18} />
                      </span>
                    )}
                    <div className="min-w-0">
                      <p className="max-w-64 truncate text-sm font-semibold">{item.name || item.article || interpolate(copy.excel.fallbackOrder, { id: item.orderId })}</p>
                      <p className="mt-1 max-w-64 truncate text-xs text-[var(--text-secondary)]">{item.brand || "—"}</p>
                    </div>
                  </div>
                </td>
                <td className="px-4 py-4 align-top text-sm">
                  <p className="font-medium">{item.article || "—"}</p>
                  <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{item.barcode || "—"}</p>
                </td>
                <td className="px-4 py-4 align-top text-sm text-[var(--text-secondary)]">
                  {[item.color, item.size].filter(Boolean).join(" · ") || "—"}
                </td>
                <td className="px-5 py-4 align-top">
                  <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${item.stickerAvailable ? "bg-emerald-50 text-emerald-800" : "bg-amber-50 text-amber-800"}`}>
                    {item.stickerAvailable ? <CheckCircle2 aria-hidden="true" size={13} /> : <AlertCircle aria-hidden="true" size={13} />}
                    {item.stickerAvailable ? (item.sticker || copy.excel.received) : copy.excel.notFound}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function isSafePage(page: ImportedOrderPage): boolean {
  if (page.cancelled) return page.sessionId === "" && page.items.length === 0;
  return SESSION_ID.test(page.sessionId)
    && isSafeFileName(page.fileName)
    && typeof page.query === "string"
    && page.query.length <= 120
    && page.page >= 1
    && page.pageSize === PAGE_SIZE
    && page.totalItems >= 0
    && page.totalPages >= 0
    && page.importedItems >= page.totalItems
    && page.stickerItems >= 0
    && page.stickerItems <= page.importedItems
    && Array.isArray(page.items)
    && page.items.length <= PAGE_SIZE
    && page.items.every(isSafeItem);
}

function isSafeFileName(value: string): boolean {
  return typeof value === "string"
    && value.length > 0
    && value.length <= 180
    && !/[\\/\p{Cc}]/u.test(value);
}

function isSafeItem(item: ImportedOrderItem): boolean {
  return /^[1-9][0-9]{0,18}$/.test(item.orderId)
    && safeText(item.name, 160)
    && safeText(item.brand, 120)
    && safeText(item.article, 120)
    && safeText(item.color, 80)
    && safeText(item.size, 80)
    && safeText(item.barcode, 128)
    && safeText(item.sticker, 128)
    && (item.imagePath === "" || SAFE_IMAGE_PATH.test(item.imagePath));
}

function safeText(value: string, maxLength: number): boolean {
  return typeof value === "string" && value.length <= maxLength && !/\p{Cc}/u.test(value);
}

function excelImportErrorKind(error: unknown): ImportErrorKind {
  if (!(error instanceof JDeskError)) return "unavailable";
  if (error.data !== null && typeof error.data === "object") {
    const kind = (error.data as { kind?: unknown }).kind;
    if (kind === "token_invalid" || kind === "rate_limited") return kind;
  }
  return error.code === "INVALID_REQUEST" ? "invalid_file" : "unavailable";
}

function excelImportErrorMessage(kind: ImportErrorKind, copy: SupplyCopy): string {
  if (kind === "rate_limited") {
    return copy.excel.errors.rateLimited;
  }
  if (kind === "token_invalid") {
    return copy.excel.errors.tokenInvalid;
  }
  if (kind === "invalid_file") {
    return copy.excel.errors.invalidFile;
  }
  return copy.excel.errors.unavailable;
}
