import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { getCopy } from "../../i18n";
import { SupportDialog } from "./SupportDialog";

vi.mock("../../generated/commands", () => ({
  commands: {
    diagnostics: { summary: vi.fn(), export: vi.fn() },
  },
}));

const summaryCommand = vi.mocked(commands.diagnostics.summary);
const exportCommand = vi.mocked(commands.diagnostics.export);
const secret = "support-secret-must-not-render";

const summary = {
  appVersion: "1.1.7",
  jdeskVersion: "0.1.3",
  javaVersion: "25",
  osFamily: "macos",
  osVersion: "26.5.1",
  architecture: "arm64",
  databaseStatus: "healthy",
  shopCount: 8,
  supplyCount: 863,
  printJobCount: 34,
  pendingCredentialCount: 0,
  pendingTombstoneCount: 0,
};

describe("SupportDialog", () => {
  beforeEach(() => {
    summaryCommand.mockReset();
    exportCommand.mockReset();
    summaryCommand.mockResolvedValue(summary);
  });

  it("traps keyboard focus and returns it to the button that opened the dialog", async () => {
    const user = userEvent.setup();

    function Harness() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Open support</button>
          {open ? <SupportDialog onClose={() => setOpen(false)} copy={getCopy("en").support} /> : null}
        </>
      );
    }

    render(<Harness />);
    const trigger = screen.getByRole("button", { name: "Open support" });
    await user.click(trigger);
    const close = await screen.findByRole("button", { name: "Close diagnostics" });
    expect(close).toHaveFocus();

    const exportButton = await screen.findByRole("button", { name: "Export support bundle" });
    exportButton.focus();
    await user.tab();
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(exportButton).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Diagnostics and support" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("renders only a validated aggregate summary and closes with Escape", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<SupportDialog onClose={onClose} copy={getCopy("en").support} />);

    expect(await screen.findByRole("dialog", { name: "Diagnostics and support" })).toBeVisible();
    expect(await screen.findByText("863")).toBeVisible();
    expect(screen.getByText("macOS 26.5.1 · arm64")).toBeVisible();
    expect(screen.getByText("Healthy")).toBeVisible();
    expect(document.body.textContent).not.toContain(secret);
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("rejects a malformed bridge response without rendering attacker text and can retry", async () => {
    summaryCommand
      .mockResolvedValueOnce({ ...summary, appVersion: null, osVersion: `${secret}\n` } as never)
      .mockResolvedValueOnce(summary);
    const user = userEvent.setup();
    render(<SupportDialog onClose={vi.fn()} copy={getCopy("en").support} />);

    expect(await screen.findByText("Could not load local diagnostics")).toBeVisible();
    expect(document.body.textContent).not.toContain(secret);
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("863")).toBeVisible();
    expect(summaryCommand).toHaveBeenCalledTimes(2);
  });

  it("exports only after an explicit action and handles native cancellation as success", async () => {
    exportCommand
      .mockResolvedValueOnce({ exported: false, cancelled: true })
      .mockResolvedValueOnce({ exported: true, cancelled: false });
    const user = userEvent.setup();
    render(<SupportDialog onClose={vi.fn()} copy={getCopy("en").support} />);

    await screen.findByText("863");
    expect(exportCommand).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Export support bundle" }));
    expect(await screen.findByText("Export cancelled. No file was written.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Export support bundle" }));
    expect(await screen.findByText("Support bundle saved.")).toBeVisible();
    expect(exportCommand).toHaveBeenNthCalledWith(1, {});
    expect(exportCommand).toHaveBeenCalledTimes(2);
  });

  it("maps malformed receipts and raw failures to one safe export message", async () => {
    exportCommand
      .mockResolvedValueOnce({ exported: true, cancelled: true })
      .mockRejectedValueOnce(new Error(secret));
    const user = userEvent.setup();
    render(<SupportDialog onClose={vi.fn()} copy={getCopy("en").support} />);

    await screen.findByText("863");
    await user.click(screen.getByRole("button", { name: "Export support bundle" }));
    expect(await screen.findByText("Could not export the support bundle.")).toBeVisible();
    expect(document.body.textContent).not.toContain(secret);
    await user.click(screen.getByRole("button", { name: "Export support bundle" }));
    await waitFor(() => expect(exportCommand).toHaveBeenCalledTimes(2));
    expect(document.body.textContent).not.toContain(secret);
  });
});
