import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { JDeskError } from "jdesk-client";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { commands } from "./generated/commands";
import { exportSupplyPdf } from "./features/printing/nativePrintCommands";

vi.mock("./generated/commands", () => ({
  commands: {
    workspace: { bootstrap: vi.fn() },
    dashboard: { load: vi.fn() },
    supplies: {
      list: vi.fn(),
      detail: vi.fn(),
      refresh: vi.fn(),
      refreshStatus: vi.fn(),
      cancelRefresh: vi.fn(),
    },
    orders: {
      importExcel: vi.fn(),
      importedPage: vi.fn(),
    },
    printing: {
      setup: vi.fn(),
      saveOptions: vi.fn(),
      exportSupply: vi.fn(),
      openExport: vi.fn(),
    },
    wildberries: {
      syncOverview: vi.fn(),
      syncStatus: vi.fn(),
      cancelSync: vi.fn(),
    },
  },
}));

vi.mock("./features/printing/nativePrintCommands", () => ({
  exportSupplyPdf: vi.fn(),
}));

const bootstrap = vi.mocked(commands.workspace.bootstrap);
const loadDashboard = vi.mocked(commands.dashboard.load);
const listSupplies = vi.mocked(commands.supplies.list);
const loadSupplyDetail = vi.mocked(commands.supplies.detail);
const refreshSupply = vi.mocked(commands.supplies.refresh);
const refreshSupplyStatus = vi.mocked(commands.supplies.refreshStatus);
const cancelSupplyRefresh = vi.mocked(commands.supplies.cancelRefresh);
const importExcel = vi.mocked(commands.orders.importExcel);
const loadImportedOrders = vi.mocked(commands.orders.importedPage);
const loadPrintSetup = vi.mocked(commands.printing.setup);
const savePrintOptions = vi.mocked(commands.printing.saveOptions);
const exportSupplyPdfCommand = vi.mocked(exportSupplyPdf);
const openExportedPdf = vi.mocked(commands.printing.openExport);
const syncOverview = vi.mocked(commands.wildberries.syncOverview);
const syncStatus = vi.mocked(commands.wildberries.syncStatus);
const cancelSync = vi.mocked(commands.wildberries.cancelSync);
const secret = "wb-secret-that-must-not-enter-the-dom";

