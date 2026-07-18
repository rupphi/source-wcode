import {
  Barcode,
  Boxes,
  ChevronDown,
  CircleHelp,
  History,
  KeyRound,
  LayoutDashboard,
  PackageSearch,
  RefreshCw,
  Settings,
  Store,
  Truck,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  useWildberriesSync,
  type WildberriesSyncController,
} from "./features/wildberries/useWildberriesSync";
import { commands } from "./generated/commands";
import type { BootstrapResponse, DashboardResponse, ShopSummary } from "./generated/types";

type WorkspaceState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: BootstrapResponse };

type DashboardState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: DashboardResponse };

const navigation = [
  { label: "Главная", icon: LayoutDashboard, active: true },
  { label: "Поставки FBS", icon: Truck },
  { label: "Заказы", icon: PackageSearch },
  { label: "Печать штрихкодов", icon: Barcode },
  { label: "Поставки FBO", icon: Boxes },
  { label: "История печати", icon: History },
];

const numberFormat = new Intl.NumberFormat("ru-RU");

export function App() {
  const [workspace, setWorkspace] = useState<WorkspaceState>({ status: "loading" });
  const [dashboard, setDashboard] = useState<DashboardState>({ status: "idle" });
  const [selectedShopId, setSelectedShopId] = useState<number | null>(null);
  const dashboardRequest = useRef(0);

  const loadDashboard = useCallback(async (shopId: number) => {
    const requestId = ++dashboardRequest.current;
    setDashboard({ status: "loading" });
    try {
      const response = await commands.dashboard.load({ shopId });
      if (dashboardRequest.current === requestId) {
        setDashboard({ status: "ready", data: response });
      }
    } catch {
      if (dashboardRequest.current === requestId) {
        setDashboard({ status: "error" });
      }
    }
  }, []);
  const wildberriesSync = useWildberriesSync(selectedShopId, loadDashboard);

  useEffect(() => {
    let active = true;
    void commands.workspace.bootstrap({ locale: document.documentElement.lang || "ru" }).then(
      (response) => {
        if (!active) return;
        const initialShopId = response.hasSelectedShop
          ? response.selectedShopId
          : (response.shops[0]?.id ?? null);
        setWorkspace({ status: "ready", data: response });
        setSelectedShopId(initialShopId);
        if (initialShopId !== null) {
          void loadDashboard(initialShopId);
        }
      },
      () => {
        if (!active) return;
        setWorkspace({ status: "error" });
        setSelectedShopId(null);
      },
    );
    return () => {
      active = false;
      dashboardRequest.current += 1;
    };
  }, [loadDashboard]);

  const retryWorkspace = async () => {
    dashboardRequest.current += 1;
    setWorkspace({ status: "loading" });
    setDashboard({ status: "idle" });
    try {
      const response = await commands.workspace.bootstrap({ locale: document.documentElement.lang || "ru" });
      const initialShopId = response.hasSelectedShop
        ? response.selectedShopId
        : (response.shops[0]?.id ?? null);
      setWorkspace({ status: "ready", data: response });
      setSelectedShopId(initialShopId);
      if (initialShopId !== null) {
        await loadDashboard(initialShopId);
      }
    } catch {
      setWorkspace({ status: "error" });
      setSelectedShopId(null);
    }
  };

  const selectShop = (shopId: number) => {
    setSelectedShopId(shopId);
    void loadDashboard(shopId);
  };

  if (workspace.status === "loading") {
    return <CenteredState kind="loading" />;
  }

  if (workspace.status === "error") {
    return <CenteredState kind="error" onRetry={() => void retryWorkspace()} />;
  }

  const selectedShop = workspace.data.shops.find((shop) => shop.id === selectedShopId) ?? null;

  return (
    <div className="min-h-screen bg-[var(--surface-canvas)] text-[var(--text-primary)] md:grid md:grid-cols-[15.5rem_1fr]">
      <Sidebar version={workspace.data.app.version} />
      <div className="min-w-0">
        <header className="sticky top-0 z-20 flex min-h-18 items-center justify-between gap-4 border-b border-[var(--border-subtle)] bg-[color:var(--surface-elevated)] px-4 md:px-7">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-[0.12em] text-[var(--text-muted)] uppercase">
              Рабочее пространство
            </p>
            <h1 className="truncate text-lg font-semibold tracking-[-0.02em]">Управление продажами</h1>
          </div>
          <div className="flex items-center gap-2">
            <button className="icon-button" type="button" aria-label="Помощь">
              <CircleHelp aria-hidden="true" size={18} />
            </button>
            <button className="icon-button" type="button" aria-label="Настройки">
              <Settings aria-hidden="true" size={18} />
            </button>
          </div>
        </header>

        <main className="mx-auto w-full max-w-[96rem] p-4 md:p-7 lg:p-9">
          <section className="mb-7 flex flex-col justify-between gap-5 lg:flex-row lg:items-end">
            <div>
              <p className="mb-2 inline-flex items-center gap-2 text-sm font-medium text-[var(--accent-strong)]">
                <span className="h-2 w-2 rounded-full bg-[var(--accent)]" aria-hidden="true" />
                Локальные данные WCode
              </p>
              <h2 className="text-3xl font-semibold tracking-[-0.035em]">Обзор магазина</h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--text-secondary)]">
                Быстрый срез каталога, новых заказов и активных поставок без раскрытия API-токена.
              </p>
            </div>
            <ShopPicker shops={workspace.data.shops} selectedId={selectedShopId} onSelect={selectShop} />
          </section>

          {selectedShop === null ? (
            <EmptyWorkspace />
          ) : (
            <Dashboard shop={selectedShop} state={dashboard} sync={wildberriesSync} />
          )}
        </main>
      </div>
    </div>
  );
}

