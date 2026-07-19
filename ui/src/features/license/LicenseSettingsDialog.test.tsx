import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { LicenseSettingsDialog } from "./LicenseSettingsDialog";

vi.mock("../../generated/commands", () => ({
  commands: {
    license: {
      activate: vi.fn(),
      deactivate: vi.fn(),
      refresh: vi.fn(),
      status: vi.fn(),
    },
  },
}));

const status = vi.mocked(commands.license.status);
const activate = vi.mocked(commands.license.activate);
const deactivate = vi.mocked(commands.license.deactivate);
const refresh = vi.mocked(commands.license.refresh);
const secret = "WC-ABCDE-FGHIJ-KLMNO-PQRST";

function license(overrides = {}) {
  return {
    status: "valid",
    kizAllowed: true,
    hasStoredKey: true,
    plan: "standard",
    issuedAt: "2026-07-18T00:00:00Z",
    expiresAt: "2026-08-18T00:00:00Z",
    offlineGraceEndsAt: "2026-08-01T00:00:00Z",
    daysRemaining: 30,
    errorKind: "",
    ...overrides,
  };
}

describe("LicenseSettingsDialog", () => {
  beforeEach(() => {
    status.mockReset();
    activate.mockReset();
    deactivate.mockReset();
    refresh.mockReset();
    status.mockResolvedValue(license());
    refresh.mockResolvedValue(license());
    activate.mockResolvedValue({ accepted: true, license: license(), errorKind: "" });
    deactivate.mockResolvedValue({
      accepted: true,
      license: license({
        status: "not_activated",
        kizAllowed: false,
        hasStoredKey: false,
        plan: "",
        issuedAt: "",
        expiresAt: "",
        offlineGraceEndsAt: "",
        daysRemaining: 0,
      }),
      errorKind: "",
    });
  });

  it("renders a bounded active summary without exposing the stored key", async () => {
    const onClose = vi.fn();
    const { rerender } = render(<LicenseSettingsDialog open onClose={onClose} />);

    expect(await screen.findByText("Лицензия активна")).toBeVisible();
    expect(screen.getByRole("button", { name: "Закрыть настройки" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    expect(screen.getByText("Действует до 18.08.2026")).toBeVisible();
    expect(screen.getByText("30 дней осталось")).toBeVisible();
    expect(screen.getByText("STANDARD")).toBeVisible();
    expect(status).toHaveBeenCalledWith({});
    expect(document.body).not.toHaveTextContent(secret);

    const user = userEvent.setup();
    await user.keyboard("{Shift>}{Tab}{/Shift}");
    expect(screen.getByRole("button", { name: "Отвязать это устройство" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledOnce();
    rerender(<LicenseSettingsDialog open={false} onClose={onClose} />);
    expect(document.body.style.overflow).toBe("");
  });

  it("activates from an explicit key form then clears the key from the DOM", async () => {
    const user = userEvent.setup();
    status.mockResolvedValue(license({
      status: "not_activated", kizAllowed: false, hasStoredKey: false, plan: "",
      issuedAt: "", expiresAt: "", offlineGraceEndsAt: "", daysRemaining: 0,
    }));
    render(<LicenseSettingsDialog open onClose={() => undefined} />);
    const input = await screen.findByRole("textbox", { name: "Лицензионный ключ" });
    await user.type(input, secret.toLowerCase());
    await user.click(screen.getByRole("button", { name: "Активировать лицензию" }));

    expect(activate).toHaveBeenCalledWith({ licenseKey: secret });
    expect(await screen.findByText("Лицензия активна")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("shows allowlisted activation errors and supports a manual offline refresh", async () => {
    const user = userEvent.setup();
    status.mockResolvedValue(license({ status: "network_error", kizAllowed: false, errorKind: "network" }));
    activate.mockResolvedValue({
      accepted: false,
      license: license({ status: "not_activated", kizAllowed: false, hasStoredKey: false }),
      errorKind: "network",
    });
    refresh.mockResolvedValue(license({ status: "offline_grace", errorKind: "offline" }));
    render(<LicenseSettingsDialog open onClose={() => undefined} />);

    await user.click(await screen.findByRole("button", { name: "Проверить лицензию" }));
    expect(await screen.findByText("Офлайн-режим")).toBeVisible();
    expect(refresh).toHaveBeenCalledWith({});

    await user.clear(screen.getByRole("textbox", { name: "Лицензионный ключ" }));
    await user.type(screen.getByRole("textbox", { name: "Лицензионный ключ" }), secret);
    await user.click(screen.getByRole("button", { name: "Активировать лицензию" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось связаться с сервером лицензий");
  });

  it("requires a second explicit confirmation before removing this device", async () => {
    const user = userEvent.setup();
    render(<LicenseSettingsDialog open onClose={() => undefined} />);
    await screen.findByText("Лицензия активна");
    await user.click(screen.getByRole("button", { name: "Отвязать это устройство" }));

    expect(await screen.findByText("Отвязать лицензию от этого устройства?")).toBeVisible();
    expect(screen.getByRole("button", { name: "Отмена" })).toHaveFocus();
    expect(deactivate).not.toHaveBeenCalled();
    await user.keyboard("{Escape}");
    expect(screen.queryByText("Отвязать лицензию от этого устройства?")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Закрыть настройки" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    expect(deactivate).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Отвязать это устройство" }));
    await user.click(screen.getByRole("button", { name: "Подтвердить отвязку устройства" }));
    expect(deactivate).toHaveBeenCalledWith({ confirmed: true });
    expect(await screen.findByText("Лицензия не активирована")).toBeVisible();
  });

  it("rejects malformed bridge responses before rendering their content", async () => {
    status.mockResolvedValue(license({ plan: secret }));
    render(<LicenseSettingsDialog open onClose={() => undefined} />);

    expect(await screen.findByText("Не удалось загрузить состояние лицензии")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
    await waitFor(() => expect(status).toHaveBeenCalledTimes(1));
  });
});
