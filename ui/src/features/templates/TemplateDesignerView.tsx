import {
  AlertCircle,
  Barcode,
  Box,
  Check,
  Copy,
  Eye,
  EyeOff,
  Grid2X2,
  Layers3,
  MousePointer2,
  Plus,
  RotateCcw,
  Save,
  Trash2,
  Type,
} from "lucide-react";
import { useEffect, useState } from "react";
import { useModalFocus } from "../../components/useModalFocus";
import { commands } from "../../generated/commands";
import type {
  TemplateDesignerResponse,
  TemplateElementItem,
  TemplateMutationResponse,
  TemplatePaletteItem,
  TemplateSummary,
} from "../../generated/types";
import { TemplateCanvas } from "./TemplateCanvas";
import { clampMetric } from "./templateGeometry";
import type { TemplateDesignerCopy, TemplateNoticeKey } from "./templateDesignerI18n";

type DesignerMode = "fbs" | "fbo";
type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: TemplateDesignerResponse };
type NameAction = "create" | "duplicate" | "rename";
type ConfirmAction = "delete" | "reset";

const requiredTypes = new Set(["kiz_datamatrix", "barcode_code128", "sticker_tail"]);

export function TemplateDesignerView({ copy, locale }: { copy: TemplateDesignerCopy; locale: string }) {
  const [mode, setMode] = useState<DesignerMode>("fbs");
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [selectedTemplateId, setSelectedTemplateId] = useState("");
  const [selectedElementId, setSelectedElementId] = useState("");
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [snap, setSnap] = useState(true);
  const [paletteKey, setPaletteKey] = useState("");
  const [clipboard, setClipboard] = useState<TemplateElementItem | null>(null);
  const [nameAction, setNameAction] = useState<NameAction | null>(null);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const [notice, setNotice] = useState<{ kind: "error" | "success"; key: TemplateNoticeKey } | null>(null);

  useEffect(() => {
    let active = true;
    void commands.templates.loadDesigner({ mode }).then(
      (data) => {
        if (!active) return;
        const initialTemplate = data.templates.find((template) => template.defaultTemplate) ?? data.templates[0];
        setState({ status: "ready", data });
        setSelectedTemplateId(initialTemplate?.id ?? "");
        setSelectedElementId(initialTemplate?.elements[0]?.id ?? "");
        setPaletteKey(data.palette[0]?.key ?? "");
        setDirty(false);
        setClipboard(null);
      },
      () => {
        if (active) setState({ status: "error" });
      },
    );
    return () => {
      active = false;
    };
  }, [mode, reloadKey]);

  const selectedTemplate = state.status === "ready"
    ? state.data.templates.find((template) => template.id === selectedTemplateId) ?? state.data.templates[0] ?? null
    : null;
  const selectedElement = selectedTemplate?.elements.find((element) => element.id === selectedElementId)
    ?? selectedTemplate?.elements[0]
    ?? null;

  const selectMode = (nextMode: DesignerMode) => {
    if (nextMode === mode) return;
    if (dirty) {
      setNotice({ kind: "error", key: "dirtyGuard" });
      return;
    }
    setState({ status: "loading" });
    setSelectedTemplateId("");
    setSelectedElementId("");
    setNotice(null);
    setMode(nextMode);
  };

  const selectTemplate = (template: TemplateSummary) => {
    if (dirty && template.id !== selectedTemplate?.id) {
      setNotice({ kind: "error", key: "dirtyGuard" });
      return;
    }
    setSelectedTemplateId(template.id);
    setSelectedElementId(template.elements[0]?.id ?? "");
    setNotice(null);
  };

  const replaceDesigner = (response: TemplateMutationResponse, successKey: TemplateNoticeKey) => {
    const template = response.designer.templates.find((item) => item.id === response.selectedTemplateId)
      ?? response.designer.templates[0];
    setState({ status: "ready", data: response.designer });
    setSelectedTemplateId(template?.id ?? "");
    setSelectedElementId(template?.elements[0]?.id ?? "");
    setPaletteKey((current) => current || response.designer.palette[0]?.key || "");
    setDirty(false);
    setClipboard(null);
    setNotice({ kind: "success", key: successKey });
  };

  const runMutation = async (operation: () => Promise<TemplateMutationResponse>, successKey: TemplateNoticeKey) => {
    setBusy(true);
    setNotice(null);
    try {
      replaceDesigner(await operation(), successKey);
    } catch {
      setNotice({ kind: "error", key: "mutationError" });
    } finally {
      setBusy(false);
    }
  };

  const updateTemplate = (updater: (template: TemplateSummary) => TemplateSummary) => {
    if (state.status !== "ready" || selectedTemplate === null) return;
    const updated = updater(selectedTemplate);
    setState({
      status: "ready",
      data: { ...state.data, templates: state.data.templates.map((item) => item.id === updated.id ? updated : item) },
    });
    setDirty(true);
    setNotice(null);
  };

  const updateElement = (id: string, patch: Partial<TemplateElementItem>) => {
    updateTemplate((template) => ({
      ...template,
      elements: template.elements.map((element) => element.id === id ? { ...element, ...patch } : element),
    }));
  };

  const addElement = async () => {
    if (selectedTemplate === null || state.status !== "ready" || !paletteKey) return;
    setBusy(true);
    setNotice(null);
    try {
      const zIndex = Math.max(0, ...selectedTemplate.elements.map((element) => element.zIndex)) + 1;
      const element = await commands.templates.createElement({ mode, paletteKey, zIndex });
      updateTemplate((template) => ({ ...template, elements: [...template.elements, element] }));
      setSelectedElementId(element.id);
    } catch {
      setNotice({ kind: "error", key: "addError" });
    } finally {
      setBusy(false);
    }
  };

  const copyElement = () => {
    if (selectedElement !== null) setClipboard(selectedElement);
  };

  const pasteElement = () => {
    if (clipboard === null || selectedTemplate === null || state.status !== "ready") return;
    if (selectedTemplate.elements.length >= state.data.maxElements) {
      setNotice({ kind: "error", key: "elementLimit" });
      return;
    }
    const zIndex = Math.max(0, ...selectedTemplate.elements.map((element) => element.zIndex)) + 1;
    const id = typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `copy-${Date.now()}`;
    const copiedElement = {
      ...clipboard,
      id,
      label: `${clipboard.label} — ${copy.canvasActions.copySuffix}`,
      xMm: clampMetric(clipboard.xMm + 1, 0, state.data.pageWidthMm - clipboard.widthMm),
      yMm: clampMetric(clipboard.yMm + 1, 0, state.data.pageHeightMm - clipboard.heightMm),
      zIndex,
    };
    updateTemplate((template) => ({ ...template, elements: [...template.elements, copiedElement] }));
    setSelectedElementId(copiedElement.id);
  };

  const deleteElement = () => {
    if (selectedTemplate === null || selectedElement === null) return;
    const sameType = selectedTemplate.elements.filter((element) => element.type === selectedElement.type);
    if (requiredTypes.has(selectedElement.type) && sameType.length === 1) {
      setNotice({ kind: "error", key: "requiredElement" });
      return;
    }
    const remaining = selectedTemplate.elements.filter((element) => element.id !== selectedElement.id);
    updateTemplate((template) => ({ ...template, elements: remaining }));
    setSelectedElementId(remaining[0]?.id ?? "");
  };

  const save = () => {
    if (selectedTemplate === null) return;
    void runMutation(() => commands.templates.save({
      mode,
      template: {
        id: selectedTemplate.id,
        name: selectedTemplate.name,
        elements: selectedTemplate.elements.map((element) => ({ ...element })),
      },
    }), "saved");
  };

  const discard = () => {
    setState({ status: "loading" });
    setReloadKey((value) => value + 1);
    setNotice(null);
  };

  if (state.status === "loading") return <DesignerLoading mode={mode} copy={copy} onMode={selectMode} />;
  if (state.status === "error") {
    return (
      <div className="space-y-4">
        <ModeBar mode={mode} copy={copy} onMode={selectMode} />
        <section className="grid min-h-72 place-items-center rounded-2xl border border-red-200 bg-red-50 p-8 text-center" role="alert">
          <div>
            <AlertCircle className="mx-auto mb-3 text-red-600" aria-hidden="true" size={28} />
            <h3 className="font-semibold text-red-950">{copy.load.errorTitle}</h3>
            <p className="mt-2 text-sm text-red-800">{copy.load.errorDetail}</p>
            <button className="mt-4 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white" type="button" onClick={() => {
              setState({ status: "loading" });
              setReloadKey((value) => value + 1);
            }}>
              {copy.load.retry}
            </button>
          </div>
        </section>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <ModeBar mode={mode} copy={copy} onMode={selectMode} />
      <DesignerToolbar
        copy={copy}
        template={selectedTemplate}
        dirty={dirty}
        busy={busy}
        atTemplateLimit={state.data.templates.length >= state.data.maxTemplates}
        onNameAction={setNameAction}
        onConfirmAction={setConfirmAction}
        onDefault={() => selectedTemplate && void runMutation(
          () => commands.templates.setDefault({ mode, templateId: selectedTemplate.id }),
          "defaultSet",
        )}
        onSave={save}
        onDiscard={discard}
      />
      {dirty && <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm font-semibold text-amber-900">{copy.dirty}</div>}
      {notice && (
        <div className={`rounded-xl border px-4 py-2.5 text-sm ${notice.kind === "error" ? "border-red-200 bg-red-50 text-red-900" : "border-emerald-200 bg-emerald-50 text-emerald-900"}`} role={notice.kind === "error" ? "alert" : "status"}>
          {copy.notices[notice.key]}
        </div>
      )}
      {selectedTemplate === null ? (
        <EmptyDesigner copy={copy} />
      ) : (
        <div className="grid items-start gap-4 xl:grid-cols-[14rem_minmax(25rem,1fr)_18rem]">
          <CatalogPanel
            copy={copy}
            locale={locale}
            templates={state.data.templates}
            palette={state.data.palette}
            paletteKey={paletteKey}
            selectedTemplateId={selectedTemplate.id}
            selectedElementId={selectedElement?.id ?? ""}
            busy={busy}
            atElementLimit={selectedTemplate.elements.length >= state.data.maxElements}
            onPalette={setPaletteKey}
            onAdd={() => void addElement()}
            onTemplate={selectTemplate}
            onElement={setSelectedElementId}
          />
          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-3 py-2 shadow-[var(--shadow-control)]">
              <div className="flex flex-wrap gap-2">
                <ControlButton label={copy.canvasActions.copy} disabled={selectedElement === null || busy} onClick={copyElement}><Copy size={15} /></ControlButton>
                <ControlButton label={copy.canvasActions.paste} disabled={clipboard === null || busy} onClick={pasteElement}><Plus size={15} /></ControlButton>
                <ControlButton label={copy.canvasActions.delete} danger disabled={selectedElement === null || busy} onClick={deleteElement}><Trash2 size={15} /></ControlButton>
              </div>
              <label className="flex items-center gap-2 text-xs font-medium text-[var(--text-secondary)]">
                <input type="checkbox" checked={snap} onChange={(event) => setSnap(event.target.checked)} />
                {copy.canvasActions.snap}
              </label>
            </div>
            <TemplateCanvas
              template={selectedTemplate}
              selectedElementId={selectedElement?.id ?? ""}
              widthMm={state.data.pageWidthMm}
              heightMm={state.data.pageHeightMm}
              snap={snap}
              disabled={busy}
              copy={copy.canvas}
              locale={locale}
              onElement={setSelectedElementId}
              onChange={updateElement}
            />
          </div>
          <InspectorPanel
            copy={copy}
            element={selectedElement}
            pageWidthMm={state.data.pageWidthMm}
            pageHeightMm={state.data.pageHeightMm}
            disabled={busy}
            onChange={(patch) => selectedElement && updateElement(selectedElement.id, patch)}
          />
        </div>
      )}
      {nameAction && (
        <NameDialog
          copy={copy}
          action={nameAction}
          initialName={nameAction === "rename" ? selectedTemplate?.name ?? "" : ""}
          busy={busy}
          onClose={() => setNameAction(null)}
          onSubmit={(name) => {
            setNameAction(null);
            if (nameAction === "create") {
              void runMutation(() => commands.templates.create({ mode, name }), "created");
            } else if (nameAction === "duplicate" && selectedTemplate) {
              void runMutation(() => commands.templates.duplicate({ mode, templateId: selectedTemplate.id, name }), "duplicated");
            } else if (selectedTemplate) {
              void runMutation(() => commands.templates.rename({ mode, templateId: selectedTemplate.id, name }), "renamed");
            }
          }}
        />
      )}
      {confirmAction && selectedTemplate && (
        <ConfirmDialog
          copy={copy}
          action={confirmAction}
          templateName={selectedTemplate.name}
          busy={busy}
          onClose={() => setConfirmAction(null)}
          onConfirm={() => {
            setConfirmAction(null);
            void runMutation(
              () => confirmAction === "delete"
                ? commands.templates.delete({ mode, templateId: selectedTemplate.id })
                : commands.templates.reset({ mode, templateId: selectedTemplate.id }),
              confirmAction === "delete" ? "deleted" : "reset",
            );
          }}
        />
      )}
    </div>
  );
}

function ModeBar({ mode, copy, onMode }: { mode: DesignerMode; copy: TemplateDesignerCopy; onMode: (mode: DesignerMode) => void }) {
  return (
    <section className="flex flex-col justify-between gap-3 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)] sm:flex-row sm:items-center">
      <div className="inline-flex w-fit rounded-xl bg-[var(--surface-muted)] p-1" role="tablist" aria-label={copy.mode.aria}>
        {(["fbs", "fbo"] as const).map((item) => (
          <button
            className={`min-w-20 rounded-lg px-4 py-2 text-sm font-semibold transition ${mode === item ? "bg-[var(--surface-elevated)] text-[var(--text-primary)] shadow-[var(--shadow-control)]" : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]"}`}
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
        {copy.mode.localCatalog}
      </div>
    </section>
  );
}

function DesignerToolbar({
  copy,
  template,
  dirty,
  busy,
  atTemplateLimit,
  onNameAction,
  onConfirmAction,
  onDefault,
  onSave,
  onDiscard,
}: {
  copy: TemplateDesignerCopy;
  template: TemplateSummary | null;
  dirty: boolean;
  busy: boolean;
  atTemplateLimit: boolean;
  onNameAction: (action: NameAction) => void;
  onConfirmAction: (action: ConfirmAction) => void;
  onDefault: () => void;
  onSave: () => void;
  onDiscard: () => void;
}) {
  const mutationsDisabled = busy || dirty;
  return (
    <section className="flex flex-wrap items-center gap-2 rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)]" aria-label={copy.toolbar.aria}>
      <ControlButton label={copy.toolbar.create} disabled={mutationsDisabled || atTemplateLimit} onClick={() => onNameAction("create")}><Plus size={15} /></ControlButton>
      <ControlButton label={copy.toolbar.duplicate} disabled={mutationsDisabled || template === null || atTemplateLimit} onClick={() => onNameAction("duplicate")}><Copy size={15} /></ControlButton>
      <ControlButton label={copy.toolbar.rename} disabled={mutationsDisabled || template === null} onClick={() => onNameAction("rename")}><Type size={15} /></ControlButton>
      <ControlButton label={copy.toolbar.makeDefault} disabled={mutationsDisabled || template === null || template.defaultTemplate} onClick={onDefault}><Check size={15} /></ControlButton>
      <ControlButton label={copy.toolbar.reset} disabled={mutationsDisabled || template === null} onClick={() => onConfirmAction("reset")}><RotateCcw size={15} /></ControlButton>
      <ControlButton label={copy.toolbar.delete} danger disabled={mutationsDisabled || template === null} onClick={() => onConfirmAction("delete")}><Trash2 size={15} /></ControlButton>
      <span className="min-w-2 flex-1" />
      {dirty && <ControlButton label={copy.toolbar.discard} disabled={busy} onClick={onDiscard}><RotateCcw size={15} /></ControlButton>}
      <ControlButton label={copy.toolbar.save} primary disabled={busy || !dirty || template === null} onClick={onSave}><Save size={15} /></ControlButton>
    </section>
  );
}

function ControlButton({
  label,
  children,
  disabled,
  danger = false,
  primary = false,
  onClick,
}: {
  label: string;
  children: React.ReactNode;
  disabled: boolean;
  danger?: boolean;
  primary?: boolean;
  onClick: () => void;
}) {
  const tone = primary
    ? "bg-[var(--button-primary)] text-white hover:brightness-95"
    : danger
      ? "border border-[var(--danger)]/30 bg-[var(--surface-elevated)] text-[var(--danger)] hover:bg-[var(--danger-soft)]"
      : "border border-[var(--border-subtle)] bg-[var(--surface-elevated)] text-[var(--text-primary)] hover:border-[var(--border-strong)]";
  return (
    <button className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-45 ${tone}`} type="button" disabled={disabled} onClick={onClick} aria-label={label}>
      {children}<span>{label}</span>
    </button>
  );
}

function CatalogPanel({
  copy,
  locale,
  templates,
  palette,
  paletteKey,
  selectedTemplateId,
  selectedElementId,
  busy,
  atElementLimit,
  onPalette,
  onAdd,
  onTemplate,
  onElement,
}: {
  copy: TemplateDesignerCopy;
  locale: string;
  templates: TemplateSummary[];
  palette: TemplatePaletteItem[];
  paletteKey: string;
  selectedTemplateId: string;
  selectedElementId: string;
  busy: boolean;
  atElementLimit: boolean;
  onPalette: (key: string) => void;
  onAdd: () => void;
  onTemplate: (template: TemplateSummary) => void;
  onElement: (id: string) => void;
}) {
  const selectedTemplate = templates.find((template) => template.id === selectedTemplateId) ?? templates[0];
  return (
    <aside className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="border-b border-[var(--border-subtle)] p-4">
        <div className="flex items-center gap-2"><Layers3 aria-hidden="true" size={17} /><h3 className="text-sm font-semibold">{copy.catalog.title}</h3></div>
        <div className="mt-3 grid gap-2">
          {templates.map((template) => (
            <button
              className={`rounded-xl border px-3 py-2.5 text-left transition ${template.id === selectedTemplateId ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-[var(--border-subtle)] hover:border-[var(--border-strong)]"}`}
              type="button"
              key={template.id}
              onClick={() => onTemplate(template)}
              aria-label={formatCopy(copy.catalog.templateAria, { name: template.name })}
            >
              <span className="block truncate text-sm font-semibold">{template.name}</span>
              <span className="mt-1 flex items-center gap-2 text-[0.7rem] text-[var(--text-muted)]">
                {formatCopy(copy.catalog.elements, { count: formatNumber(template.elements.length, locale) })}
                {template.defaultTemplate && <span className="rounded-full bg-white/75 px-2 py-0.5 text-[var(--accent-strong)]">{copy.catalog.default}</span>}
              </span>
            </button>
          ))}
        </div>
      </div>
      {selectedTemplate && (
        <div className="p-4">
          <div className="mb-4 grid gap-2">
            <label className="text-[0.68rem] font-semibold tracking-[0.1em] text-[var(--text-muted)] uppercase">
              {copy.catalog.newElement}
              <select className="mt-1.5 w-full rounded-lg border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-2.5 py-2 text-xs font-medium normal-case tracking-normal text-[var(--text-primary)]" aria-label={copy.catalog.newElement} value={paletteKey} onChange={(event) => onPalette(event.target.value)}>
                {palette.map((item) => <option key={item.key} value={item.key}>{item.label}</option>)}
              </select>
            </label>
            <button className="rounded-lg bg-[var(--accent-soft)] px-3 py-2 text-xs font-semibold text-[var(--accent-strong)] disabled:opacity-45" type="button" disabled={busy || atElementLimit || !paletteKey} onClick={onAdd}>{copy.catalog.addElement}</button>
          </div>
          <p className="mb-2 text-[0.68rem] font-semibold tracking-[0.12em] text-[var(--text-muted)] uppercase">{copy.catalog.layers}</p>
          <div className="grid gap-1.5">
            {[...selectedTemplate.elements].sort((left, right) => right.zIndex - left.zIndex).map((element) => (
              <button
                className={`flex items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs transition ${element.id === selectedElementId ? "bg-[var(--surface-muted)] font-semibold text-[var(--text-primary)]" : "text-[var(--text-secondary)] hover:bg-[var(--surface-muted)]"}`}
                type="button"
                key={element.id}
                aria-label={formatCopy(copy.catalog.selectLayer, { name: element.label })}
                onClick={() => onElement(element.id)}
              >
                <ElementIcon type={element.type} /><span className="min-w-0 flex-1 truncate">{element.label}</span>
                {element.visible ? <Eye aria-label={copy.catalog.visible} size={14} /> : <EyeOff aria-label={copy.catalog.hidden} size={14} />}
              </button>
            ))}
          </div>
        </div>
      )}
    </aside>
  );
}

function InspectorPanel({
  copy,
  element,
  pageWidthMm,
  pageHeightMm,
  disabled,
  onChange,
}: {
  copy: TemplateDesignerCopy;
  element: TemplateElementItem | null;
  pageWidthMm: number;
  pageHeightMm: number;
  disabled: boolean;
  onChange: (patch: Partial<TemplateElementItem>) => void;
}) {
  if (element === null) {
    return <aside className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]"><p className="py-8 text-center text-xs leading-5 text-[var(--text-muted)]">{copy.inspector.empty}</p></aside>;
  }
  const metric = (key: "xMm" | "yMm" | "widthMm" | "heightMm", value: number) => {
    const minimum = key === "widthMm" || key === "heightMm" ? 0.1 : 0;
    const maximum = key === "xMm"
      ? pageWidthMm - element.widthMm
      : key === "yMm"
        ? pageHeightMm - element.heightMm
        : key === "widthMm"
          ? pageWidthMm - element.xMm
          : pageHeightMm - element.yMm;
    onChange({ [key]: clampMetric(value, minimum, maximum) });
  };
  return (
    <aside className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)]">
      <div className="flex items-center gap-2 border-b border-[var(--border-subtle)] pb-3"><MousePointer2 aria-hidden="true" size={17} /><h3 className="text-sm font-semibold">{copy.inspector.title}</h3></div>
      <div className="mt-4 space-y-4">
        <TextField label={copy.inspector.name} value={element.label} disabled={disabled} onChange={(label) => onChange({ label })} />
        {(element.type === "text_field" || element.type === "static_text" || element.type === "sticker_tail") && <TextField label={copy.inspector.prefix} value={element.prefix} disabled={disabled} onChange={(prefix) => onChange({ prefix })} />}
        {element.type === "static_text" && <TextField label={copy.inspector.text} value={element.content} disabled={disabled} onChange={(content) => onChange({ content })} />}
        <label className="flex items-center justify-between rounded-xl border border-[var(--border-subtle)] px-3 py-2.5 text-xs font-semibold">
          {copy.inspector.visible}<input type="checkbox" aria-label={copy.inspector.visible} checked={element.visible} disabled={disabled} onChange={(event) => onChange({ visible: event.target.checked })} />
        </label>
        <fieldset>
          <legend className="mb-2 text-[0.68rem] font-semibold tracking-[0.1em] text-[var(--text-muted)] uppercase">{copy.inspector.geometry}</legend>
          <div className="grid grid-cols-2 gap-2">
            <MetricField label={copy.inspector.x} value={element.xMm} disabled={disabled} onChange={(value) => metric("xMm", value)} />
            <MetricField label={copy.inspector.y} value={element.yMm} disabled={disabled} onChange={(value) => metric("yMm", value)} />
            <MetricField label={copy.inspector.width} value={element.widthMm} disabled={disabled} onChange={(value) => metric("widthMm", value)} />
            <MetricField label={copy.inspector.height} value={element.heightMm} disabled={disabled} onChange={(value) => metric("heightMm", value)} />
          </div>
        </fieldset>
        {!new Set(["kiz_datamatrix", "separator_line"]).has(element.type) && (
          <div className="grid grid-cols-2 gap-2">
            <MetricField label={copy.inspector.font} value={element.fontSizePt} disabled={disabled} onChange={(value) => onChange({ fontSizePt: clampMetric(value, 1, 72) })} />
            <label className="text-[0.68rem] font-medium text-[var(--text-secondary)]">{copy.inspector.alignment}<select className="mt-1 w-full rounded-lg border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-2 py-2 text-xs" value={element.align} disabled={disabled} onChange={(event) => onChange({ align: event.target.value })}><option value="left">{copy.inspector.left}</option><option value="center">{copy.inspector.center}</option><option value="right">{copy.inspector.right}</option></select></label>
            <label className="flex items-center gap-2 text-xs font-medium"><input type="checkbox" checked={element.bold} disabled={disabled} onChange={(event) => onChange({ bold: event.target.checked })} />{copy.inspector.bold}</label>
            {element.type === "barcode_code128" && <label className="flex items-center gap-2 text-xs font-medium"><input type="checkbox" checked={element.humanReadable} disabled={disabled} onChange={(event) => onChange({ humanReadable: event.target.checked })} />{copy.inspector.barcodeDigits}</label>}
          </div>
        )}
        <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] px-3 py-2.5 text-xs leading-5 text-[var(--text-secondary)]">{copy.inspector.geometryHint}</div>
      </div>
    </aside>
  );
}

