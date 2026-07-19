import { Download, PackageCheck, RefreshCw, ShieldCheck, XCircle } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { commands } from "../../generated/commands";
import type { AppCopy } from "../../i18n";
import { interpolate } from "../../i18n";

const VERSION = /^\d{1,5}\.\d{1,5}\.\d{1,5}$/;
const JOB_ID = /^[0-9a-f-]{36}$/;
const TIMESTAMP = /^20\d{2}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;
const CHECK_KEYS = new Set([
  "state", "currentVersion", "version", "publishedAt", "notes", "mandatory", "installSupported",
]);
const START_KEYS = new Set(["accepted", "jobId", "version"]);
const STATUS_KEYS = new Set([
  "jobId", "version", "state", "downloadedBytes", "totalBytes", "completedAt", "errorKind", "retryable",
]);
const CANCEL_KEYS = new Set(["cancelRequested", "jobId"]);
const INSTALL_KEYS = new Set(["accepted", "version"]);
const SKIP_KEYS = new Set(["skipped", "version"]);

type Release = {
  state: "available";
  currentVersion: string;
  version: string;
  publishedAt: string;
  notes: string[];
  mandatory: boolean;
  installSupported: boolean;
};
type CheckState = "idle" | "checking" | "current" | "unavailable" | "skipped" | Release;
type Job = {
  jobId: string;
  version: string;
  state: "running" | "completed" | "cancelled" | "failed";
  downloadedBytes: number;
  totalBytes: number;
};
type InstallState = "idle" | "busy" | "started" | "error";

function exactObject(value: unknown, keys: Set<string>): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null) return false;
  const actual = Object.keys(value);
  return actual.length === keys.size && actual.every((key) => keys.has(key));
}

function validTimestamp(value: unknown, empty = false): value is string {
  return typeof value === "string" && ((empty && value === "")
    || (TIMESTAMP.test(value) && !Number.isNaN(Date.parse(value))));
}

function validNote(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 500
    && value === value.trim()
    && !Array.from(value).some((character) => character.charCodeAt(0) <= 31 || character.charCodeAt(0) === 127);
}

function validCheck(value: unknown): value is Release | { state: "current" | "unavailable" | "skipped" } {
  if (!exactObject(value, CHECK_KEYS)) return false;
  if (value.state !== "available" && value.state !== "current"
    && value.state !== "unavailable" && value.state !== "skipped") return false;
  if (typeof value.currentVersion !== "string" || !VERSION.test(value.currentVersion)
    || typeof value.version !== "string" || typeof value.publishedAt !== "string"
    || !Array.isArray(value.notes) || value.notes.length > 20 || !value.notes.every(validNote)
    || typeof value.mandatory !== "boolean" || typeof value.installSupported !== "boolean") return false;
  if (value.state === "current" || value.state === "unavailable") {
    return value.version === "" && value.publishedAt === "" && value.notes.length === 0
      && !value.mandatory && !value.installSupported;
  }
  if (value.state === "skipped") {
    return VERSION.test(value.version) && validTimestamp(value.publishedAt)
      && !value.mandatory && !value.installSupported;
  }
  return VERSION.test(value.version) && validTimestamp(value.publishedAt);
}

function validStart(value: unknown, version: string): value is { accepted: boolean; jobId: string; version: string } {
  if (!exactObject(value, START_KEYS)) return false;
  return typeof value.accepted === "boolean" && typeof value.jobId === "string"
    && JOB_ID.test(value.jobId) && value.version === version;
}

function validStatus(value: unknown, jobId: string, version: string): value is Job & { completedAt: string } {
  if (!exactObject(value, STATUS_KEYS)) return false;
  const states = new Set(["running", "completed", "cancelled", "failed"]);
  const errors = new Set(["", "download_failed", "cancelled"]);
  return value.jobId === jobId && value.version === version && typeof value.state === "string"
    && states.has(value.state) && Number.isSafeInteger(value.downloadedBytes)
    && Number(value.downloadedBytes) >= 0 && Number.isSafeInteger(value.totalBytes)
    && Number(value.totalBytes) >= 1024 * 1024 && Number(value.totalBytes) <= 512 * 1024 * 1024
    && Number(value.downloadedBytes) <= Number(value.totalBytes)
    && validTimestamp(value.completedAt, true) && typeof value.errorKind === "string"
    && errors.has(value.errorKind) && typeof value.retryable === "boolean"
    && (value.state === "running" ? value.completedAt === "" : value.completedAt !== "");
}

