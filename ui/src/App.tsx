import { CircleHelp, Settings } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { CenteredState } from "./components/CenteredState";
import { ShopPicker } from "./components/ShopPicker";
import { Sidebar, type WorkspaceView } from "./components/Sidebar";
import {
  DashboardView,
  EmptyWorkspace,
  type DashboardState,
} from "./features/dashboard/DashboardView";
import { getDashboardCopy } from "./features/dashboard/dashboardI18n";
import { useWildberriesSync } from "./features/wildberries/useWildberriesSync";
import { SupplyListView } from "./features/supplies/SupplyListView";
import { getSupplyCopy, getSupplyLocale } from "./features/supplies/supplyI18n";
import { PackingView } from "./features/packing/PackingView";
import { getPackingCopy } from "./features/packing/packingI18n";
import { FboPackingView } from "./features/fbo/FboPackingView";
import { getFboCopy } from "./features/fbo/fboI18n";
import { KizMappingView } from "./features/kizmapping/KizMappingView";
import { getKizMappingCopy } from "./features/kizmapping/kizMappingI18n";
import { ZnackView } from "./features/znack/ZnackView";
import { getZnackCopy } from "./features/znack/znackI18n";
import { PrintHistoryView } from "./features/history/PrintHistoryView";
import { getHistoryCopy } from "./features/history/historyI18n";
import { TemplateDesignerView } from "./features/templates/TemplateDesignerView";
import { getTemplateDesignerCopy } from "./features/templates/templateDesignerI18n";
import { LicenseSettingsDialog } from "./features/license/LicenseSettingsDialog";
import { ShopManagementDialog } from "./features/shops/ShopManagementDialog";
import { SupportDialog } from "./features/diagnostics/SupportDialog";
import { validShopState } from "./features/shops/shopState";
import { commands } from "./generated/commands";
import type { BootstrapResponse, ShopState } from "./generated/types";
import { getCopy, isLanguage, isTheme } from "./i18n";
import type { Language, ThemeMode } from "./i18n";

type WorkspaceState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: BootstrapResponse };

