import { AlertTriangle, CheckCircle2, KeyRound, Languages, Monitor, Moon, RefreshCw, ShieldCheck, Sun, Unplug, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { commands } from "../../generated/commands";
import type { ActionResponse, StatusResponse } from "../../generated/types";
import { getCopy, interpolate, isLanguage, isTheme } from "../../i18n";
import type { AppCopy, Language, ThemeMode } from "../../i18n";

const KEY = /^WC-(?:[A-Z0-9]{5}-){3}[A-Z0-9]{5}$/;
const STATUSES = new Set([
  "not_activated", "valid", "offline_grace", "expired", "invalid", "device_limit",
  "clock_tampered", "network_error",
]);
const ERRORS = new Set([
  "", "offline", "expired", "invalid_license", "device_limit", "clock_tampered",
  "network", "unavailable",
]);

type State =
  | { status: "loading" | "error" }
  | { status: "ready"; data: StatusResponse };

function validTimestamp(value: string) {
  return value === "" || (/^20\d{2}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)
    && !Number.isNaN(Date.parse(value)));
}

function validStatus(value: unknown): value is StatusResponse {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as StatusResponse;
  if (!STATUSES.has(candidate.status) || typeof candidate.kizAllowed !== "boolean"
    || typeof candidate.hasStoredKey !== "boolean" || typeof candidate.plan !== "string"
    || !/^[a-z0-9_-]{0,32}$/.test(candidate.plan) || !ERRORS.has(candidate.errorKind)
    || !Number.isInteger(candidate.daysRemaining) || candidate.daysRemaining < 0
    || candidate.daysRemaining > 36_500 || typeof candidate.issuedAt !== "string"
    || typeof candidate.expiresAt !== "string" || typeof candidate.offlineGraceEndsAt !== "string"
    || !validTimestamp(candidate.issuedAt) || !validTimestamp(candidate.expiresAt)
    || !validTimestamp(candidate.offlineGraceEndsAt)) return false;
  return candidate.kizAllowed === (candidate.status === "valid" || candidate.status === "offline_grace");
}

function validAction(value: unknown): value is ActionResponse {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as ActionResponse;
  return typeof candidate.accepted === "boolean" && ERRORS.has(candidate.errorKind)
    && validStatus(candidate.license) && (!candidate.accepted || candidate.errorKind === "");
}

function formatDate(value: string, language: Language) {
  const locale = { ru: "ru-RU", en: "en-US", vi: "vi-VN", zh: "zh-CN" }[language];
  return new Intl.DateTimeFormat(locale, {
    day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC",
  }).format(new Date(value));
}

function errorCopy(kind: string, copy: AppCopy["settings"]["license"]) {
  return ({
    invalid_license: copy.errors.invalidLicense,
    device_limit: copy.errors.deviceLimit,
    network: copy.errors.network,
    unavailable: copy.errors.unavailable,
  } as Record<string, string>)[kind] ?? copy.errors.generic;
}

function statusCopy(data: StatusResponse, copy: AppCopy["settings"]["license"], language: Language) {
  return ({
    not_activated: [copy.status.notActivatedTitle, copy.status.notActivatedDescription],
    valid: [copy.status.validTitle, data.expiresAt
      ? interpolate(copy.status.validUntil, { date: formatDate(data.expiresAt, language) })
      : copy.status.verified],
    offline_grace: [copy.status.offlineTitle, data.offlineGraceEndsAt
      ? interpolate(copy.status.offlineUntil, { date: formatDate(data.offlineGraceEndsAt, language) })
      : copy.status.reconnect],
    expired: [copy.status.expiredTitle, data.expiresAt
      ? interpolate(copy.status.expiredOn, { date: formatDate(data.expiresAt, language) })
      : copy.status.renew],
    invalid: [copy.status.invalidTitle, copy.status.invalidDescription],
    device_limit: [copy.status.deviceLimitTitle, copy.status.deviceLimitDescription],
    clock_tampered: [copy.status.clockTitle, copy.status.clockDescription],
    network_error: [copy.status.networkTitle, copy.status.networkDescription],
  } as Record<string, [string, string]>)[data.status];
}

