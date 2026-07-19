import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import type { MutationPreview, PackingBoardRequest, PackingBoardResponse } from "../../generated/types";
import { PackingView } from "./PackingView";
import { getPackingCopy } from "./packingI18n";

vi.mock("../../generated/commands", () => ({
  commands: {
    packing: {
      board: vi.fn(),
      execute: vi.fn(),
      prepareAdd: vi.fn(),
      prepareCreate: vi.fn(),
      prepareDeliver: vi.fn(),
    },
  },
}));

const board = vi.mocked(commands.packing.board);
const execute = vi.mocked(commands.packing.execute);
const prepareAdd = vi.mocked(commands.packing.prepareAdd);
const prepareCreate = vi.mocked(commands.packing.prepareCreate);
const prepareDeliver = vi.mocked(commands.packing.prepareDeliver);

describe("PackingView mutations", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    board.mockImplementation(async (request) => response(request));
    execute.mockResolvedValue({ action: "create", supplyId: "SUP-NEW", itemCount: 2, accepted: true });
  });

  it("keeps exact order ids through create preview and one explicit confirmation", async () => {
    const user = userEvent.setup();
    prepareCreate.mockResolvedValue(preview({
      action: "create",
      supplyName: "Shipment 19.07",
      itemCount: 2,
      kizCount: 1,
      warnings: ["kiz_required"],
    }));
    render(<PackingView shopId={7} />);

    await user.click(await screen.findByRole("checkbox", { name: "Выбрать заказ #9007199254741001" }));
    await user.click(screen.getByRole("checkbox", { name: "Выбрать заказ #102" }));
    await user.click(screen.getByRole("button", { name: "Создать поставку" }));
    const form = await screen.findByRole("dialog", { name: "Новая поставка" });
    await user.clear(within(form).getByRole("textbox", { name: "Название поставки" }));
    await user.type(within(form).getByRole("textbox", { name: "Название поставки" }), "Shipment 19.07");
    await user.click(within(form).getByRole("button", { name: "Проверить" }));

    await waitFor(() => expect(prepareCreate).toHaveBeenCalledWith({
      shopId: 7,
      name: "Shipment 19.07",
      orderIds: ["9007199254741001", "102"],
    }));
    const confirmation = await screen.findByRole("dialog", { name: "Подтвердить создание поставки" });
    expect(within(confirmation).getByText("1 заказ требует KIZ")).toBeVisible();
    await user.click(within(confirmation).getByRole("button", { name: "Создать в Wildberries" }));

    await waitFor(() => expect(execute).toHaveBeenCalledWith({
      shopId: 7,
      previewId: "11111111-1111-1111-1111-111111111111",
      confirmed: true,
    }));
    expect(await screen.findByText("Поставка SUP-NEW создана")).toBeVisible();
  });

  it("loads bounded open supplies and confirms adding the selected orders", async () => {
    const user = userEvent.setup();
    prepareAdd.mockResolvedValue(preview({
      action: "add",
      supplyId: "SUP-OPEN",
      supplyName: "Open supply",
      itemCount: 1,
    }));
    execute.mockResolvedValue({ action: "add", supplyId: "SUP-OPEN", itemCount: 1, accepted: true });
    render(<PackingView shopId={7} />);

    await user.click(await screen.findByRole("checkbox", { name: "Выбрать заказ #102" }));
    await user.click(screen.getByRole("button", { name: "Добавить в поставку" }));
    const chooser = await screen.findByRole("dialog", { name: "Выберите поставку" });
    await user.type(within(chooser).getByRole("searchbox", { name: "Поиск поставки" }), "SUP-OPEN");
    await user.click(within(chooser).getByRole("button", { name: "Найти" }));
    await waitFor(() => expect(board).toHaveBeenCalledWith({
      shopId: 7,
      tab: "preparation",
      query: "SUP-OPEN",
      categories: [],
      page: 1,
      pageSize: 100,
    }));
    await user.click(await within(chooser).findByRole("radio", { name: /Open supply/ }));
    await user.click(within(chooser).getByRole("button", { name: "Проверить" }));

    await waitFor(() => expect(prepareAdd).toHaveBeenCalledWith({
      shopId: 7,
      supplyId: "SUP-OPEN",
      orderIds: ["102"],
    }));
    const confirmation = await screen.findByRole("dialog", { name: "Подтвердить добавление заказов" });
    await user.click(within(confirmation).getByRole("button", { name: "Добавить заказы" }));
    await waitFor(() => expect(execute).toHaveBeenCalledWith(expect.objectContaining({ confirmed: true })));
  });

  it("appends packing batches while preserving selected orders", async () => {
    const user = userEvent.setup();
    board.mockImplementation(async (request) => ({
      ...response(request),
      totalItems: 40,
      totalPages: 2,
      orders: request.tab === "new" ? [order(request.page === 1 ? "101" : "201", false)] : [],
    }));
    render(<PackingView shopId={7} />);

    await user.click(await screen.findByRole("checkbox", { name: "Выбрать заказ #101" }));
    expect(screen.queryByRole("button", { name: "Следующая страница очереди" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Показать ещё" }));

    expect(await screen.findByRole("checkbox", { name: "Выбрать заказ #201" })).toBeVisible();
    expect(screen.getByRole("checkbox", { name: "Выбрать заказ #101" })).toBeChecked();
    expect(screen.getByText(/Выбрано:/).closest("p")).toHaveTextContent("1");
    expect(board).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, pageSize: 20 }));
  });

  it("shows print and KIZ blockers without exposing a deliver action", async () => {
    const user = userEvent.setup();
    prepareDeliver.mockResolvedValue(preview({
      action: "deliver",
      supplyId: "SUP-OPEN",
      supplyName: "Open supply",
      itemCount: 5,
      ready: false,
      blockers: ["labels_missing", "kiz_missing"],
    }));
    render(<PackingView shopId={7} />);

    await user.click(await screen.findByRole("tab", { name: /На сборке/ }));
    await user.click(await screen.findByRole("button", { name: "Проверить передачу Open supply" }));

    const blocked = await screen.findByRole("dialog", { name: "Поставка не готова к передаче" });
    expect(within(blocked).getByText("Сначала распечатайте этикетки поставки")).toBeVisible();
    expect(within(blocked).getByText("Не все обязательные KIZ прикреплены")).toBeVisible();
    expect(within(blocked).queryByRole("button", { name: "Передать в доставку" })).not.toBeInTheDocument();
    expect(execute).not.toHaveBeenCalled();
  });

  it("keeps the English packing journey guarded until explicit confirmation", async () => {
    const user = userEvent.setup();
    prepareCreate.mockResolvedValue(preview({
      action: "create",
      supplyName: "Shipment 19.07",
      itemCount: 1,
      kizCount: 1,
      warnings: ["kiz_required"],
    }));
    render(<PackingView shopId={7} copy={getPackingCopy("en")} locale="en-US" />);

    await user.click(await screen.findByRole("checkbox", { name: "Select order #102" }));
    await user.click(screen.getByRole("button", { name: "Create supply" }));
    const form = await screen.findByRole("dialog", { name: "New supply" });
    await user.clear(within(form).getByRole("textbox", { name: "Supply name" }));
    await user.type(within(form).getByRole("textbox", { name: "Supply name" }), "Shipment 19.07");
    await user.click(within(form).getByRole("button", { name: "Check" }));

    const confirmation = await screen.findByRole("dialog", { name: "Confirm supply creation" });
    expect(within(confirmation).getByText("Orders requiring KIZ: 1")).toBeVisible();
    expect(within(confirmation).getByRole("button", { name: "Create in Wildberries" })).toBeVisible();
    expect(execute).not.toHaveBeenCalled();
  });
});

