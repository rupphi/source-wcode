import { RefreshCw } from "lucide-react";

export function CenteredState({
  kind,
  onRetry,
}: {
  kind: "loading" | "error";
  onRetry?: () => void;
}) {
  return (
    <main className="grid min-h-screen place-items-center bg-[var(--surface-canvas)] p-6">
      <section className="w-full max-w-md rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-8 text-center shadow-[var(--shadow-panel)]">
        {kind === "loading" ? (
          <>
            <RefreshCw className="mx-auto mb-5 animate-spin text-[var(--accent-strong)]" size={28} />
            <h1 className="text-lg font-semibold" role="status">
              Загружаем рабочее пространство
            </h1>
            <p className="mt-2 text-sm text-[var(--text-secondary)]">
              Проверяем локальную базу и список магазинов.
            </p>
          </>
        ) : (
          <div role="alert">
            <h1 className="text-lg font-semibold">Не удалось открыть рабочее пространство</h1>
            <p className="mt-2 text-sm text-[var(--text-secondary)]">
              Данные не изменены. Проверьте подключение и повторите.
            </p>
            <button
              className="mt-5 rounded-xl bg-[var(--accent-strong)] px-4 py-2.5 text-sm font-semibold text-white"
              type="button"
              onClick={onRetry}
            >
              Повторить
            </button>
          </div>
        )}
      </section>
    </main>
  );
}
