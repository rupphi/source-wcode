import { CheckCircle2, FileText, FolderOpen, Layers3, Printer, X } from "lucide-react";
import { JDeskError } from "jdesk-client";
import { useMemo, useState } from "react";
import { useModalFocus } from "../../components/useModalFocus";
import { commands } from "../../generated/commands";
import type { OrderSortRequest, PrintExportResponse, PrintSetupResponse } from "../../generated/types";
import { interpolate } from "../../i18n";
import { exportSupplyPdf } from "./nativePrintCommands";
import { defaultPrintSetupCopy, type PrintSetupCopy } from "./PrintSetupCopy";

const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PRINT_JOB_ID = /^[1-9][0-9]{0,19}$/;

type PageOrder = "barcode_then_sticker" | "sticker_then_barcode";
type DialogState =
  | { status: "closed" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: PrintSetupResponse; dirty: boolean; saved: boolean; saving: boolean; saveError: boolean };
type ExportErrorKind = "token_invalid" | "rate_limited" | "preflight_failed" | "unavailable";
type ExportState =
  | { status: "idle" }
  | { status: "working" }
  | { status: "cancelled" }
  | { status: "error"; kind: ExportErrorKind }
  | { status: "success"; data: PrintExportResponse; opening: "" | "labels" | "details"; openError: boolean };

