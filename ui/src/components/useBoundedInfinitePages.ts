import { useCallback, useEffect, useRef, useState } from "react";

export type InfinitePage<T, Summary = never> = {
  items: readonly T[];
  hasMore: boolean;
  summary?: Summary;
};

export type InfinitePagesStatus = "loading" | "ready" | "loadingMore" | "error" | "loadMoreError";

type InfinitePagesState<T, Summary> = {
  resetKey: string | number;
  items: readonly T[];
  page: number;
  hasMore: boolean;
  addedCount: number;
  summary: Summary | undefined;
  status: InfinitePagesStatus;
};

/**
 * Accumulates already-validated, bounded read pages without changing their backend contract.
 * resetKey must encode every shop/query/filter value that changes the list, while getId must stay
 * stable for that key. Mutations must never be passed as loadPage.
 */
export function useBoundedInfinitePages<T, Summary = never>({
  resetKey,
  loadPage,
  getId,
}: {
  resetKey: string | number;
  loadPage: (page: number) => Promise<InfinitePage<T, Summary>>;
  getId: (item: T) => string | number;
}) {
  const [state, setState] = useState<InfinitePagesState<T, Summary>>(
    () => emptyState(resetKey),
  );
  const generation = useRef(0);
  const inFlight = useRef<{ generation: number; page: number } | null>(null);
  const loadPageRef = useRef(loadPage);
  const getIdRef = useRef(getId);

  useEffect(() => {
    loadPageRef.current = loadPage;
    getIdRef.current = getId;
  }, [getId, loadPage]);

  const startPage = useCallback((page: number, currentGeneration: number, initial: boolean) => {
    const pending = inFlight.current;
    if (pending?.generation === currentGeneration && pending.page === page) return;
    inFlight.current = { generation: currentGeneration, page };
    setState((current) => ({
      ...current,
      addedCount: 0,
      status: initial ? "loading" : "loadingMore",
    }));

    void loadPageRef.current(page).then(
      (response) => {
        if (generation.current !== currentGeneration) return;
        if (inFlight.current?.generation === currentGeneration && inFlight.current.page === page) {
          inFlight.current = null;
        }
        setState((current) => {
          const base = initial ? [] : current.items;
          const next = appendUnique(base, response.items, getIdRef.current);
          return {
            resetKey: current.resetKey,
            items: next,
            page,
            hasMore: response.hasMore,
            addedCount: next.length - base.length,
            summary: response.summary ?? current.summary,
            status: "ready",
          };
        });
      },
      () => {
        if (generation.current !== currentGeneration) return;
        if (inFlight.current?.generation === currentGeneration && inFlight.current.page === page) {
          inFlight.current = null;
        }
        setState((current) => ({
          ...current,
          addedCount: 0,
          status: initial ? "error" : "loadMoreError",
        }));
      },
    );
  }, []);

  useEffect(() => {
    const currentGeneration = ++generation.current;
    inFlight.current = null;
    queueMicrotask(() => {
      if (generation.current !== currentGeneration) return;
      setState(emptyState(resetKey));
      startPage(1, currentGeneration, true);
    });
    return () => {
      if (generation.current === currentGeneration) generation.current += 1;
      if (inFlight.current?.generation === currentGeneration) inFlight.current = null;
    };
  }, [resetKey, startPage]);

  const visibleState = Object.is(state.resetKey, resetKey) ? state : emptyState<T, Summary>(resetKey);

  const loadMore = useCallback(() => {
    if (visibleState.status !== "ready" || !visibleState.hasMore) return;
    startPage(visibleState.page + 1, generation.current, false);
  }, [startPage, visibleState.hasMore, visibleState.page, visibleState.status]);

  const retry = useCallback(() => {
    if (visibleState.status === "error") {
      startPage(1, generation.current, true);
    } else if (visibleState.status === "loadMoreError") {
      startPage(visibleState.page + 1, generation.current, false);
    }
  }, [startPage, visibleState.page, visibleState.status]);

  return { ...visibleState, loadMore, retry };
}

function emptyState<T, Summary>(resetKey: string | number): InfinitePagesState<T, Summary> {
  return {
    resetKey,
    items: [],
    page: 0,
    hasMore: false,
    addedCount: 0,
    summary: undefined,
    status: "loading",
  };
}

function appendUnique<T>(existing: readonly T[], incoming: readonly T[], getId: (item: T) => string | number) {
  const seen = new Set(existing.map(getId));
  const items = [...existing];
  for (const item of incoming) {
    const id = getId(item);
    if (seen.has(id)) continue;
    seen.add(id);
    items.push(item);
  }
  return items;
}