function TextField({ label, value, disabled, onChange }: { label: string; value: string; disabled: boolean; onChange: (value: string) => void }) {
  return <label className="block text-[0.68rem] font-medium text-[var(--text-secondary)]">{label}<input className="mt-1 w-full rounded-lg border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-2.5 py-2 text-xs text-[var(--text-primary)]" value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} /></label>;
}

function MetricField({ label, value, disabled, onChange }: { label: string; value: number; disabled: boolean; onChange: (value: number) => void }) {
  const [draft, setDraft] = useState(String(roundMetric(value)));
  const [focused, setFocused] = useState(false);
  const displayValue = focused ? draft : String(roundMetric(value));
  return (
    <label className="text-[0.68rem] font-medium text-[var(--text-secondary)]">
      {label}
      <input className="mt-1 w-full rounded-lg border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-2.5 py-2 text-xs font-semibold text-[var(--text-primary)]" aria-label={label} type="number" step="0.1" value={displayValue} disabled={disabled} onFocus={() => {
        setDraft(String(roundMetric(value)));
        setFocused(true);
      }} onBlur={() => {
        setFocused(false);
        if (draft === "") setDraft(String(roundMetric(value)));
      }} onChange={(event) => {
        setDraft(event.target.value);
        const next = event.target.valueAsNumber;
        if (Number.isFinite(next)) onChange(next);
      }} />
    </label>
  );
}

