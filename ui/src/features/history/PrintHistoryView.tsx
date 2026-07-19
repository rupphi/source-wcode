import {
  AlertCircle,
  CalendarDays,
  CheckCircle2,
  Clock3,
  ExternalLink,
  FileText,
  LoaderCircle,
  PackageOpen,
  Printer,
  Search,
  TriangleAlert,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { commands } from "../../generated/commands";
import type {
  PrintHistoryItem,
  PrintHistoryResponse,
  ReprintHistoryResponse,
} from "../../generated/types";
import { reprintHistoryPdf } from "../printing/nativePrintCommands";
import { Pagination } from "../supplies/SupplyTable";
import { interpolate } from "../../i18n";
import type { HistoryCopy } from "./historyI18n";

type HistoryStatus = "all" | "success" | "failed";
type HistoryState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | { status: "ready"; requestKey: string; data: PrintHistoryResponse };
type ReprintState =
  | { status: "idle" }
  | { status: "running"; jobId: string }
  | { status: "error"; jobId: string }
  | {
      status: "success";
      data: ReprintHistoryResponse;
      openingKind: "labels" | "details" | null;
      openError: boolean;
    };

const PAGE_SIZE = 25;
export function PrintHistoryView({ copy, locale, shopId }: { copy: HistoryCopy; locale: string; shopId: number }) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<HistoryStatus>("all");
  const [page, setPage] = useState(1);
  const [retryKey, setRetryKey] = useState(0);
  const [state, setState] = useState<HistoryState>({ status: "loading", requestKey: "" });
  const [reprintState, setReprintState] = useState<ReprintState>({ status: "idle" });
  const requestSequence = useRef(0);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const dateTimeFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }), [locale]);
  const requestKey = JSON.stringify([shopId, query, status, page, retryKey]);

  useEffect(() => {
    const requestId = ++requestSequence.current;
    let active = true;
    void commands.printing.history({ shopId, query, status, page, pageSize: PAGE_SIZE }).then(
      (response) => {
        if (!active || requestSequence.current !== requestId) return;
        if (!matchesRequest(response, shopId, query, status, page)) {
          setState({ status: "error", requestKey });
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
  }, [page, query, requestKey, shopId, status]);

  const visibleState: HistoryState = state.requestKey === requestKey
    ? state
    : { status: "loading", requestKey };
  const data = visibleState.status === "ready" ? visibleState.data : null;
  const successfulItems = data?.successfulItems ?? 0;
  const failedItems = data?.failedItems ?? 0;

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalized = draftQuery.trim();
    if (normalized === query && page === 1) {
      setRetryKey((value) => value + 1);
    }
    setQuery(normalized);
    setPage(1);
  };

  const selectStatus = (nextStatus: HistoryStatus) => {
    setStatus(nextStatus);
    setPage(1);
  };

  const reprint = async (item: PrintHistoryItem) => {
    if (!item.canReprint || reprintState.status === "running") return;
    setReprintState({ status: "running", jobId: item.jobId });
    try {
      const response = await reprintHistoryPdf({ shopId, jobId: item.jobId });
      if (response.cancelled) {
        setReprintState({ status: "idle" });
      } else if (
        response.jobId !== item.jobId
        || !response.exportId
        || !response.labelsFileName
        || !response.detailsFileName
      ) {
        setReprintState({ status: "error", jobId: item.jobId });
      } else {
        setReprintState({ status: "success", data: response, openingKind: null, openError: false });
      }
    } catch {
      setReprintState({ status: "error", jobId: item.jobId });
    }
  };

  const openReprint = async (fileKind: "labels" | "details") => {
    if (reprintState.status !== "success" || reprintState.openingKind !== null) return;
    const { data } = reprintState;
    setReprintState({ ...reprintState, openingKind: fileKind, openError: false });
    try {
      await commands.printing.openHistoryReprint({ shopId, exportId: data.exportId, fileKind });
      setReprintState((current) => current.status === "success" && current.data.exportId === data.exportId
        ? { ...current, openingKind: null, openError: false }
        : current);
    } catch {
      setReprintState((current) => current.status === "success" && current.data.exportId === data.exportId
        ? { ...current, openingKind: null, openError: true }
        : current);
    }
  };

  return (
    <div className="grid gap-5">
      <section className="grid gap-3 sm:grid-cols-3" aria-label={copy.summaryAria}>
        <HistoryMetric
          icon={Clock3}
          label={copy.total}
          value={successfulItems + failedItems}
          tone="neutral"
          loading={visibleState.status === "loading"}
          numberFormat={numberFormat}
        />
        <HistoryMetric
          icon={CheckCircle2}
          label={copy.success}
          value={successfulItems}
          tone="success"
          loading={visibleState.status === "loading"}
          numberFormat={numberFormat}
        />
        <HistoryMetric
          icon={TriangleAlert}
          label={copy.failed}
          value={failedItems}
          tone="danger"
          loading={visibleState.status === "loading"}
          numberFormat={numberFormat}
        />
      </section>

      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.searchLabel}</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={copy.searchPlaceholder}
              aria-label={copy.searchLabel}
            />
          </label>
          <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329]" type="submit">
            <Search aria-hidden="true" size={16} />
            {copy.search}
          </button>
        </form>

        <div className="mt-4 flex flex-wrap gap-2" aria-label={copy.filterAria}>
          <FilterButton active={status === "all"} onClick={() => selectStatus("all")}>{copy.filters.all} <Count numberFormat={numberFormat} value={successfulItems + failedItems} loading={visibleState.status === "loading"} /></FilterButton>
          <FilterButton active={status === "success"} onClick={() => selectStatus("success")}>{copy.filters.success} <Count numberFormat={numberFormat} value={successfulItems} loading={visibleState.status === "loading"} /></FilterButton>
          <FilterButton active={status === "failed"} onClick={() => selectStatus("failed")}>{copy.filters.failed} <Count numberFormat={numberFormat} value={failedItems} loading={visibleState.status === "loading"} /></FilterButton>
        </div>
      </section>

      {reprintState.status === "success" && (
        <ReprintResult copy={copy} numberFormat={numberFormat} state={reprintState} onOpen={openReprint} />
      )}
      {reprintState.status === "error" && (
        <div className="flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert">
          <AlertCircle className="mt-0.5 shrink-0 text-red-600" aria-hidden="true" size={18} />
          <p><strong>{copy.reprintErrorTitle}</strong> {copy.reprintErrorDetail}</p>
        </div>
      )}

      {visibleState.status === "loading" ? (
        <HistoryLoading copy={copy} />
      ) : visibleState.status === "error" ? (
        <HistoryError copy={copy} onRetry={() => setRetryKey((value) => value + 1)} />
      ) : visibleState.data.items.length === 0 ? (
        <HistoryEmpty copy={copy} filtered={query.length > 0 || status !== "all"} />
      ) : (
        <HistoryTable
          items={visibleState.data.items}
          copy={copy}
          dateTimeFormat={dateTimeFormat}
          numberFormat={numberFormat}
          runningJobId={reprintState.status === "running" ? reprintState.jobId : null}
          onReprint={reprint}
        />
      )}

      {visibleState.status === "ready" && visibleState.data.items.length > 0 && (
        <Pagination
          page={visibleState.data.page}
          totalPages={visibleState.data.totalPages}
          totalItems={visibleState.data.totalItems}
          onPage={setPage}
          ariaLabel={copy.pagination.aria}
          previousLabel={copy.pagination.previous}
          nextLabel={copy.pagination.next}
          foundLabel={copy.pagination.found}
          pageOfLabel={copy.pagination.pageOf}
          locale={locale}
        />
      )}
    </div>
  );
}

