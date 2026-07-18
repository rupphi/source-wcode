import { AlertTriangle, CheckCircle2, KeyRound, RefreshCw, ShieldCheck, Unplug, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { commands } from "../../generated/commands";
import type { ActionResponse, StatusResponse } from "../../generated/types";

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

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC",
  }).format(new Date(value));
}

function errorCopy(kind: string) {
  return ({
    invalid_license: "Лицензионный ключ недействителен или отозван.",
    device_limit: "Достигнут лимит устройств для этой лицензии.",
    network: "Не удалось связаться с сервером лицензий. Проверьте подключение к интернету.",
    unavailable: "Не удалось выполнить операцию. Повторите позже или обратитесь в поддержку.",
  } as Record<string, string>)[kind] ?? "Не удалось выполнить операцию с лицензией.";
}

function statusCopy(data: StatusResponse) {
  return ({
    not_activated: ["Лицензия не активирована", "Введите ключ, полученный после оплаты подписки."],
    valid: ["Лицензия активна", data.expiresAt ? `Действует до ${formatDate(data.expiresAt)}` : "Подписка подтверждена сервером."],
    offline_grace: ["Офлайн-режим", data.offlineGraceEndsAt
      ? `Можно продолжать работу офлайн до ${formatDate(data.offlineGraceEndsAt)}.`
      : "Подключитесь к интернету для повторной проверки."],
    expired: ["Срок лицензии истёк", data.expiresAt ? `Лицензия закончилась ${formatDate(data.expiresAt)}.` : "Продлите подписку и повторите проверку."],
    invalid: ["Лицензия недействительна", "Ключ отозван, повреждён или не прошёл проверку подписи."],
    device_limit: ["Достигнут лимит устройств", "Освободите слот на другом устройстве или обратитесь в поддержку."],
    clock_tampered: ["Проверьте системные часы", "Дата или время были переведены назад относительно подписанного файла."],
    network_error: ["Нет подтверждения лицензии", "Сервер недоступен, а безопасный офлайн-период закончился."],
  } as Record<string, [string, string]>)[data.status];
}

