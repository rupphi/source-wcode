import { useCallback, useEffect, useRef, useState } from "react";

export type InfinitePage<T> = {
  items: readonly T[];
  hasMore: boolean;
};

export type InfinitePagesStatus = "loading" | "ready" | "loadingMore" | "error" | "loadMoreError";

type InfinitePagesState<T> = {
  items: readonly T[];
  page: number;
  hasMore: boolean;
  addedCount: number;
  status: InfinitePagesStatus;
};

/**
 * Accumulates already-validated, bounded read pages without changing their backend contract.
 * resetKey must encode every shop/query/filter value that changes the list, while getId must stay
 * stable for that key. Mutations must never be passed as loadPage.
 */
export function useBoundedInfinitePages<T>({
  resetKey,
  loadPage,
  getId,
}: {
  resetKey: string | number;
  loadPage: (page: number) => Promise<InfinitePage<T>>;
  getId: (item: T) => string | number;
}) {
  const [state, setState] = useState<InfinitePagesState<T>>({
    items: [],
    page: 0,
    hasMore: false,
    addedCount: 0,
    status: "loading",
  });
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
            items: next,
            page,
            hasMore: response.hasMore,
            addedCount: next.length - base.length,
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
      setState({ items: [], page: 0, hasMore: false, addedCount: 0, status: "loading" });
      startPage(1, currentGeneration, true);
    });
    return () => {
      if (generation.current === currentGeneration) generation.current += 1;
      if (inFlight.current?.generation === currentGeneration) inFlight.current = null;
    };
  }, [resetKey, startPage]);

  const loadMore = useCallback(() => {
    if (state.status !== "ready" || !state.hasMore) return;
    startPage(state.page + 1, generation.current, false);
  }, [startPage, state.hasMore, state.page, state.status]);

  const retry = useCallback(() => {
    if (state.status === "error") {
      startPage(1, generation.current, true);
    } else if (state.status === "loadMoreError") {
      startPage(state.page + 1, generation.current, false);
    }
  }, [startPage, state.page, state.status]);

  return { ...state, loadMore, retry };
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
