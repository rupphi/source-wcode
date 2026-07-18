import {
  Barcode,
  Boxes,
  History,
  LayoutDashboard,
  PackageSearch,
  Truck,
} from "lucide-react";

const navigation = [
  { label: "Главная", icon: LayoutDashboard, active: true },
  { label: "Поставки FBS", icon: Truck },
  { label: "Заказы", icon: PackageSearch },
  { label: "Печать штрихкодов", icon: Barcode },
  { label: "Поставки FBO", icon: Boxes },
  { label: "История печати", icon: History },
];

export function Sidebar({ version }: { version: string }) {
  return (
    <aside className="border-b border-white/10 bg-[var(--sidebar)] text-white md:sticky md:top-0 md:flex md:h-screen md:flex-col md:border-r md:border-b-0">
      <div className="flex h-18 items-center gap-3 px-5">
        <div className="grid size-9 place-items-center rounded-[0.65rem] bg-[var(--accent)] text-sm font-black text-[var(--sidebar)]">
          W
        </div>
        <div>
          <p className="text-base font-semibold tracking-[-0.02em]">WCode</p>
          <p className="text-[0.68rem] font-medium tracking-[0.14em] text-white/45 uppercase">
            Seller desktop
          </p>
        </div>
      </div>
      <nav className="hidden flex-1 px-3 py-5 md:block" aria-label="Основная навигация">
        <p className="mb-2 px-3 text-[0.68rem] font-semibold tracking-[0.14em] text-white/35 uppercase">
          Работа
        </p>
        <ul className="grid gap-1">
          {navigation.map(({ label, icon: Icon, active }) => (
            <li key={label}>
              <button
                className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition ${
                  active
                    ? "bg-white/11 font-semibold text-white"
                    : "text-white/58 hover:bg-white/6 hover:text-white"
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
