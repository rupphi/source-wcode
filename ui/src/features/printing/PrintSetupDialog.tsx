import { CheckCircle2, FileText, Layers3, Printer, X } from "lucide-react";
import { useEffect, useState } from "react";
import { commands } from "../../generated/commands";
import type { PrintSetupResponse } from "../../generated/types";

type PageOrder = "barcode_then_sticker" | "sticker_then_barcode";
type DialogState =
  | { status: "closed" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: PrintSetupResponse; saved: boolean; saving: boolean; saveError: boolean };

export function PrintSetupDialog({ shopId, orderCount }: { shopId: number; orderCount: number }) {
  const [state, setState] = useState<DialogState>({ status: "closed" });
  const [pageOrder, setPageOrder] = useState<PageOrder>("barcode_then_sticker");
  const [copies, setCopies] = useState("1");

  const close = () => setState({ status: "closed" });

  useEffect(() => {
    if (state.status === "closed") return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && (state.status !== "ready" || !state.saving)) {
        close();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [state]);

  const open = async () => {
    setState({ status: "loading" });
    try {
      const response = await commands.printing.setup({ shopId });
      if (!isPrintSetup(response, shopId)) {
        setState({ status: "error" });
        return;
      }
      setPageOrder(response.pageOrder);
      setCopies(String(response.barcodeCopies));
      setState({ status: "ready", data: response, saved: false, saving: false, saveError: false });
    } catch {
      setState({ status: "error" });
    }
  };

  const parsedCopies = Number(copies);
  const validCopies = Number.isInteger(parsedCopies) && parsedCopies >= 1 && parsedCopies <= 100;
  const pageCount = validCopies ? orderCount * (parsedCopies + 1) : 0;

  const save = async () => {
    if (state.status !== "ready" || !validCopies || state.saving) return;
    setState({ ...state, saved: false, saving: true, saveError: false });
    try {
      const response = await commands.printing.saveOptions({
        shopId,
        pageOrder,
        barcodeCopies: parsedCopies,
      });
      if (!isPrintSetup(response, shopId)) {
        setState({ status: "error" });
        return;
      }
      setPageOrder(response.pageOrder);
      setCopies(String(response.barcodeCopies));
      setState({ status: "ready", data: response, saved: true, saving: false, saveError: false });
    } catch {
      setState({ ...state, saved: false, saving: false, saveError: true });
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
        Настроить печать
      </button>

      {state.status !== "closed" && (
        <div className="fixed inset-0 z-50 grid place-items-center bg-[#10231b]/45 p-4 backdrop-blur-[2px]" onMouseDown={(event) => {
          if (event.target === event.currentTarget && (state.status !== "ready" || !state.saving)) close();
        }}>
          <section
            aria-labelledby="print-setup-title"
            aria-modal="true"
            className="max-h-[calc(100vh-2rem)] w-full max-w-2xl overflow-y-auto rounded-3xl border border-white/60 bg-[var(--surface-elevated)] shadow-2xl"
            role="dialog"
          >
            <header className="flex items-start justify-between border-b border-[var(--border-subtle)] px-6 py-5">
              <div>
                <p className="mb-1 text-xs font-semibold tracking-[0.12em] text-[var(--accent-strong)] uppercase">PDF и этикетки</p>
                <h3 className="text-xl font-semibold tracking-[-0.025em]" id="print-setup-title">Настройка печати</h3>
                <p className="mt-1 text-sm text-[var(--text-secondary)]">Проверьте макет и порядок страниц перед созданием файлов.</p>
              </div>
              <button autoFocus className="grid size-9 place-items-center rounded-xl text-[var(--text-muted)] transition hover:bg-[var(--surface-muted)] hover:text-[var(--text-primary)] disabled:cursor-wait disabled:opacity-50" disabled={state.status === "ready" && state.saving} onClick={close} type="button" aria-label="Закрыть настройку печати">
                <X aria-hidden="true" size={19} />
              </button>
            </header>

            {state.status === "loading" && (
              <div className="grid min-h-80 place-items-center p-8" role="status">
                <div className="text-center">
                  <Printer className="mx-auto mb-3 animate-pulse text-[var(--accent-strong)]" aria-hidden="true" size={28} />
                  <p className="font-semibold">Загружаем настройки печати…</p>
                </div>
              </div>
            )}

            {state.status === "error" && (
              <div className="grid min-h-80 place-items-center p-8 text-center" role="alert">
                <div>
                  <h4 className="font-semibold">Не удалось загрузить настройки</h4>
                  <p className="mt-2 text-sm text-[var(--text-secondary)]">Локальные данные не изменены. Повторите запрос.</p>
                  <button className="mt-4 rounded-xl bg-[var(--sidebar)] px-4 py-2.5 text-sm font-semibold text-white" onClick={() => void open()} type="button">Повторить</button>
                </div>
              </div>
            )}

            {state.status === "ready" && (
              <div className="grid gap-5 p-6">
                <div className="grid gap-3 sm:grid-cols-2">
                  <SummaryCard icon={<FileText size={18} />} label="Активный шаблон" value={defaultTemplate(state.data)?.name ?? "Основной шаблон"} detail={`${state.data.pageWidthMm} × ${state.data.pageHeightMm} мм`} />
                  <SummaryCard icon={<Layers3 size={18} />} label="Объём задания" value={`${orderCount.toLocaleString("ru-RU")} заказов`} detail={`${pageCount.toLocaleString("ru-RU")} страниц PDF`} />
                </div>

                <fieldset>
                  <legend className="mb-3 text-sm font-semibold">Порядок страниц</legend>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <PageOrderOption
                      checked={pageOrder === "barcode_then_sticker"}
                      description="Сначала товарная этикетка, затем стикер задания WB."
                      disabled={state.saving}
                      label="Этикетка, затем стикер WB"
                      onChange={() => { setPageOrder("barcode_then_sticker"); setState({ ...state, saved: false, saveError: false }); }}
                    />
                    <PageOrderOption
                      checked={pageOrder === "sticker_then_barcode"}
                      description="Сначала стикер задания WB, затем товарная этикетка."
                      disabled={state.saving}
                      label="Стикер WB, затем этикетка"
                      onChange={() => { setPageOrder("sticker_then_barcode"); setState({ ...state, saved: false, saveError: false }); }}
                    />
                  </div>
                </fieldset>

                <label className="grid gap-2 sm:max-w-xs">
                  <span className="text-sm font-semibold">Копий этикетки</span>
                  <input
                    aria-label="Копий этикетки"
                    className="h-11 rounded-xl border border-[var(--border-strong)] bg-white px-3 text-sm shadow-[var(--shadow-control)] outline-none focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
                    disabled={state.saving}
                    max={100}
                    min={1}
                    onChange={(event) => { setCopies(event.target.value); setState({ ...state, saved: false, saveError: false }); }}
                    type="number"
                    value={copies}
                  />
                  <span className={`text-xs ${validCopies ? "text-[var(--text-muted)]" : "font-semibold text-red-700"}`}>
                    {validCopies ? "От 1 до 100 копий на один заказ." : "Введите целое число от 1 до 100."}
                  </span>
                </label>

                <div className="flex flex-col-reverse gap-3 border-t border-[var(--border-subtle)] pt-5 sm:flex-row sm:items-center sm:justify-between">
                  <div aria-live="polite" className={`min-h-5 text-sm font-semibold ${state.saveError ? "text-red-700" : "text-emerald-700"}`}>
                    {state.saved && <span className="inline-flex items-center gap-2"><CheckCircle2 aria-hidden="true" size={16} />Настройки сохранены</span>}
                    {state.saveError && "Не удалось сохранить. Проверьте настройки и повторите."}
                  </div>
                  <button
                    className="inline-flex h-11 items-center justify-center rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#1c3329] disabled:cursor-not-allowed disabled:opacity-50"
                    disabled={!validCopies || state.saving}
                    onClick={() => void save()}
                    type="button"
                  >
                    {state.saving ? "Сохраняем…" : "Сохранить настройки"}
                  </button>
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  );
}

function SummaryCard({ icon, label, value, detail }: { icon: React.ReactNode; label: string; value: string; detail: string }) {
  return (
    <div className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
      <div className="mb-3 text-[var(--accent-strong)]" aria-hidden="true">{icon}</div>
      <p className="text-xs font-semibold text-[var(--text-secondary)]">{label}</p>
      <p className="mt-1 font-semibold">{value}</p>
      <p className="mt-1 text-xs text-[var(--text-muted)]">{detail}</p>
    </div>
  );
}

function PageOrderOption({ checked, disabled, label, description, onChange }: { checked: boolean; disabled: boolean; label: string; description: string; onChange: () => void }) {
  return (
    <label className={`rounded-2xl border p-4 transition ${disabled ? "cursor-wait opacity-60" : "cursor-pointer"} ${checked ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-[var(--border-subtle)] hover:border-[var(--border-strong)]"}`}>
      <span className="flex items-start gap-3">
        <input aria-label={label} className="mt-0.5 size-4 accent-[var(--accent-strong)]" disabled={disabled} type="radio" checked={checked} onChange={onChange} name="print-page-order" />
        <span>
          <span className="block text-sm font-semibold">{label}</span>
          <span className="mt-1 block text-xs leading-5 text-[var(--text-secondary)]">{description}</span>
        </span>
      </span>
    </label>
  );
}

function defaultTemplate(response: PrintSetupResponse) {
  return response.templates.find((template) => template.id === response.defaultTemplateId && template.defaultTemplate);
}

function isPrintSetup(value: unknown, shopId: number): value is PrintSetupResponse & { pageOrder: PageOrder } {
  if (!value || typeof value !== "object") return false;
  const setup = value as PrintSetupResponse;
  const validOrder = setup.pageOrder === "barcode_then_sticker" || setup.pageOrder === "sticker_then_barcode";
  const validTemplates = Array.isArray(setup.templates)
    && setup.templates.length >= 1
    && setup.templates.length <= 100
    && setup.templates.every((template) => Number.isInteger(template.id)
      && template.id > 0
      && typeof template.name === "string"
      && template.name.length > 0
      && template.name.length <= 120
      && !hasControlCharacters(template.name)
      && typeof template.defaultTemplate === "boolean");
  return setup.shopId === shopId
    && validOrder
    && Number.isInteger(setup.barcodeCopies)
    && setup.barcodeCopies >= 1
    && setup.barcodeCopies <= 100
    && Number.isInteger(setup.defaultTemplateId)
    && setup.defaultTemplateId > 0
    && Number.isFinite(setup.pageWidthMm)
    && setup.pageWidthMm > 0
    && setup.pageWidthMm <= 1_000
    && Number.isFinite(setup.pageHeightMm)
    && setup.pageHeightMm > 0
    && setup.pageHeightMm <= 1_000
    && validTemplates
    && setup.templates.filter((template) => template.defaultTemplate).length === 1
    && defaultTemplate(setup) !== undefined;
}

function hasControlCharacters(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint < 32 || codePoint === 127;
  });
}
