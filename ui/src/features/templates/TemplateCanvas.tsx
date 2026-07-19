import { useRef } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";
import type { TemplateElementItem, TemplateSummary } from "../../generated/types";
import { moveGeometry, resizeGeometry } from "./templateGeometry";
import type { TemplateDesignerCopy } from "./templateDesignerI18n";

export function TemplateCanvas({
  template,
  selectedElementId,
  widthMm,
  heightMm,
  snap,
  disabled,
  copy,
  locale,
  onElement,
  onChange,
}: {
  template: TemplateSummary;
  selectedElementId: string;
  widthMm: number;
  heightMm: number;
  snap: boolean;
  disabled: boolean;
  copy: TemplateDesignerCopy["canvas"];
  locale: string;
  onElement: (id: string) => void;
  onChange: (id: string, geometry: Partial<TemplateElementItem>) => void;
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[#e8ebe7] shadow-[var(--shadow-panel)]">
      <div className="flex items-center justify-between gap-3 border-b border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{template.name}</p>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">{copy.hint}</p>
        </div>
        <span className="shrink-0 rounded-lg bg-[var(--surface-muted)] px-2.5 py-1.5 text-xs font-semibold">
          {formatNumber(widthMm, locale)} × {formatNumber(heightMm, locale)} {copy.unit}
        </span>
      </div>
      <div className="grid min-h-[27rem] place-items-center overflow-auto p-5 sm:p-8">
        <div
          className="template-canvas relative w-full max-w-[42rem] overflow-hidden border border-slate-300 bg-white shadow-[0_18px_45px_rgb(20_35_29_/_0.15)]"
          style={{ aspectRatio: `${widthMm} / ${heightMm}` }}
          aria-label={copy.previewAria.replace("{name}", template.name)}
        >
          {template.elements.filter((element) => element.visible).map((element) => (
            <CanvasElement
              element={element}
              widthMm={widthMm}
              heightMm={heightMm}
              selected={element.id === selectedElementId}
              snap={snap}
              disabled={disabled}
              copy={copy}
              onSelect={() => onElement(element.id)}
              onChange={(geometry) => onChange(element.id, geometry)}
              key={element.id}
            />
          ))}
        </div>
      </div>
    </section>
  );
}

function CanvasElement({
  element,
  widthMm,
  heightMm,
  selected,
  snap,
  disabled,
  copy,
  onSelect,
  onChange,
}: {
  element: TemplateElementItem;
  widthMm: number;
  heightMm: number;
  selected: boolean;
  snap: boolean;
  disabled: boolean;
  copy: TemplateDesignerCopy["canvas"];
  onSelect: () => void;
  onChange: (geometry: Partial<TemplateElementItem>) => void;
}) {
  const node = useRef<HTMLDivElement>(null);
  const interaction = useRef<{
    kind: "move" | "resize";
    clientX: number;
    clientY: number;
    widthPx: number;
    heightPx: number;
    element: TemplateElementItem;
  } | null>(null);
  const style = {
    left: `${(element.xMm / widthMm) * 100}%`,
    top: `${(element.yMm / heightMm) * 100}%`,
    width: `${(element.widthMm / widthMm) * 100}%`,
    height: `${(element.heightMm / heightMm) * 100}%`,
    zIndex: element.zIndex,
  };

  const begin = (event: ReactPointerEvent, kind: "move" | "resize") => {
    if (disabled || event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    onSelect();
    const canvas = node.current?.parentElement?.getBoundingClientRect();
    if (!canvas || canvas.width <= 0 || canvas.height <= 0) return;
    node.current?.setPointerCapture(event.pointerId);
    interaction.current = {
      kind,
      clientX: event.clientX,
      clientY: event.clientY,
      widthPx: canvas.width,
      heightPx: canvas.height,
      element,
    };
  };

  const move = (event: ReactPointerEvent) => {
    const start = interaction.current;
    if (!start) return;
    const deltaX = ((event.clientX - start.clientX) / start.widthPx) * widthMm;
    const deltaY = ((event.clientY - start.clientY) / start.heightPx) * heightMm;
    onChange(start.kind === "move"
      ? moveGeometry(start.element, deltaX, deltaY, widthMm, heightMm, snap)
      : resizeGeometry(start.element, deltaX, deltaY, widthMm, heightMm, snap));
  };

  const finish = (event: ReactPointerEvent) => {
    if (!interaction.current) return;
    interaction.current = null;
    if (node.current?.hasPointerCapture(event.pointerId)) node.current.releasePointerCapture(event.pointerId);
  };

  return (
    <div
      ref={node}
      className={`absolute min-h-px min-w-px touch-none overflow-hidden border text-slate-950 transition ${selected ? "cursor-move border-emerald-500 ring-2 ring-emerald-400/45" : "cursor-pointer border-transparent hover:border-emerald-400/60"}`}
      style={style}
      role="button"
      tabIndex={0}
      aria-label={copy.selectElement.replace("{name}", element.label)}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") onSelect();
      }}
      onPointerDown={(event) => begin(event, "move")}
      onPointerMove={move}
      onPointerUp={finish}
      onPointerCancel={finish}
    >
      <ElementPreview element={element} copy={copy} />
      {selected && !disabled && (
        <span
          className="absolute right-0 bottom-0 size-3 cursor-nwse-resize border border-white bg-emerald-500 shadow"
          aria-hidden="true"
          onPointerDown={(event) => begin(event, "resize")}
        />
      )}
    </div>
  );
}

function ElementPreview({ element, copy }: { element: TemplateElementItem; copy: TemplateDesignerCopy["canvas"] }) {
  if (element.type === "barcode_code128") {
    return <span className="flex h-full flex-col justify-end gap-[3%] p-[3%]"><span className="barcode-sample min-h-0 flex-1" />{element.humanReadable && <span className="truncate text-center text-[clamp(0.35rem,1vw,0.65rem)]">2039556250474</span>}</span>;
  }
  if (element.type === "kiz_datamatrix") return <span className="data-matrix-sample block size-full" />;
  if (element.type === "separator_line") return <span className="block h-1/2 border-b border-slate-950" />;
  const sampleText: Record<string, string> = {
    article: "WB-1048", barcode: "2039556250474", brand: "WCODE STUDIO", color: copy.sample.color,
    name: copy.sample.name, ru_size: "RU 46", size: "M", subject_name: copy.sample.subject,
  };
  const text = element.type === "static_text"
    ? `${element.prefix}${element.content}`
    : element.type === "sticker_tail"
      ? `${element.prefix}0474`
      : `${element.prefix}${sampleText[element.fieldKey] ?? element.label}`;
  return (
    <span
      className="flex size-full items-center overflow-hidden px-[2%] leading-tight"
      style={{
        fontSize: `clamp(0.35rem, ${(element.fontSizePt / 8) * 1.15}vw, 0.9rem)`,
        fontWeight: element.bold ? 700 : 500,
        justifyContent: element.align === "right" ? "flex-end" : element.align === "center" ? "center" : "flex-start",
        textAlign: element.align as "left" | "center" | "right",
      }}
    >
      {text || element.label}
    </span>
  );
}

function formatNumber(value: number, locale: string) {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
}