export function PrintSetupDialog({
  shopId,
  supplyId,
  query,
  sort,
  orderCount,
  copy = defaultPrintSetupCopy,
  locale = "ru-RU",
}: {
  shopId: number;
  supplyId: string;
  query: string;
  sort: OrderSortRequest;
  orderCount: number;
  copy?: PrintSetupCopy;
  locale?: string;
}) {
  const [state, setState] = useState<DialogState>({ status: "closed" });
  const [pageOrder, setPageOrder] = useState<PageOrder>("barcode_then_sticker");
  const [copies, setCopies] = useState("1");
  const [exportState, setExportState] = useState<ExportState>({ status: "idle" });
  const busy = state.status === "ready" && (state.saving || exportState.status === "working");
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);

  const close = () => {
    if (!busy) setState({ status: "closed" });
  };
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLElement>(busy, close, state.status !== "closed");

  const open = async () => {
    setState({ status: "loading" });
    setExportState({ status: "idle" });
    try {
      const response = await commands.printing.setup({ shopId });
      if (!isPrintSetup(response, shopId)) {
        setState({ status: "error" });
        return;
      }
      setPageOrder(response.pageOrder);
      setCopies(String(response.barcodeCopies));
      setState({ status: "ready", data: response, dirty: false, saved: false, saving: false, saveError: false });
    } catch {
      setState({ status: "error" });
    }
  };

  const parsedCopies = Number(copies);
  const validCopies = Number.isInteger(parsedCopies) && parsedCopies >= 1 && parsedCopies <= 100;
  const pageCount = validCopies ? orderCount * (parsedCopies + 1) : 0;

  const markChanged = (next: DialogState & { status: "ready" }) => {
    setState({ ...next, dirty: true, saved: false, saveError: false });
    setExportState({ status: "idle" });
  };

  const save = async () => {
    if (state.status !== "ready" || !validCopies || busy) return;
    const current = state;
    setState({ ...current, saved: false, saving: true, saveError: false });
    try {
      const response = await saveOptions();
      if (!response) {
        setState({ status: "error" });
        return;
      }
      applySavedSetup(response, true);
    } catch {
      setState({ ...current, saved: false, saving: false, saveError: true });
    }
  };

  const createPdf = async () => {
    if (state.status !== "ready" || !validCopies || busy || orderCount < 1) return;
    const current = state;
    setExportState({ status: "working" });
    setState({ ...current, saved: false, saving: false, saveError: false });
    try {
      let effectiveOrder = pageOrder;
      let effectiveCopies = parsedCopies;
      if (current.dirty) {
        const saved = await saveOptions();
        if (!saved) {
          setState({ status: "error" });
          setExportState({ status: "idle" });
          return;
        }
        effectiveOrder = saved.pageOrder;
        effectiveCopies = saved.barcodeCopies;
        applySavedSetup(saved, false);
      }
      const response = await exportSupplyPdf({
        shopId,
        supplyId,
        query,
        sort,
        pageOrder: effectiveOrder,
        barcodeCopies: effectiveCopies,
      });
      if (response.cancelled) {
        setExportState({ status: "cancelled" });
        return;
      }
      if (!isPrintExport(response, effectiveCopies)) {
        setExportState({ status: "error", kind: "unavailable" });
        return;
      }
      setExportState({ status: "success", data: response, opening: "", openError: false });
    } catch (error) {
      setExportState({ status: "error", kind: printExportErrorKind(error) });
    }
  };

  const saveOptions = async () => {
    const response = await commands.printing.saveOptions({
      shopId,
      pageOrder,
      barcodeCopies: parsedCopies,
    });
    return isPrintSetup(response, shopId) ? response : null;
  };

  const applySavedSetup = (response: PrintSetupResponse & { pageOrder: PageOrder }, showSaved: boolean) => {
    setPageOrder(response.pageOrder);
    setCopies(String(response.barcodeCopies));
    setState({ status: "ready", data: response, dirty: false, saved: showSaved, saving: false, saveError: false });
  };

  const openExport = async (fileKind: "labels" | "details") => {
    if (exportState.status !== "success" || exportState.opening !== "") return;
    const current = exportState;
    setExportState({ ...current, opening: fileKind, openError: false });
    try {
      const response = await commands.printing.openExport({ shopId, exportId: current.data.exportId, fileKind });
      const expectedName = fileKind === "labels" ? current.data.labelsFileName : current.data.detailsFileName;
      if (!response.opened || response.fileName !== expectedName) {
        setExportState({ ...current, opening: "", openError: true });
        return;
      }
      setExportState({ ...current, opening: "", openError: false });
    } catch {
      setExportState({ ...current, opening: "", openError: true });
    }
  };

  return (
    <>
      <button
        className="inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-4 text-sm font-semibold text-white shadow-[var(--shadow-control)] transition hover:bg-[#1c3329] disabled:cursor-not-allowed disabled:opacity-50"
        disabled={orderCount < 1}
        onClick={() => void open()}
        type="button"
      >
        <Printer aria-hidden="true" size={16} />
        {copy.button}
      </button>

      {state.status !== "closed" && (
        <div className="fixed inset-0 z-50 grid place-items-center bg-[#10231b]/45 p-4 backdrop-blur-[2px]" onMouseDown={(event) => {
          if (event.target === event.currentTarget) close();
        }}>
          <section ref={dialogRef} aria-labelledby="print-setup-title" aria-modal="true" className="max-h-[calc(100vh-2rem)] w-full max-w-2xl overflow-y-auto rounded-3xl border border-white/60 bg-[var(--surface-elevated)] shadow-2xl" role="dialog">
            <header className="flex items-start justify-between border-b border-[var(--border-subtle)] px-6 py-5">
              <div>
                <p className="mb-1 text-xs font-semibold tracking-[0.12em] text-[var(--accent-strong)] uppercase">{copy.eyebrow}</p>
                <h3 className="text-xl font-semibold tracking-[-0.025em]" id="print-setup-title">{copy.title}</h3>
                <p className="mt-1 text-sm text-[var(--text-secondary)]">{copy.description}</p>
              </div>
              <button ref={initialFocusRef} className="grid size-9 place-items-center rounded-xl text-[var(--text-muted)] transition hover:bg-[var(--surface-muted)] hover:text-[var(--text-primary)] disabled:cursor-wait disabled:opacity-50" disabled={busy} onClick={close} type="button" aria-label={copy.close}>
                <X aria-hidden="true" size={19} />
              </button>
            </header>

            {state.status === "loading" && <LoadingState copy={copy} />}
            {state.status === "error" && <LoadError copy={copy} onRetry={() => void open()} />}

            {state.status === "ready" && (
              <div className="grid gap-5 p-6">
                <div className="grid gap-3 sm:grid-cols-2">
                  <SummaryCard icon={<FileText size={18} />} label={copy.activeTemplate} value={defaultTemplate(state.data)?.name ?? copy.defaultTemplate} detail={interpolate(copy.dimensions, { width: state.data.pageWidthMm, height: state.data.pageHeightMm })} />
                  <SummaryCard icon={<Layers3 size={18} />} label={copy.jobVolume} value={interpolate(copy.orders, { count: numberFormat.format(orderCount) })} detail={interpolate(copy.pages, { count: numberFormat.format(pageCount) })} />
                </div>

                <fieldset>
                  <legend className="mb-3 text-sm font-semibold">{copy.pageOrder}</legend>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <PageOrderOption checked={pageOrder === "barcode_then_sticker"} description={copy.labelFirstDescription} disabled={busy} label={copy.labelFirst} onChange={() => { setPageOrder("barcode_then_sticker"); markChanged(state); }} />
                    <PageOrderOption checked={pageOrder === "sticker_then_barcode"} description={copy.stickerFirstDescription} disabled={busy} label={copy.stickerFirst} onChange={() => { setPageOrder("sticker_then_barcode"); markChanged(state); }} />
                  </div>
                </fieldset>

                <label className="grid gap-2 sm:max-w-xs">
                  <span className="text-sm font-semibold">{copy.copies}</span>
                  <input aria-label={copy.copies} className="h-11 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-3 text-sm shadow-[var(--shadow-control)] outline-none focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]" disabled={busy} max={100} min={1} onChange={(event) => { setCopies(event.target.value); markChanged(state); }} type="number" value={copies} />
                  <span className={`text-xs ${validCopies ? "text-[var(--text-muted)]" : "font-semibold text-red-700"}`}>{validCopies ? copy.copiesHint : copy.copiesInvalid}</span>
                </label>

                <ExportNotice state={exportState} onOpen={openExport} copy={copy} numberFormat={numberFormat} />

                <div className="flex flex-col gap-3 border-t border-[var(--border-subtle)] pt-5 sm:flex-row sm:items-center sm:justify-between">
                  <div aria-live="polite" className={`min-h-5 text-sm font-semibold ${state.saveError ? "text-red-700" : "text-emerald-700"}`}>
                    {state.saved && <span className="inline-flex items-center gap-2"><CheckCircle2 aria-hidden="true" size={16} />{copy.saved}</span>}
                    {state.saveError && copy.saveError}
                  </div>
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <button className="inline-flex h-11 items-center justify-center rounded-xl border border-[var(--border-strong)] px-4 text-sm font-semibold transition hover:border-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50" disabled={!validCopies || busy} onClick={() => void save()} type="button">{state.saving ? copy.saving : copy.save}</button>
                    <button className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329] disabled:cursor-not-allowed disabled:opacity-50" disabled={!validCopies || busy || orderCount < 1} onClick={() => void createPdf()} type="button">
                      <FileText aria-hidden="true" size={16} />
                      {exportState.status === "working" ? copy.preparingPdf : copy.createPdf}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  );
}

function LoadingState({ copy }: { copy: PrintSetupCopy }) {
  return <div className="grid min-h-80 place-items-center p-8" role="status"><div className="text-center"><Printer className="mx-auto mb-3 animate-pulse text-[var(--accent-strong)]" aria-hidden="true" size={28} /><p className="font-semibold">{copy.loading}</p></div></div>;
}

function LoadError({ copy, onRetry }: { copy: PrintSetupCopy; onRetry: () => void }) {
  return <div className="grid min-h-80 place-items-center p-8 text-center" role="alert"><div><h4 className="font-semibold">{copy.loadError}</h4><p className="mt-2 text-sm text-[var(--text-secondary)]">{copy.unchanged}</p><button className="mt-4 rounded-xl bg-[var(--sidebar)] px-4 py-2.5 text-sm font-semibold text-white" onClick={onRetry} type="button">{copy.retry}</button></div></div>;
}

function ExportNotice({ state, onOpen, copy, numberFormat }: { state: ExportState; onOpen: (kind: "labels" | "details") => Promise<void>; copy: PrintSetupCopy; numberFormat: Intl.NumberFormat }) {
  if (state.status === "idle" || state.status === "working") return null;
  if (state.status === "cancelled") {
    return <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900" role="status">{copy.cancelled}</div>;
  }
  if (state.status === "error") {
    return <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert"><span className="font-semibold">{copy.exportFailed}</span> {printExportErrorMessage(state.kind, copy)}</div>;
  }
  return (
    <div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-emerald-950" role="status">
      <div className="flex items-center gap-2 font-semibold"><CheckCircle2 aria-hidden="true" size={18} />{copy.pdfReady}</div>
      <p className="mt-1 text-xs leading-5 text-emerald-800">{interpolate(copy.pagesCreated, { count: numberFormat.format(state.data.pageCount) })}</p>
      <div className="mt-3 grid gap-2 sm:grid-cols-2">
        <ExportFileButton fileName={state.data.labelsFileName} label={copy.openLabels} loading={state.opening === "labels"} onClick={() => void onOpen("labels")} copy={copy} />
        <ExportFileButton fileName={state.data.detailsFileName} label={copy.openDetails} loading={state.opening === "details"} onClick={() => void onOpen("details")} copy={copy} />
      </div>
      {state.data.kizAttachmentCount > 0 && <p className="mt-3 text-xs font-semibold text-amber-800">{interpolate(copy.kizBackground, { count: numberFormat.format(state.data.kizAttachmentCount) })}</p>}
      {state.openError && <p className="mt-3 text-xs font-semibold text-red-700" role="alert">{copy.openError}</p>}
    </div>
  );
}

function ExportFileButton({ fileName, label, loading, onClick, copy }: { fileName: string; label: string; loading: boolean; onClick: () => void; copy: PrintSetupCopy }) {
  return <button aria-label={label} className="flex items-center gap-3 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-3 py-3 text-left transition hover:border-[var(--accent)] disabled:cursor-wait disabled:opacity-60" disabled={loading} onClick={onClick} type="button"><FolderOpen aria-hidden="true" className="shrink-0 text-[var(--accent-strong)]" size={18} /><span className="min-w-0"><span className="block text-xs font-semibold">{loading ? copy.opening : label}</span><span className="block truncate text-xs text-[var(--text-secondary)]">{fileName}</span></span></button>;
}

function SummaryCard({ icon, label, value, detail }: { icon: React.ReactNode; label: string; value: string; detail: string }) {
  return <div className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4"><div className="mb-3 text-[var(--accent-strong)]" aria-hidden="true">{icon}</div><p className="text-xs font-semibold text-[var(--text-secondary)]">{label}</p><p className="mt-1 font-semibold">{value}</p><p className="mt-1 text-xs text-[var(--text-muted)]">{detail}</p></div>;
}

function PageOrderOption({ checked, disabled, label, description, onChange }: { checked: boolean; disabled: boolean; label: string; description: string; onChange: () => void }) {
  return <label className={`rounded-2xl border p-4 transition ${disabled ? "cursor-wait opacity-60" : "cursor-pointer"} ${checked ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-[var(--border-subtle)] hover:border-[var(--border-strong)]"}`}><span className="flex items-start gap-3"><input aria-label={label} className="mt-0.5 size-4 accent-[var(--accent-strong)]" disabled={disabled} type="radio" checked={checked} onChange={onChange} name="print-page-order" /><span><span className="block text-sm font-semibold">{label}</span><span className="mt-1 block text-xs leading-5 text-[var(--text-secondary)]">{description}</span></span></span></label>;
}

function defaultTemplate(response: PrintSetupResponse) {
  return response.templates.find((template) => template.id === response.defaultTemplateId && template.defaultTemplate);
}

function isPrintSetup(value: unknown, shopId: number): value is PrintSetupResponse & { pageOrder: PageOrder } {
  if (!value || typeof value !== "object") return false;
  const setup = value as PrintSetupResponse;
  const validOrder = setup.pageOrder === "barcode_then_sticker" || setup.pageOrder === "sticker_then_barcode";
  const validTemplates = Array.isArray(setup.templates) && setup.templates.length >= 1 && setup.templates.length <= 100 && setup.templates.every((template) => Number.isInteger(template.id) && template.id > 0 && safeName(template.name, 120) && typeof template.defaultTemplate === "boolean");
  return setup.shopId === shopId && validOrder && Number.isInteger(setup.barcodeCopies) && setup.barcodeCopies >= 1 && setup.barcodeCopies <= 100 && Number.isInteger(setup.defaultTemplateId) && setup.defaultTemplateId > 0 && Number.isFinite(setup.pageWidthMm) && setup.pageWidthMm > 0 && setup.pageWidthMm <= 1_000 && Number.isFinite(setup.pageHeightMm) && setup.pageHeightMm > 0 && setup.pageHeightMm <= 1_000 && validTemplates && setup.templates.filter((template) => template.defaultTemplate).length === 1 && defaultTemplate(setup) !== undefined;
}

function isPrintExport(value: unknown, barcodeCopies: number): value is PrintExportResponse {
  if (!value || typeof value !== "object") return false;
  const result = value as PrintExportResponse;
  return result.cancelled === false && SESSION_ID.test(result.exportId) && safeFileName(result.labelsFileName) && safeFileName(result.detailsFileName) && PRINT_JOB_ID.test(result.printJobId) && Number.isInteger(result.itemCount) && result.itemCount >= 1 && result.itemCount <= 5_000 && result.pageCount === result.itemCount * (barcodeCopies + 1) && Number.isInteger(result.kizAttachmentCount) && result.kizAttachmentCount >= 0 && result.kizAttachmentCount <= result.itemCount;
}

function safeName(value: unknown, maxLength: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maxLength && !hasControlCharacters(value);
}

function safeFileName(value: unknown): value is string {
  return safeName(value, 180) && !value.includes("/") && !value.includes("\\") && value.toLowerCase().endsWith(".pdf");
}

function hasControlCharacters(value: string): boolean {
  return Array.from(value).some((character) => { const codePoint = character.codePointAt(0) ?? 0; return codePoint < 32 || codePoint === 127; });
}

function printExportErrorKind(error: unknown): ExportErrorKind {
  if (!(error instanceof JDeskError) || error.data === null || typeof error.data !== "object") return "unavailable";
  const kind = (error.data as { kind?: unknown }).kind;
  if (kind === "token_invalid" || kind === "rate_limited" || kind === "preflight_failed") return kind;
  return "unavailable";
}

function printExportErrorMessage(kind: ExportErrorKind, copy: PrintSetupCopy): string {
  if (kind === "token_invalid") return copy.errors.tokenInvalid;
  if (kind === "rate_limited") return copy.errors.rateLimited;
  if (kind === "preflight_failed") return copy.errors.preflightFailed;
  return copy.errors.unavailable;
}
