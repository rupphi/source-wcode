import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { ZnackView } from "./ZnackView";

vi.mock("../../generated/commands", () => ({
  commands: {
    znack: {
      products: vi.fn(),
      saveSettings: vi.fn(),
      setProductVisibility: vi.fn(),
      settings: vi.fn(),
    },
  },
}));

const loadSettings = vi.mocked(commands.znack.settings);
const saveSettings = vi.mocked(commands.znack.saveSettings);
const loadProducts = vi.mocked(commands.znack.products);
const setVisibility = vi.mocked(commands.znack.setProductVisibility);
const gtin = "04601234567890";
const secondGtin = "04601234567891";
const secret = "znack-private-selector-must-not-enter-the-dom";

function settings(version = "a".repeat(64)) {
  return {
    shopId: 7,
    omsId: "OMS-7",
    omsConnection: "CONNECTION-7",
    documentNumber: "DOC-7",
    documentDate: "18.07.2026",
    autoIntroduction: true,
    signatureStatus: "VERIFIED",
    certificateLabel: "ООО Маркировка",
    certificateValidTo: "2027-07-18",
    version,
  };
}

function product(currentGtin = gtin, name = "Ботинки Alpine", deleted = false) {
  return {
    gtin: currentGtin,
    productName: name,
    category: "Обувь",
    tnVed: "6403",
    cisType: "UNIT",
    goodMarkStatus: "READY",
    goodTurnStatus: "NOT_READY",
    readinessCheckedAt: "2026-07-18T00:00:00Z",
    deleted,
  };
}

describe("ZnackView", () => {
  beforeEach(() => {
    loadSettings.mockReset();
    saveSettings.mockReset();
    loadProducts.mockReset();
    setVisibility.mockReset();
    loadSettings.mockResolvedValue(settings());
    saveSettings.mockResolvedValue(settings("b".repeat(64)));
    loadProducts.mockImplementation(async (request) => ({
      ...request,
      hasMore: request.page === 1,
      availableCategories: ["Обувь", "Одежда"],
      items: request.deleted
        ? [product(secondGtin, "Скрытая куртка", true)]
        : request.page === 1 ? [product()] : [product(secondGtin, "Кеды North")],
    }));
    setVisibility.mockImplementation(async (request) => ({
      shopId: request.shopId,
      deleted: request.deleted,
      changed: request.gtins.length,
    }));
  });

  it("loads safe settings and saves only editable fields with the opaque version", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);

    expect(await screen.findByDisplayValue("OMS-7")).toBeVisible();
    expect(screen.getByText("Подпись проверена")).toBeVisible();
    expect(screen.getByText("ООО Маркировка")).toBeVisible();
    const save = screen.getByRole("button", { name: "Сохранить настройки Znack" });
    expect(save).toBeDisabled();

    const oms = screen.getByRole("textbox", { name: "OMS ID" });
    await user.clear(oms);
    await user.type(oms, "OMS-8");
    await user.clear(screen.getByRole("textbox", { name: "Соединение OMS" }));
    await user.type(screen.getByRole("textbox", { name: "Соединение OMS" }), "CONNECTION-8");
    await user.click(save);

    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith({
      shopId: 7,
      omsId: "OMS-8",
      omsConnection: "CONNECTION-8",
      documentNumber: "DOC-7",
      documentDate: "18.07.2026",
      autoIntroduction: true,
      version: "a".repeat(64),
    }));
    expect(await screen.findByText("Настройки Znack сохранены")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("loads bounded active product pages with exact search and category filters", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));

    expect(await screen.findByText("Ботинки Alpine")).toBeVisible();
    expect(loadProducts).toHaveBeenLastCalledWith({
      shopId: 7,
      query: "",
      categories: [],
      deleted: false,
      page: 1,
      pageSize: 50,
    });
    expect(screen.getByText("Маркировка готова")).toBeVisible();
    expect(screen.getByText("Оборот не готов")).toBeVisible();

    await user.type(screen.getByRole("searchbox", { name: "Поиск товаров Znack" }), " Alpine ");
    await user.click(screen.getByRole("button", { name: "Найти товар Znack" }));
    await waitFor(() => expect(loadProducts).toHaveBeenLastCalledWith(expect.objectContaining({
      query: "Alpine",
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: "Категории товаров Znack" }));
    await user.click(screen.getByRole("checkbox", { name: "Одежда" }));
    await waitFor(() => expect(loadProducts).toHaveBeenLastCalledWith(expect.objectContaining({
      categories: ["Одежда"],
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: "Следующая страница товаров Znack" }));
    expect(await screen.findByText("Кеды North")).toBeVisible();
    expect(screen.getByText("Страница 2")).toBeVisible();
  });

  it("hides and restores bounded selections then refreshes the current list", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    await screen.findByText("Ботинки Alpine");

    await user.click(screen.getByRole("checkbox", { name: `Выбрать GTIN ${gtin}` }));
    await user.click(screen.getByRole("button", { name: "Скрыть выбранные GTIN" }));
    await waitFor(() => expect(setVisibility).toHaveBeenCalledWith({
      shopId: 7,
      gtins: [gtin],
      deleted: true,
    }));
    expect(await screen.findByText("Скрыто GTIN: 1")).toBeVisible();

    await user.click(screen.getByRole("tab", { name: "Скрытые" }));
    expect(await screen.findByText("Скрытая куртка")).toBeVisible();
    const hiddenPanel = screen.getByRole("tabpanel", { name: "Скрытые" });
    await user.click(within(hiddenPanel).getByRole("checkbox", { name: `Выбрать GTIN ${secondGtin}` }));
    await user.click(within(hiddenPanel).getByRole("button", { name: "Восстановить выбранные GTIN" }));
    await waitFor(() => expect(setVisibility).toHaveBeenLastCalledWith({
      shopId: 7,
      gtins: [secondGtin],
      deleted: false,
    }));
    expect(await screen.findByText("Восстановлено GTIN: 1")).toBeVisible();
  });

  it("shows generic retry states and a safe empty product state", async () => {
    const user = userEvent.setup();
    loadSettings
      .mockRejectedValueOnce(new Error(`settings failed ${secret}`))
      .mockResolvedValueOnce(settings());
    render(<ZnackView shopId={7} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить настройки Znack");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить загрузку настроек" }));
    expect(await screen.findByDisplayValue("OMS-7")).toBeVisible();

    loadProducts.mockResolvedValueOnce({
      shopId: 7,
      query: "",
      categories: [],
      deleted: false,
      page: 1,
      pageSize: 50,
      hasMore: false,
      availableCategories: [],
      items: [],
    });
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    expect(await screen.findByText("Локальный каталог Znack пока пуст")).toBeVisible();
  });
});
