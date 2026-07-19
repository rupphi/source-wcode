import { Activity, Database, Download, RefreshCw, ShieldCheck, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useModalFocus } from "../../components/useModalFocus";
import { commands } from "../../generated/commands";
import type { DiagnosticsSummary, ExportResponse } from "../../generated/types";
import type { AppCopy } from "../../i18n";

const SAFE_TEXT = /^[A-Za-z0-9][A-Za-z0-9._ -]{0,63}$/;
const OS_FAMILIES = new Set(["macos", "windows", "linux", "other"]);
const ARCHITECTURES = new Set(["arm64", "x86_64", "other"]);
const DATABASE_STATES = new Set(["healthy", "unavailable", "corrupt"]);
const SUMMARY_KEYS = new Set([
  "appVersion", "jdeskVersion", "javaVersion", "osFamily", "osVersion", "architecture",
  "databaseStatus", "shopCount", "supplyCount", "printJobCount", "pendingCredentialCount",
  "pendingTombstoneCount",
]);

type State =
  | { status: "loading" | "error" }
  | { status: "ready"; data: DiagnosticsSummary };
type ExportState = "idle" | "busy" | "success" | "cancelled" | "error";

function boundedCount(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) >= 0 && Number(value) <= 1_000_000;
}

function safeText(value: unknown): value is string {
  return typeof value === "string" && SAFE_TEXT.test(value);
}

function validSummary(value: unknown): value is DiagnosticsSummary {
  if (typeof value !== "object" || value === null) return false;
  const keys = Object.keys(value);
  if (keys.length !== SUMMARY_KEYS.size || keys.some((key) => !SUMMARY_KEYS.has(key))) return false;
  const candidate = value as DiagnosticsSummary;
  return safeText(candidate.appVersion) && safeText(candidate.jdeskVersion)
    && safeText(candidate.javaVersion) && OS_FAMILIES.has(candidate.osFamily)
    && safeText(candidate.osVersion) && ARCHITECTURES.has(candidate.architecture)
    && DATABASE_STATES.has(candidate.databaseStatus) && boundedCount(candidate.shopCount)
    && boundedCount(candidate.supplyCount) && boundedCount(candidate.printJobCount)
    && boundedCount(candidate.pendingCredentialCount) && boundedCount(candidate.pendingTombstoneCount);
}

function validReceipt(value: unknown): value is ExportResponse {
  if (typeof value !== "object" || value === null || Object.keys(value).length !== 2) return false;
  const candidate = value as ExportResponse;
  return typeof candidate.exported === "boolean" && typeof candidate.cancelled === "boolean"
    && candidate.exported !== candidate.cancelled;
}

