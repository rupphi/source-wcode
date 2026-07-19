import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import type { ProductItem } from "../../generated/types";
import { ZnackPurchaseDialog } from "./ZnackPurchaseDialog";

vi.mock("../../generated/commands", () => ({
  commands: {
    znack: { preparePurchase: vi.fn(), startPurchase: vi.fn() },
  },
}));

const preparePurchase = vi.mocked(commands.znack.preparePurchase);
const startPurchase = vi.mocked(commands.znack.startPurchase);
const purchaseId = "44444444-4444-4444-8444-444444444444";
const version = "a".repeat(64);
const product: ProductItem = {
  gtin: "04601234567890",
  productName: "Ботинки Alpine",
  category: "Обувь",
  tnVed: "640399",
  cisType: "UNIT",
  goodMarkStatus: "READY",
  goodTurnStatus: "READY",
  readinessCheckedAt: "2026-07-19T17:00:00Z",
  deleted: false,
};

describe("ZnackPurchaseDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    preparePurchase.mockResolvedValue({
      shopId: 7,
      purchaseId,
      gtin: product.gtin,
      productName: product.productName,
      quantity: 1,
      autoIntroduction: true,
      warnings: ["automatic_introduction"],
      expiresAt: "2026-07-19T18:00:00Z",
      version,
    });
  });

  it("rejects an unexpected preview before exposing paid confirmation", async () => {
    const user = userEvent.setup();
    preparePurchase.mockResolvedValueOnce({
      shopId: 8,
      purchaseId,
      gtin: product.gtin,
      productName: product.productName,
      quantity: 1,
      autoIntroduction: true,
      warnings: ["automatic_introduction"],
      expiresAt: "2026-07-19T18:00:00Z",
      version,
    });
    renderDialog();

    await user.click(screen.getByRole("button", { name: "Подготовить покупку" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось подготовить покупку");
    expect(screen.queryByRole("button", { name: "Подтвердить покупку КИЗ" })).not.toBeInTheDocument();
    expect(startPurchase).not.toHaveBeenCalled();
  });

  it("keeps the dialog open and withholds success for a mismatched receipt", async () => {
    const user = userEvent.setup();
    const onStarted = vi.fn();
    startPurchase.mockResolvedValue({
      accepted: true,
      purchase: {
        purchaseId,
        gtin: product.gtin,
        productName: product.productName,
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
    renderDialog(onStarted);

    await user.click(screen.getByRole("button", { name: "Подготовить покупку" }));
    const dialog = await screen.findByRole("dialog", { name: "Подтверждение покупки КИЗ" });
    await user.click(within(dialog).getByRole("button", { name: "Подтвердить покупку КИЗ" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Покупка не запущена");
    expect(onStarted).not.toHaveBeenCalled();
  });
});

function renderDialog(onStarted = vi.fn()) {
  return render(
    <ZnackPurchaseDialog
      shopId={7}
      product={product}
      settingsVersion={version}
      canPurchase
      onClose={vi.fn()}
      onStarted={onStarted}
    />,
  );
}
