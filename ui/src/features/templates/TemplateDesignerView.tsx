import {
  AlertCircle,
  Barcode,
  Box,
  Check,
  Eye,
  EyeOff,
  Grid2X2,
  Layers3,
  MousePointer2,
  RotateCcw,
  Type,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { commands } from "../../generated/commands";
import type {
  TemplateDesignerResponse,
  TemplateElementItem,
  TemplateSummary,
} from "../../generated/types";

type DesignerMode = "fbs" | "fbo";
type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: TemplateDesignerResponse };

const sampleText: Record<string, string> = {
  article: "WB-1048",
  brand: "WCODE STUDIO",
  color: "Графит",
  name: "Базовая футболка",
  nm_id: "10492876",
  ru_size: "RU 46",
  size: "M",
  subject_name: "Футболки",
};

export function TemplateDesignerView() {
  const [mode, setMode] = useState<DesignerMode>("fbs");
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [selectedTemplateId, setSelectedTemplateId] = useState("");
  const [selectedElementId, setSelectedElementId] = useState("");

  useEffect(() => {
    let active = true;
    void commands.templates.loadDesigner({ mode }).then(
      (data) => {
        if (!active) return;
        const initialTemplate = data.templates.find((template) => template.defaultTemplate)
          ?? data.templates[0];
        setState({ status: "ready", data });
        setSelectedTemplateId(initialTemplate?.id ?? "");
        setSelectedElementId(initialTemplate?.elements[0]?.id ?? "");
      },
      () => {
        if (active) setState({ status: "error" });
      },
    );
    return () => {
      active = false;
    };
  }, [mode, reloadKey]);

  const selectMode = (nextMode: DesignerMode) => {
    if (nextMode === mode) return;
    setState({ status: "loading" });
    setSelectedTemplateId("");
    setSelectedElementId("");
    setMode(nextMode);
  };

  if (state.status === "loading") return <DesignerLoading mode={mode} onMode={selectMode} />;
  if (state.status === "error") {
    return (
      <div className="space-y-4">
        <ModeBar mode={mode} onMode={selectMode} />
        <section className="grid min-h-72 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
          <div>
            <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={28} />
            <h3 className="font-semibold text-red-950">Не удалось загрузить шаблоны</h3>
            <p className="mt-2 text-sm text-red-800">Локальная библиотека не изменена. Повторите запрос.</p>
            <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={() => {
              setState({ status: "loading" });
              setReloadKey((value) => value + 1);
            }}>
              Повторить
            </button>
          </div>
        </section>
      </div>
    );
  }

  const selectedTemplate = state.data.templates.find((template) => template.id === selectedTemplateId)
    ?? state.data.templates[0]
    ?? null;
  const selectedElement = selectedTemplate?.elements.find((element) => element.id === selectedElementId)
    ?? selectedTemplate?.elements[0]
    ?? null;

  const selectTemplate = (template: TemplateSummary) => {
    setSelectedTemplateId(template.id);
    setSelectedElementId(template.elements[0]?.id ?? "");
  };

  return (
    <div className="space-y-4">
      <ModeBar mode={mode} onMode={selectMode} />
      {selectedTemplate === null ? (
        <EmptyDesigner />
      ) : (
        <div className="grid items-start gap-4 xl:grid-cols-[14rem_minmax(25rem,1fr)_18rem]">
          <CatalogPanel
            templates={state.data.templates}
            selectedTemplateId={selectedTemplate.id}
            selectedElementId={selectedElement?.id ?? ""}
            onTemplate={selectTemplate}
            onElement={setSelectedElementId}
          />
          <CanvasPanel
            template={selectedTemplate}
            selectedElementId={selectedElement?.id ?? ""}
            widthMm={state.data.pageWidthMm}
            heightMm={state.data.pageHeightMm}
            onElement={setSelectedElementId}
          />
          <InspectorPanel element={selectedElement} />
        </div>
      )}
    </div>
  );
}

