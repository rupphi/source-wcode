import {
  AlertCircle,
  PackageOpen,
  Search,
} from "lucide-react";
import { useCallback, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { InfiniteLoadTrigger } from "../../components/InfiniteLoadTrigger";
import { useBoundedInfinitePages } from "../../components/useBoundedInfinitePages";
import { commands } from "../../generated/commands";
import type { SupplyItem } from "../../generated/types";
import { interpolate } from "../../i18n";
import { SupplyDetailView } from "./SupplyDetailView";
import { SupplyTable } from "./SupplyTable";
import { defaultSupplyCopy, type SupplyCopy } from "./supplyI18n";

type SupplyStatus = "all" | "open" | "closed";
type SupplyListSummary = {
  openItems: number;
  closedItems: number;
};

const PAGE_SIZE = 25;
export function SupplyListView({
  shopId,
  licenseAllowed = false,
  copy = defaultSupplyCopy,
  locale = "ru-RU",
}: {
  shopId: number;
  licenseAllowed?: boolean;
  copy?: SupplyCopy;
  locale?: string;
}) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<SupplyStatus>("all");
  const [retryKey, setRetryKey] = useState(0);
  const [selectedSupply, setSelectedSupply] = useState<{ shopId: number; item: SupplyItem } | null>(null);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const loadPage = useCallback(async (page: number) => {
    const response = await commands.supplies.list({ shopId, query, status: filter, page, pageSize: PAGE_SIZE });
    if (response.shopId !== shopId
      || response.query !== query
      || response.status !== filter
      || response.page !== page
      || response.pageSize !== PAGE_SIZE) {
      throw new Error("Unexpected supply list response");
    }
    return {
      items: response.items,
      hasMore: page < response.totalPages,
      summary: {
        openItems: response.openItems,
        closedItems: response.closedItems,
      },
    };
  }, [filter, query, shopId]);
  const pages = useBoundedInfinitePages<SupplyItem, SupplyListSummary>({
    resetKey: JSON.stringify([shopId, query, filter, retryKey]),
    loadPage,
    getId: supplyId,
  });

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalizedQuery = draftQuery.trim();
    if (normalizedQuery === query) {
      setRetryKey((key) => key + 1);
    }
    setQuery(normalizedQuery);
  };

  const selectFilter = (status: SupplyStatus) => {
    setFilter(status);
  };

  const openItems = pages.summary?.openItems ?? 0;
  const closedItems = pages.summary?.closedItems ?? 0;
  const totalItems = openItems + closedItems;

  if (selectedSupply?.shopId === shopId) {
    return (
      <SupplyDetailView
        shopId={shopId}
        summary={selectedSupply.item}
        onBack={() => setSelectedSupply(null)}
        onSupplyRefreshed={() => setRetryKey((key) => key + 1)}
        licenseAllowed={licenseAllowed}
        copy={copy}
        locale={locale}
      />
    );
  }

  return (
    <div className="grid gap-3">
      <section className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)] md:p-4">
        <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.list.searchLabel}</span>
            <Search
              className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]"
              aria-hidden="true"
              size={18}
            />
            <input
              className="h-9 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-3 pl-10 text-xs shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={copy.list.searchPlaceholder}
              aria-label={copy.list.searchLabel}
            />
          </label>
          <button
            className="inline-flex h-9 items-center justify-center gap-2 rounded-lg bg-[var(--accent-strong)] px-4 text-xs font-semibold text-white transition hover:bg-[var(--accent)]"
            type="submit"
          >
            <Search aria-hidden="true" size={16} />
            {copy.list.search}
          </button>
        </form>

        <div className="mt-3 flex flex-wrap gap-1.5" aria-label={copy.list.filtersLabel}>
          <FilterButton active={filter === "all"} onClick={() => selectFilter("all")}>
            {copy.list.all} <Count value={totalItems} loading={pages.status === "loading"} numberFormat={numberFormat} />
          </FilterButton>
          <FilterButton active={filter === "open"} onClick={() => selectFilter("open")}>
            {copy.list.openPlural} <Count value={openItems} loading={pages.status === "loading"} numberFormat={numberFormat} />
          </FilterButton>
          <FilterButton active={filter === "closed"} onClick={() => selectFilter("closed")}>
            {copy.list.closedPlural} <Count value={closedItems} loading={pages.status === "loading"} numberFormat={numberFormat} />
          </FilterButton>
        </div>
      </section>

      {pages.status === "loading" && pages.items.length === 0 ? (
        <LoadingTable label={copy.list.loading} />
      ) : pages.status === "error" && pages.items.length === 0 ? (
        <ErrorState copy={copy} onRetry={pages.retry} />
      ) : pages.items.length === 0 ? (
        <EmptyState copy={copy} hasQuery={query.length > 0 || filter !== "all"} />
      ) : (
        <SupplyTable
          items={[...pages.items]}
          onOpen={(item) => setSelectedSupply({ shopId, item })}
          copy={copy}
          locale={locale}
        />
      )}

      {pages.items.length > 0 && (
        <InfiniteLoadTrigger
          status={pages.status}
          hasMore={pages.hasMore}
          copy={{
            loading: copy.list.loadingMore,
            loadMore: copy.list.loadMore,
            loadError: copy.list.loadMoreError,
            retry: copy.list.retry,
            end: copy.list.allLoaded,
          }}
          announcement={pages.addedCount > 0
            ? interpolate(copy.list.added, { count: numberFormat.format(pages.addedCount) })
            : ""}
          onLoadMore={pages.loadMore}
          onRetry={pages.retry}
        />
      )}
    </div>
  );
}

