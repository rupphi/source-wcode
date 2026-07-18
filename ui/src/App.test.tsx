import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { commands } from "./generated/commands";

vi.mock("./generated/commands", () => ({
  commands: {
    workspace: { bootstrap: vi.fn() },
    dashboard: { load: vi.fn() },
    supplies: { list: vi.fn() },
    wildberries: {
      syncOverview: vi.fn(),
      syncStatus: vi.fn(),
      cancelSync: vi.fn(),
    },
  },
}));

const bootstrap = vi.mocked(commands.workspace.bootstrap);
const loadDashboard = vi.mocked(commands.dashboard.load);
const listSupplies = vi.mocked(commands.supplies.list);
const syncOverview = vi.mocked(commands.wildberries.syncOverview);
const syncStatus = vi.mocked(commands.wildberries.syncStatus);
const cancelSync = vi.mocked(commands.wildberries.cancelSync);
const secret = "wb-secret-that-must-not-enter-the-dom";

describe("App", () => {
  beforeEach(() => {
    bootstrap.mockReset();
    loadDashboard.mockReset();
    listSupplies.mockReset();
    syncOverview.mockReset();
    syncStatus.mockReset();
    cancelSync.mockReset();
  });

  it("loads sanitized shops and the selected shop dashboard", async () => {
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true, apiKey: secret }],
      hasSelectedShop: true,
      selectedShopId: 7,
    } as unknown as Awaited<ReturnType<typeof commands.workspace.bootstrap>>);
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 17_922,
      newOrderCount: 31,
      openSupplyCount: 8,
    });

    render(<App />);

    expect(screen.getByRole("status")).toHaveTextContent("Загружаем рабочее пространство");
    expect(await screen.findByRole("heading", { name: "Обзор магазина" })).toBeVisible();
    expect(screen.getByRole("combobox", { name: "Магазин" })).toHaveValue("7");
    expect(screen.getByText("17 922")).toBeVisible();
    expect(screen.getByText("31")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("loads the newly selected shop and ignores no interaction state", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [
        { id: 7, name: "Основной магазин", tokenConfigured: true },
        { id: 9, name: "Второй магазин", tokenConfigured: false },
      ],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockImplementation(async ({ shopId }) => ({
      shopId,
      productCount: shopId === 7 ? 10 : 20,
      newOrderCount: shopId === 7 ? 1 : 2,
      openSupplyCount: shopId === 7 ? 3 : 4,
    }));

    render(<App />);
    const picker = await screen.findByRole("combobox", { name: "Магазин" });
    await user.selectOptions(picker, "9");

    await waitFor(() => expect(loadDashboard).toHaveBeenLastCalledWith({ shopId: 9 }));
    expect(await screen.findByText("20")).toBeVisible();
    expect(screen.getByText("Токен не настроен")).toBeVisible();
  });

  it("shows a safe retry state when bootstrap fails", async () => {
    const user = userEvent.setup();
    bootstrap.mockRejectedValueOnce(new Error("raw database details"));
    bootstrap.mockResolvedValueOnce({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось открыть рабочее пространство");
    expect(document.body).not.toHaveTextContent("raw database details");
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Добавьте магазин, чтобы начать работу")).toBeVisible();
  });

  it("syncs Wildberries in the background and reloads local KPIs", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard
      .mockResolvedValueOnce({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 2 })
      .mockResolvedValueOnce({ shopId: 7, productCount: 12, newOrderCount: 1, openSupplyCount: 3 });
    syncOverview.mockResolvedValue({ accepted: true, shopId: 7, jobId: "job-7" });
    syncStatus.mockResolvedValue({
      jobId: "job-7",
      shopId: 7,
      state: "completed",
      products: 2,
      supplies: 1,
      orders: 0,
      statuses: 0,
      completedAt: "2026-07-18T10:20:00Z",
      errorKind: "",
      httpStatus: 0,
      retryable: false,
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Синхронизировать с Wildberries" }));

    await waitFor(() => expect(syncOverview).toHaveBeenCalledWith({ shopId: 7 }));
    await waitFor(() => expect(syncStatus).toHaveBeenCalledWith({ shopId: 7, jobId: "job-7" }));
    await waitFor(() => expect(loadDashboard).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Синхронизация завершена")).toBeVisible();
    expect(screen.getByText("12")).toBeVisible();
  });

  it("shows an actionable token error without upstream details", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 1,
      openSupplyCount: 2,
    });
    syncOverview.mockResolvedValue({ accepted: true, shopId: 7, jobId: "job-7" });
    syncStatus.mockResolvedValue({
      jobId: "job-7",
      shopId: 7,
      state: "failed",
      products: 0,
      supplies: 0,
      orders: 0,
      statuses: 0,
      completedAt: "2026-07-18T10:20:00Z",
      errorKind: "token_invalid",
      httpStatus: 401,
      retryable: false,
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Синхронизировать с Wildberries" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Токен Wildberries недействителен или не имеет нужных прав",
    );
    expect(document.body).not.toHaveTextContent("401");
  });

  it("opens a paginated local supply workspace without exposing secrets", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 1,
      openSupplyCount: 20,
    });
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 26,
      totalPages: 2,
      openItems: 20,
      closedItems: 6,
      items: [
        {
          id: "WB-GI-1",
          name: "Поставка Москва",
          status: "open",
          mode: "b2b",
          createdAt: "2026-07-18T10:00:00Z",
          itemCount: 12,
          apiKey: secret,
        },
      ],
    } as unknown as Awaited<ReturnType<typeof commands.supplies.list>>);

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));

    expect(await screen.findByRole("heading", { name: "Поставки FBS" })).toBeVisible();
    await waitFor(() =>
      expect(listSupplies).toHaveBeenCalledWith({
        shopId: 7,
        query: "",
        status: "all",
        page: 1,
        pageSize: 25,
      }),
    );
    expect(screen.getByText("Поставка Москва")).toBeVisible();
    expect(screen.getByText("WB-GI-1")).toBeVisible();
    expect(screen.getByRole("button", { name: /Открытые.*20/ })).toBeVisible();
    expect(screen.getByText("Страница 1 из 2")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("searches, filters, and paginates supplies from the local bridge", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 30 });
    listSupplies.mockImplementation(async (request) => ({
      ...request,
      totalItems: 30,
      totalPages: 2,
      openItems: 30,
      closedItems: 4,
      items: [
        {
          id: `SUPPLY-${request.page}`,
          name: `Поставка ${request.page}`,
          status: request.status === "closed" ? "closed" : "open",
          mode: "consumer",
          createdAt: "2026-07-18T10:00:00Z",
          itemCount: 5,
        },
      ],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: /Открытые.*30/ }));

    await waitFor(() =>
      expect(listSupplies).toHaveBeenLastCalledWith({
        shopId: 7,
        query: "",
        status: "open",
        page: 1,
        pageSize: 25,
      }),
    );

    await user.type(screen.getByRole("searchbox", { name: "Поиск поставок" }), "  Москва  ");
    await user.click(screen.getByRole("button", { name: "Найти" }));
    await waitFor(() =>
      expect(listSupplies).toHaveBeenLastCalledWith({
        shopId: 7,
        query: "Москва",
        status: "open",
        page: 1,
        pageSize: 25,
      }),
    );

    await user.click(await screen.findByRole("button", { name: "Следующая страница" }));
    await waitFor(() =>
      expect(listSupplies).toHaveBeenLastCalledWith({
        shopId: 7,
        query: "Москва",
        status: "open",
        page: 2,
        pageSize: 25,
      }),
    );
    expect(await screen.findByText("SUPPLY-2")).toBeVisible();
  });

  it("shows a safe retry state when the supply query fails", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 2 });
    listSupplies
      .mockRejectedValueOnce(new Error(`sqlite failure ${secret}`))
      .mockResolvedValueOnce({
        shopId: 7,
        query: "",
        status: "all",
        page: 1,
        pageSize: 25,
        totalItems: 0,
        totalPages: 0,
        openItems: 0,
        closedItems: 0,
        items: [],
      });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить поставки");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Поставок пока нет")).toBeVisible();
    expect(listSupplies).toHaveBeenCalledTimes(2);
  });
});
