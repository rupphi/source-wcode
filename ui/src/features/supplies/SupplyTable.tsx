import { ArrowLeft, ArrowRight, ArrowUpRight, CalendarDays } from "lucide-react";
import { useMemo } from "react";
import type { SupplyItem } from "../../generated/types";
import { interpolate } from "../../i18n";
import { defaultSupplyCopy, type SupplyCopy } from "./supplyI18n";

export function SupplyTable({ items, onOpen, copy = defaultSupplyCopy, locale = "ru-RU" }: { items: SupplyItem[]; onOpen: (item: SupplyItem) => void; copy?: SupplyCopy; locale?: string }) {
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const dateTimeFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }), [locale]);
  return (
    <section className="overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full table-fixed border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-3 py-2.5" scope="col">{copy.list.columns.supply}</th>
              <th className="w-24 px-3 py-2.5" scope="col">{copy.list.columns.status}</th>
              <th className="hidden w-24 px-3 py-2.5 lg:table-cell" scope="col">{copy.list.columns.mode}</th>
              <th className="hidden w-44 px-3 py-2.5 xl:table-cell" scope="col">{copy.list.columns.created}</th>
              <th className="hidden w-24 px-3 py-2.5 text-right sm:table-cell" scope="col">{copy.list.columns.items}</th>
              <th className="w-14 px-2 py-2.5" scope="col"><span className="sr-only">{copy.list.columns.actions}</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.id}>
                <td className="min-w-0 px-3 py-3">
                  <p className="max-w-md truncate text-sm font-semibold">{item.name}</p>
                  <p className="mt-0.5 truncate font-mono text-[0.68rem] text-[var(--text-muted)]">{item.id}</p>
                </td>
                <td className="px-3 py-3"><StatusBadge status={item.status} copy={copy} /></td>
                <td className="hidden px-3 py-3 text-xs text-[var(--text-secondary)] lg:table-cell">{modeLabel(item.mode, copy)}</td>
                <td className="hidden px-3 py-3 text-xs text-[var(--text-secondary)] xl:table-cell">
                  <span className="inline-flex items-center gap-2 whitespace-nowrap">
                    <CalendarDays aria-hidden="true" size={15} />
                    {formatCreatedAt(item.createdAt, dateTimeFormat)}
                  </span>
                </td>
                <td className="hidden px-3 py-3 text-right text-xs font-semibold tabular-nums sm:table-cell">
                  {numberFormat.format(item.itemCount)}
                </td>
                <td className="px-2 py-3">
                  <button
                    className="icon-button"
                    type="button"
                    aria-label={interpolate(copy.list.openSupply, { name: item.name })}
                    onClick={() => onOpen(item)}
                  >
                    <ArrowUpRight aria-hidden="true" size={17} />
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

function StatusBadge({ status, copy }: { status: string; copy: SupplyCopy }) {
  const open = status === "open";
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
        open ? "bg-amber-50 text-amber-800" : "bg-emerald-50 text-emerald-800"
      }`}
    >
      <span className={`size-1.5 rounded-full ${open ? "bg-amber-500" : "bg-emerald-500"}`} />
      {open ? copy.list.open : copy.list.closed}
    </span>
  );
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPage,
  ariaLabel,
  previousLabel,
  nextLabel,
  foundLabel,
  pageOfLabel,
  copy = defaultSupplyCopy,
  locale = "ru-RU",
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
  ariaLabel?: string;
  previousLabel?: string;
  nextLabel?: string;
  foundLabel?: string;
  pageOfLabel?: string;
  copy?: SupplyCopy;
  locale?: string;
}) {
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  return (
    <nav
      className="flex flex-col items-center justify-between gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3 sm:flex-row"
      aria-label={ariaLabel ?? copy.list.pagination}
    >
      <p className="text-sm text-[var(--text-secondary)]">
        {foundLabel ?? copy.list.found} <span className="font-semibold text-[var(--text-primary)]">{numberFormat.format(totalItems)}</span>
      </p>
      <div className="flex items-center gap-3">
        <button
          className="icon-button"
          type="button"
          aria-label={previousLabel ?? copy.list.previousPage}
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >
          <ArrowLeft aria-hidden="true" size={17} />
        </button>
        <p className="min-w-30 text-center text-sm font-semibold tabular-nums">
          {interpolate(pageOfLabel ?? copy.list.pageOf, {
            page: numberFormat.format(page),
            total: numberFormat.format(Math.max(totalPages, 1)),
          })}
        </p>
        <button
          className="icon-button"
          type="button"
          aria-label={nextLabel ?? copy.list.nextPage}
          disabled={page >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          <ArrowRight aria-hidden="true" size={17} />
        </button>
      </div>
    </nav>
  );
}

function formatCreatedAt(value: string, dateTimeFormat: Intl.DateTimeFormat): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : dateTimeFormat.format(date);
}

function modeLabel(mode: string, copy: SupplyCopy): string {
  if (mode === "b2b") return "B2B";
  if (mode === "consumer") return "B2C";
  return copy.list.modeUnknown;
}
