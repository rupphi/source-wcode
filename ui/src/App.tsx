import { CircleHelp, Settings } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { CenteredState } from "./components/CenteredState";
import { ShopPicker } from "./components/ShopPicker";
import { Sidebar } from "./components/Sidebar";
import {
  DashboardView,
  EmptyWorkspace,
  type DashboardState,
} from "./features/dashboard/DashboardView";
import { useWildberriesSync } from "./features/wildberries/useWildberriesSync";
import { commands } from "./generated/commands";
import type { BootstrapResponse } from "./generated/types";

type WorkspaceState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: BootstrapResponse };

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
            <ShopPicker
              shops={workspace.data.shops}
              selectedId={selectedShopId}
              onSelect={selectShop}
            />
          </section>

          {selectedShop === null ? (
            <EmptyWorkspace />
          ) : (
            <DashboardView shop={selectedShop} state={dashboard} sync={wildberriesSync} />
          )}
        </main>
      </div>
    </div>
  );
}
