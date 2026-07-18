import { Boxes, KeyRound, PackageSearch, RefreshCw, Store, Truck } from "lucide-react";
import type { WildberriesSyncController } from "../wildberries/useWildberriesSync";
import type { DashboardResponse, ShopSummary } from "../../generated/types";

export type DashboardState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: DashboardResponse };

const numberFormat = new Intl.NumberFormat("ru-RU");

export function DashboardView({
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
                <span
                  className="inline-block h-9 w-24 animate-pulse rounded-lg bg-[var(--surface-muted)]"
                  aria-label="Загрузка"
                />
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
      <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3 text-sm text-[var(--text-secondary)]" role="status">
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

export function EmptyWorkspace() {
  return (
    <section className="grid min-h-80 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <div className="mx-auto mb-4 grid size-12 place-items-center rounded-xl bg-[var(--surface-muted)] text-[var(--text-secondary)]">
          <Store aria-hidden="true" size={22} />
        </div>
        <h3 className="font-semibold">Добавьте магазин, чтобы начать работу</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">
          Управление магазинами будет подключено в следующем разделе.
        </p>
      </div>
    </section>
  );
}
