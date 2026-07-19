import { act, renderHook, waitFor } from "@testing-library/react";
import { StrictMode, type PropsWithChildren } from "react";
import { describe, expect, it, vi } from "vitest";
import { useBoundedInfinitePages } from "./useBoundedInfinitePages";

type Item = { id: number; label: string };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}

describe("useBoundedInfinitePages", () => {
  it("coalesces the initial read under React StrictMode", async () => {
    const loadPage = vi.fn().mockResolvedValue({ items: [], hasMore: false });
    const wrapper = ({ children }: PropsWithChildren) => <StrictMode>{children}</StrictMode>;
    const { result } = renderHook(() => useBoundedInfinitePages<Item>({
      resetKey: "shop-7",
      loadPage,
      getId: (item) => item.id,
    }), { wrapper });

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(loadPage).toHaveBeenCalledTimes(1);
  });

  it("loads one next page at a time and deduplicates stable item IDs", async () => {
    const secondPage = deferred<{ items: Item[]; hasMore: boolean }>();
    const loadPage = vi.fn(async (page: number) => page === 1
      ? { items: [{ id: 1, label: "One" }, { id: 2, label: "Two" }], hasMore: true }
      : secondPage.promise);
    const { result } = renderHook(() => useBoundedInfinitePages({
      resetKey: "shop-7:all",
      loadPage,
      getId: (item: Item) => item.id,
    }));

    await waitFor(() => expect(result.current.status).toBe("ready"));
    act(() => {
      result.current.loadMore();
      result.current.loadMore();
    });
    expect(loadPage).toHaveBeenCalledTimes(2);
    expect(loadPage).toHaveBeenLastCalledWith(2);
    expect(result.current.status).toBe("loadingMore");

    secondPage.resolve({
      items: [{ id: 2, label: "Updated duplicate" }, { id: 3, label: "Three" }],
      hasMore: false,
    });
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.items.map((item) => item.id)).toEqual([1, 2, 3]);
    expect(result.current.addedCount).toBe(1);
    expect(result.current.hasMore).toBe(false);
  });

  it("ignores a stale response after the list key changes", async () => {
    const oldPage = deferred<{ items: Item[]; hasMore: boolean }>();
    const newPage = deferred<{ items: Item[]; hasMore: boolean }>();
    const oldLoader = vi.fn(() => oldPage.promise);
    const newLoader = vi.fn(() => newPage.promise);
    const { result, rerender } = renderHook(
      ({ resetKey, loadPage }) => useBoundedInfinitePages({
        resetKey,
        loadPage,
        getId: (item: Item) => item.id,
      }),
      { initialProps: { resetKey: "shop-7", loadPage: oldLoader } },
    );

    await waitFor(() => expect(oldLoader).toHaveBeenCalledWith(1));
    rerender({ resetKey: "shop-8", loadPage: newLoader });
    await waitFor(() => expect(newLoader).toHaveBeenCalledWith(1));
    oldPage.resolve({ items: [{ id: 7, label: "Old shop" }], hasMore: false });
    await act(async () => { await oldPage.promise; });
    expect(result.current.items).toEqual([]);
    expect(result.current.status).toBe("loading");

    newPage.resolve({ items: [{ id: 8, label: "New shop" }], hasMore: false });
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.items).toEqual([{ id: 8, label: "New shop" }]);
  });

  it("keeps loaded items when a later page fails and retries that page", async () => {
    const loadPage = vi.fn()
      .mockResolvedValueOnce({ items: [{ id: 1, label: "One" }], hasMore: true })
      .mockRejectedValueOnce(new Error("page failed"))
      .mockResolvedValueOnce({ items: [{ id: 2, label: "Two" }], hasMore: false });
    const { result } = renderHook(() => useBoundedInfinitePages({
      resetKey: "shop-7",
      loadPage,
      getId: (item: Item) => item.id,
    }));

    await waitFor(() => expect(result.current.status).toBe("ready"));
    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.status).toBe("loadMoreError"));
    expect(result.current.items).toEqual([{ id: 1, label: "One" }]);

    act(() => result.current.retry());
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(loadPage).toHaveBeenLastCalledWith(2);
    expect(result.current.items.map((item) => item.id)).toEqual([1, 2]);
  });
});