function supplyId(item: SupplyItem) {
  return item.id;
}

function FilterButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      className={`inline-flex h-8 items-center gap-1.5 rounded-lg px-2.5 text-xs font-semibold transition ${
        active
          ? "bg-[var(--accent-soft)] text-[var(--accent-strong)]"
          : "bg-[var(--surface-muted)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
      }`}
      type="button"
      aria-pressed={active}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function Count({ value, loading, numberFormat }: { value: number; loading: boolean; numberFormat: Intl.NumberFormat }) {
  return (
    <span className="rounded-md bg-white/65 px-1.5 py-0.5 text-xs tabular-nums">
      {loading ? "…" : numberFormat.format(value)}
    </span>
  );
}

function LoadingTable({ label }: { label: string }) {
  return (
    <section
      className="grid gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)]"
      aria-label={label}
    >
      {[0, 1, 2, 3, 4].map((row) => (
        <div className="flex items-center gap-4 py-2" key={row}>
          <span className="h-10 flex-1 animate-pulse rounded-lg bg-[var(--surface-muted)]" />
          <span className="h-8 w-24 animate-pulse rounded-lg bg-[var(--surface-muted)]" />
          <span className="hidden h-8 w-32 animate-pulse rounded-lg bg-[var(--surface-muted)] sm:block" />
        </div>
      ))}
    </section>
  );
}

function ErrorState({ copy, onRetry }: { copy: SupplyCopy; onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div>
        <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} />
        <h3 className="font-semibold text-red-950">{copy.list.errorTitle}</h3>
        <p className="mt-2 text-sm text-red-800">{copy.list.errorDescription}</p>
        <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>
          {copy.list.retry}
        </button>
      </div>
    </section>
  );
}

function EmptyState({ copy, hasQuery }: { copy: SupplyCopy; hasQuery: boolean }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        {hasQuery ? (
          <Search className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        ) : (
          <PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        )}
        <h3 className="font-semibold">{hasQuery ? copy.list.emptySearchTitle : copy.list.emptyTitle}</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">
          {hasQuery ? copy.list.emptySearchDescription : copy.list.emptyDescription}
        </p>
      </div>
    </section>
  );
}