function Sidebar({ version }: { version: string }) {
  return (
    <aside className="border-b border-white/10 bg-[var(--sidebar)] text-white md:sticky md:top-0 md:flex md:h-screen md:flex-col md:border-r md:border-b-0">
      <div className="flex h-18 items-center gap-3 px-5">
        <div className="grid size-9 place-items-center rounded-[0.65rem] bg-[var(--accent)] text-sm font-black text-[var(--sidebar)]">
          W
        </div>
        <div>
          <p className="text-base font-semibold tracking-[-0.02em]">WCode</p>
          <p className="text-[0.68rem] font-medium tracking-[0.14em] text-white/45 uppercase">Seller desktop</p>
        </div>
      </div>
      <nav className="hidden flex-1 px-3 py-5 md:block" aria-label="Основная навигация">
        <p className="mb-2 px-3 text-[0.68rem] font-semibold tracking-[0.14em] text-white/35 uppercase">Работа</p>
        <ul className="grid gap-1">
          {navigation.map(({ label, icon: Icon, active }) => (
            <li key={label}>
              <button
                className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition ${
                  active ? "bg-white/11 font-semibold text-white" : "text-white/58 hover:bg-white/6 hover:text-white"
                }`}
                type="button"
                aria-current={active ? "page" : undefined}
              >
                <Icon aria-hidden="true" size={18} strokeWidth={active ? 2.2 : 1.8} />
                <span>{label}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>
      <div className="hidden border-t border-white/8 p-4 md:block">
        <div className="rounded-xl bg-white/5 px-3 py-3">
          <p className="text-xs font-medium text-white/72">WCode {version}</p>
          <p className="mt-1 text-[0.7rem] leading-4 text-white/38">jDesk preview · локальный режим</p>
        </div>
      </div>
    </aside>
  );
}

function ShopPicker({
  shops,
  selectedId,
  onSelect,
}: {
  shops: ShopSummary[];
  selectedId: number | null;
  onSelect: (shopId: number) => void;
}) {
  return (
    <label className="relative grid min-w-64 gap-1.5 text-xs font-semibold text-[var(--text-secondary)]">
      Магазин
      <Store className="pointer-events-none absolute bottom-3 left-3 text-[var(--text-muted)]" size={17} />
      <select
        className="h-11 appearance-none rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-10 pl-10 text-sm font-medium text-[var(--text-primary)] shadow-[var(--shadow-control)] outline-none transition hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
        value={selectedId ?? ""}
        onChange={(event) => onSelect(Number(event.target.value))}
        disabled={shops.length === 0}
      >
        {shops.length === 0 && <option value="">Нет магазинов</option>}
        {shops.map((shop) => (
          <option key={shop.id} value={shop.id}>
            {shop.name}
          </option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute right-3 bottom-3 text-[var(--text-muted)]" size={17} />
    </label>
  );
}

function Dashboard({
  shop,
  state,
  sync,
}: {
  shop: ShopSummary;
  state: DashboardState;
  sync: WildberriesSyncController;
}) {
  const data = state.status === "ready" ? state.data : null;
  const syncing = ["starting", "running", "cancelling"].includes(sync.state.status);
  const metrics = [
    { label: "Товаров в каталоге", value: data?.productCount, icon: Boxes },
    { label: "Новых заказов", value: data?.newOrderCount, icon: PackageSearch },
    { label: "Открытых поставок", value: data?.openSupplyCount, icon: Truck },
  ];

  return (
    <div className="grid gap-5">
      <section className="grid overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)] sm:grid-cols-[1fr_auto]">
        <div className="flex min-w-0 items-center gap-4 p-5 md:p-6">
          <div className="grid size-11 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
            <Store aria-hidden="true" size={21} />
          </div>
          <div className="min-w-0">
            <h3 className="truncate font-semibold">{shop.name}</h3>
            <p className="mt-1 flex items-center gap-1.5 text-xs text-[var(--text-secondary)]">
              <KeyRound aria-hidden="true" size={13} />
              {shop.tokenConfigured ? "Токен подключён" : "Токен не настроен"}
            </p>
          </div>
        </div>
        <div className="flex items-center border-t border-[var(--border-subtle)] px-5 py-4 sm:border-t-0 sm:border-l">
          <button
            className="inline-flex h-10 items-center gap-2 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] hover:text-[var(--accent-strong)] disabled:cursor-wait disabled:opacity-55"
            type="button"
            onClick={() => void (syncing ? sync.cancel() : sync.start())}
            disabled={!shop.tokenConfigured || sync.state.status === "cancelling"}
          >
            <RefreshCw className={syncing ? "animate-spin" : ""} size={16} />
            {syncing ? "Отменить синхронизацию" : "Синхронизировать с Wildberries"}
          </button>
        </div>
      </section>

      <SyncNotice state={sync.state} />

      {state.status === "error" && (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
          Не удалось загрузить показатели. Повторите попытку.
        </div>
      )}

      <section className="grid gap-4 md:grid-cols-3" aria-label="Ключевые показатели">
        {metrics.map(({ label, value, icon: Icon }) => (
          <article
            className="relative overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)] md:p-6"
            key={label}
          >
            <div className="mb-7 flex items-start justify-between gap-4">
              <p className="text-sm font-medium text-[var(--text-secondary)]">{label}</p>
              <span className="grid size-9 place-items-center rounded-lg bg-[var(--surface-muted)] text-[var(--text-secondary)]">
                <Icon aria-hidden="true" size={18} />
              </span>
            </div>
            <p className="text-3xl font-semibold tracking-[-0.04em] tabular-nums">
              {state.status === "loading" || state.status === "idle" ? (
                <span className="inline-block h-9 w-24 animate-pulse rounded-lg bg-[var(--surface-muted)]" aria-label="Загрузка" />
              ) : value === undefined ? (
                "—"
              ) : (
                numberFormat.format(value)
              )}
            </p>
          </article>
        ))}
      </section>
    </div>
  );
}

function SyncNotice({ state }: { state: WildberriesSyncController["state"] }) {
  if (state.status === "idle") return null;
  if (state.status === "starting" || state.status === "running" || state.status === "cancelling") {
    return (
      <div
        className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
        role="status"
        aria-live="polite"
      >
        {state.status === "cancelling"
          ? "Останавливаем после безопасного завершения текущего шага…"
          : "Получаем актуальные данные Wildberries в фоновом режиме…"}
      </div>
    );
  }
  if (state.status === "completed") {
    return (
      <div
        className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
        role="status"
      >
        <span className="font-semibold">Синхронизация завершена</span>
        <span className="ml-2 text-emerald-800">
          Обновлено: товаров {numberFormat.format(state.result.products)}, поставок{" "}
          {numberFormat.format(state.result.supplies)}.
        </span>
      </div>
    );
  }
  if (state.status === "cancelled") {
    return (
      <div className="rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700" role="status">
        Синхронизация остановлена. Уже сохранённые страницы данных оставлены без изменений.
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
      {syncErrorMessage(state.errorKind, state.retryable)}
    </div>
  );
}

function syncErrorMessage(errorKind: string, retryable: boolean): string {
  if (errorKind === "token_invalid" || errorKind === "token_missing") {
    return "Токен Wildberries недействителен или не имеет нужных прав. Проверьте настройки магазина.";
  }
  if (errorKind === "rate_limited") {
    return "Wildberries временно ограничил запросы. Повторите синхронизацию позже.";
  }
  return retryable
    ? "Wildberries не завершил синхронизацию. Локальные данные сохранены — можно повторить попытку."
    : "Синхронизацию нельзя запустить с текущими настройками магазина.";
}

function EmptyWorkspace() {
  return (
    <section className="grid min-h-80 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <div className="mx-auto mb-4 grid size-12 place-items-center rounded-xl bg-[var(--surface-muted)] text-[var(--text-secondary)]">
          <Store aria-hidden="true" size={22} />
        </div>
        <h3 className="font-semibold">Добавьте магазин, чтобы начать работу</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">Управление магазинами будет подключено в следующем разделе.</p>
      </div>
    </section>
  );
}

function CenteredState({ kind, onRetry }: { kind: "loading" | "error"; onRetry?: () => void }) {
  return (
    <main className="grid min-h-screen place-items-center bg-[var(--surface-canvas)] p-6">
      <section className="w-full max-w-md rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-8 text-center shadow-[var(--shadow-panel)]">
        {kind === "loading" ? (
          <>
            <RefreshCw className="mx-auto mb-5 animate-spin text-[var(--accent-strong)]" size={28} />
            <h1 className="text-lg font-semibold" role="status">Загружаем рабочее пространство</h1>
            <p className="mt-2 text-sm text-[var(--text-secondary)]">Проверяем локальную базу и список магазинов.</p>
          </>
        ) : (
          <div role="alert">
            <h1 className="text-lg font-semibold">Не удалось открыть рабочее пространство</h1>
            <p className="mt-2 text-sm text-[var(--text-secondary)]">Данные не изменены. Проверьте подключение и повторите.</p>
            <button className="mt-5 rounded-xl bg-[var(--accent-strong)] px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={onRetry}>
              Повторить
            </button>
          </div>
        )}
      </section>
    </main>
  );
}
