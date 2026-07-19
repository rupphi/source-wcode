import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import type { MutationPreview, SupplyItem } from "../../generated/types";
import { SupplyDetailView } from "./SupplyDetailView";

vi.mock("../../generated/commands", () => ({
  commands: {
    supplies: { detail: vi.fn() },
    packing: { execute: vi.fn(), prepareDeliver: vi.fn() },
  },
}));

vi.mock("../printing/PrintSetupDialog", () => ({
  PrintSetupDialog: () => <button type="button">Печать этикеток</button>,
}));

vi.mock("./ExcelImportPanel", () => ({
  ExcelImportPanel: () => null,
}));

vi.mock("./useSupplyRefresh", () => ({
  useSupplyRefresh: () => ({
    state: { status: "idle" },
    start: vi.fn(),
    cancel: vi.fn(),
  }),
}));

const detail = vi.mocked(commands.supplies.detail);
const execute = vi.mocked(commands.packing.execute);
const prepareDeliver = vi.mocked(commands.packing.prepareDeliver);
const openSupply: SupplyItem = {
  id: "SUP-OPEN",
  name: "Open supply",
  status: "open",
  mode: "consumer",
  createdAt: "2026-07-19T09:00:00Z",
  itemCount: 5,
};

describe("SupplyDetailView delivery", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    detail.mockResolvedValue({
      supply: openSupply,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 0,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [],
    });
  });

  it("previews the exact open supply and executes only after explicit confirmation", async () => {
    const user = userEvent.setup();
    const onSupplyRefreshed = vi.fn();
    prepareDeliver.mockResolvedValue(preview());
    execute.mockResolvedValue({ action: "deliver", supplyId: "SUP-OPEN", itemCount: 5, accepted: true });
    render(<SupplyDetailView shopId={7} summary={openSupply} onBack={vi.fn()} onSupplyRefreshed={onSupplyRefreshed} />);

    await user.click(await screen.findByRole("button", { name: "Проверить передачу Open supply" }));
    expect(prepareDeliver).toHaveBeenCalledWith({ shopId: 7, supplyId: "SUP-OPEN" });
    expect(execute).not.toHaveBeenCalled();

    const confirmation = await screen.findByRole("dialog", { name: "Подтвердить передачу поставки" });
    const close = within(confirmation).getByRole("button", { name: "Закрыть" });
    const confirm = within(confirmation).getByRole("button", { name: "Передать в доставку" });
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(confirm).toHaveFocus();
    await user.tab();
    expect(close).toHaveFocus();
    await user.click(confirm);

    await waitFor(() => expect(execute).toHaveBeenCalledWith({
      shopId: 7,
      previewId: "11111111-1111-1111-1111-111111111111",
      confirmed: true,
    }));
    expect(await screen.findByRole("status")).toHaveTextContent("Поставка SUP-OPEN передана в доставку");
    expect(onSupplyRefreshed).toHaveBeenCalledTimes(1);
  });

  it("shows delivery blockers and never exposes the execute action", async () => {
    const user = userEvent.setup();
    prepareDeliver.mockResolvedValue(preview({
      ready: false,
      blockers: ["labels_missing", "kiz_missing"],
    }));
    render(<SupplyDetailView shopId={7} summary={openSupply} onBack={vi.fn()} onSupplyRefreshed={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: "Проверить передачу Open supply" }));

    const blocked = await screen.findByRole("dialog", { name: "Поставка не готова к передаче" });
    expect(within(blocked).getByText("Сначала распечатайте этикетки поставки")).toBeVisible();
    expect(within(blocked).getByText("Не все обязательные KIZ прикреплены")).toBeVisible();
    expect(within(blocked).queryByRole("button", { name: "Передать в доставку" })).not.toBeInTheDocument();
    expect(execute).not.toHaveBeenCalled();
  });

  it("keeps the confirmation open and does not publish success for a mismatched receipt", async () => {
    const user = userEvent.setup();
    const onSupplyRefreshed = vi.fn();
    prepareDeliver.mockResolvedValue(preview());
    execute.mockResolvedValue({ action: "deliver", supplyId: "OTHER-SUPPLY", itemCount: 5, accepted: true });
    render(<SupplyDetailView shopId={7} summary={openSupply} onBack={vi.fn()} onSupplyRefreshed={onSupplyRefreshed} />);

    await user.click(await screen.findByRole("button", { name: "Проверить передачу Open supply" }));
    const confirmation = await screen.findByRole("dialog", { name: "Подтвердить передачу поставки" });
    await user.click(within(confirmation).getByRole("button", { name: "Передать в доставку" }));

    expect(await within(confirmation).findByRole("alert")).toHaveTextContent("Операция не выполнена");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(onSupplyRefreshed).not.toHaveBeenCalled();
  });

  it("fails closed for a mismatched preview and hides delivery for a closed supply", async () => {
    const user = userEvent.setup();
    prepareDeliver.mockResolvedValue(preview({ supplyId: "OTHER-SUPPLY" }));
    const { unmount } = render(<SupplyDetailView shopId={7} summary={openSupply} onBack={vi.fn()} onSupplyRefreshed={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: "Проверить передачу Open supply" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Операция не выполнена");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(execute).not.toHaveBeenCalled();

    unmount();
    const closed = { ...openSupply, status: "closed" };
    detail.mockResolvedValue({
      supply: closed,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 0,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [],
    });
    render(<SupplyDetailView shopId={7} summary={closed} onBack={vi.fn()} onSupplyRefreshed={vi.fn()} />);
    await screen.findByText("Закрыта");
    expect(screen.queryByRole("button", { name: /Проверить передачу/ })).not.toBeInTheDocument();
  });
});

function preview(overrides: Partial<MutationPreview> = {}): MutationPreview {
  return {
    shopId: 7,
    previewId: "11111111-1111-1111-1111-111111111111",
    action: "deliver",
    supplyId: "SUP-OPEN",
    supplyName: "Open supply",
    itemCount: 5,
    kizCount: 0,
    ready: true,
    blockers: [],
    warnings: [],
    expiresAt: "2026-07-19T10:00:00Z",
    ...overrides,
  };
}
