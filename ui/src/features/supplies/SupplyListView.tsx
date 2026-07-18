import {
  AlertCircle,
  PackageOpen,
  Search,
} from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { commands } from "../../generated/commands";
import type { ListSuppliesResponse, SupplyItem } from "../../generated/types";
import { SupplyDetailView } from "./SupplyDetailView";
import { Pagination, SupplyTable } from "./SupplyTable";

type SupplyStatus = "all" | "open" | "closed";
type SupplyListState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | { status: "ready"; requestKey: string; data: ListSuppliesResponse };

const PAGE_SIZE = 25;
const numberFormat = new Intl.NumberFormat("ru-RU");

export function SupplyListView({ shopId }: { shopId: number }) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<SupplyStatus>("all");
  const [page, setPage] = useState(1);
  const [retryKey, setRetryKey] = useState(0);
  const [state, setState] = useState<SupplyListState>({ status: "loading", requestKey: "" });
  const [selectedSupply, setSelectedSupply] = useState<{ shopId: number; item: SupplyItem } | null>(null);
  const requestSequence = useRef(0);
  const requestKey = JSON.stringify([shopId, query, filter, page, retryKey]);

  useEffect(() => {
    const requestId = ++requestSequence.current;
    let active = true;
    void commands.supplies.list({ shopId, query, status: filter, page, pageSize: PAGE_SIZE }).then(
      (response) => {
        if (!active || requestSequence.current !== requestId) return;
        if (response.shopId !== shopId
          || response.query !== query
          || response.status !== filter
          || response.page !== page
          || response.pageSize !== PAGE_SIZE) {
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
  }, [filter, page, query, requestKey, shopId]);

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalizedQuery = draftQuery.trim();
    if (normalizedQuery === query && page === 1) {
      setRetryKey((key) => key + 1);
    }
    setPage(1);
    setQuery(normalizedQuery);
  };

  const selectFilter = (status: SupplyStatus) => {
    setFilter(status);
    setPage(1);
  };

  const visibleState: SupplyListState = state.requestKey === requestKey
    ? state
    : { status: "loading", requestKey };
  const data = visibleState.status === "ready" ? visibleState.data : null;
  const openItems = data?.openItems ?? 0;
  const closedItems = data?.closedItems ?? 0;
  const totalItems = openItems + closedItems;

  if (selectedSupply?.shopId === shopId) {
    return (
      <SupplyDetailView
        shopId={shopId}
        summary={selectedSupply.item}
        onBack={() => setSelectedSupply(null)}
      />
    );
  }

  return (
    <div className="grid gap-5">
      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Поиск поставок</span>
            <Search
              className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]"
              aria-hidden="true"
              size={18}
            />
            <input
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder="ID или название поставки"
              aria-label="Поиск поставок"
            />
          </label>
          <button
            className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329]"
            type="submit"
          >
            <Search aria-hidden="true" size={16} />
            Найти
          </button>
        </form>

        <div className="mt-4 flex flex-wrap gap-2" aria-label="Фильтр поставок">
          <FilterButton active={filter === "all"} onClick={() => selectFilter("all")}>
            Все <Count value={totalItems} loading={visibleState.status === "loading"} />
          </FilterButton>
          <FilterButton active={filter === "open"} onClick={() => selectFilter("open")}>
            Открытые <Count value={openItems} loading={visibleState.status === "loading"} />
          </FilterButton>
          <FilterButton active={filter === "closed"} onClick={() => selectFilter("closed")}>
            Закрытые <Count value={closedItems} loading={visibleState.status === "loading"} />
          </FilterButton>
        </div>
      </section>

      {visibleState.status === "loading" ? (
        <LoadingTable />
      ) : visibleState.status === "error" ? (
        <ErrorState onRetry={() => setRetryKey((key) => key + 1)} />
      ) : visibleState.data.items.length === 0 ? (
        <EmptyState hasQuery={query.length > 0 || filter !== "all"} />
      ) : (
        <SupplyTable
          items={visibleState.data.items}
          onOpen={(item) => setSelectedSupply({ shopId, item })}
        />
      )}

      {visibleState.status === "ready" && visibleState.data.items.length > 0 && (
        <Pagination
          page={visibleState.data.page}
          totalPages={visibleState.data.totalPages}
          totalItems={visibleState.data.totalItems}
          onPage={setPage}
        />
      )}
    </div>
  );
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
      className={`inline-flex h-9 items-center gap-2 rounded-lg px-3 text-sm font-semibold transition ${
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

function Count({ value, loading }: { value: number; loading: boolean }) {
  return (
    <span className="rounded-md bg-white/65 px-1.5 py-0.5 text-xs tabular-nums">
      {loading ? "…" : numberFormat.format(value)}
    </span>
  );
}

function LoadingTable() {
  return (
    <section
      className="grid gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)]"
      aria-label="Загрузка поставок"
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

function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div>
        <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} />
        <h3 className="font-semibold text-red-950">Не удалось загрузить поставки</h3>
        <p className="mt-2 text-sm text-red-800">Локальные данные не изменены. Повторите запрос.</p>
        <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>
          Повторить
        </button>
      </div>
    </section>
  );
}

function EmptyState({ hasQuery }: { hasQuery: boolean }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        {hasQuery ? (
          <Search className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        ) : (
          <PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        )}
        <h3 className="font-semibold">{hasQuery ? "Поставки не найдены" : "Поставок пока нет"}</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">
          {hasQuery ? "Измените запрос или выберите другой статус." : "Запустите синхронизацию на главной странице."}
        </p>
      </div>
    </section>
  );
}
