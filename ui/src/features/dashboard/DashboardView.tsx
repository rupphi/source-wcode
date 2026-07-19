import { Boxes, KeyRound, PackageSearch, RefreshCw, Store, Truck } from "lucide-react";
import { useMemo } from "react";
import type { WildberriesSyncController } from "../wildberries/useWildberriesSync";
import type { DashboardResponse, ShopSummary } from "../../generated/types";
import type { AppCopy } from "../../i18n";
import { interpolate } from "../../i18n";
import type { DashboardCopy } from "./dashboardI18n";

export type DashboardState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: DashboardResponse };

export function DashboardView({
  copy,
  locale,
  shop,
  state,
  sync,
}: {
  copy: DashboardCopy;
  locale: string;
  shop: ShopSummary;
  state: DashboardState;
  sync: WildberriesSyncController;
}) {
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const data = state.status === "ready" ? state.data : null;
  const syncing = ["starting", "running", "cancelling"].includes(sync.state.status);
  const metrics = [
    { label: copy.products, value: data?.productCount, icon: Boxes },
    { label: copy.orders, value: data?.newOrderCount, icon: PackageSearch },
    { label: copy.supplies, value: data?.openSupplyCount, icon: Truck },
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
              {shop.tokenConfigured ? copy.tokenConnected : copy.tokenMissing}
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
            {syncing ? copy.syncCancel : copy.syncStart}
          </button>
        </div>
      </section>

      <SyncNotice copy={copy} numberFormat={numberFormat} state={sync.state} />

      {state.status === "error" && (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
          {copy.loadError}
        </div>
      )}

      <section className="grid gap-4 md:grid-cols-3" aria-label={copy.metricsAria}>
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
                  aria-label={copy.loading}
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

function SyncNotice({ copy, numberFormat, state }: { copy: DashboardCopy; numberFormat: Intl.NumberFormat; state: WildberriesSyncController["state"] }) {
  if (state.status === "idle") return null;
  if (state.status === "starting" || state.status === "running" || state.status === "cancelling") {
    return (
      <div
        className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
        role="status"
        aria-live="polite"
      >
        {state.status === "cancelling"
          ? copy.sync.stopping
          : copy.sync.running}
      </div>
    );
  }
  if (state.status === "completed") {
    return (
      <div
        className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
        role="status"
      >
        <span className="font-semibold">{copy.sync.completed}</span>
        <span className="ml-2 text-emerald-800">
          {interpolate(copy.sync.completedDetail, {
            products: numberFormat.format(state.result.products),
            supplies: numberFormat.format(state.result.supplies),
          })}
        </span>
      </div>
    );
  }
  if (state.status === "cancelled") {
    return (
      <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3 text-sm text-[var(--text-secondary)]" role="status">
        {copy.sync.cancelled}
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
      {syncErrorMessage(copy, state.errorKind, state.retryable)}
    </div>
  );
}

function syncErrorMessage(copy: DashboardCopy, errorKind: string, retryable: boolean): string {
  if (errorKind === "token_invalid" || errorKind === "token_missing") {
    return copy.sync.tokenInvalid;
  }
  if (errorKind === "rate_limited") {
    return copy.sync.rateLimited;
  }
  return retryable
    ? copy.sync.retryable
    : copy.sync.blocked;
}

export function EmptyWorkspace({ copy }: { copy: AppCopy["shop"] }) {
  return (
    <section className="grid min-h-80 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div>
        <div className="mx-auto mb-4 grid size-12 place-items-center rounded-xl bg-[var(--surface-muted)] text-[var(--text-secondary)]">
          <Store aria-hidden="true" size={22} />
        </div>
        <h3 className="font-semibold">{copy.emptyTitle}</h3>
        <p className="mt-2 text-sm text-[var(--text-secondary)]">
          {copy.emptyDescription}
        </p>
      </div>
    </section>
  );
}
