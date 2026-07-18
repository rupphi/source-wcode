import {
  AlertCircle,
  Boxes,
  CalendarDays,
  CheckCircle2,
  CircleDotDashed,
  ImageIcon,
  PackageOpen,
  Search,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type {
  PackingBoardResponse,
  PackingOrderItem,
  PackingSupplyItem,
  SupplyItem,
} from "../../generated/types";
import { Pagination } from "../supplies/SupplyTable";
import { SupplyDetailView } from "../supplies/SupplyDetailView";

type PackingTab = "new" | "preparation" | "dispatch";
type PackingState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | { status: "ready"; requestKey: string; data: PackingBoardResponse };

const PAGE_SIZE = 20;
const numberFormat = new Intl.NumberFormat("ru-RU");
const moneyFormat = new Intl.NumberFormat("ru-RU", {
  style: "currency",
  currency: "RUB",
  maximumFractionDigits: 2,
});
const dateTimeFormat = new Intl.DateTimeFormat("ru-RU", {
  dateStyle: "medium",
  timeStyle: "short",
});

export function PackingView({ shopId }: { shopId: number }) {
  const [tab, setTab] = useState<PackingTab>("new");
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [page, setPage] = useState(1);
  const [retryKey, setRetryKey] = useState(0);
  const [state, setState] = useState<PackingState>({ status: "loading", requestKey: "" });
  const [selectedSupply, setSelectedSupply] = useState<SupplyItem | null>(null);
  const requestSequence = useRef(0);
  const requestKey = JSON.stringify([shopId, tab, query, categories, page, retryKey]);

  useEffect(() => {
    const requestId = ++requestSequence.current;
    let active = true;
    void commands.packing.board({
      shopId,
      tab,
      query,
      categories,
      page,
      pageSize: PAGE_SIZE,
    }).then(
      (response) => {
        if (!active || requestSequence.current !== requestId) return;
        if (!matchesRequest(response, shopId, tab, query, categories, page)) {
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
  }, [categories, page, query, requestKey, shopId, tab]);

  const visibleState: PackingState = state.requestKey === requestKey
    ? state
    : { status: "loading", requestKey };
  const data = visibleState.status === "ready" ? visibleState.data : null;

  const selectTab = (nextTab: PackingTab) => {
    setSelectedSupply(null);
    setTab(nextTab);
    setCategories([]);
    setPage(1);
  };

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalized = draftQuery.trim();
    if (normalized === query && page === 1) {
      setRetryKey((value) => value + 1);
    }
    setQuery(normalized);
    setPage(1);
  };

  const toggleCategory = (category: string) => {
    setCategories((current) => current.includes(category)
      ? current.filter((value) => value !== category)
      : [...current, category]);
    setPage(1);
  };

  if (selectedSupply !== null) {
    return (
      <SupplyDetailView
        shopId={shopId}
        summary={selectedSupply}
        onBack={() => setSelectedSupply(null)}
        onSupplyRefreshed={() => setRetryKey((value) => value + 1)}
      />
    );
  }

  return (
    <div className="grid gap-5">
      <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
        <div className="flex flex-col gap-4 border-b border-[var(--border-subtle)] bg-[linear-gradient(120deg,var(--surface-elevated),var(--accent-soft))] p-5 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--sidebar)] text-white">
              <Boxes aria-hidden="true" size={20} />
            </span>
            <div>
              <h3 className="font-semibold tracking-[-0.01em]">Очередь комплектации</h3>
              <p className="mt-1 max-w-2xl text-sm leading-5 text-[var(--text-secondary)]">
                Данные читаются из локальной базы. Операции создания, наполнения и передачи поставки будут доступны только как подтверждаемые действия.
              </p>
            </div>
          </div>
          <span className="inline-flex w-fit items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800">
            <ShieldCheck aria-hidden="true" size={15} />
            Без изменений в WB
          </span>
        </div>

        <div className="grid grid-cols-1 gap-2 p-3 sm:grid-cols-3" role="tablist" aria-label="Этапы упаковки FBS">
          <TabButton
            active={tab === "new"}
            icon={Sparkles}
            label="Новые заказы"
            count={data?.newOrderCount}
            onClick={() => selectTab("new")}
          />
          <TabButton
            active={tab === "preparation"}
            icon={CircleDotDashed}
            label="На сборке"
            count={data?.preparationCount}
            onClick={() => selectTab("preparation")}
          />
          <TabButton
            active={tab === "dispatch"}
            icon={CheckCircle2}
            label="К отгрузке"
            count={data?.dispatchCount}
            onClick={() => selectTab("dispatch")}
          />
        </div>
      </section>

      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 sm:flex-row" onSubmit={submitSearch} role="search">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Поиск в очереди упаковки</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              type="search"
              value={draftQuery}
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={tab === "new" ? "Заказ, товар, артикул или штрихкод" : "ID или название поставки"}
              aria-label="Поиск в очереди упаковки"
            />
          </label>
          <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329]" type="submit">
            <Search aria-hidden="true" size={16} />
            Найти
          </button>
        </form>

        {tab === "new" && (data?.availableCategories.length ?? 0) > 0 && (
          <div className="mt-4 flex flex-wrap items-center gap-2" aria-label="Категории новых заказов">
            <span className="mr-1 text-xs font-semibold tracking-[0.04em] text-[var(--text-muted)] uppercase">Категории</span>
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

      {visibleState.status === "loading" ? (
        <PackingLoading />
      ) : visibleState.status === "error" ? (
        <PackingError onRetry={() => setRetryKey((value) => value + 1)} />
      ) : visibleState.data.totalItems === 0 ? (
        <PackingEmpty tab={tab} filtered={query.length > 0 || categories.length > 0} />
      ) : tab === "new" ? (
        <OrderGrid items={visibleState.data.orders} />
      ) : (
        <PackingSupplyTable
          items={visibleState.data.supplies}
          onOpen={(item) => setSelectedSupply(item)}
        />
      )}

      {visibleState.status === "ready" && visibleState.data.totalItems > 0 && (
        <Pagination
          page={visibleState.data.page}
          totalPages={visibleState.data.totalPages}
          totalItems={visibleState.data.totalItems}
          onPage={setPage}
          ariaLabel="Пагинация очереди упаковки"
          previousLabel="Предыдущая страница очереди"
          nextLabel="Следующая страница очереди"
        />
      )}
    </div>
  );
}

function TabButton({ active, icon: Icon, label, count, onClick }: {
  active: boolean;
  icon: typeof Sparkles;
  label: string;
  count: number | undefined;
  onClick: () => void;
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

function OrderGrid({ items }: { items: PackingOrderItem[] }) {
  return (
    <section className="grid gap-3 sm:grid-cols-2 2xl:grid-cols-3" aria-label="Новые заказы">
      {items.map((item) => (
        <article className="flex min-w-0 gap-4 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]" key={item.orderId}>
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
                <p className="mt-1 truncate text-xs text-[var(--text-secondary)]">{[item.brand, item.subject].filter(Boolean).join(" · ") || "Без категории"}</p>
              </div>
              <p className="shrink-0 text-sm font-bold tabular-nums">{moneyFormat.format(item.priceKopecks / 100)}</p>
            </div>
            <div className="mt-3 flex flex-wrap gap-1.5 text-[0.7rem] font-medium text-[var(--text-secondary)]">
              {item.article && <span className="rounded-md bg-[var(--surface-muted)] px-2 py-1">{item.article}</span>}
              {(item.russianSize || item.size) && <span className="rounded-md bg-[var(--surface-muted)] px-2 py-1">Размер {item.russianSize || item.size}</span>}
              {item.color && <span className="rounded-md bg-[var(--surface-muted)] px-2 py-1">{item.color}</span>}
            </div>
            <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-[var(--border-subtle)] pt-3">
              <span className="font-mono text-[0.7rem] text-[var(--text-muted)]">#{item.orderId}</span>
              {item.requiresKiz && <span className="rounded-full bg-violet-50 px-2 py-1 text-[0.68rem] font-semibold text-violet-800">Требуется KIZ</span>}
            </div>
          </div>
        </article>
      ))}
    </section>
  );
}

function PackingSupplyTable({ items, onOpen }: { items: PackingSupplyItem[]; onOpen: (item: PackingSupplyItem) => void }) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[46rem] border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-5 py-3.5" scope="col">Поставка</th>
              <th className="px-4 py-3.5" scope="col">Схема</th>
              <th className="px-4 py-3.5" scope="col">Создана</th>
              <th className="px-4 py-3.5 text-right" scope="col">Заказов</th>
              <th className="px-5 py-3.5 text-right" scope="col">Действие</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.id}>
                <td className="px-5 py-4">
                  <p className="max-w-md truncate text-sm font-semibold">{item.name}</p>
                  <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{item.id}</p>
                </td>
                <td className="px-4 py-4 text-sm text-[var(--text-secondary)]">{item.mode === "b2b" ? "B2B" : item.mode === "consumer" ? "B2C" : "Не указана"}</td>
                <td className="px-4 py-4 text-sm text-[var(--text-secondary)]">
                  <span className="inline-flex items-center gap-2 whitespace-nowrap"><CalendarDays aria-hidden="true" size={15} />{formatCreatedAt(item.createdAt)}</span>
                </td>
                <td className="px-4 py-4 text-right text-sm font-semibold tabular-nums">{numberFormat.format(item.itemCount)}</td>
                <td className="px-5 py-4 text-right">
                  <button className="rounded-lg bg-[var(--accent-soft)] px-3 py-2 text-xs font-semibold text-[var(--accent-strong)]" type="button" onClick={() => onOpen(item)}>
                    Открыть
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function PackingLoading() {
  return (
    <section className="grid gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)]" aria-label="Загрузка очереди упаковки">
      {[0, 1, 2, 3].map((row) => <span className="h-20 animate-pulse rounded-xl bg-[var(--surface-muted)]" key={row} />)}
    </section>
  );
}

function PackingError({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
      <div>
        <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={26} />
        <h3 className="font-semibold text-red-950">Не удалось открыть очередь упаковки</h3>
        <p className="mt-2 text-sm text-red-800">Локальные данные не изменены. Повторите запрос.</p>
        <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>Повторить</button>
      </div>
    </section>
  );
}

function PackingEmpty({ tab, filtered }: { tab: PackingTab; filtered: boolean }) {
  const emptyCopy = tab === "new" ? "Новых заказов пока нет" : tab === "preparation" ? "Поставок на сборке пока нет" : "Готовых отгрузок пока нет";
  return (
    <section className="grid min-h-64 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <PackageOpen className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={26} />
        <h3 className="font-semibold">{filtered ? "Ничего не найдено" : emptyCopy}</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">{filtered ? "Измените запрос или категории." : "Обновите данные Wildberries на главной странице."}</p>
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
): boolean {
  return response.shopId === shopId
    && response.tab === tab
    && response.query === query
    && response.page === page
    && response.pageSize === PAGE_SIZE
    && response.categories.length === categories.length
    && response.categories.every((value, index) => value === categories[index]);
}

function formatCreatedAt(value: string): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : dateTimeFormat.format(date);
}
