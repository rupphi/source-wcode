import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Toast } from "./Toast";

describe("Toast", () => {
  afterEach(() => vi.useRealTimers());

  it("announces success, can be dismissed, and closes automatically", () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    const { rerender } = render(<Toast message="Saved" closeLabel="Dismiss" onDismiss={onDismiss} duration={4_000} />);

    expect(screen.getByRole("status")).toHaveTextContent("Saved");
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(onDismiss).toHaveBeenCalledOnce();

    onDismiss.mockClear();
    rerender(<Toast message="Saved again" closeLabel="Dismiss" onDismiss={onDismiss} duration={4_000} />);
    act(() => vi.advanceTimersByTime(4_000));
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});
