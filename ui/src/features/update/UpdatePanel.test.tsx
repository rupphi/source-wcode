import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { getCopy } from "../../i18n";
import { UpdatePanel } from "./UpdatePanel";

vi.mock("../../generated/commands", () => ({
  commands: {
    updates: {
      check: vi.fn(),
      startDownload: vi.fn(),
      downloadStatus: vi.fn(),
      cancelDownload: vi.fn(),
      install: vi.fn(),
      skip: vi.fn(),
    },
  },
}));

const check = vi.mocked(commands.updates.check);
const startDownload = vi.mocked(commands.updates.startDownload);
const downloadStatus = vi.mocked(commands.updates.downloadStatus);
const cancelDownload = vi.mocked(commands.updates.cancelDownload);
const install = vi.mocked(commands.updates.install);
const skip = vi.mocked(commands.updates.skip);
const jobId = "123e4567-e89b-12d3-a456-426614174000";
const secret = "https://private.example/installer?secret=must-not-render";

const available = {
  state: "available",
  currentVersion: "1.2.2",
  version: "1.2.3",
  publishedAt: "2026-07-19T00:00:00Z",
  notes: ["Signed installer", "Fresh rollback snapshot"],
  mandatory: true,
  installSupported: true,
};

describe("UpdatePanel", () => {
  beforeEach(() => {
    check.mockReset();
    startDownload.mockReset();
    downloadStatus.mockReset();
    cancelDownload.mockReset();
    install.mockReset();
    skip.mockReset();
    check.mockResolvedValue(available);
    startDownload.mockResolvedValue({ accepted: true, jobId, version: "1.2.3" });
    downloadStatus.mockResolvedValue({
      jobId,
      version: "1.2.3",
      state: "completed",
      downloadedBytes: 12_345_678,
      totalBytes: 12_345_678,
      completedAt: "2026-07-19T00:01:00Z",
      errorKind: "",
      retryable: false,
    });
    cancelDownload.mockResolvedValue({ cancelRequested: true, jobId });
    install.mockResolvedValue({ accepted: true, version: "1.2.3" });
    skip.mockResolvedValue({ skipped: true, version: "1.2.3" });
  });

  it("requires an explicit check and does not auto-download a mandatory release", async () => {
    const user = userEvent.setup();
    render(<UpdatePanel copy={getCopy("en").settings.update} />);

    expect(check).not.toHaveBeenCalled();
    expect(startDownload).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Check for updates" }));

    expect(await screen.findByText("WCode 1.2.3 is available")).toBeVisible();
    expect(screen.getByText("Priority release — installation is still your choice.")).toBeVisible();
    expect(screen.getByText("Signed installer")).toBeVisible();
    expect(startDownload).not.toHaveBeenCalled();
  });

  it("rejects malformed bridge metadata without rendering attacker-controlled text", async () => {
    check.mockResolvedValue({ ...available, notes: [secret], unexpected: secret } as never);
    const user = userEvent.setup();
    render(<UpdatePanel copy={getCopy("en").settings.update} />);

    await user.click(screen.getByRole("button", { name: "Check for updates" }));

    expect(await screen.findByText("Could not verify updates safely.")).toBeVisible();
    expect(document.body.textContent).not.toContain(secret);
  });

  it("downloads only after confirmation and requires a second action to install", async () => {
    const user = userEvent.setup();
    render(<UpdatePanel copy={getCopy("en").settings.update} />);
    await user.click(screen.getByRole("button", { name: "Check for updates" }));
    await screen.findByText("WCode 1.2.3 is available");

    await user.click(screen.getByRole("button", { name: "Download signed update" }));
    expect(startDownload).toHaveBeenCalledWith({ version: "1.2.3" });
    expect(install).not.toHaveBeenCalled();
    expect(await screen.findByRole("button", { name: "Install and restart WCode" })).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Install and restart WCode" }));
    expect(install).toHaveBeenCalledWith({ jobId });
    expect(await screen.findByText("Installer started. WCode will close safely.")).toBeVisible();
  });

  it("offers cancellation while downloading and maps cancellation to a safe state", async () => {
    downloadStatus
      .mockResolvedValueOnce({
        jobId,
        version: "1.2.3",
        state: "running",
        downloadedBytes: 4_000_000,
        totalBytes: 12_345_678,
        completedAt: "",
        errorKind: "",
        retryable: false,
      })
      .mockResolvedValue({
        jobId,
        version: "1.2.3",
        state: "cancelled",
        downloadedBytes: 4_000_000,
        totalBytes: 12_345_678,
        completedAt: "2026-07-19T00:01:00Z",
        errorKind: "cancelled",
        retryable: true,
      });
    const user = userEvent.setup();
    render(<UpdatePanel copy={getCopy("en").settings.update} />);
    await user.click(screen.getByRole("button", { name: "Check for updates" }));
    await user.click(await screen.findByRole("button", { name: "Download signed update" }));
    await screen.findByText("32%");

    await user.click(screen.getByRole("button", { name: "Cancel download" }));

    expect(cancelDownload).toHaveBeenCalledWith({ jobId });
    await waitFor(() => expect(screen.getByText("Download cancelled. No installer was kept.")).toBeVisible());
  });

  it("stays busy until the backend confirms cancellation and cleanup", async () => {
    downloadStatus.mockResolvedValue({
      jobId,
      version: "1.2.3",
      state: "running",
      downloadedBytes: 4_000_000,
      totalBytes: 12_345_678,
      completedAt: "",
      errorKind: "",
      retryable: false,
    });
    const busyChanged = vi.fn();
    const user = userEvent.setup();
    render(<UpdatePanel copy={getCopy("en").settings.update} onBusyChange={busyChanged} />);
    await user.click(screen.getByRole("button", { name: "Check for updates" }));
    await user.click(await screen.findByRole("button", { name: "Download signed update" }));
    await screen.findByText("32%");

    await user.click(screen.getByRole("button", { name: "Cancel download" }));

    expect(cancelDownload).toHaveBeenCalledWith({ jobId });
    expect(screen.queryByText("Download cancelled. No installer was kept.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel download" })).toBeDisabled();
    expect(busyChanged).toHaveBeenLastCalledWith(true);
  });

  it("persists an explicit skip only for an optional release", async () => {
    check.mockResolvedValue({ ...available, mandatory: false });
    const user = userEvent.setup();
    render(<UpdatePanel copy={getCopy("en").settings.update} />);
    await user.click(screen.getByRole("button", { name: "Check for updates" }));

    await user.click(await screen.findByRole("button", { name: "Skip this version" }));

    expect(skip).toHaveBeenCalledWith({ version: "1.2.3" });
    expect(await screen.findByText("This version is skipped. A newer release will appear on the next check.")).toBeVisible();
  });
});