export function SupportDialog({ onClose, copy }: { onClose: () => void; copy: AppCopy["support"] }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [exportState, setExportState] = useState<ExportState>("idle");
  const request = useRef(0);
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLDivElement>(exportState === "busy", onClose);

  const load = useCallback(async () => {
    const current = ++request.current;
    setState({ status: "loading" });
    setExportState("idle");
    try {
      const response: unknown = await commands.diagnostics.summary({});
      if (current !== request.current) return;
      setState(validSummary(response) ? { status: "ready", data: response } : { status: "error" });
    } catch {
      if (current === request.current) setState({ status: "error" });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
    return () => { request.current += 1; };
  }, [load]);

  const exportBundle = async () => {
    if (state.status !== "ready" || exportState === "busy") return;
    const current = request.current;
    setExportState("busy");
    try {
      const response: unknown = await commands.diagnostics.export({});
      if (current !== request.current) return;
      if (!validReceipt(response)) {
        setExportState("error");
      } else {
        setExportState(response.exported ? "success" : "cancelled");
      }
    } catch {
      if (current === request.current) setExportState("error");
    }
  };

  const data = state.status === "ready" ? state.data : null;
  const databaseCopy = data ? copy.database[data.databaseStatus as keyof typeof copy.database] : "";
  const osCopy = data ? copy.os[data.osFamily as keyof typeof copy.os] : "";
  const counts = data ? [
    [copy.shops, data.shopCount],
    [copy.supplies, data.supplyCount],
    [copy.printJobs, data.printJobCount],
    [copy.pendingCredentials, data.pendingCredentialCount],
    [copy.pendingDeletions, data.pendingTombstoneCount],
  ] as const : [];

  return (
    <div
      ref={dialogRef}
      className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="support-dialog-title"
    >
      <div className="max-h-[min(48rem,calc(100vh-2rem))] w-full max-w-3xl overflow-y-auto rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-popover)]">
        <header className="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-5 py-4">
          <div>
            <p className="text-xs font-semibold tracking-[0.12em] text-[var(--accent-strong)] uppercase">WCode</p>
            <h2 id="support-dialog-title" className="mt-1 text-xl font-semibold">{copy.title}</h2>
            <p className="mt-1 max-w-xl text-sm leading-5 text-[var(--text-muted)]">{copy.description}</p>
          </div>
          <button ref={initialFocusRef} className="icon-button shrink-0" type="button" aria-label={copy.close} disabled={exportState === "busy"} onClick={onClose}>
            <X aria-hidden="true" size={19} />
          </button>
        </header>

        <div className="space-y-5 p-5">
          {state.status === "loading" ? (
            <div className="grid min-h-52 place-items-center" role="status" aria-label={copy.loading}>
              <RefreshCw aria-hidden="true" className="animate-spin text-[var(--accent)]" size={25} />
            </div>
          ) : null}

          {state.status === "error" ? (
            <div className="rounded-xl border border-[var(--danger)]/20 bg-[var(--danger-soft)] p-4" role="alert">
              <p className="font-semibold">{copy.loadError}</p>
              <button className="secondary-button mt-3" type="button" onClick={() => void load()}>
                <RefreshCw aria-hidden="true" size={15} />
                {copy.retry}
              </button>
            </div>
          ) : null}

          {data ? (
            <>
              <section className="grid gap-3 sm:grid-cols-2" aria-label={copy.systemHealth}>
                <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
                  <div className="flex items-center gap-2 text-sm font-semibold">
                    <Activity aria-hidden="true" className="text-[var(--accent-strong)]" size={18} />
                    {copy.platform}
                  </div>
                  <p className="mt-3 text-lg font-semibold">{osCopy} {data.osVersion} · {data.architecture}</p>
                  <p className="mt-1 text-xs text-[var(--text-muted)]">WCode {data.appVersion}</p>
                </div>
                <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
                  <div className="flex items-center gap-2 text-sm font-semibold">
                    <Database aria-hidden="true" className="text-[var(--accent-strong)]" size={18} />
                    {copy.databaseTitle}
                  </div>
                  <p className={`mt-3 text-lg font-semibold ${data.databaseStatus === "healthy" ? "text-[var(--success)]" : "text-[var(--warning)]"}`}>{databaseCopy}</p>
                  <p className="mt-1 text-xs text-[var(--text-muted)]">{copy.aggregateOnly}</p>
                </div>
              </section>

              <section aria-labelledby="support-local-counts">
                <h3 id="support-local-counts" className="text-sm font-semibold">{copy.localCounts}</h3>
                <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
                  {counts.map(([label, value]) => (
                    <div key={label} className="rounded-xl border border-[var(--border-subtle)] p-3 text-center">
                      <p className="text-xl font-semibold tabular-nums">{value}</p>
                      <p className="mt-1 text-xs leading-4 text-[var(--text-muted)]">{label}</p>
                    </div>
                  ))}
                </div>
              </section>

              <section className="rounded-xl border border-[var(--accent)]/20 bg-[var(--accent-soft)] p-4">
                <div className="flex items-start gap-3">
                  <ShieldCheck aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--accent-strong)]" size={19} />
                  <div>
                    <h3 className="font-semibold">{copy.privacyTitle}</h3>
                    <p className="mt-1 text-sm leading-6 text-[var(--text-secondary)]">{copy.privacyDescription}</p>
                  </div>
                </div>
              </section>

              <div className="flex flex-col-reverse items-stretch justify-between gap-3 sm:flex-row sm:items-center">
                <div aria-live="polite">
                  {exportState === "success" ? <p className="notice-success">{copy.exportSuccess}</p> : null}
                  {exportState === "cancelled" ? <p className="text-sm text-[var(--text-muted)]">{copy.exportCancelled}</p> : null}
                  {exportState === "error" ? <p className="notice-error" role="alert">{copy.exportError}</p> : null}
                </div>
                <button className="primary-button shrink-0" type="button" disabled={exportState === "busy"} onClick={() => void exportBundle()}>
                  {exportState === "busy" ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} /> : <Download aria-hidden="true" size={16} />}
                  {exportState === "busy" ? copy.exporting : copy.exportBundle}
                </button>
              </div>
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
