import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { JDeskError } from "jdesk-client";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AppCopy } from "../../i18n";
import { commands } from "../../generated/commands";
import { ShopManagementDialog } from "./ShopManagementDialog";

vi.mock("../../generated/commands", () => ({
  commands: {
    shops: {
      create: vi.fn(),
      delete: vi.fn(),
      update: vi.fn(),
    },
  },
}));

const create = vi.mocked(commands.shops.create);
const update = vi.mocked(commands.shops.update);
const remove = vi.mocked(commands.shops.delete);
const secret = "wb-secret-that-must-leave-the-dom";

const copy = {
  label: "Shop",
  empty: "No shops",
  emptyTitle: "Add a shop",
  emptyDescription: "Open shop management.",
  manage: "Manage shops",
  dialogTitle: "Shop management",
  dialogDescription: "Names and write-only API tokens.",
  close: "Close shop management",
  add: "Add shop",
  edit: "Edit",
  remove: "Delete",
  selected: "Selected",
  tokenConfigured: "Token configured",
  tokenMissing: "Token missing",
  name: "Shop name",
  token: "Wildberries API token",
  tokenCreateHint: "Required to create the shop.",
  tokenEditHint: "Leave blank to retain the existing token.",
  saveCreate: "Create shop",
  saveEdit: "Save changes",
  back: "Back to shops",
  deleteTitle: "Delete Main?",
  deleteDescription: "Related local data will be deleted.",
  deleteConfirm: "Delete local shop data",
  saving: "Saving shop",
  errors: {
    invalid: "Check the name and token.",
    busy: "Wait for background work to finish.",
    unavailable: "Could not change the shop.",
  },
} satisfies AppCopy["shop"];

const shops = [
  { id: 7, name: "Main", tokenConfigured: true },
  { id: 8, name: "Backup", tokenConfigured: false },
];

function firstButton(name: string) {
  const button = screen.getAllByRole("button", { name }).at(0);
  if (button === undefined) throw new Error(`Missing ${name} button`);
  return button;
}

function backupShop() {
  const shop = shops.at(1);
  if (shop === undefined) throw new Error("Missing backup shop fixture");
  return shop;
}

describe("ShopManagementDialog", () => {
  beforeEach(() => {
    create.mockReset();
    update.mockReset();
    remove.mockReset();
  });

  it("contains keyboard focus and returns it after closing the shop manager", async () => {
    const user = userEvent.setup();

    function Harness() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Open shops</button>
          {open ? (
            <ShopManagementDialog
              shops={shops}
              selectedId={7}
              onClose={() => setOpen(false)}
              onState={vi.fn()}
              copy={copy}
            />
          ) : null}
        </>
      );
    }

    render(<Harness />);
    const trigger = screen.getByRole("button", { name: "Open shops" });
    await user.click(trigger);
    const close = screen.getByRole("button", { name: "Close shop management" });
    expect(close).toHaveFocus();

    const deleteButtons = screen.getAllByRole("button", { name: "Delete" });
    const lastDelete = deleteButtons.at(-1);
    if (lastDelete === undefined) throw new Error("Missing delete action");
    lastDelete.focus();
    await user.tab();
    expect(close).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Shop management" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("sends a create token once and unmounts it after a validated success", async () => {
    const onClose = vi.fn();
    const onState = vi.fn();
    create.mockResolvedValue({ shops, hasSelectedShop: true, selectedShopId: 7 });
    const user = userEvent.setup();
    render(<ShopManagementDialog shops={shops} selectedId={7} onClose={onClose} onState={onState} copy={copy} />);

    await user.click(screen.getByRole("button", { name: "Add shop" }));
    await user.type(screen.getByLabelText("Shop name"), "Created");
    await user.type(screen.getByLabelText("Wildberries API token"), secret);
    expect(document.body.textContent).not.toContain(secret);
    await user.click(screen.getByRole("button", { name: "Create shop" }));

    await waitFor(() => expect(create).toHaveBeenCalledWith({ name: "Created", apiKey: secret }));
    expect(onState).toHaveBeenCalledWith({ shops, hasSelectedShop: true, selectedShopId: 7 });
    expect(onClose).toHaveBeenCalledOnce();
    expect(screen.queryByDisplayValue(secret)).not.toBeInTheDocument();
  });

  it("opens edit with a blank write-only token and blank retains the stored value", async () => {
    update.mockResolvedValue({ shops, hasSelectedShop: true, selectedShopId: 7 });
    const user = userEvent.setup();
    render(<ShopManagementDialog shops={shops} selectedId={7} onClose={vi.fn()} onState={vi.fn()} copy={copy} />);

    await user.click(firstButton("Edit"));

    expect(screen.getByLabelText("Shop name")).toHaveValue("Main");
    expect(screen.getByLabelText("Wildberries API token")).toHaveValue("");
    expect(document.body.textContent).not.toContain(secret);
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(update).toHaveBeenCalledWith({ shopId: 7, name: "Main", apiKey: "" }));
  });

  it("uses Escape to leave the token form without closing the shop manager", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<ShopManagementDialog shops={shops} selectedId={7} onClose={onClose} onState={vi.fn()} copy={copy} />);

    await user.click(screen.getByRole("button", { name: "Add shop" }));
    await user.type(screen.getByLabelText("Wildberries API token"), secret);
    await user.keyboard("{Escape}");

    expect(screen.getByRole("dialog", { name: "Shop management" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Add shop" })).toBeVisible();
    expect(screen.queryByDisplayValue(secret)).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("requires a second destructive confirmation before delete", async () => {
    remove.mockResolvedValue({ shops: [backupShop()], hasSelectedShop: true, selectedShopId: 8 });
    const user = userEvent.setup();
    render(<ShopManagementDialog shops={shops} selectedId={7} onClose={vi.fn()} onState={vi.fn()} copy={copy} />);

    await user.click(firstButton("Delete"));
    expect(remove).not.toHaveBeenCalled();
    expect(screen.getByText("Related local data will be deleted.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Delete local shop data" }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith({ shopId: 7, confirmed: true }));
  });

  it("rejects malformed bridge state without rendering attacker-controlled text", async () => {
    create.mockResolvedValue({
      shops: [{ id: 7, name: `bad\n${secret}`, tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    const user = userEvent.setup();
    render(<ShopManagementDialog shops={shops} selectedId={7} onClose={vi.fn()} onState={vi.fn()} copy={copy} />);

    await user.click(screen.getByRole("button", { name: "Add shop" }));
    await user.type(screen.getByLabelText("Shop name"), "Created");
    await user.type(screen.getByLabelText("Wildberries API token"), "safe-input");
    await user.click(screen.getByRole("button", { name: "Create shop" }));

    expect(await screen.findByText("Could not change the shop.")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(secret);
  });

  it("maps only the allowlisted busy error", async () => {
    remove.mockRejectedValue(new JDeskError(
      "INVALID_REQUEST",
      "unsafe backend text",
      { kind: "shop_busy" },
    ));
    const user = userEvent.setup();
    render(<ShopManagementDialog shops={shops} selectedId={7} onClose={vi.fn()} onState={vi.fn()} copy={copy} />);

    await user.click(firstButton("Delete"));
    await user.click(screen.getByRole("button", { name: "Delete local shop data" }));

    expect(await screen.findByText("Wait for background work to finish.")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("unsafe backend text");
  });
});
