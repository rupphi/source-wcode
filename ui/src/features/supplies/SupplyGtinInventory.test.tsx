import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { SupplyGtinInventory } from "./SupplyGtinInventory";

vi.mock("../../generated/commands", () => ({
  commands: {
    kizMapping: { catalog: vi.fn() },
    znack: { settings: vi.fn(), preparePurchase: vi.fn(), startPurchase: vi.fn() },
  },
}));

const catalog = vi.mocked(commands.kizMapping.catalog);
const settings = vi.mocked(commands.znack.settings);
const preparePurchase = vi.mocked(commands.znack.preparePurchase);
const startPurchase = vi.mocked(commands.znack.startPurchase);
const gtin = "04601234567890";
const version = "a".repeat(64);
const purchaseId = "44444444-4444-4444-8444-444444444444";

describe("SupplyGtinInventory", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    catalog.mockImplementation(async (request) => ({
      ...request,
      hasMore: false,
      availableCategories: ["Обувь"],
      items: [{
        gtin,
        productName: "Ботинки Alpine",
        category: "Обувь",
        available: 12,
        reserved: 1,
        consumed: 4,
        mappingRuleCount: 2,
        orderStatus: "CODES_READY",
        pipelineStage: "COMPLETED",
        errorMessage: "",
        syncedAt: "2026-07-19T17:00:00Z",
      }],
    }));
    settings.mockResolvedValue({
      shopId: 7,
      omsId: "OMS-7",
      omsConnection: "connection",
      documentNumber: "DOC-7",
      documentDate: "19.07.2026",
      autoIntroduction: true,
      signatureStatus: "VERIFIED",
      certificateLabel: "CN=Fixture",
      certificateValidTo: "2027-07-19T00:00:00Z",
      version,
    });
    preparePurchase.mockResolvedValue({
      shopId: 7,
      purchaseId,
      gtin,
      productName: "Ботинки Alpine",
      quantity: 2,
      autoIntroduction: true,
      warnings: ["automatic_introduction"],
      expiresAt: "2026-07-19T18:00:00Z",
      version,
    });
    startPurchase.mockResolvedValue({
      accepted: true,
      purchase: {
        purchaseId,
        gtin,
        productName: "Ботинки Alpine",
        quantity: 2,
        stage: "validating",
        state: "running",
        downloadedCodes: 0,
        progress: 0,
        errorKind: "",
        retryable: false,
        canRetryIntroduction: false,
        createdAt: "2026-07-19T17:00:00Z",
        updatedAt: "2026-07-19T17:00:00Z",
      },
    });
  });

  it("reads bounded local inventory and reuses explicit paid purchase confirmation", async () => {
    const user = userEvent.setup();
    render(<SupplyGtinInventory shopId={7} licenseAllowed />);

    expect(await screen.findByText("Ботинки Alpine")).toBeVisible();
    expect(screen.getByText("12 KIZ доступно")).toBeVisible();
    expect(catalog).toHaveBeenCalledWith({ shopId: 7, query: "", categories: [], page: 1, pageSize: 10 });
    expect(preparePurchase).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: `Купить КИЗ для ${gtin}` }));
    const quantity = screen.getByRole("spinbutton", { name: "Количество КИЗ" });
    await user.clear(quantity);
    await user.type(quantity, "2");
    await user.click(screen.getByRole("button", { name: "Подготовить покупку" }));
    expect(preparePurchase).toHaveBeenCalledWith({ shopId: 7, gtin, quantity: 2, version });

    const confirmation = await screen.findByRole("dialog", { name: "Подтверждение покупки КИЗ" });
    await user.click(within(confirmation).getByRole("button", { name: "Подтвердить покупку КИЗ" }));
    expect(startPurchase).toHaveBeenCalledWith({ shopId: 7, purchaseId, version, confirmed: true });
    expect(await screen.findByRole("status")).toHaveTextContent("Покупка КИЗ запущена");
    await waitFor(() => expect(catalog).toHaveBeenCalledTimes(2));
  });

  it("keeps purchase disabled when the shared license oracle denies it", async () => {
    render(<SupplyGtinInventory shopId={7} licenseAllowed={false} />);

    const buy = await screen.findByRole("button", { name: `Купить КИЗ для ${gtin}` });
    expect(buy).toBeDisabled();
    expect(preparePurchase).not.toHaveBeenCalled();
  });

  it("fails closed instead of rendering malformed catalog data", async () => {
    catalog.mockImplementationOnce(async (request) => ({
      ...request,
      hasMore: false,
      availableCategories: [],
      items: [{
        gtin: "not-a-gtin",
        productName: "unsafe",
        category: "",
        available: -1,
        reserved: 0,
        consumed: 0,
        mappingRuleCount: 0,
        orderStatus: "",
        pipelineStage: "",
        errorMessage: "",
        syncedAt: "",
      }],
    }));
    render(<SupplyGtinInventory shopId={7} licenseAllowed />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить локальный каталог GTIN");
    expect(screen.queryByText("unsafe")).not.toBeInTheDocument();
  });

  it("retries a transient persisted-settings read without calling purchase commands", async () => {
    const user = userEvent.setup();
    settings.mockRejectedValueOnce(new Error("fixture failure"));
    render(<SupplyGtinInventory shopId={7} licenseAllowed />);

    const error = await screen.findByRole("alert");
    expect(error).toHaveTextContent("Не удалось проверить настройки Znack");
    await user.click(within(error).getByRole("button", { name: "Повторить" }));

    await waitFor(() => expect(settings).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole("button", { name: `Купить КИЗ для ${gtin}` })).toBeEnabled();
    expect(preparePurchase).not.toHaveBeenCalled();
  });
});
