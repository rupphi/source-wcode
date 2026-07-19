import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { InfiniteLoadTrigger } from "./InfiniteLoadTrigger";

const copy = {
  loading: "Loading more items",
  loadMore: "Load more",
  loadError: "Could not load more items",
  retry: "Try again",
  end: "All items loaded",
};

describe("InfiniteLoadTrigger", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("keeps a keyboard fallback when IntersectionObserver is unavailable", async () => {
    vi.stubGlobal("IntersectionObserver", undefined);
    const onLoadMore = vi.fn();
    const user = userEvent.setup();
    render(
      <InfiniteLoadTrigger
        status="ready"
        hasMore
        copy={copy}
        onLoadMore={onLoadMore}
        onRetry={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(onLoadMore).toHaveBeenCalledOnce();
  });

  it("loads once when an observed sentinel repeats before loading state renders", () => {
    let intersect: IntersectionObserverCallback | undefined;
    class Observer {
      constructor(callback: IntersectionObserverCallback) { intersect = callback; }
      observe() {}
      disconnect() {}
      unobserve() {}
      takeRecords() { return []; }
      readonly root = null;
      readonly rootMargin = "240px";
      readonly thresholds = [0];
    }
    vi.stubGlobal("IntersectionObserver", Observer);
    const onLoadMore = vi.fn();
    render(
      <InfiniteLoadTrigger
        status="ready"
        hasMore
        copy={copy}
        onLoadMore={onLoadMore}
        onRetry={vi.fn()}
      />,
    );

    const callback = intersect;
    if (callback === undefined) throw new Error("Observer was not created");
    const entry = { isIntersecting: true } as IntersectionObserverEntry;
    act(() => {
      callback([entry], {} as IntersectionObserver);
      callback([entry], {} as IntersectionObserver);
    });
    expect(onLoadMore).toHaveBeenCalledOnce();
  });

  it("announces appended items and exposes retry and end states", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = render(
      <InfiniteLoadTrigger
        status="loadMoreError"
        hasMore
        copy={copy}
        announcement="3 more items loaded"
        onLoadMore={vi.fn()}
        onRetry={onRetry}
      />,
    );

    expect(screen.getByText("3 more items loaded")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("alert")).toHaveTextContent("Could not load more items");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledOnce();

    rerender(
      <InfiniteLoadTrigger
        status="ready"
        hasMore={false}
        copy={copy}
        onLoadMore={vi.fn()}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("All items loaded");
  });
});
