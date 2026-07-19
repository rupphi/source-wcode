import { CheckCircle2, X } from "lucide-react";
import { useEffect } from "react";

export function Toast({ message, closeLabel, onDismiss, duration = 5_000 }: { message: string; closeLabel: string; onDismiss: () => void; duration?: number }) {
  useEffect(() => {
    const timer = window.setTimeout(onDismiss, duration);
    return () => window.clearTimeout(timer);
  }, [duration, message, onDismiss]);

  return (
    <div className="fixed right-3 bottom-3 z-[70] flex w-[min(24rem,calc(100vw-1.5rem))] items-center gap-3 rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2.5 text-sm font-medium text-emerald-950 shadow-[var(--shadow-popover)]" role="status" aria-live="polite">
      <CheckCircle2 aria-hidden="true" className="shrink-0 text-emerald-700" size={18} />
      <span className="min-w-0 flex-1">{message}</span>
      <button className="icon-button shrink-0 border-0 bg-transparent text-emerald-800 hover:bg-emerald-100" type="button" aria-label={closeLabel} title={closeLabel} onClick={onDismiss}>
        <X aria-hidden="true" size={16} />
      </button>
    </div>
  );
}