function NameDialog({ copy, action, initialName, busy, onClose, onSubmit }: { copy: TemplateDesignerCopy; action: NameAction; initialName: string; busy: boolean; onClose: () => void; onSubmit: (name: string) => void }) {
  const [name, setName] = useState(initialName);
  const labels = action === "create" ? { title: copy.dialogs.createTitle, submit: copy.dialogs.createSubmit } : action === "duplicate" ? { title: copy.dialogs.duplicateTitle, submit: copy.dialogs.duplicateSubmit } : { title: copy.dialogs.renameTitle, submit: copy.dialogs.renameSubmit };
  return (
    <Modal title={labels.title} closeLabel={copy.dialogs.close} busy={busy} onClose={onClose}>
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); const trimmed = name.trim(); if (trimmed) onSubmit(trimmed); }}>
        <label className="block text-sm font-medium">{copy.dialogs.name}<input className="mt-2 w-full rounded-xl border border-[var(--border-strong)] px-3 py-2.5" aria-label={copy.dialogs.name} value={name} maxLength={120} disabled={busy} onChange={(event) => setName(event.target.value)} /></label>
        <div className="flex justify-end gap-2"><DialogCancel label={copy.dialogs.cancel} onClick={onClose} /><button className="rounded-lg bg-[var(--button-primary)] px-4 py-2 text-sm font-semibold text-white disabled:opacity-45" type="submit" disabled={busy || !name.trim()}>{labels.submit}</button></div>
      </form>
    </Modal>
  );
}

