import type { TemplateElementItem } from "../../generated/types";

type Geometry = Pick<TemplateElementItem, "xMm" | "yMm" | "widthMm" | "heightMm">;

export function moveGeometry(
  element: Geometry,
  deltaX: number,
  deltaY: number,
  pageWidth: number,
  pageHeight: number,
  snap: boolean,
): Geometry {
  return {
    ...element,
    xMm: normalize(clamp(element.xMm + deltaX, 0, pageWidth - element.widthMm), snap),
    yMm: normalize(clamp(element.yMm + deltaY, 0, pageHeight - element.heightMm), snap),
  };
}

export function resizeGeometry(
  element: Geometry,
  deltaX: number,
  deltaY: number,
  pageWidth: number,
  pageHeight: number,
  snap: boolean,
): Geometry {
  const minimum = snap ? 1 : 0.1;
  return {
    ...element,
    widthMm: normalize(clamp(element.widthMm + deltaX, minimum, pageWidth - element.xMm), snap),
    heightMm: normalize(clamp(element.heightMm + deltaY, minimum, pageHeight - element.yMm), snap),
  };
}

export function clampMetric(value: number, minimum: number, maximum: number) {
  return roundHundredth(clamp(value, minimum, Math.max(minimum, maximum)));
}

function normalize(value: number, snap: boolean) {
  return snap ? Math.round(value) : roundHundredth(value);
}

function roundHundredth(value: number) {
  return Math.round(value * 100) / 100;
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.max(minimum, Math.min(maximum, value));
}
