import { RefreshCw } from "lucide-react";
import type { AppCopy } from "../i18n";

export function CenteredState({
  kind,
  onRetry,
  copy,
}: {
  kind: "loading" | "error";
  onRetry?: () => void;
  copy: AppCopy["center"] & AppCopy["common"];
}) {
  return (
    <main className="grid min-h-screen place-items-center bg-[var(--surface-canvas)] p-6">
      <section className="w-full max-w-md rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-8 text-center shadow-[var(--shadow-panel)]">
        {kind === "loading" ? (
          <>
            <RefreshCw className="mx-auto mb-5 animate-spin text-[var(--accent-strong)]" size={28} />
            <h1 className="text-lg font-semibold" role="status">
              {copy.loadingTitle}
            </h1>
            <p className="mt-2 text-sm text-[var(--text-secondary)]">
              {copy.loadingDescription}
            </p>
          </>
        ) : (
          <div role="alert">
            <h1 className="text-lg font-semibold">{copy.errorTitle}</h1>
            <p className="mt-2 text-sm text-[var(--text-secondary)]">
              {copy.errorDescription}
            </p>
            <button
              className="mt-5 rounded-xl bg-[var(--button-primary)] px-4 py-2.5 text-sm font-semibold text-white"
              type="button"
              onClick={onRetry}
            >
              {copy.retry}
            </button>
          </div>
        )}
      </section>
    </main>
  );
}