function HistoryMetric({ icon: Icon, label, value, tone, loading, numberFormat }: {
  icon: typeof Clock3;
  label: string;
  value: number;
  tone: "neutral" | "success" | "danger";
  loading: boolean;
  numberFormat: Intl.NumberFormat;
}) {
  const toneClass = tone === "success"
    ? "bg-emerald-50 text-emerald-700"
    : tone === "danger" ? "bg-red-50 text-red-700" : "bg-[var(--accent-soft)] text-[var(--accent-strong)]";
  return (
    <article className="flex items-center gap-4 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]">
      <span className={`grid size-10 place-items-center rounded-xl ${toneClass}`}><Icon aria-hidden="true" size={19} /></span>
      <div>
        <p className="text-xs font-semibold tracking-[0.04em] text-[var(--text-muted)] uppercase">{label}</p>
        <p className="mt-1 text-2xl font-bold tabular-nums">{loading ? "…" : numberFormat.format(value)}</p>
      </div>
    </article>
  );
}

function HistoryTable({ copy, dateTimeFormat, items, numberFormat, runningJobId, onReprint }: {
  copy: HistoryCopy;
  dateTimeFormat: Intl.DateTimeFormat;
  items: PrintHistoryItem[];
  numberFormat: Intl.NumberFormat;
  runningJobId: string | null;
  onReprint: (item: PrintHistoryItem) => void;
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[54rem] border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-5 py-3.5" scope="col">{copy.table.date}</th>
              <th className="px-4 py-3.5" scope="col">{copy.table.supply}</th>
              <th className="px-4 py-3.5" scope="col">{copy.table.template}</th>
              <th className="px-4 py-3.5 text-right" scope="col">{copy.table.labels}</th>
              <th className="px-5 py-3.5" scope="col">{copy.table.status}</th>
              <th className="px-5 py-3.5 text-right" scope="col">{copy.table.action}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.jobId}>
                <td className="px-5 py-4 text-sm text-[var(--text-secondary)]">
                  <span className="inline-flex items-center gap-2 whitespace-nowrap"><CalendarDays aria-hidden="true" size={15} />{formatPrintedAt(dateTimeFormat, item.printedAt)}</span>
                  <p className="mt-1 font-mono text-[0.68rem] text-[var(--text-muted)]">{interpolate(copy.table.job, { id: item.jobId })}</p>
                </td>
                <td className="px-4 py-4">
                  <p className="max-w-sm truncate text-sm font-semibold">{item.supplyName}</p>
                  <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{item.supplyId || "—"}</p>
                </td>
                <td className="px-4 py-4 text-sm text-[var(--text-secondary)]">
                  <span className="inline-flex items-center gap-2"><FileText aria-hidden="true" size={15} />{item.templateName || copy.table.defaultTemplate}</span>
                </td>
                <td className="px-4 py-4 text-right text-sm font-semibold tabular-nums">{numberFormat.format(item.itemCount)}</td>
                <td className="px-5 py-4"><StatusBadge copy={copy} status={item.status} /></td>
                <td className="px-5 py-4 text-right">
                  {item.canReprint ? (
                    <button
                      className="inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-3 text-xs font-semibold text-[var(--text-primary)] shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] hover:text-[var(--accent-strong)] disabled:cursor-wait disabled:opacity-60"
                      type="button"
                      aria-label={interpolate(copy.table.reprintAria, { supply: item.supplyName })}
                      disabled={runningJobId !== null}
                      onClick={() => onReprint(item)}
                    >
                      {runningJobId === item.jobId
                        ? <LoaderCircle className="animate-spin" aria-hidden="true" size={15} />
                        : <Printer aria-hidden="true" size={15} />}
                      {runningJobId === item.jobId ? copy.table.creating : copy.table.reprint}
                    </button>
                  ) : <span className="text-sm text-[var(--text-muted)]" aria-label={copy.table.unavailable}>—</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ReprintResult({ copy, numberFormat, state, onOpen }: {
  copy: HistoryCopy;
  numberFormat: Intl.NumberFormat;
  state: Extract<ReprintState, { status: "success" }>;
  onOpen: (fileKind: "labels" | "details") => void;
}) {
  return (
    <section className="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-emerald-950 shadow-[var(--shadow-panel)]" aria-live="polite">
      <div className="flex items-start gap-3">
        <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-emerald-100 text-emerald-700"><CheckCircle2 aria-hidden="true" size={19} /></span>
        <div className="min-w-0 flex-1">
          <h3 className="font-semibold">{copy.result.title}</h3>
          <p className="mt-1 text-sm text-emerald-800">{interpolate(copy.result.saved, { count: numberFormat.format(state.data.itemCount) })}</p>
          <div className="mt-3 grid gap-2 lg:grid-cols-2">
            <ReprintFile
              fileName={state.data.labelsFileName}
              label={copy.result.labels}
              openLabel={copy.result.openLabels}
              opening={state.openingKind === "labels"}
              disabled={state.openingKind !== null}
              onOpen={() => onOpen("labels")}
            />
            <ReprintFile
              fileName={state.data.detailsFileName}
              label={copy.result.details}
              openLabel={copy.result.openDetails}
              opening={state.openingKind === "details"}
              disabled={state.openingKind !== null}
              onOpen={() => onOpen("details")}
            />
          </div>
          {state.openError && <p className="mt-3 text-sm font-medium text-red-700" role="alert">{copy.result.openError}</p>}
        </div>
      </div>
    </section>
  );
}

function ReprintFile({ fileName, label, openLabel, opening, disabled, onOpen }: {
  fileName: string;
  label: string;
  openLabel: string;
  opening: boolean;
  disabled: boolean;
  onOpen: () => void;
}) {
  return (
    <div className="flex min-w-0 items-center gap-3 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-3 py-2.5">
      <FileText className="shrink-0 text-emerald-700" aria-hidden="true" size={17} />
      <div className="min-w-0 flex-1">
        <p className="text-[0.68rem] font-semibold tracking-[0.05em] text-emerald-700 uppercase">{label}</p>
        <p className="truncate text-sm font-medium" title={fileName}>{fileName}</p>
      </div>
      <button
        className="icon-button shrink-0 border border-[var(--border-subtle)] bg-[var(--surface-elevated)] text-[var(--accent-strong)]"
        type="button"
        aria-label={openLabel}
        disabled={disabled}
        onClick={onOpen}
      >
        {opening ? <LoaderCircle className="animate-spin" aria-hidden="true" size={16} /> : <ExternalLink aria-hidden="true" size={16} />}
      </button>
    </div>
  );
}

function StatusBadge({ copy, status }: { copy: HistoryCopy; status: string }) {
  const success = status === "success";
  return <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${success ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-800"}`}><span className={`size-1.5 rounded-full ${success ? "bg-emerald-500" : "bg-red-500"}`} />{success ? copy.status.success : copy.status.failed}</span>;
}

function FilterButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return <button className={`inline-flex h-9 items-center gap-2 rounded-lg px-3 text-sm font-semibold transition ${active ? "bg-[var(--accent-soft)] text-[var(--accent-strong)]" : "bg-[var(--surface-muted)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"}`} type="button" aria-pressed={active} onClick={onClick}>{children}</button>;
}

function Count({ numberFormat, value, loading }: { numberFormat: Intl.NumberFormat; value: number; loading: boolean }) {
  return <span className="rounded-md bg-white/65 px-1.5 py-0.5 text-xs tabular-nums">{loading ? "…" : numberFormat.format(value)}</span>;
}

function HistoryLoading({ copy }: { copy: HistoryCopy }) {
  return <section className="grid gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)]" aria-label={copy.loading}>{[0, 1, 2, 3].map((row) => <span className="h-16 animate-pulse rounded-xl bg-[var(--surface-muted)]" key={row} />)}</section>;
}

function HistoryError({ copy, onRetry }: { copy: HistoryCopy; onRetry: () => void }) {
  return <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert"><div><AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} /><h3 className="font-semibold text-red-950">{copy.loadErrorTitle}</h3><p className="mt-2 text-sm text-red-800">{copy.loadErrorDetail}</p><button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>{copy.retry}</button></div></section>;
}

function HistoryEmpty({ copy, filtered }: { copy: HistoryCopy; filtered: boolean }) {
  return <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center"><div><PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} /><h3 className="font-semibold">{filtered ? copy.empty.filteredTitle : copy.empty.title}</h3><p className="mt-2 text-sm text-[var(--text-secondary)]">{filtered ? copy.empty.filteredDetail : copy.empty.detail}</p></div></section>;
}

function matchesRequest(response: PrintHistoryResponse, shopId: number, query: string, status: HistoryStatus, page: number): boolean {
  return response.shopId === shopId
    && response.query === query
    && response.status === status
    && response.page === page
    && response.pageSize === PAGE_SIZE;
}

function formatPrintedAt(dateTimeFormat: Intl.DateTimeFormat, value: string): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : dateTimeFormat.format(date);
}