function validCancel(value: unknown, jobId: string): value is { cancelRequested: boolean; jobId: string } {
  return exactObject(value, CANCEL_KEYS) && typeof value.cancelRequested === "boolean" && value.jobId === jobId;
}

function validInstall(value: unknown, version: string): value is { accepted: boolean; version: string } {
  return exactObject(value, INSTALL_KEYS) && value.accepted === true && value.version === version;
}

function validSkip(value: unknown, version: string): value is { skipped: boolean; version: string } {
  return exactObject(value, SKIP_KEYS) && value.skipped === true && value.version === version;
}

export function UpdatePanel({
  copy,
  onBusyChange,
}: {
  copy: AppCopy["settings"]["update"];
  onBusyChange?: (busy: boolean) => void;
}) {
  const [checkState, setCheckState] = useState<CheckState>("idle");
  const [job, setJob] = useState<Job | null>(null);
  const [installState, setInstallState] = useState<InstallState>("idle");
  const [cancelPending, setCancelPending] = useState(false);
  const pollBusy = useRef(false);
  const release = typeof checkState === "object" ? checkState : null;
  const busy = checkState === "checking" || job?.state === "running" || installState === "busy";
  const activeJobId = job?.jobId;
  const activeJobState = job?.state;
  const activeJobVersion = job?.version;

  useEffect(() => onBusyChange?.(Boolean(busy)), [busy, onBusyChange]);

  useEffect(() => {
    if (!activeJobId || activeJobState !== "running" || !activeJobVersion) return;
    let active = true;
    const poll = async () => {
      if (pollBusy.current) return;
      pollBusy.current = true;
      try {
        const response: unknown = await commands.updates.downloadStatus({ jobId: activeJobId });
        if (!active) return;
        if (!validStatus(response, activeJobId, activeJobVersion)) {
          return;
        } else {
          setJob({
            jobId: response.jobId,
            version: response.version,
            state: response.state,
            downloadedBytes: response.downloadedBytes,
            totalBytes: response.totalBytes,
          });
          if (response.state !== "running") setCancelPending(false);
        }
      } catch {
        // Keep the dialog busy while the local worker's terminal state is unknown.
      } finally {
        pollBusy.current = false;
      }
    };
    void poll();
    const timer = window.setInterval(() => void poll(), 250);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [activeJobId, activeJobState, activeJobVersion]);

  const progress = useMemo(() => {
    if (!job || job.totalBytes <= 0) return 0;
    return Math.min(100, Math.round((job.downloadedBytes / job.totalBytes) * 100));
  }, [job]);

  const checkUpdates = async () => {
    if (busy) return;
    setCheckState("checking");
    setJob(null);
    setInstallState("idle");
    setCancelPending(false);
    try {
      const response: unknown = await commands.updates.check({});
      if (!validCheck(response)) {
        setCheckState("unavailable");
      } else if (response.state === "available") {
        setCheckState(response);
      } else {
        setCheckState(response.state);
      }
    } catch {
      setCheckState("unavailable");
    }
  };

  const start = async () => {
    if (!release || !release.installSupported || busy) return;
    try {
      const response: unknown = await commands.updates.startDownload({ version: release.version });
      if (!validStart(response, release.version)) throw new Error("Invalid update start response");
      setJob({
        jobId: response.jobId,
        version: response.version,
        state: "running",
        downloadedBytes: 0,
        totalBytes: 0,
      });
      setCancelPending(false);
    } catch {
      setJob({ jobId: "", version: release.version, state: "failed", downloadedBytes: 0, totalBytes: 0 });
    }
  };

  const cancel = async () => {
    if (!job || job.state !== "running" || cancelPending) return;
    setCancelPending(true);
    try {
      const response: unknown = await commands.updates.cancelDownload({ jobId: job.jobId });
      if (!validCancel(response, job.jobId) || !response.cancelRequested) throw new Error("Invalid cancellation");
    } catch {
      setCancelPending(false);
    }
  };

  const installUpdate = async () => {
    if (!job || job.state !== "completed" || installState === "busy") return;
    setInstallState("busy");
    try {
      const response: unknown = await commands.updates.install({ jobId: job.jobId });
      setInstallState(validInstall(response, job.version) ? "started" : "error");
    } catch {
      setInstallState("error");
    }
  };

  const skipUpdate = async () => {
    if (!release || release.mandatory || busy) return;
    try {
      const response: unknown = await commands.updates.skip({ version: release.version });
      setCheckState(validSkip(response, release.version) ? "skipped" : "unavailable");
    } catch {
      setCheckState("unavailable");
    }
  };

  return (
    <section aria-labelledby="update-settings-title">
      <div className="flex items-start gap-3">
        <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
          <PackageCheck aria-hidden="true" size={19} />
        </div>
        <div>
          <h3 id="update-settings-title" className="font-semibold">{copy.title}</h3>
          <p className="mt-1 text-sm leading-5 text-[var(--text-muted)]">{copy.description}</p>
        </div>
      </div>

      <div className="mt-4 rounded-xl border border-[var(--border-subtle)] p-4" aria-live="polite">
        {checkState === "idle" ? (
          <button className="secondary-button" type="button" onClick={() => void checkUpdates()}>
            <RefreshCw aria-hidden="true" size={16} />
            {copy.check}
          </button>
        ) : null}
        {checkState === "checking" ? (
          <p className="flex items-center gap-2 text-sm" role="status">
            <RefreshCw aria-hidden="true" className="animate-spin" size={16} />{copy.checking}
          </p>
        ) : null}
        {checkState === "current" ? (
          <div>
            <p className="notice-success">{copy.current}</p>
            <button className="secondary-button mt-3" type="button" onClick={() => void checkUpdates()}>{copy.check}</button>
          </div>
        ) : null}
        {checkState === "unavailable" ? (
          <div role="alert">
            <p className="notice-error">{copy.unavailable}</p>
            <button className="secondary-button mt-3" type="button" onClick={() => void checkUpdates()}>{copy.check}</button>
          </div>
        ) : null}
        {checkState === "skipped" ? <p className="text-sm text-[var(--text-muted)]">{copy.skipped}</p> : null}
        {release ? (
          <div className="space-y-4">
            <div className="flex items-start gap-3">
              <ShieldCheck aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--success)]" size={19} />
              <div>
                <h4 className="font-semibold">{interpolate(copy.available, { version: release.version })}</h4>
                <p className="mt-1 text-sm text-[var(--text-muted)]">{copy.verified}</p>
              </div>
            </div>
            {release.mandatory ? <p className="notice-warning">{copy.priority}</p> : null}
            {release.notes.length > 0 ? (
              <div>
                <h5 className="text-sm font-semibold">{copy.notes}</h5>
                <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-[var(--text-secondary)]">
                  {release.notes.map((note) => <li key={note}>{note}</li>)}
                </ul>
              </div>
            ) : null}
            {!release.installSupported ? <p className="text-sm text-[var(--text-muted)]">{copy.unsupported}</p> : null}
            {release.installSupported && !job ? (
              <div className="flex flex-wrap gap-2">
                <button className="primary-button" type="button" onClick={() => void start()}>
                  <Download aria-hidden="true" size={16} />{copy.download}
                </button>
                {!release.mandatory ? (
                  <button className="secondary-button" type="button" onClick={() => void skipUpdate()}>{copy.skip}</button>
                ) : null}
              </div>
            ) : null}
            {job?.state === "running" ? (
              <div>
                <div className="flex items-center justify-between gap-3 text-sm">
                  <span>{copy.downloading}</span><span className="font-semibold tabular-nums">{progress}%</span>
                </div>
                <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--surface-muted)]">
                  <div className="h-full bg-[var(--accent)] transition-[width]" style={{ width: `${progress}%` }} />
                </div>
                <button
                  className="secondary-button mt-3"
                  type="button"
                  disabled={cancelPending}
                  onClick={() => void cancel()}
                >
                  <XCircle aria-hidden="true" size={16} />{copy.cancel}
                </button>
              </div>
            ) : null}
            {job?.state === "cancelled" ? <p className="text-sm text-[var(--text-muted)]">{copy.cancelled}</p> : null}
            {job?.state === "failed" ? <p className="notice-error" role="alert">{copy.downloadError}</p> : null}
            {job?.state === "completed" && installState === "idle" ? (
              <button className="primary-button" type="button" onClick={() => void installUpdate()}>{copy.install}</button>
            ) : null}
            {installState === "busy" ? <p role="status">{copy.installing}</p> : null}
            {installState === "started" ? <p className="notice-success">{copy.installerStarted}</p> : null}
            {installState === "error" ? <p className="notice-error" role="alert">{copy.installError}</p> : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}