export function LicenseSettingsDialog({
  open,
  onClose,
  onStatusChange,
}: {
  open: boolean;
  onClose: () => void;
  onStatusChange?: (allowed: boolean) => void;
}) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirmDeactivate, setConfirmDeactivate] = useState(false);
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
      if (event.key !== "Escape" || busy) return;
      if (confirmDeactivate) {
        setConfirmDeactivate(false);
      } else {
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [busy, confirmDeactivate, onClose, open]);

  if (!open) return null;
  const data = state.status === "ready" ? state.data : null;
  const copy = data ? statusCopy(data) : null;

  const applyAction = (response: ActionResponse) => {
    if (!validAction(response)) {
      setState({ status: "error" });
      return;
    }
    setState({ status: "ready", data: response.license });
    onStatusChange?.(response.license.kizAllowed);
    if (response.accepted) setKey("");
    if (!response.accepted) setError(errorCopy(response.errorKind));
  };

  const activate = async () => {
    const normalized = key.trim().toUpperCase();
    if (!KEY.test(normalized) || busy) return;
    setBusy(true);
    setError("");
    try {
      applyAction(await commands.license.activate({ licenseKey: normalized }));
    } catch {
      setError(errorCopy("unavailable"));
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
      setError(errorCopy("unavailable"));
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
      setError(errorCopy("unavailable"));
    } finally {
      setBusy(false);
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
            <h2 id="settings-dialog-title" className="mt-0.5 text-xl font-semibold">Настройки приложения</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="Закрыть настройки"
            disabled={busy}
            onClick={onClose}
          >
            <X aria-hidden="true" size={19} />
          </button>
        </div>
        <div className="space-y-5 p-5">
          <section aria-labelledby="license-settings-title">
            <div className="flex items-start gap-3">
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
                <KeyRound aria-hidden="true" size={19} />
              </div>
              <div>
                <h3 id="license-settings-title" className="font-semibold">Лицензия WCode</h3>
                <p className="mt-1 text-sm leading-5 text-[var(--text-muted)]">
                  Подписка устройства и безопасный офлайн-доступ к покупке KIZ.
                </p>
              </div>
            </div>
            {state.status === "loading" ? (
              <div className="grid min-h-48 place-items-center" role="status" aria-label="Загрузка лицензии">
                <RefreshCw aria-hidden="true" className="animate-spin text-[var(--accent)]" size={24} />
              </div>
            ) : null}
            {state.status === "error" ? (
              <div className="mt-5 rounded-xl border border-[var(--danger)]/20 bg-[var(--danger-soft)] p-4" role="alert">
                <p className="font-semibold">Не удалось загрузить состояние лицензии</p>
                <button className="secondary-button mt-3" type="button" onClick={() => void loadStatus()}>
                  <RefreshCw aria-hidden="true" size={15} />
                  Повторить
                </button>
              </div>
            ) : null}
            {data && copy ? (
              <div className="mt-5 space-y-4">
                <div className={`rounded-xl border p-4 ${data.kizAllowed ? "border-[var(--success)]/20 bg-[var(--success-soft)]" : "border-[var(--border-subtle)] bg-[var(--surface-muted)]"}`}>
                  <div className="flex items-start gap-3">
                    {data.kizAllowed ? (
                      <CheckCircle2 aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--success)]" size={19} />
                    ) : (
                      <AlertTriangle aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--warning)]" size={19} />
                    )}
                    <div>
                      <h4 className="font-semibold">{copy[0]}</h4>
                      <p className="mt-1 text-sm leading-6 text-[var(--text-secondary)]">{copy[1]}</p>
                      {data.status === "valid" ? (
                        <p className="mt-2 text-xs font-medium text-[var(--text-muted)]">
                          {data.daysRemaining} дней осталось
                        </p>
                      ) : null}
                    </div>
                  </div>
                  {data.plan ? <span className="status-pill status-info mt-3">{data.plan.toUpperCase()}</span> : null}
                </div>
                <div className="rounded-xl border border-[var(--border-subtle)] p-4">
                  <label className="field-label">
                    <span>Лицензионный ключ</span>
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
                  <p className="mt-2 text-xs leading-5 text-[var(--text-muted)]">Сохранённый ключ никогда не возвращается в WebView. Введите новый ключ только для активации или замены.</p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <button
                      className="primary-button"
                      type="button"
                      disabled={!KEY.test(key.trim()) || busy}
                      onClick={() => void activate()}
                      aria-label="Активировать лицензию"
                    >
                      {busy ? (
                        <RefreshCw aria-hidden="true" className="animate-spin" size={16} />
                      ) : (
                        <ShieldCheck aria-hidden="true" size={16} />
                      )}
                      Активировать
                    </button>
                    {data.hasStoredKey ? (
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy}
                        onClick={() => void refresh()}
                        aria-label="Проверить лицензию"
                      >
                        <RefreshCw aria-hidden="true" size={16} />
                        Проверить
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
                    aria-label="Отвязать это устройство"
                  >
                    <Unplug aria-hidden="true" size={16} />
                    Отвязать это устройство
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
              Отвязать лицензию от этого устройства?
            </h3>
            <p className="mt-3 text-sm leading-6 text-[var(--text-secondary)]">
              Локальный ключ и подписанный файл будут удалены сразу. Если сервер недоступен,
              занятый слот может потребоваться освободить позже через поддержку.
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                className="secondary-button"
                type="button"
                disabled={busy}
                autoFocus
                onClick={() => setConfirmDeactivate(false)}
              >
                Отмена
              </button>
              <button
                className="primary-button"
                type="button"
                disabled={busy}
                onClick={() => void deactivate()}
                aria-label="Подтвердить отвязку устройства"
              >
                <Unplug aria-hidden="true" size={16} />
                Подтвердить отвязку
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
