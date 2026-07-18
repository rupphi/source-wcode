import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { commands } from "./generated/commands";

vi.mock("./generated/commands", () => ({
  commands: {
    workspace: { bootstrap: vi.fn() },
    dashboard: { load: vi.fn() },
  },
}));

const bootstrap = vi.mocked(commands.workspace.bootstrap);
const loadDashboard = vi.mocked(commands.dashboard.load);
const secret = "wb-secret-that-must-not-enter-the-dom";

describe("App", () => {
  beforeEach(() => {
    bootstrap.mockReset();
    loadDashboard.mockReset();
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
});