function ConfirmDialog({ copy, action, templateName, busy, onClose, onConfirm }: { copy: TemplateDesignerCopy; action: ConfirmAction; templateName: string; busy: boolean; onClose: () => void; onConfirm: () => void }) {
  const reset = action === "reset";
  return (
    <Modal title={reset ? copy.dialogs.resetTitle : copy.dialogs.deleteTitle} closeLabel={copy.dialogs.close} busy={busy} onClose={onClose}>
      <p className="text-sm leading-6 text-[var(--text-secondary)]">{formatCopy(reset ? copy.dialogs.resetDetail : copy.dialogs.deleteDetail, { name: templateName })}</p>
      <div className="mt-5 flex justify-end gap-2"><DialogCancel label={copy.dialogs.cancel} onClick={onClose} /><button className={`rounded-lg px-4 py-2 text-sm font-semibold text-white disabled:opacity-45 ${reset ? "bg-[var(--button-primary)]" : "bg-red-700"}`} type="button" disabled={busy} onClick={onConfirm}>{reset ? copy.dialogs.reset : copy.dialogs.delete}</button></div>
    </Modal>
  );
}

function Modal({ title, closeLabel, children, busy, onClose }: { title: string; closeLabel: string; children: React.ReactNode; busy: boolean; onClose: () => void }) {
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLElement>(busy, onClose);
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/35 p-4" onMouseDown={(event) => { if (!busy && event.target === event.currentTarget) onClose(); }}>
      <section ref={dialogRef} className="w-full max-w-md rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl" role="dialog" aria-modal="true" aria-labelledby="template-dialog-title">
        <div className="mb-4 flex items-start justify-between gap-3"><h2 className="text-lg font-semibold" id="template-dialog-title">{title}</h2><button ref={initialFocusRef} className="rounded-lg px-2 py-1 text-sm text-[var(--text-muted)] hover:bg-[var(--surface-muted)]" type="button" aria-label={closeLabel} disabled={busy} onClick={onClose}>×</button></div>
        {children}
      </section>
    </div>
  );
}

