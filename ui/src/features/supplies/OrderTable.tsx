import { useMemo, useState } from "react";
import { CalendarDays, ImageIcon, KeyRound } from "lucide-react";
import type { OrderItem } from "../../generated/types";
import { interpolate } from "../../i18n";
import { defaultSupplyCopy, type SupplyCopy } from "./supplyI18n";

const SAFE_ORDER_IMAGE_PATH = /^jdesk:\/\/app\/order-images\/[A-Za-z0-9_-]{43}\.(?:png|jpg)$/;

export function OrderTable({ items, copy = defaultSupplyCopy, locale = "ru-RU" }: { items: OrderItem[]; copy?: SupplyCopy; locale?: string }) {
  const priceFormat = useMemo(() => new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }), [locale]);
  const dateTimeFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }), [locale]);
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[66rem] border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-5 py-3.5" scope="col">{copy.orders.columns.order}</th>
              <th className="px-4 py-3.5" scope="col">{copy.orders.columns.product}</th>
              <th className="px-4 py-3.5" scope="col">{copy.orders.columns.articleBarcode}</th>
              <th className="px-4 py-3.5" scope="col">{copy.orders.columns.variant}</th>
              <th className="px-4 py-3.5" scope="col">{copy.orders.columns.status}</th>
              <th className="px-5 py-3.5 text-right" scope="col">{copy.orders.columns.price}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.orderId}>
                <td className="px-5 py-4 align-top">
                  <p className="font-mono text-xs font-semibold text-[var(--text-primary)]">{item.orderId}</p>
                  <p className="mt-2 inline-flex items-center gap-1.5 whitespace-nowrap text-xs text-[var(--text-muted)]">
                    <CalendarDays aria-hidden="true" size={13} />
                    {formatCreatedAt(item.createdAt, dateTimeFormat)}
                  </p>
                </td>
                <td className="px-4 py-4 align-top">
                  <div className="flex min-w-0 items-start gap-3">
                    <OrderThumbnail name={item.name} path={item.imagePath} copy={copy} />
                    <div className="min-w-0">
                      <p className="max-w-64 truncate text-sm font-semibold">{item.name}</p>
                      <p className="mt-1 max-w-64 truncate text-xs text-[var(--text-secondary)]">{[item.brand, item.subject].filter(Boolean).join(" · ") || "—"}</p>
                      {item.nmId && <p className="mt-1 text-xs text-[var(--text-muted)]">nmID {item.nmId}</p>}
                    </div>
                  </div>
                </td>
                <td className="px-4 py-4 align-top text-sm">
                  <p className="font-medium">{item.article || "—"}</p>
                  <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{item.barcode || "—"}</p>
                </td>
                <td className="px-4 py-4 align-top text-sm text-[var(--text-secondary)]">
                  <p>{[item.color, item.size].filter(Boolean).join(" · ") || "—"}</p>
                  {item.russianSize && <p className="mt-1 text-xs text-[var(--text-muted)]">RU {item.russianSize}</p>}
                </td>
                <td className="px-4 py-4 align-top">
                  <OrderStatus status={item.supplierStatus} copy={copy} />
                  <p className="mt-2 text-xs text-[var(--text-muted)]">WB: {item.wbStatus || "—"}</p>
                  {item.requiresKiz && (
                    <p className="mt-2 inline-flex items-center gap-1.5 text-xs font-semibold text-violet-700">
                      <KeyRound aria-hidden="true" size={13} />
                      {copy.orders.requiresKiz}
                    </p>
                  )}
                </td>
                <td className="px-5 py-4 text-right align-top text-sm font-semibold whitespace-nowrap tabular-nums">
                  {priceFormat.format(item.priceKopecks / 100)} ₽
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function OrderThumbnail({ name, path, copy = defaultSupplyCopy }: { name: string; path: string; copy?: SupplyCopy }) {
  const [failed, setFailed] = useState(false);
  const canRender = SAFE_ORDER_IMAGE_PATH.test(path) && !failed;

  return (
    <div className="grid size-12 shrink-0 place-items-center overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] text-[var(--text-muted)]">
      {canRender ? (
        <img
          alt={interpolate(copy.orders.productPhoto, { name })}
          className="size-full object-cover"
          decoding="async"
          loading="lazy"
          onError={() => setFailed(true)}
          src={path}
        />
      ) : (
        <ImageIcon aria-hidden="true" size={19} />
      )}
    </div>
  );
}

function OrderStatus({ status, copy }: { status: string; copy: SupplyCopy }) {
  const labels: Record<string, string> = copy.orders.statuses;
  return (
    <span className="inline-flex rounded-full bg-sky-50 px-2.5 py-1 text-xs font-semibold text-sky-800">
      {labels[status] ?? (status || copy.orders.statuses.unknown)}
    </span>
  );
}

export function OrderTableLoading({ copy = defaultSupplyCopy }: { copy?: SupplyCopy }) {
  return (
    <section className="grid gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-[var(--shadow-panel)]" aria-label={copy.orders.loading}>
      {[0, 1, 2, 3].map((row) => (
        <div className="flex items-center gap-4 py-3" key={row}>
          <span className="h-12 w-40 animate-pulse rounded-lg bg-[var(--surface-muted)]" />
          <span className="h-12 flex-1 animate-pulse rounded-lg bg-[var(--surface-muted)]" />
          <span className="hidden h-12 w-32 animate-pulse rounded-lg bg-[var(--surface-muted)] sm:block" />
        </div>
      ))}
    </section>
  );
}

function formatCreatedAt(value: string, dateTimeFormat: Intl.DateTimeFormat): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : dateTimeFormat.format(date);
}
