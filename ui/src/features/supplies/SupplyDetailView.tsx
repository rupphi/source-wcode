import { AlertCircle, ArrowLeft, Check, PackageOpen, Search, SlidersHorizontal } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type { OrderSortRequest, SupplyDetailResponse, SupplyItem } from "../../generated/types";
import { OrderTable, OrderTableLoading } from "./OrderTable";
import { Pagination } from "./SupplyTable";

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
}: {
  shopId: number;
  summary: SupplyItem;
  onBack: () => void;
}) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState<OrderSortRequest>(DEFAULT_SORT);
  const [retryKey, setRetryKey] = useState(0);
  const [state, setState] = useState<DetailState>({ status: "loading", requestKey: "" });
  const requestSequence = useRef(0);
  const requestKey = JSON.stringify([shopId, summary.id, query, page, sort, retryKey]);

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

  return (
    <div className="grid gap-5">
      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)] md:p-6">
        <button
          className="mb-5 inline-flex items-center gap-2 text-sm font-semibold text-[var(--text-secondary)] transition hover:text-[var(--accent-strong)]"
          type="button"
          onClick={onBack}
        >
          <ArrowLeft aria-hidden="true" size={17} />
          К списку поставок
        </button>
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
          <div className="min-w-0">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <SupplyStatus status={supply.status} />
              <span className="rounded-full bg-[var(--surface-muted)] px-2.5 py-1 text-xs font-semibold text-[var(--text-secondary)]">
                {supply.mode === "b2b" ? "B2B" : supply.mode === "consumer" ? "B2C" : "Схема не указана"}
              </span>
            </div>
            <h3 className="truncate text-2xl font-semibold tracking-[-0.03em]">{supply.name}</h3>
            <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{supply.id}</p>
          </div>
          <div className="rounded-xl bg-[var(--surface-muted)] px-4 py-3 text-right">
            <p className="text-xs font-semibold text-[var(--text-secondary)]">Заказов в поставке</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">{supply.itemCount}</p>
          </div>
        </div>
      </section>

      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Поиск заказов</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder="Номер задания, артикул или штрихкод"
              aria-label="Поиск заказов"
            />
          </label>
          <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329]" type="submit">
            <Search aria-hidden="true" size={16} />
            Найти заказ
          </button>
        </form>
        <div className="mt-4 flex flex-wrap items-center gap-2" aria-label="Сортировка заказов">
          <span className="mr-1 inline-flex items-center gap-2 text-xs font-semibold text-[var(--text-secondary)]">
            <SlidersHorizontal aria-hidden="true" size={15} />
            Сортировать по
          </span>
          <SortToggle label="Предмет" checked={sort.bySubject} onChange={() => toggleSort("bySubject")} />
          <SortToggle label="Артикул" checked={sort.byArticle} onChange={() => toggleSort("byArticle")} />
          <SortToggle label="Цвет" checked={sort.byColor} onChange={() => toggleSort("byColor")} />
          <SortToggle label="Размер" checked={sort.bySize} onChange={() => toggleSort("bySize")} />
        </div>
      </section>

      {visibleState.status === "loading" ? (
        <OrderTableLoading />
      ) : visibleState.status === "error" ? (
        <DetailError onRetry={() => setRetryKey((key) => key + 1)} />
      ) : visibleState.data.items.length === 0 ? (
        <DetailEmpty hasQuery={query.length > 0} />
      ) : (
        <OrderTable items={visibleState.data.items} />
      )}

      {visibleState.status === "ready" && visibleState.data.items.length > 0 && (
        <Pagination
          page={visibleState.data.page}
          totalPages={visibleState.data.totalPages}
          totalItems={visibleState.data.totalItems}
          onPage={setPage}
          ariaLabel="Пагинация заказов"
          previousLabel="Предыдущая страница заказов"
          nextLabel="Следующая страница заказов"
        />
      )}
    </div>
  );
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
      <span className={`grid size-4 place-items-center rounded border ${checked ? "border-[var(--accent-strong)] bg-[var(--accent-strong)] text-white" : "border-[var(--border-strong)] bg-white"}`}>
        {checked && <Check aria-hidden="true" size={11} strokeWidth={3} />}
      </span>
      {label}
    </label>
  );
}

function SupplyStatus({ status }: { status: string }) {
  const open = status === "open";
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${open ? "bg-amber-50 text-amber-800" : "bg-emerald-50 text-emerald-800"}`}>
      <span className={`size-1.5 rounded-full ${open ? "bg-amber-500" : "bg-emerald-500"}`} />
      {open ? "Открыта" : "Закрыта"}
    </span>
  );
}

function DetailError({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div>
        <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} />
        <h4 className="font-semibold text-red-950">Не удалось загрузить заказы</h4>
        <p className="mt-2 text-sm text-red-800">Локальные данные не изменены. Повторите запрос.</p>
        <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>Повторить</button>
      </div>
    </section>
  );
}

function DetailEmpty({ hasQuery }: { hasQuery: boolean }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        <h4 className="font-semibold">{hasQuery ? "Заказы не найдены" : "В поставке пока нет заказов"}</h4>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{hasQuery ? "Измените поисковый запрос." : "Обновление с Wildberries будет добавлено отдельным безопасным действием."}</p>
      </div>
    </section>
  );
}