function DialogCancel({ label, onClick }: { label: string; onClick: () => void }) {
  return <button className="rounded-lg border border-[var(--border-subtle)] px-4 py-2 text-sm font-semibold" type="button" onClick={onClick}>{label}</button>;
}

function ElementIcon({ type }: { type: string }) {
  if (type === "barcode_code128") return <Barcode aria-hidden="true" size={14} />;
  if (type === "kiz_datamatrix") return <Grid2X2 aria-hidden="true" size={14} />;
  if (type === "text_field" || type === "static_text") return <Type aria-hidden="true" size={14} />;
  return <Box aria-hidden="true" size={14} />;
}

function DesignerLoading({ mode, copy, onMode }: { mode: DesignerMode; copy: TemplateDesignerCopy; onMode: (mode: DesignerMode) => void }) {
  return <div className="space-y-4"><ModeBar mode={mode} copy={copy} onMode={onMode} /><section className="grid gap-4 xl:grid-cols-[14rem_minmax(25rem,1fr)_18rem]" aria-label={copy.load.aria}>{["h-72", "h-[34rem]", "h-80"].map((height, index) => <span className={`${height} animate-pulse rounded-2xl bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]`} key={index} />)}</section></div>;
}

function EmptyDesigner({ copy }: { copy: TemplateDesignerCopy }) {
  return <section className="grid min-h-72 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-8 text-center"><div><RotateCcw className="mx-auto mb-3 text-[var(--text-muted)]" aria-hidden="true" size={28} /><h3 className="font-semibold">{copy.empty.title}</h3><p className="mt-2 text-sm text-[var(--text-secondary)]">{copy.empty.detail}</p></div></section>;
}

function formatCopy(value: string, replacements: Record<string, string>) {
  return Object.entries(replacements).reduce((result, [key, replacement]) => result.replace(`{${key}}`, replacement), value);
}

function formatNumber(value: number, locale: string) {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
}

function roundMetric(value: number) {
  return Math.round(value * 100) / 100;
}