describe("App", () => {
  beforeEach(() => {
    bootstrap.mockReset();
    loadDashboard.mockReset();
    listSupplies.mockReset();
    loadSupplyDetail.mockReset();
    refreshSupply.mockReset();
    refreshSupplyStatus.mockReset();
    cancelSupplyRefresh.mockReset();
    importExcel.mockReset();
    loadImportedOrders.mockReset();
    loadPrintSetup.mockReset();
    savePrintOptions.mockReset();
    exportSupplyPdfCommand.mockReset();
    openExportedPdf.mockReset();
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

  it("opens a supply order detail with precise identifiers and no remote image data", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [{
        id: "WB-GI-1",
        name: "Поставка Москва",
        status: "open",
        mode: "b2b",
        createdAt: "2026-07-18T10:00:00Z",
        itemCount: 1,
      }],
    });
    loadSupplyDetail.mockResolvedValue({
      supply: {
        id: "WB-GI-1",
        name: "Поставка Москва",
        status: "open",
        mode: "b2b",
        createdAt: "2026-07-18T10:00:00Z",
        itemCount: 1,
      },
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [{
        orderId: "9007199254740993",
        nmId: "1001",
        name: "Куртка",
        brand: "WCode Brand",
        subject: "Одежда",
        article: "ART-1",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "SKU-1",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 12_345,
        supplierStatus: "confirm",
        wbStatus: "sorted",
        requiresKiz: true,
        imagePath: `jdesk://app/order-images/${"A".repeat(43)}.png`,
        imageUrl: `https://untrusted.example/${secret}`,
      }],
    } as unknown as Awaited<ReturnType<typeof commands.supplies.detail>>);

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));

    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenCalledWith({
        shopId: 7,
        supplyId: "WB-GI-1",
        query: "",
        page: 1,
        pageSize: 25,
        sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      }),
    );
    expect(await screen.findByRole("heading", { name: "Поставка Москва" })).toBeVisible();
    expect(screen.getByText("9007199254740993")).toBeVisible();
    expect(screen.getByText("Куртка")).toBeVisible();
    expect(screen.getByText("123,45 ₽")).toBeVisible();
    expect(screen.getByText("Требуется КИЗ")).toBeVisible();
    expect(screen.getByRole("img", { name: "Фото товара Куртка" })).toHaveAttribute(
      "src",
      `jdesk://app/order-images/${"A".repeat(43)}.png`,
    );
    expect(document.body).not.toHaveTextContent(secret);

    await user.click(screen.getByRole("button", { name: "К списку поставок" }));
    expect(await screen.findByText("Поставка Москва")).toBeVisible();
  });

  it("searches, sorts, and paginates supply orders through the typed detail command", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 30,
    };
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [supply],
    });
    loadSupplyDetail.mockImplementation(async (request) => ({
      supply,
      query: request.query,
      page: request.page,
      pageSize: request.pageSize,
      totalItems: 30,
      totalPages: 2,
      sort: request.sort,
      items: [{
        orderId: `ORDER-${request.page}`,
        nmId: "1001",
        name: `Куртка ${request.page}`,
        brand: "Brand",
        subject: "Одежда",
        article: "ART-1",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "SKU-1",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 10_000,
        supplierStatus: "confirm",
        wbStatus: "sorted",
        requiresKiz: false,
        imagePath: "",
      }],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await screen.findByText("ORDER-1");

    await user.click(screen.getByRole("checkbox", { name: "Размер" }));
    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({
        page: 1,
        sort: { bySubject: true, byArticle: true, byColor: true, bySize: false },
      })),
    );

    await user.type(screen.getByRole("searchbox", { name: "Поиск заказов" }), "  SKU-1  ");
    await user.click(screen.getByRole("button", { name: "Найти заказ" }));
    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({ query: "SKU-1", page: 1 })),
    );

    await user.click(await screen.findByRole("button", { name: "Следующая страница заказов" }));
    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({ query: "SKU-1", page: 2 })),
    );
    expect(await screen.findByText("ORDER-2")).toBeVisible();
  });

  it("imports an Excel workbook through a native dialog and keeps its path out of the UI", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 1,
    };
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [supply],
    });
    loadSupplyDetail.mockResolvedValue({
      supply,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [{
        orderId: "LOCAL-1",
        nmId: "1001",
        name: "Локальный товар",
        brand: "Brand",
        subject: "Одежда",
        article: "LOCAL-ART",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "LOCAL-SKU",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 10_000,
        supplierStatus: "confirm",
        wbStatus: "sorted",
        requiresKiz: false,
        imagePath: "",
      }],
    });
    const sessionId = "00000000-0000-4000-8000-000000000001";
    importExcel
      .mockRejectedValueOnce(new JDeskError(
        "INTERNAL_ERROR",
        "safe public message",
        { kind: "rate_limited", httpStatus: 429, retryable: true },
      ))
      .mockResolvedValueOnce({
      cancelled: false,
      sessionId,
      fileName: "orders.xlsx",
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 30,
      totalPages: 2,
      importedItems: 30,
      stickerItems: 29,
      items: [{
        orderId: "9007199254740993",
        name: "Импортированный товар",
        brand: "Excel Brand",
        article: "ART-EXCEL",
        color: "Чёрный",
        size: "L",
        barcode: "SKU-EXCEL",
        sticker: "12 34",
        stickerAvailable: true,
        imagePath: `jdesk://app/order-images/${"B".repeat(43)}.jpg`,
      }],
      });
    loadImportedOrders.mockImplementation(async (request) => ({
      cancelled: false,
      sessionId,
      fileName: "orders.xlsx",
      query: request.query,
      page: request.page,
      pageSize: request.pageSize,
      totalItems: 1,
      totalPages: 1,
      importedItems: 30,
      stickerItems: 29,
      items: [{
        orderId: "9007199254740993",
        name: "Импортированный товар",
        brand: "Excel Brand",
        article: "ART-EXCEL",
        color: "Чёрный",
        size: "L",
        barcode: "SKU-EXCEL",
        sticker: "12 34",
        stickerAvailable: true,
        imagePath: `jdesk://app/order-images/${"B".repeat(43)}.jpg`,
      }],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await screen.findByText("LOCAL-1");
    await user.click(screen.getByRole("button", { name: "Импортировать Excel" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Wildberries ограничил частоту запросов стикеров",
    );
    expect(document.body).not.toHaveTextContent("safe public message");
    await user.click(screen.getByRole("button", { name: "Импортировать Excel" }));

    await waitFor(() => expect(importExcel).toHaveBeenCalledTimes(2));
    expect(importExcel).toHaveBeenLastCalledWith({ shopId: 7, pageSize: 25 });
    expect(await screen.findByRole("heading", { name: "Заказы из orders.xlsx" })).toBeVisible();
    expect(screen.getByText("9007199254740993")).toBeVisible();
    expect(screen.queryByText("LOCAL-1")).not.toBeInTheDocument();
    expect(screen.getByText("29 из 30")).toBeVisible();
    expect(screen.getByRole("img", { name: "Фото товара Импортированный товар" })).toHaveAttribute(
      "src",
      `jdesk://app/order-images/${"B".repeat(43)}.jpg`,
    );

    await user.type(screen.getByRole("searchbox", { name: "Поиск импортированных заказов" }), "  ART-EXCEL  ");
    await user.click(screen.getByRole("button", { name: "Найти импортированный заказ" }));
    await waitFor(() => expect(loadImportedOrders).toHaveBeenCalledWith({
      shopId: 7,
      sessionId,
      query: "ART-EXCEL",
      page: 1,
      pageSize: 25,
    }));
    expect(document.body).not.toHaveTextContent("/private/operator");
    expect(document.body).not.toHaveTextContent(secret);

    importExcel.mockResolvedValueOnce({
      cancelled: true,
      sessionId: "",
      fileName: "",
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 0,
      totalPages: 0,
      importedItems: 0,
      stickerItems: 0,
      items: [],
    });
    await user.click(screen.getByRole("button", { name: "Другой файл" }));
    expect(await screen.findByRole("heading", { name: "Заказы из orders.xlsx" })).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Вернуться к заказам поставки" }));
    expect(await screen.findByText("LOCAL-1")).toBeVisible();
  });

  it("loads and persists the bounded print setup for the selected supply", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 2,
    };
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [supply],
    });
    loadSupplyDetail.mockResolvedValue({
      supply,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 2,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [],
    });
    loadPrintSetup.mockResolvedValue({
      shopId: 7,
      pageOrder: "barcode_then_sticker",
      barcodeCopies: 2,
      defaultTemplateId: 9,
      pageWidthMm: 58,
      pageHeightMm: 40,
      templates: [
        { id: 9, name: "Основной шаблон", defaultTemplate: true },
        { id: 10, name: "Компактный", defaultTemplate: false },
      ],
    });
    savePrintOptions.mockResolvedValue({
      shopId: 7,
      pageOrder: "sticker_then_barcode",
      barcodeCopies: 4,
      defaultTemplateId: 9,
      pageWidthMm: 58,
      pageHeightMm: 40,
      templates: [
        { id: 9, name: "Основной шаблон", defaultTemplate: true },
        { id: 10, name: "Компактный", defaultTemplate: false },
      ],
    });
    exportSupplyPdfCommand.mockResolvedValue({
      cancelled: false,
      exportId: "00000000-0000-4000-8000-000000000009",
      labelsFileName: "labels.pdf",
      detailsFileName: "NHAT_HANG-labels.pdf",
      printJobId: "9007199254740993",
      itemCount: 2,
      pageCount: 10,
      kizAttachmentCount: 1,
    });
    openExportedPdf.mockResolvedValue({ opened: true, fileName: "NHAT_HANG-labels.pdf" });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await user.click(await screen.findByRole("button", { name: "Настроить печать" }));

    await waitFor(() => expect(loadPrintSetup).toHaveBeenCalledWith({ shopId: 7 }));
    expect(await screen.findByRole("dialog", { name: "Настройка печати" })).toBeVisible();
    expect(screen.getByText("Основной шаблон")).toBeVisible();
    expect(screen.getByText("58 × 40 мм")).toBeVisible();
    expect(screen.getByText("6 страниц PDF")).toBeVisible();

    await user.click(screen.getByRole("radio", { name: "Стикер WB, затем этикетка" }));
    const copies = screen.getByRole("spinbutton", { name: "Копий этикетки" });
    await user.clear(copies);
    await user.type(copies, "4");
    await user.click(screen.getByRole("button", { name: "Сохранить настройки" }));

    await waitFor(() => expect(savePrintOptions).toHaveBeenCalledWith({
      shopId: 7,
      pageOrder: "sticker_then_barcode",
      barcodeCopies: 4,
    }));
    expect(await screen.findByText("Настройки сохранены")).toBeVisible();
    expect(screen.getByText("10 страниц PDF")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Создать PDF" }));
    await waitFor(() => expect(exportSupplyPdfCommand).toHaveBeenCalledWith({
      shopId: 7,
      supplyId: "WB-GI-1",
      query: "",
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      pageOrder: "sticker_then_barcode",
      barcodeCopies: 4,
    }));
    expect(await screen.findByText("PDF готовы")).toBeVisible();
    expect(screen.getByText("labels.pdf")).toBeVisible();
    expect(screen.getByText("NHAT_HANG-labels.pdf")).toBeVisible();
    expect(document.body).not.toHaveTextContent("/private/operator");

    await user.click(screen.getByRole("button", { name: "Открыть лист подбора" }));
    await waitFor(() => expect(openExportedPdf).toHaveBeenCalledWith({
      shopId: 7,
      exportId: "00000000-0000-4000-8000-000000000009",
      fileKind: "details",
    }));
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("refreshes supply orders in the background without losing detail selection", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 30,
    };
    let refreshed = false;
    listSupplies.mockImplementation(async () => ({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [{ ...supply, itemCount: refreshed ? 31 : 30 }],
    }));
    loadSupplyDetail.mockImplementation(async (request) => ({
      supply: { ...supply, itemCount: refreshed ? 31 : 30 },
      query: request.query,
      page: request.page,
      pageSize: request.pageSize,
      totalItems: 30,
      totalPages: 2,
      sort: request.sort,
      items: [{
        orderId: `ORDER-${request.page}`,
        nmId: "1001",
        name: "Куртка",
        brand: "Brand",
        subject: "Одежда",
        article: "ART-1",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "SKU-1",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 10_000,
        supplierStatus: refreshed ? "complete" : "confirm",
        wbStatus: refreshed ? "sorted" : "waiting",
        requiresKiz: false,
        imagePath: "",
      }],
    }));
    refreshSupply
      .mockRejectedValueOnce(new JDeskError(
        "INVALID_REQUEST",
        "safe public message",
        { kind: "shop_busy", retryable: true },
      ))
      .mockResolvedValueOnce({
        accepted: true,
        shopId: 7,
        supplyId: "WB-GI-1",
        jobId: "00000000-0000-0000-0000-000000000001",
      });
    refreshSupplyStatus.mockImplementation(async () => {
      refreshed = true;
      return {
        jobId: "00000000-0000-0000-0000-000000000001",
        shopId: 7,
        supplyId: "WB-GI-1",
        state: "completed",
        localOrders: 31,
        completedAt: "2026-07-18T11:00:00Z",
        errorKind: "",
        httpStatus: 0,
        retryable: false,
      };
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await user.click(await screen.findByRole("checkbox", { name: "Размер" }));
    await user.type(screen.getByRole("searchbox", { name: "Поиск заказов" }), "SKU-1");
    await user.click(screen.getByRole("button", { name: "Найти заказ" }));
    await user.click(await screen.findByRole("button", { name: "Следующая страница заказов" }));
    await screen.findByText("ORDER-2");

    await user.click(screen.getByRole("button", { name: "Обновить из Wildberries" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Для этого магазина уже обновляется другая поставка",
    );
    expect(document.body).not.toHaveTextContent("safe public message");
    await user.click(screen.getByRole("button", { name: "Обновить из Wildberries" }));

    await waitFor(() => expect(refreshSupply).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(refreshSupplyStatus).toHaveBeenCalledWith({
      shopId: 7,
      supplyId: "WB-GI-1",
      jobId: "00000000-0000-0000-0000-000000000001",
    }));
    await waitFor(() => expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({
      shopId: 7,
      supplyId: "WB-GI-1",
      query: "SKU-1",
      page: 2,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: false },
    })));
    await waitFor(() => expect(listSupplies).toHaveBeenCalledTimes(3));
    expect(await screen.findByText("Данные поставки обновлены")).toBeVisible();
    expect(screen.getByText("В доставке")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Поставка Москва" })).toBeVisible();

    await user.click(screen.getByRole("button", { name: "К списку поставок" }));
    const openSupply = await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" });
    expect(openSupply.closest("tr")).toHaveTextContent("31");
  });
});