export function LicenseSettingsDialog({
  open,
  onClose,
  onStatusChange,
  copy = getCopy("ru"),
  language = "ru",
  theme = "dark",
  onPreferencesChange,
}: {
  open: boolean;
  onClose: () => void;
  onStatusChange?: (allowed: boolean) => void;
  copy?: AppCopy;
  language?: Language;
  theme?: ThemeMode;
  onPreferencesChange?: (language: Language, theme: ThemeMode) => void;
}) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirmDeactivate, setConfirmDeactivate] = useState(false);
  const [preferenceBusy, setPreferenceBusy] = useState(false);
  const [preferenceError, setPreferenceError] = useState("");
  const requestRef = useRef(0);

  const loadStatus = useCallback(async () => {
    const request = ++requestRef.current;
    await Promise.resolve();
    if (request !== requestRef.current) return;
    setState({ status: "loading" });
    setError("");
    try {
      const response: unknown = await commands.license.status({});
      if (request !== requestRef.current) return;
      if (!validStatus(response)) {
        setState({ status: "error" });
        return;
      }
      setState({ status: "ready", data: response });
      onStatusChange?.(response.kizAllowed);
    } catch {
      if (request === requestRef.current) setState({ status: "error" });
    }
  }, [onStatusChange]);

  useEffect(() => {
    if (!open) return;
    void Promise.resolve().then(loadStatus);
    return () => {
      requestRef.current += 1;
    };
  }, [loadStatus, open]);

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || busy || preferenceBusy) return;
      if (confirmDeactivate) {
        setConfirmDeactivate(false);
      } else {
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [busy, confirmDeactivate, onClose, open, preferenceBusy]);

  if (!open) return null;
  const data = state.status === "ready" ? state.data : null;
  const licenseCopy = copy.settings.license;
  const statusText = data ? statusCopy(data, licenseCopy, language) : null;

  const applyAction = (response: ActionResponse) => {
    if (!validAction(response)) {
      setState({ status: "error" });
      return;
    }
    setState({ status: "ready", data: response.license });
    onStatusChange?.(response.license.kizAllowed);
    if (response.accepted) setKey("");
    if (!response.accepted) setError(errorCopy(response.errorKind, licenseCopy));
  };

  const activate = async () => {
    const normalized = key.trim().toUpperCase();
    if (!KEY.test(normalized) || busy) return;
    setBusy(true);
    setError("");
    try {
      applyAction(await commands.license.activate({ licenseKey: normalized }));
    } catch {
      setError(errorCopy("unavailable", licenseCopy));
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const response = await commands.license.refresh({});
      if (!validStatus(response)) throw new Error("Unexpected license response");
      setState({ status: "ready", data: response });
      onStatusChange?.(response.kizAllowed);
    } catch {
      setError(errorCopy("unavailable", licenseCopy));
    } finally {
      setBusy(false);
    }
  };

  const deactivate = async () => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      applyAction(await commands.license.deactivate({ confirmed: true }));
      setConfirmDeactivate(false);
    } catch {
      setError(errorCopy("unavailable", licenseCopy));
    } finally {
      setBusy(false);
    }
  };

  const changeLanguage = async (next: Language) => {
    if (preferenceBusy || next === language) return;
    setPreferenceBusy(true);
    setPreferenceError("");
    try {
      const response: unknown = await commands.preferences.setLanguage({ language: next });
      if (typeof response !== "object" || response === null) throw new Error("Invalid preferences");
      const candidate = response as { language?: unknown; theme?: unknown };
      if (!isLanguage(candidate.language) || !isTheme(candidate.theme)) throw new Error("Invalid preferences");
      onPreferencesChange?.(candidate.language, candidate.theme);
    } catch {
      setPreferenceError(copy.settings.preferenceError);
    } finally {
      setPreferenceBusy(false);
    }
  };

  const changeTheme = async (next: ThemeMode) => {
    if (preferenceBusy || next === theme) return;
    setPreferenceBusy(true);
    setPreferenceError("");
    try {
      const response: unknown = await commands.preferences.setTheme({ theme: next });
      if (typeof response !== "object" || response === null) throw new Error("Invalid preferences");
      const candidate = response as { language?: unknown; theme?: unknown };
      if (!isLanguage(candidate.language) || !isTheme(candidate.theme)) throw new Error("Invalid preferences");
      onPreferencesChange?.(candidate.language, candidate.theme);
    } catch {
      setPreferenceError(copy.settings.preferenceError);
    } finally {
      setPreferenceBusy(false);
    }
  };

  return (
    <>
      <div
        className="fixed inset-0 z-50 grid place-items-center bg-black/45 p-4"
        role="dialog"
        aria-modal="true"
        aria-hidden={confirmDeactivate || undefined}
        inert={confirmDeactivate || undefined}
        aria-labelledby="settings-dialog-title"
      >
        <div className="max-h-[min(52rem,calc(100vh-2rem))] w-full max-w-2xl overflow-y-auto rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-2xl">
        <div className="sticky top-0 z-10 flex items-center justify-between gap-4 border-b border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-5 py-4">
          <div>
            <p className="text-xs font-semibold tracking-wide text-[var(--text-muted)] uppercase">WCode</p>
            <h2 id="settings-dialog-title" className="mt-0.5 text-xl font-semibold">{copy.settings.title}</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label={copy.settings.close}
            disabled={busy || preferenceBusy}
            onClick={onClose}
          >
            <X aria-hidden="true" size={19} />
          </button>
        </div>
        <div className="space-y-5 p-5">
          <section aria-labelledby="interface-settings-title">
            <div className="flex items-start gap-3">
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
                <Languages aria-hidden="true" size={19} />
              </div>
              <div>
                <h3 id="interface-settings-title" className="font-semibold">{copy.settings.interfaceTitle}</h3>
                <p className="mt-1 text-sm leading-5 text-[var(--text-muted)]">{copy.settings.interfaceDescription}</p>
              </div>
            </div>
            <div className="mt-4 grid gap-4 rounded-xl border border-[var(--border-subtle)] p-4 sm:grid-cols-2">
              <label className="field-label">
                <span>{copy.settings.language}</span>
                <select
                  className="text-input"
                  value={language}
                  disabled={preferenceBusy}
                  onChange={(event) => void changeLanguage(event.target.value as Language)}
                >
                  <option value="ru">Русский</option>
                  <option value="en">English</option>
                  <option value="vi">Tiếng Việt</option>
                  <option value="zh">中文</option>
                </select>
              </label>
              <div>
                <p className="field-label mb-2">{copy.settings.theme}</p>
                <div className="grid grid-cols-3 gap-1.5" role="group" aria-label={copy.settings.theme}>
                  {([
                    ["dark", copy.settings.dark, Moon],
                    ["light", copy.settings.light, Sun],
                    ["system", copy.settings.system, Monitor],
                  ] as const).map(([value, label, Icon]) => (
                    <button
                      key={value}
                      className={theme === value ? "primary-button px-2" : "secondary-button px-2"}
                      type="button"
                      aria-label={label}
                      aria-pressed={theme === value}
                      disabled={preferenceBusy}
                      onClick={() => void changeTheme(value)}
                    >
                      <Icon aria-hidden="true" size={15} />
                      <span className="hidden lg:inline">{label}</span>
                      <span className="sr-only lg:hidden">{label}</span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
            {preferenceError ? <p className="notice-error mt-4" role="alert">{preferenceError}</p> : null}
          </section>
          <section aria-labelledby="license-settings-title">
            <div className="flex items-start gap-3">
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
                <KeyRound aria-hidden="true" size={19} />
              </div>
              <div>
                <h3 id="license-settings-title" className="font-semibold">{licenseCopy.title}</h3>
                <p className="mt-1 text-sm leading-5 text-[var(--text-muted)]">
                  {licenseCopy.description}
                </p>
              </div>
            </div>
            {state.status === "loading" ? (
              <div className="grid min-h-48 place-items-center" role="status" aria-label={licenseCopy.loading}>
                <RefreshCw aria-hidden="true" className="animate-spin text-[var(--accent)]" size={24} />
              </div>
            ) : null}
            {state.status === "error" ? (
              <div className="mt-5 rounded-xl border border-[var(--danger)]/20 bg-[var(--danger-soft)] p-4" role="alert">
                <p className="font-semibold">{licenseCopy.loadError}</p>
                <button className="secondary-button mt-3" type="button" onClick={() => void loadStatus()}>
                  <RefreshCw aria-hidden="true" size={15} />
                  {copy.common.retry}
                </button>
              </div>
            ) : null}
            {data && statusText ? (
              <div className="mt-5 space-y-4">
                <div className={`rounded-xl border p-4 ${data.kizAllowed ? "border-[var(--success)]/20 bg-[var(--success-soft)]" : "border-[var(--border-subtle)] bg-[var(--surface-muted)]"}`}>
                  <div className="flex items-start gap-3">
                    {data.kizAllowed ? (
                      <CheckCircle2 aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--success)]" size={19} />
                    ) : (
                      <AlertTriangle aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--warning)]" size={19} />
                    )}
                    <div>
                      <h4 className="font-semibold">{statusText[0]}</h4>
                      <p className="mt-1 text-sm leading-6 text-[var(--text-secondary)]">{statusText[1]}</p>
                      {data.status === "valid" ? (
                        <p className="mt-2 text-xs font-medium text-[var(--text-muted)]">
                          {interpolate(licenseCopy.status.daysRemaining, { days: data.daysRemaining })}
                        </p>
                      ) : null}
                    </div>
                  </div>
                  {data.plan ? <span className="status-pill status-info mt-3">{data.plan.toUpperCase()}</span> : null}
                </div>
                <div className="rounded-xl border border-[var(--border-subtle)] p-4">
                  <label className="field-label">
                    <span>{licenseCopy.keyLabel}</span>
                    <input
                      className="text-input font-mono uppercase"
                      type="text"
                      value={key}
                      maxLength={26}
                      autoComplete="off"
                      spellCheck={false}
                      placeholder="WC-XXXXX-XXXXX-XXXXX-XXXXX"
                      disabled={busy}
                      autoFocus
                      onChange={(event) => {
                        setKey(event.target.value.toUpperCase());
                        setError("");
                      }}
                    />
                  </label>
                  <p className="mt-2 text-xs leading-5 text-[var(--text-muted)]">{licenseCopy.keyHint}</p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <button
                      className="primary-button"
                      type="button"
                      disabled={!KEY.test(key.trim()) || busy}
                      onClick={() => void activate()}
                      aria-label={licenseCopy.activateLabel}
                    >
                      {busy ? (
                        <RefreshCw aria-hidden="true" className="animate-spin" size={16} />
                      ) : (
                        <ShieldCheck aria-hidden="true" size={16} />
                      )}
                      {licenseCopy.activate}
                    </button>
                    {data.hasStoredKey ? (
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy}
                        onClick={() => void refresh()}
                        aria-label={licenseCopy.checkLabel}
                      >
                        <RefreshCw aria-hidden="true" size={16} />
                        {licenseCopy.check}
                      </button>
                    ) : null}
                  </div>
                </div>
                {data.hasStoredKey ? (
                  <button
                    className="secondary-button text-[var(--danger)]"
                    type="button"
                    disabled={busy}
                    onClick={() => setConfirmDeactivate(true)}
                    aria-label={licenseCopy.unlink}
                  >
                    <Unplug aria-hidden="true" size={16} />
                    {licenseCopy.unlink}
                  </button>
                ) : null}
              </div>
            ) : null}
            {error ? <p className="notice-error mt-4" role="alert">{error}</p> : null}
          </section>
        </div>
        </div>
      </div>
      {confirmDeactivate ? (
        <div
          className="fixed inset-0 z-[60] grid place-items-center bg-black/50 p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="deactivate-title"
        >
          <div className="w-full max-w-md rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl">
            <h3 id="deactivate-title" className="text-lg font-semibold">
              {licenseCopy.confirmTitle}
            </h3>
            <p className="mt-3 text-sm leading-6 text-[var(--text-secondary)]">
              {licenseCopy.confirmDescription}
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                className="secondary-button"
                type="button"
                disabled={busy}
                autoFocus
                onClick={() => setConfirmDeactivate(false)}
              >
                {copy.common.cancel}
              </button>
              <button
                className="primary-button"
                type="button"
                disabled={busy}
                onClick={() => void deactivate()}
                aria-label={licenseCopy.confirm}
              >
                <Unplug aria-hidden="true" size={16} />
                {licenseCopy.confirm}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
