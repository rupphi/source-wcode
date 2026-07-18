import { ArrowLeft, ArrowRight, CalendarDays } from "lucide-react";
import type { SupplyItem } from "../../generated/types";

const numberFormat = new Intl.NumberFormat("ru-RU");
const dateTimeFormat = new Intl.DateTimeFormat("ru-RU", {
  dateStyle: "medium",
  timeStyle: "short",
});

export function SupplyTable({ items }: { items: SupplyItem[] }) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[52rem] border-collapse text-left">
          <thead className="border-b border-[var(--border-subtle)] bg-[var(--surface-muted)]/70">
            <tr className="text-xs font-semibold tracking-[0.04em] text-[var(--text-secondary)] uppercase">
              <th className="px-5 py-3.5" scope="col">Поставка</th>
              <th className="px-4 py-3.5" scope="col">Статус</th>
              <th className="px-4 py-3.5" scope="col">Схема</th>
              <th className="px-4 py-3.5" scope="col">Создана</th>
              <th className="px-5 py-3.5 text-right" scope="col">Товаров</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-subtle)]">
            {items.map((item) => (
              <tr className="transition hover:bg-[var(--surface-muted)]/55" key={item.id}>
                <td className="px-5 py-4">
                  <p className="max-w-md truncate text-sm font-semibold">{item.name}</p>
                  <p className="mt-1 font-mono text-xs text-[var(--text-muted)]">{item.id}</p>
                </td>
                <td className="px-4 py-4"><StatusBadge status={item.status} /></td>
                <td className="px-4 py-4 text-sm text-[var(--text-secondary)]">{modeLabel(item.mode)}</td>
                <td className="px-4 py-4 text-sm text-[var(--text-secondary)]">
                  <span className="inline-flex items-center gap-2 whitespace-nowrap">
                    <CalendarDays aria-hidden="true" size={15} />
                    {formatCreatedAt(item.createdAt)}
                  </span>
                </td>
                <td className="px-5 py-4 text-right text-sm font-semibold tabular-nums">
                  {numberFormat.format(item.itemCount)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function StatusBadge({ status }: { status: string }) {
  const open = status === "open";
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
        open ? "bg-amber-50 text-amber-800" : "bg-emerald-50 text-emerald-800"
      }`}
    >
      <span className={`size-1.5 rounded-full ${open ? "bg-amber-500" : "bg-emerald-500"}`} />
      {open ? "Открыта" : "Закрыта"}
    </span>
  );
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
}) {
  return (
    <nav
      className="flex flex-col items-center justify-between gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3 sm:flex-row"
      aria-label="Пагинация поставок"
    >
      <p className="text-sm text-[var(--text-secondary)]">
        Найдено <span className="font-semibold text-[var(--text-primary)]">{numberFormat.format(totalItems)}</span>
      </p>
      <div className="flex items-center gap-3">
        <button
          className="icon-button"
          type="button"
          aria-label="Предыдущая страница"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >
          <ArrowLeft aria-hidden="true" size={17} />
        </button>
        <p className="min-w-30 text-center text-sm font-semibold tabular-nums">
          Страница {page} из {Math.max(totalPages, 1)}
        </p>
        <button
          className="icon-button"
          type="button"
          aria-label="Следующая страница"
          disabled={page >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          <ArrowRight aria-hidden="true" size={17} />
        </button>
      </div>
    </nav>
  );
}

function formatCreatedAt(value: string): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : dateTimeFormat.format(date);
}

function modeLabel(mode: string): string {
  if (mode === "b2b") return "B2B";
  if (mode === "consumer") return "B2C";
  return "Не указана";
}