export function App() {
  const [workspace, setWorkspace] = useState<WorkspaceState>({ status: "loading" });
  const [dashboard, setDashboard] = useState<DashboardState>({ status: "idle" });
  const [selectedShopId, setSelectedShopId] = useState<number | null>(null);
  const [activeView, setActiveView] = useState<WorkspaceView>("dashboard");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [shopManagerOpen, setShopManagerOpen] = useState(false);
  const [supportOpen, setSupportOpen] = useState(false);
  const [shopSelectionBusy, setShopSelectionBusy] = useState(false);
  const [shopError, setShopError] = useState("");
  const [licenseAllowed, setLicenseAllowed] = useState(false);
  const [preferences, setPreferences] = useState<{ language: Language; theme: ThemeMode }>({
    language: "ru",
    theme: "dark",
  });
  const dashboardRequest = useRef(0);
  const settingsButtonRef = useRef<HTMLButtonElement>(null);
  const shopManagerButtonRef = useRef<HTMLButtonElement>(null);
  const helpButtonRef = useRef<HTMLButtonElement>(null);

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

  const applyPreferences = useCallback((language: Language, theme: ThemeMode) => {
    document.documentElement.lang = language;
    document.documentElement.dataset.theme = theme;
    setPreferences({ language, theme });
  }, []);

  const applyWorkspace = useCallback(
    async (response: BootstrapResponse) => {
      const initialShopId = response.hasSelectedShop
        ? response.selectedShopId
        : (response.shops[0]?.id ?? null);
      setWorkspace({ status: "ready", data: response });
      setSelectedShopId(initialShopId);
      if (initialShopId !== null) {
        await loadDashboard(initialShopId);
      }
    },
    [loadDashboard],
  );

  const applyShopState = useCallback(async (response: ShopState) => {
    if (!validShopState(response)) throw new Error("Invalid shop state");
    const nextShopId = response.hasSelectedShop ? response.selectedShopId : null;
    dashboardRequest.current += 1;
    setWorkspace((current) => current.status === "ready"
      ? { status: "ready", data: { ...current.data, shops: response.shops } }
      : current);
    setSelectedShopId(nextShopId);
    setDashboard(nextShopId === null ? { status: "idle" } : { status: "loading" });
    setShopError("");
    if (nextShopId !== null) await loadDashboard(nextShopId);
  }, [loadDashboard]);

  useEffect(() => {
    let active = true;
    void commands.workspace.bootstrap({ locale: document.documentElement.lang || "ru" }).then(
      async (response) => {
        if (active) await applyWorkspace(response);
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
  }, [applyWorkspace]);

  useEffect(() => {
    let active = true;
    void commands.preferences.load({}).then(
      (response) => {
        if (active && isLanguage(response.language) && isTheme(response.theme)) {
          applyPreferences(response.language, response.theme);
        }
      },
      () => { if (active) applyPreferences("ru", "dark"); },
    );
    return () => { active = false; };
  }, [applyPreferences]);

  useEffect(() => {
    let active = true;
    void commands.license.refresh({}).then(
      (response) => {
        if (!active || typeof response.kizAllowed !== "boolean") return;
        const allowedStatus = response.status === "valid" || response.status === "offline_grace";
        setLicenseAllowed(response.kizAllowed && allowedStatus);
      },
      () => { if (active) setLicenseAllowed(false); },
    );
    return () => { active = false; };
  }, []);

  const retryWorkspace = async () => {
    dashboardRequest.current += 1;
    setWorkspace({ status: "loading" });
    setDashboard({ status: "idle" });
    try {
      await applyWorkspace(
        await commands.workspace.bootstrap({ locale: document.documentElement.lang || "ru" }),
      );
    } catch {
      setWorkspace({ status: "error" });
      setSelectedShopId(null);
    }
  };

  const selectShop = async (shopId: number) => {
    if (shopSelectionBusy || shopId === selectedShopId) return;
    setShopSelectionBusy(true);
    setShopError("");
    try {
      const response: unknown = await commands.shops.select({ shopId });
      if (!validShopState(response)) throw new Error("Invalid shop state");
      await applyShopState(response);
    } catch {
      setShopError(copy.shop.errors.unavailable);
    } finally {
      setShopSelectionBusy(false);
    }
  };

  const closeSettings = () => {
    setSettingsOpen(false);
    requestAnimationFrame(() => settingsButtonRef.current?.focus());
  };

  const closeShopManager = () => {
    setShopManagerOpen(false);
    requestAnimationFrame(() => shopManagerButtonRef.current?.focus());
  };

  const closeSupport = () => {
    setSupportOpen(false);
    requestAnimationFrame(() => helpButtonRef.current?.focus());
  };

  const copy = getCopy(preferences.language);

  if (workspace.status === "loading") {
    return <CenteredState kind="loading" copy={{ ...copy.center, ...copy.common }} />;
  }

  if (workspace.status === "error") {
    return <CenteredState kind="error" copy={{ ...copy.center, ...copy.common }} onRetry={() => void retryWorkspace()} />;
  }

  const selectedShop = workspace.data.shops.find((shop) => shop.id === selectedShopId) ?? null;
  const pageCopy = copy.shell.pages[activeView];

  return (
    <>
      <div
        className="min-h-screen bg-[var(--surface-canvas)] text-[var(--text-primary)] md:grid md:grid-cols-[15.5rem_1fr]"
        aria-hidden={settingsOpen || shopManagerOpen || supportOpen || undefined}
        inert={settingsOpen || shopManagerOpen || supportOpen || undefined}
      >
      <Sidebar
        version={workspace.data.app.version}
        activeView={activeView}
        onNavigate={setActiveView}
        copy={copy.shell}
      />
      <div className="min-w-0">
        <header className="sticky top-0 z-20 flex min-h-18 items-center justify-between gap-4 border-b border-[var(--border-subtle)] bg-[color:var(--surface-elevated)] px-4 md:px-7">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-[0.12em] text-[var(--text-muted)] uppercase">
              {copy.shell.workspaceEyebrow}
            </p>
            <h1 className="truncate text-lg font-semibold tracking-[-0.02em]">{copy.shell.workspaceTitle}</h1>
          </div>
          <div className="flex items-center gap-2">
            <button
              ref={helpButtonRef}
              className="icon-button"
              type="button"
              aria-label={copy.shell.help}
              aria-expanded={supportOpen}
              onClick={() => setSupportOpen(true)}
            >
              <CircleHelp aria-hidden="true" size={18} />
            </button>
            <button
              ref={settingsButtonRef}
              className="icon-button"
              type="button"
              aria-label={copy.shell.settings}
              aria-expanded={settingsOpen}
              onClick={() => setSettingsOpen(true)}
            >
              <Settings aria-hidden="true" size={18} />
            </button>
          </div>
        </header>

        <main className="mx-auto w-full max-w-[96rem] p-4 md:p-7 lg:p-9">
          <section className="mb-7 flex flex-col justify-between gap-5 lg:flex-row lg:items-end">
            <div>
              <p className="mb-2 inline-flex items-center gap-2 text-sm font-medium text-[var(--accent-strong)]">
                <span className="h-2 w-2 rounded-full bg-[var(--accent)]" aria-hidden="true" />
                {copy.shell.localData}
              </p>
              <h2 className="text-3xl font-semibold tracking-[-0.035em]">{pageCopy.title}</h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--text-secondary)]">
                {pageCopy.description}
              </p>
            </div>
            {activeView === "templates" ? (
              <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3 shadow-[var(--shadow-control)]">
                <p className="text-xs font-semibold text-[var(--text-primary)]">{copy.shell.localLibrary}</p>
                <p className="mt-0.5 text-xs text-[var(--text-muted)]">{copy.shell.shopIndependent}</p>
              </div>
            ) : (
              <ShopPicker
                shops={workspace.data.shops}
                selectedId={selectedShopId}
                onSelect={(shopId) => void selectShop(shopId)}
                onManage={() => setShopManagerOpen(true)}
                manageButtonRef={shopManagerButtonRef}
                busy={shopSelectionBusy}
                error={shopError}
                copy={copy.shop}
              />
            )}
          </section>

          {activeView === "templates" ? (
            <TemplateDesignerView
              copy={getTemplateDesignerCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
            />
          ) : selectedShop === null ? (
            <EmptyWorkspace copy={copy.shop} />
          ) : activeView === "supplies" ? (
            <SupplyListView
              shopId={selectedShop.id}
              licenseAllowed={licenseAllowed}
              copy={getSupplyCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
            />
          ) : activeView === "packing" ? (
            <PackingView
              key={selectedShop.id}
              shopId={selectedShop.id}
              licenseAllowed={licenseAllowed}
              copy={getPackingCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
            />
          ) : activeView === "fbo" ? (
            <FboPackingView
              key={selectedShop.id}
              shopId={selectedShop.id}
              copy={getFboCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
            />
          ) : activeView === "kizMapping" ? (
            <KizMappingView
              key={selectedShop.id}
              shopId={selectedShop.id}
              copy={getKizMappingCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
            />
          ) : activeView === "znack" ? (
            <ZnackView
              key={selectedShop.id}
              shopId={selectedShop.id}
              licenseAllowed={licenseAllowed}
              copy={getZnackCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
            />
          ) : activeView === "history" ? (
            <PrintHistoryView
              key={selectedShop.id}
              copy={getHistoryCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
              shopId={selectedShop.id}
            />
          ) : (
            <DashboardView
              copy={getDashboardCopy(preferences.language)}
              locale={getSupplyLocale(preferences.language)}
              shop={selectedShop}
              state={dashboard}
              sync={wildberriesSync}
            />
          )}
        </main>
      </div>
      </div>
      {settingsOpen ? (
        <LicenseSettingsDialog
          open
          onClose={closeSettings}
          onStatusChange={setLicenseAllowed}
          copy={copy}
          language={preferences.language}
          theme={preferences.theme}
          onPreferencesChange={applyPreferences}
        />
      ) : null}
      {shopManagerOpen ? (
        <ShopManagementDialog
          shops={workspace.data.shops}
          selectedId={selectedShopId}
          onClose={closeShopManager}
          onState={(state) => { void applyShopState(state); }}
          copy={copy.shop}
        />
      ) : null}
      {supportOpen ? <SupportDialog onClose={closeSupport} copy={copy.support} /> : null}
    </>
  );
}