function ModeBar({ mode, onMode }: { mode: DesignerMode; onMode: (mode: DesignerMode) => void }) {
  return (
    <section className="flex flex-col justify-between gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)] sm:flex-row sm:items-center">
      <div className="inline-flex w-fit rounded-xl bg-[var(--surface-muted)] p-1" role="tablist" aria-label="Тип поставки шаблона">
        {(["fbs", "fbo"] as const).map((item) => (
          <button
            className={`min-w-20 rounded-lg px-4 py-2 text-sm font-semibold transition ${mode === item ? "bg-white text-[var(--text-primary)] shadow-[var(--shadow-control)]" : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]"}`}
            type="button"
            role="tab"
            aria-selected={mode === item}
            key={item}
            onClick={() => onMode(item)}
          >
            {item.toUpperCase()}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-2 px-2 text-xs text-[var(--text-secondary)]">
        <Check className="text-[var(--accent-strong)]" aria-hidden="true" size={16} />
        Типизированный каталог · хранится локально
      </div>
    </section>
  );
}

function CatalogPanel({
  templates,
  selectedTemplateId,
  selectedElementId,
  onTemplate,
  onElement,
}: {
  templates: TemplateSummary[];
  selectedTemplateId: string;
  selectedElementId: string;
  onTemplate: (template: TemplateSummary) => void;
  onElement: (id: string) => void;
}) {
  const selectedTemplate = templates.find((template) => template.id === selectedTemplateId) ?? templates[0];
  return (
    <aside className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="border-b border-[var(--border-subtle)] p-4">
        <div className="flex items-center gap-2">
          <Layers3 aria-hidden="true" size={17} />
          <h3 className="text-sm font-semibold">Шаблоны</h3>
        </div>
        <div className="mt-3 grid gap-2">
          {templates.map((template) => (
            <button
              className={`rounded-xl border px-3 py-2.5 text-left transition ${template.id === selectedTemplateId ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-[var(--border-subtle)] hover:border-[var(--border-strong)]"}`}
              type="button"
              key={template.id}
              onClick={() => onTemplate(template)}
              aria-label={`Шаблон ${template.name}`}
            >
              <span className="block truncate text-sm font-semibold">{template.name}</span>
              <span className="mt-1 flex items-center gap-2 text-[0.7rem] text-[var(--text-muted)]">
                {template.elements.length} элементов
                {template.defaultTemplate && <span className="rounded-full bg-white/75 px-2 py-0.5 text-[var(--accent-strong)]">По умолчанию</span>}
              </span>
            </button>
          ))}
        </div>
      </div>
      {selectedTemplate && (
        <div className="p-4">
          <p className="mb-2 text-[0.68rem] font-semibold tracking-[0.12em] text-[var(--text-muted)] uppercase">Слои</p>
          <div className="grid gap-1.5">
            {[...selectedTemplate.elements].sort((left, right) => right.zIndex - left.zIndex).map((element) => (
              <button
                className={`flex items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs transition ${element.id === selectedElementId ? "bg-[var(--surface-muted)] font-semibold text-[var(--text-primary)]" : "text-[var(--text-secondary)] hover:bg-[var(--surface-muted)]"}`}
                type="button"
                key={element.id}
                aria-label={`Выбрать слой ${element.label}`}
                onClick={() => onElement(element.id)}
              >
                <ElementIcon type={element.type} />
                <span className="min-w-0 flex-1 truncate">{element.label}</span>
                {element.visible ? <Eye aria-label="Виден" size={14} /> : <EyeOff aria-label="Скрыт" size={14} />}
              </button>
            ))}
          </div>
        </div>
      )}
    </aside>
  );
}

function CanvasPanel({
  template,
  selectedElementId,
  widthMm,
  heightMm,
  onElement,
}: {
  template: TemplateSummary;
  selectedElementId: string;
  widthMm: number;
  heightMm: number;
  onElement: (id: string) => void;
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[#e8ebe7] shadow-[var(--shadow-panel)]">
      <div className="flex items-center justify-between gap-3 border-b border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-4 py-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{template.name}</p>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">Масштаб по размеру рабочей области</p>
        </div>
        <span className="shrink-0 rounded-lg bg-[var(--surface-muted)] px-2.5 py-1.5 text-xs font-semibold">{formatNumber(widthMm)} × {formatNumber(heightMm)} мм</span>
      </div>
      <div className="grid min-h-[27rem] place-items-center overflow-auto p-5 sm:p-8">
        <div
          className="template-canvas relative w-full max-w-[42rem] overflow-hidden border border-slate-300 bg-white shadow-[0_18px_45px_rgb(20_35_29_/_0.15)]"
          style={{ aspectRatio: `${widthMm} / ${heightMm}` }}
          aria-label={`Предпросмотр шаблона ${template.name}`}
        >
          {template.elements.filter((element) => element.visible).map((element) => (
            <CanvasElement
              element={element}
              widthMm={widthMm}
              heightMm={heightMm}
              selected={element.id === selectedElementId}
              onSelect={() => onElement(element.id)}
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
  onSelect,
}: {
  element: TemplateElementItem;
  widthMm: number;
  heightMm: number;
  selected: boolean;
  onSelect: () => void;
}) {
  const style = useMemo(() => ({
    left: `${(element.xMm / widthMm) * 100}%`,
    top: `${(element.yMm / heightMm) * 100}%`,
    width: `${(element.widthMm / widthMm) * 100}%`,
    height: `${(element.heightMm / heightMm) * 100}%`,
    zIndex: element.zIndex,
  }), [element, widthMm, heightMm]);
  return (
    <button
      className={`absolute min-h-px min-w-px overflow-hidden border text-slate-950 transition ${selected ? "border-emerald-500 ring-2 ring-emerald-400/45" : "border-transparent hover:border-emerald-400/60"}`}
      style={style}
      type="button"
      aria-label={`Выбрать элемент ${element.label}`}
      onClick={onSelect}
    >
      <ElementPreview element={element} />
    </button>
  );
}

function ElementPreview({ element }: { element: TemplateElementItem }) {
  if (element.type === "barcode_code128") {
    return <span className="flex h-full flex-col justify-end gap-[3%] p-[3%]"><span className="barcode-sample min-h-0 flex-1" /><span className="truncate text-center text-[clamp(0.35rem,1vw,0.65rem)]">2039556250474</span></span>;
  }
  if (element.type === "kiz_datamatrix") {
    return <span className="data-matrix-sample block size-full" />;
  }
  if (element.type === "separator_line") {
    return <span className="block h-1/2 border-b border-slate-950" />;
  }
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

function InspectorPanel({ element }: { element: TemplateElementItem | null }) {
  return (
    <aside className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]">
      <div className="flex items-center gap-2 border-b border-[var(--border-subtle)] pb-3">
        <MousePointer2 aria-hidden="true" size={17} />
        <h3 className="text-sm font-semibold">Параметры элемента</h3>
      </div>
      {element === null ? (
        <p className="py-8 text-center text-xs leading-5 text-[var(--text-muted)]">Выберите слой на макете, чтобы увидеть его точные параметры.</p>
      ) : (
        <div className="mt-4 space-y-5">
          <div>
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{element.label}</p>
                <p className="mt-1 text-xs text-[var(--text-muted)]">{element.type.replaceAll("_", " ")}</p>
              </div>
              <span className={`rounded-full px-2 py-1 text-[0.65rem] font-semibold ${element.visible ? "bg-[var(--accent-soft)] text-[var(--accent-strong)]" : "bg-slate-100 text-slate-500"}`}>
                {element.visible ? "Виден" : "Скрыт"}
              </span>
            </div>
          </div>
          <fieldset>
            <legend className="mb-2 text-[0.68rem] font-semibold tracking-[0.1em] text-[var(--text-muted)] uppercase">Геометрия</legend>
            <div className="grid grid-cols-2 gap-2">
              <MetricField label="X, мм" value={element.xMm} />
              <MetricField label="Y, мм" value={element.yMm} />
              <MetricField label="Ширина, мм" value={element.widthMm} />
              <MetricField label="Высота, мм" value={element.heightMm} />
            </div>
          </fieldset>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <Property label="Шрифт" value={`${formatNumber(element.fontSizePt)} pt`} />
            <Property label="Слой" value={String(element.zIndex)} />
            <Property label="Начертание" value={element.bold ? "Жирное" : "Обычное"} />
            <Property label="Выравнивание" value={element.align} />
          </div>
          <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] px-3 py-2.5 text-xs leading-5 text-[var(--text-secondary)]">
            Координаты показаны в миллиметрах; размер шрифта — в типографских пунктах.
          </div>
        </div>
      )}
    </aside>
  );
}

function MetricField({ label, value }: { label: string; value: number }) {
  return (
    <label className="text-[0.68rem] font-medium text-[var(--text-secondary)]">
      {label}
      <input className="mt-1 w-full rounded-lg border border-[var(--border-subtle)] bg-[var(--surface-muted)] px-2.5 py-2 text-xs font-semibold text-[var(--text-primary)]" aria-label={label} type="number" value={roundMetric(value)} readOnly />
    </label>
  );
}

function Property({ label, value }: { label: string; value: string }) {
  return <div className="rounded-xl border border-[var(--border-subtle)] px-3 py-2.5"><p className="text-[0.65rem] text-[var(--text-muted)]">{label}</p><p className="mt-1 truncate font-semibold capitalize">{value}</p></div>;
}

function ElementIcon({ type }: { type: string }) {
  if (type === "barcode_code128") return <Barcode aria-hidden="true" size={14} />;
  if (type === "kiz_datamatrix") return <Grid2X2 aria-hidden="true" size={14} />;
  if (type === "text_field" || type === "static_text") return <Type aria-hidden="true" size={14} />;
  return <Box aria-hidden="true" size={14} />;
}

function DesignerLoading({ mode, onMode }: { mode: DesignerMode; onMode: (mode: DesignerMode) => void }) {
  return (
    <div className="space-y-4">
      <ModeBar mode={mode} onMode={onMode} />
      <section className="grid gap-4 xl:grid-cols-[14rem_minmax(25rem,1fr)_18rem]" aria-label="Загрузка шаблонов">
        {["h-72", "h-[34rem]", "h-80"].map((height, index) => <span className={`${height} animate-pulse rounded-2xl bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]`} key={index} />)}
      </section>
    </div>
  );
}

function EmptyDesigner() {
  return (
    <section className="grid min-h-72 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center">
      <div><RotateCcw className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={28} /><h3 className="font-semibold">Шаблонов пока нет</h3><p className="mt-2 text-sm text-[var(--text-secondary)]">Создайте первый макет 58 × 40 мм для этого режима.</p></div>
    </section>
  );
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 }).format(value);
}

function roundMetric(value: number) {
  return Math.round(value * 100) / 100;
}
