import {
  Barcode,
  Boxes,
  History,
  LayoutDashboard,
  Link2,
  PackageSearch,
  Tag,
  Truck,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { AppCopy } from "../i18n";

export type WorkspaceView =
  | "dashboard"
  | "packing"
  | "supplies"
  | "fbo"
  | "kizMapping"
  | "znack"
  | "templates"
  | "history";

type NavigationItem = {
  label: keyof AppCopy["shell"]["nav"];
  icon: LucideIcon;
  view: WorkspaceView | null;
};

const navigation: NavigationItem[] = [
  { label: "dashboard", icon: LayoutDashboard, view: "dashboard" },
  { label: "packing", icon: PackageSearch, view: "packing" },
  { label: "supplies", icon: Truck, view: "supplies" },
  { label: "templates", icon: Barcode, view: "templates" },
  { label: "fbo", icon: Boxes, view: "fbo" },
  { label: "kizMapping", icon: Link2, view: "kizMapping" },
  { label: "znack", icon: Tag, view: "znack" },
  { label: "history", icon: History, view: "history" },
];

export function Sidebar({
  version,
  activeView,
  onNavigate,
  copy,
}: {
  version: string;
  activeView: WorkspaceView;
  onNavigate: (view: WorkspaceView) => void;
  copy: AppCopy["shell"];
}) {
  return (
    <aside className="border-b border-white/10 bg-[var(--sidebar)] text-white md:sticky md:top-0 md:flex md:h-screen md:flex-col md:border-r md:border-b-0">
      <div className="flex h-18 items-center gap-3 px-5">
        <div className="grid size-9 place-items-center rounded-[0.65rem] bg-[var(--accent)] text-sm font-black text-[var(--sidebar)]">
          W
        </div>
        <div>
          <p className="text-base font-semibold tracking-[-0.02em]">WCode</p>
          <p className="text-[0.68rem] font-medium tracking-[0.14em] text-white/45 uppercase">
            {copy.sellerDesktop}
          </p>
        </div>
      </div>
      <nav className="overflow-x-auto px-3 pb-3 md:block md:flex-1 md:py-5" aria-label={copy.navigationLabel}>
        <p className="mb-2 hidden px-3 text-[0.68rem] font-semibold tracking-[0.14em] text-white/45 uppercase md:block">
          {copy.work}
        </p>
        <ul className="flex gap-1 md:grid">
          {navigation.map(({ label: labelKey, icon: Icon, view }) => {
            const active = view === activeView;
            const label = copy.nav[labelKey];
            return (
              <li className={view === null ? "hidden md:block" : "shrink-0"} key={labelKey}>
                <button
                  className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition ${
                    active
                      ? "bg-white/11 font-semibold text-white"
                      : "text-white/58 hover:bg-white/6 hover:text-white disabled:cursor-default disabled:opacity-45"
                  }`}
                  type="button"
                  aria-current={active ? "page" : undefined}
                  onClick={view === null ? undefined : () => onNavigate(view)}
                  disabled={view === null}
                >
                  <Icon aria-hidden="true" size={18} strokeWidth={active ? 2.2 : 1.8} />
                  <span>{label}</span>
                </button>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="hidden border-t border-white/8 p-4 md:block">
        <div className="rounded-xl bg-white/5 px-3 py-3">
          <p className="text-xs font-medium text-white/72">WCode {version}</p>
          <p className="mt-1 text-[0.7rem] leading-4 text-white/45">{copy.preview}</p>
        </div>
      </div>
    </aside>
  );
}
