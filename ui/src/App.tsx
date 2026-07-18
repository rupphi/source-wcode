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
import { useWildberriesSync } from "./features/wildberries/useWildberriesSync";
import { SupplyListView } from "./features/supplies/SupplyListView";
import { PackingView } from "./features/packing/PackingView";
import { FboPackingView } from "./features/fbo/FboPackingView";
import { PrintHistoryView } from "./features/history/PrintHistoryView";
import { TemplateDesignerView } from "./features/templates/TemplateDesignerView";
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
  const [activeView, setActiveView] = useState<WorkspaceView>("dashboard");
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
  const pageCopy = {
    dashboard: {
        title: "Обзор магазина",
        description: "Быстрый срез каталога, новых заказов и активных поставок без раскрытия API-токена.",
    },
    packing: {
      title: "Упаковка FBS",
      description: "Рабочая очередь новых заказов, поставок на сборке и готовых отгрузок из локальных данных WCode.",
    },
    supplies: {
        title: "Поставки FBS",
        description: "Локальный реестр поставок Wildberries с быстрым поиском, статусами и точной пагинацией.",
    },
    history: {
      title: "История печати",
      description: "Журнал локальных PDF-заданий с безопасным статусом, шаблоном и точным количеством этикеток.",
    },
    fbo: {
      title: "Печать FBO",
      description: "Локальный каталог SKU с пакетной и быстрой печатью парных товарных этикеток и контролируемым списанием KIZ.",
    },
    templates: {
      title: "Дизайн этикеток",
      description: "Локальные шаблоны FBS и FBO с точной геометрией 58 × 40 мм и визуальной проверкой каждого элемента.",
    },
  }[activeView];

  return (
    <div className="min-h-screen bg-[var(--surface-canvas)] text-[var(--text-primary)] md:grid md:grid-cols-[15.5rem_1fr]">
      <Sidebar
        version={workspace.data.app.version}
        activeView={activeView}
        onNavigate={setActiveView}
      />
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
              <h2 className="text-3xl font-semibold tracking-[-0.035em]">{pageCopy.title}</h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--text-secondary)]">
                {pageCopy.description}
              </p>
            </div>
            {activeView === "templates" ? (
              <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3 shadow-[var(--shadow-control)]">
                <p className="text-xs font-semibold text-[var(--text-primary)]">Локальная библиотека</p>
                <p className="mt-0.5 text-xs text-[var(--text-muted)]">Не зависит от выбранного магазина</p>
              </div>
            ) : (
              <ShopPicker
                shops={workspace.data.shops}
                selectedId={selectedShopId}
                onSelect={selectShop}
              />
            )}
          </section>

          {activeView === "templates" ? (
            <TemplateDesignerView />
          ) : selectedShop === null ? (
            <EmptyWorkspace />
          ) : activeView === "supplies" ? (
            <SupplyListView shopId={selectedShop.id} />
          ) : activeView === "packing" ? (
            <PackingView key={selectedShop.id} shopId={selectedShop.id} />
          ) : activeView === "fbo" ? (
            <FboPackingView key={selectedShop.id} shopId={selectedShop.id} />
          ) : activeView === "history" ? (
            <PrintHistoryView key={selectedShop.id} shopId={selectedShop.id} />
          ) : (
            <DashboardView shop={selectedShop} state={dashboard} sync={wildberriesSync} />
          )}
        </main>
      </div>
    </div>
  );
}
