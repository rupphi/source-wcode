import {
  Barcode,
  Boxes,
  History,
  LayoutDashboard,
  Link2,
  Menu,
  PackageSearch,
  Tag,
  Truck,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useRef, useState } from "react";
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
  const [navigationOpen, setNavigationOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);

  return (
    <aside className="sticky top-0 z-30 border-b border-white/10 bg-[var(--sidebar)] text-white md:flex md:h-screen md:flex-col md:border-r md:border-b-0">
      <div className="flex h-14 items-center justify-between gap-3 px-3.5">
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="grid size-8 shrink-0 place-items-center rounded-lg bg-[var(--accent)] text-xs font-black text-[var(--sidebar)]">
            W
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold tracking-[-0.02em]">WCode</p>
            <p className="truncate text-[0.62rem] font-medium tracking-[0.12em] text-white/48 uppercase">
              {copy.sellerDesktop}
            </p>
          </div>
        </div>
        <button
          ref={menuButtonRef}
          aria-controls="primary-navigation"
          aria-expanded={navigationOpen}
          aria-label={navigationOpen ? copy.closeNavigation : copy.openNavigation}
          className="grid size-8 shrink-0 place-items-center rounded-lg border border-white/12 bg-white/6 text-white transition hover:bg-white/12 md:hidden"
          onClick={() => setNavigationOpen((open) => !open)}
          title={navigationOpen ? copy.closeNavigation : copy.openNavigation}
          type="button"
        >
          {navigationOpen ? <X aria-hidden="true" size={17} /> : <Menu aria-hidden="true" size={17} />}
        </button>
      </div>
      <nav
        id="primary-navigation"
        className={`${navigationOpen ? "block" : "hidden"} absolute inset-x-0 top-14 max-h-[calc(100vh-3.5rem)] overflow-y-auto border-b border-white/10 bg-[var(--sidebar)] px-2.5 pb-2.5 shadow-[var(--shadow-popover)] md:static md:block md:flex-1 md:border-b-0 md:py-3 md:shadow-none`}
        aria-label={copy.navigationLabel}
      >
        <p className="mb-1.5 hidden px-2.5 text-[0.62rem] font-semibold tracking-[0.12em] text-white/45 uppercase md:block">
          {copy.work}
        </p>
        <ul className="grid gap-0.5">
          {navigation.map(({ label: labelKey, icon: Icon, view }) => {
            const active = view === activeView;
            const label = copy.nav[labelKey];
            return (
              <li className={view === null ? "hidden md:block" : ""} key={labelKey}>
                <button
                  className={`flex min-h-9 w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[0.78rem] transition ${
                    active
                      ? "bg-white/11 font-semibold text-white"
                      : "text-white/58 hover:bg-white/6 hover:text-white disabled:cursor-default disabled:opacity-45"
                  }`}
                  type="button"
                  aria-current={active ? "page" : undefined}
                  onClick={view === null ? undefined : () => {
                    onNavigate(view);
                    setNavigationOpen(false);
                    if (navigationOpen) requestAnimationFrame(() => menuButtonRef.current?.focus());
                  }}
                  disabled={view === null}
                >
                  <Icon aria-hidden="true" size={17} strokeWidth={active ? 2.2 : 1.8} />
                  <span>{label}</span>
                </button>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="hidden border-t border-white/8 p-2.5 md:block">
        <div className="rounded-lg bg-white/5 px-2.5 py-2">
          <p className="text-[0.7rem] font-medium text-white/72">WCode {version}</p>
          <p className="mt-0.5 text-[0.65rem] leading-4 text-white/45">{copy.preview}</p>
        </div>
      </div>
    </aside>
  );
}
