import { describe, expect, it } from "vitest";
import { moveGeometry, resizeGeometry } from "./templateGeometry";

const element = { xMm: 2.4, yMm: 3.6, widthMm: 18, heightMm: 10 };

describe("template geometry", () => {
  it("snaps drag movement to one millimeter and keeps the element on the page", () => {
    expect(moveGeometry(element, 1.2, -10, 58, 40, true)).toEqual({
      ...element,
      xMm: 4,
      yMm: 0,
    });
    expect(moveGeometry(element, 100, 100, 58, 40, true)).toEqual({
      ...element,
      xMm: 40,
      yMm: 30,
    });
  });

  it("resizes with hundredth precision when snap is disabled and clamps to the page", () => {
    expect(resizeGeometry(element, 1.234, 100, 58, 40, false)).toEqual({
      ...element,
      widthMm: 19.23,
      heightMm: 36.4,
    });
    expect(resizeGeometry(element, -100, -100, 58, 40, false)).toEqual({
      ...element,
      widthMm: 0.1,
      heightMm: 0.1,
    });
    expect(resizeGeometry(element, -100, -100, 58, 40, true)).toEqual({
      ...element,
      widthMm: 1,
      heightMm: 1,
    });
  });
});
