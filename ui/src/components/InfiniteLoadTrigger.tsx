import { RefreshCw } from "lucide-react";
import { useEffect, useRef } from "react";
import type { InfinitePagesStatus } from "./useBoundedInfinitePages";

type TriggerCopy = {
  loading: string;
  loadMore: string;
  loadError: string;
  retry: string;
  end: string;
};

export function InfiniteLoadTrigger({
  status,
  hasMore,
  copy,
  announcement = "",
  onLoadMore,
  onRetry,
}: {
  status: InfinitePagesStatus;
  hasMore: boolean;
  copy: TriggerCopy;
  announcement?: string;
  onLoadMore: () => void;
  onRetry: () => void;
}) {
  const sentinel = useRef<HTMLSpanElement>(null);
  const requested = useRef(false);

  const requestMore = () => {
    if (requested.current || status !== "ready" || !hasMore) return;
    requested.current = true;
    onLoadMore();
  };

  useEffect(() => {
    if (status === "ready") requested.current = false;
  }, [status]);

  useEffect(() => {
    const target = sentinel.current;
    if (status !== "ready" || !hasMore || target === null
      || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) requestMore();
    }, { rootMargin: "240px 0px" });
    observer.observe(target);
    return () => observer.disconnect();
  });

  return (
    <div className="grid justify-items-center gap-2 py-3">
      <span className="sr-only" aria-atomic="true" aria-live="polite">{announcement}</span>
      {status === "loading" ? (
        <p className="inline-flex items-center gap-2 text-xs text-[var(--text-muted)]" role="status">
          <RefreshCw aria-hidden="true" className="animate-spin" size={15} />
          {copy.loading}
        </p>
      ) : status === "error" || status === "loadMoreError" ? (
        <div className="notice-error" role="alert">
          <span>{copy.loadError}</span>
          <button type="button" onClick={onRetry}>{copy.retry}</button>
        </div>
      ) : !hasMore ? (
        <p className="text-xs text-[var(--text-muted)]" role="status">{copy.end}</p>
      ) : (
        <>
          <span ref={sentinel} className="h-px w-full" aria-hidden="true" />
          <button
            className="secondary-button"
            type="button"
            disabled={status === "loadingMore"}
            onClick={requestMore}
          >
            {status === "loadingMore" ? <RefreshCw aria-hidden="true" className="animate-spin" size={14} /> : null}
            {status === "loadingMore" ? copy.loading : copy.loadMore}
          </button>
        </>
      )}
    </div>
  );
}