function response(request: PackingBoardRequest): PackingBoardResponse {
  const orders = request.tab === "new" ? [
    order("9007199254741001", true),
    order("102", false),
  ] : [];
  const supplies = request.tab === "preparation" ? [{
    id: "SUP-OPEN",
    name: "Open supply",
    status: "open",
    mode: "consumer",
    createdAt: "2026-07-19T09:00:00Z",
    itemCount: 5,
  }] : [];
  return {
    ...request,
    totalItems: request.tab === "new" ? orders.length : supplies.length,
    totalPages: 1,
    newOrderCount: 2,
    preparationCount: 1,
    dispatchCount: 0,
    availableCategories: [],
    orders,
    supplies,
  };
}

function order(orderId: string, requiresKiz: boolean) {
  return {
    orderId,
    nmId: "1001",
    name: `Order ${orderId}`,
    brand: "WCode",
    subject: "Обувь",
    article: "ART",
    color: "",
    size: "",
    russianSize: "",
    barcode: "SKU",
    createdAt: "2026-07-19T09:00:00Z",
    priceKopecks: 12345,
    requiresKiz,
    imagePath: "",
  };
}

function preview(overrides: Partial<MutationPreview>): MutationPreview {
  return {
    shopId: 7,
    previewId: "11111111-1111-1111-1111-111111111111",
    action: "create",
    supplyId: "",
    supplyName: "",
    itemCount: 0,
    kizCount: 0,
    ready: true,
    blockers: [],
    warnings: [],
    expiresAt: "2026-07-19T10:10:00Z",
    ...overrides,
  };
}
