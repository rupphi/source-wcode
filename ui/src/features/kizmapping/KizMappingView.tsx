import {
  AlertCircle,
  Boxes,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleDot,
  Layers3,
  Link2,
  LoaderCircle,
  PackageCheck,
  Search,
  ShieldCheck,
  Tags,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { commands } from "../../generated/commands";
import type {
  CatalogResponse,
  EditorResponse,
  GenderOption,
  GtinItem,
  SelectionRequest,
  SubjectOption,
} from "../../generated/types";
import { matchesCatalogResponse } from "./kizCatalogContract";

type CatalogState =
  | { status: "loading"; requestKey: string }
  | { status: "error"; requestKey: string }
  | { status: "ready"; requestKey: string; data: CatalogResponse };

type EditorState =
  | { status: "closed" }
  | { status: "loading"; gtin: string }
  | { status: "error"; gtin: string }
  | {
      status: "ready";
      data: EditorResponse;
      draft: Map<string, RuleDraft>;
      activeSubject: string | null;
      saving: boolean;
      saveError: boolean;
    };

type RuleDraft = {
  wildcard: boolean;
  genders: Set<string>;
};

const PAGE_SIZE = 50;
const PAGE_LIMIT = 100_000;
const MAX_CATEGORY_FILTERS = 30;
const UNSPECIFIED_GENDER = "__UNSPECIFIED__";
const numberFormat = new Intl.NumberFormat("ru-RU");

export function KizMappingView({ shopId }: { shopId: number }) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [categoriesOpen, setCategoriesOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [retryKey, setRetryKey] = useState(0);
  const [catalog, setCatalog] = useState<CatalogState>({ status: "loading", requestKey: "" });
  const [editor, setEditor] = useState<EditorState>({ status: "closed" });
  const [savedNotice, setSavedNotice] = useState(false);
  const catalogSequence = useRef(0);
  const editorSequence = useRef(0);
  const requestKey = JSON.stringify([shopId, query, categories, page, retryKey]);

  useEffect(() => {
    const requestId = ++catalogSequence.current;
    let active = true;
    void commands.kizMapping.catalog({
      shopId,
      query,
      categories,
      page,
      pageSize: PAGE_SIZE,
    }).then(
      (response) => {
        if (!active || catalogSequence.current !== requestId) return;
        if (!matchesCatalogResponse(response, shopId, query, categories, page, PAGE_SIZE)) {
          setCatalog({ status: "error", requestKey });
          return;
        }
        setCatalog({ status: "ready", requestKey, data: response });
      },
      () => {
        if (active && catalogSequence.current === requestId) {
          setCatalog({ status: "error", requestKey });
        }
      },
    );
    return () => {
      active = false;
    };
  }, [categories, page, query, requestKey, shopId]);

  const visibleCatalog: CatalogState = catalog.requestKey === requestKey
    ? catalog
    : { status: "loading", requestKey };

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next = draftQuery.trim();
    setPage(1);
    if (next === query) setRetryKey((value) => value + 1);
    else setQuery(next);
  };

  const toggleCategory = (category: string) => {
    setPage(1);
    setCategories((current) => {
      if (current.includes(category)) return current.filter((value) => value !== category);
      return current.length >= MAX_CATEGORY_FILTERS ? current : [...current, category];
    });
  };

  const clearFilters = () => {
    setDraftQuery("");
    setQuery("");
    setCategories([]);
    setPage(1);
    setCategoriesOpen(false);
    if (!query && categories.length === 0 && page === 1) {
      setRetryKey((value) => value + 1);
    }
  };

  const openEditor = (gtin: string) => {
    const requestId = ++editorSequence.current;
    setSavedNotice(false);
    setEditor({ status: "loading", gtin });
    void commands.kizMapping.editor({ shopId, gtin }).then(
      (response) => {
        if (editorSequence.current !== requestId) return;
        if (!matchesEditor(response, shopId, gtin)) {
          setEditor({ status: "error", gtin });
          return;
        }
        setEditor({
          status: "ready",
          data: response,
          draft: draftFrom(response),
          activeSubject: response.subjects.find((subject) => subject.selected)?.subjectName
            ?? response.subjects[0]?.subjectName
            ?? null,
          saving: false,
          saveError: false,
        });
      },
      () => {
        if (editorSequence.current === requestId) setEditor({ status: "error", gtin });
      },
    );
  };

  const closeEditor = () => {
    editorSequence.current += 1;
    setEditor({ status: "closed" });
  };

  const saveEditor = async () => {
    if (editor.status !== "ready" || editor.saving) return;
    const current = editor;
    const selections = flattenDraft(current.data, current.draft);
    setEditor({ ...current, saving: true, saveError: false });
    try {
      const response = await commands.kizMapping.save({ shopId, gtin: current.data.gtin, selections });
      if (!matchesEditor(response, shopId, current.data.gtin)) {
        setEditor({ ...current, saving: false, saveError: true });
        return;
      }
      setEditor({ status: "closed" });
      setSavedNotice(true);
      setRetryKey((value) => value + 1);
    } catch {
      setEditor((value) => value.status === "ready" && value.data.gtin === current.data.gtin
        ? { ...value, saving: false, saveError: true }
        : value);
    }
  };

  return (
    <div className="grid gap-5">
      <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
        <div className="flex flex-col gap-4 bg-[linear-gradient(120deg,var(--surface-elevated),#eef9f3)] p-5 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--sidebar)] text-white">
              <Link2 aria-hidden="true" size={20} />
            </span>
            <div>
              <h3 className="font-semibold tracking-[-0.01em]">Соответствия SKU и GTIN</h3>
              <p className="mt-1 max-w-2xl text-sm leading-5 text-[var(--text-secondary)]">
                Свяжите категории и значения пола из локального каталога Wildberries с GTIN для точного подбора KIZ при печати.
              </p>
            </div>
          </div>
          <span className="inline-flex w-fit items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800">
            <ShieldCheck aria-hidden="true" size={15} />
            Изменяются только локальные правила WCode
          </span>
        </div>
      </section>

      {savedNotice ? (
        <div className="flex items-center justify-between gap-3 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900" role="status">
          <span className="flex items-center gap-2 font-medium">
            <CheckCircle2 aria-hidden="true" size={18} />
            Соответствие GTIN сохранено
          </span>
          <button className="rounded-lg p-1 text-emerald-800 hover:bg-emerald-100" type="button" aria-label="Закрыть уведомление" onClick={() => setSavedNotice(false)}>
            <X aria-hidden="true" size={17} />
          </button>
        </div>
      ) : null}

      <section className="rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4 shadow-[var(--shadow-panel)] md:p-5">
        <form className="flex flex-col gap-3 lg:flex-row" role="search" onSubmit={submitSearch}>
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Поиск GTIN</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              aria-label="Поиск GTIN"
              className="h-11 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-4 pl-10 text-sm shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder="GTIN, название товара или категория"
              type="search"
              value={draftQuery}
            />
          </label>
          <button className="h-11 rounded-xl bg-[var(--sidebar)] px-5 text-sm font-semibold text-white transition hover:bg-[#203b30]" type="submit" aria-label="Найти GTIN">
            Найти
          </button>
          <div className="relative">
            <button
              aria-expanded={categoriesOpen}
              aria-label="Категории GTIN"
              className="flex h-11 w-full items-center justify-between gap-2 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-medium text-[var(--text-primary)] shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] lg:w-auto"
              onClick={() => setCategoriesOpen((value) => !value)}
              type="button"
            >
              <span className="flex items-center gap-2">
                <Tags aria-hidden="true" size={17} />
                {categories.length === 0 ? "Категории" : `Выбрано: ${categories.length}`}
              </span>
              <ChevronDown aria-hidden="true" size={16} />
            </button>
            {categoriesOpen ? (
              <div className="absolute right-0 z-20 mt-2 max-h-72 w-full min-w-64 overflow-auto rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-2 shadow-xl lg:w-72">
                {visibleCatalog.status === "ready" && visibleCatalog.data.availableCategories.length > 0 ? (
                  visibleCatalog.data.availableCategories.map((category) => (
                    <label className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-sm hover:bg-[var(--surface-muted)]" key={category}>
                      <input checked={categories.includes(category)} disabled={!categories.includes(category) && categories.length >= MAX_CATEGORY_FILTERS} onChange={() => toggleCategory(category)} type="checkbox" />
                      <span className="min-w-0 truncate">{category}</span>
                    </label>
                  ))
                ) : (
                  <p className="px-3 py-2 text-sm text-[var(--text-muted)]">Категорий пока нет</p>
                )}
              </div>
            ) : null}
          </div>
          {(query || categories.length > 0) ? (
            <button className="h-11 rounded-xl px-3 text-sm font-medium text-[var(--accent-strong)] hover:bg-[var(--accent-soft)]" onClick={clearFilters} type="button">
              Сбросить
            </button>
          ) : null}
        </form>
        {categories.length > 0 ? (
          <div className="mt-3 flex flex-wrap gap-2">
            {categories.map((category) => (
              <button className="inline-flex items-center gap-1.5 rounded-full bg-[var(--accent-soft)] px-3 py-1 text-xs font-semibold text-[var(--accent-strong)]" key={category} onClick={() => toggleCategory(category)} type="button">
                {category}
                <X aria-hidden="true" size={13} />
              </button>
            ))}
          </div>
        ) : null}
      </section>

      {editor.status === "error" ? (
        <section className="flex flex-col items-start gap-3 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-rose-900 shadow-[var(--shadow-panel)] sm:flex-row sm:items-center sm:justify-between" role="alert">
          <span className="flex items-center gap-2 text-sm font-medium">
            <AlertCircle aria-hidden="true" size={18} />
            Не удалось открыть редактор соответствий
          </span>
          <button className="rounded-lg border border-[var(--danger)]/35 bg-[var(--surface-elevated)] px-3 py-2 text-sm font-semibold text-[var(--danger)] hover:bg-[var(--danger-soft)]" onClick={() => openEditor(editor.gtin)} type="button" aria-label="Повторить открытие редактора">
            Повторить
          </button>
        </section>
      ) : null}

      <CatalogContent
        state={visibleCatalog}
        filtered={Boolean(query || categories.length > 0)}
        onRetry={() => setRetryKey((value) => value + 1)}
        onOpenEditor={openEditor}
        onPrevious={() => setPage((value) => Math.max(1, value - 1))}
        onNext={() => setPage((value) => Math.min(PAGE_LIMIT, value + 1))}
      />

      {editor.status === "loading" ? <EditorLoading gtin={editor.gtin} onClose={closeEditor} /> : null}
      {editor.status === "ready" ? (
        <MappingEditor
          state={editor}
          onChange={setEditor}
          onClose={closeEditor}
          onSave={() => void saveEditor()}
        />
      ) : null}
    </div>
  );
}

function CatalogContent({
  state,
  filtered,
  onRetry,
  onOpenEditor,
  onPrevious,
  onNext,
}: {
  state: CatalogState;
  filtered: boolean;
  onRetry: () => void;
  onOpenEditor: (gtin: string) => void;
  onPrevious: () => void;
  onNext: () => void;
}) {
  if (state.status === "loading") {
    return (
      <section className="grid min-h-72 place-items-center rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]" aria-label="Загрузка каталога GTIN">
        <div className="grid justify-items-center gap-3 text-sm text-[var(--text-secondary)]">
          <LoaderCircle className="animate-spin text-[var(--accent-strong)]" aria-hidden="true" size={28} />
          Загружаем локальный каталог GTIN…
        </div>
      </section>
    );
  }
  if (state.status === "error") {
    return (
      <section className="grid min-h-72 place-items-center rounded-2xl border border-rose-200 bg-rose-50 p-6 text-center shadow-[var(--shadow-panel)]" role="alert">
        <div className="grid max-w-md justify-items-center gap-3">
          <span className="grid size-11 place-items-center rounded-full bg-rose-100 text-rose-700"><AlertCircle aria-hidden="true" size={22} /></span>
          <div>
            <h3 className="font-semibold text-rose-950">Не удалось загрузить каталог GTIN</h3>
            <p className="mt-1 text-sm leading-5 text-rose-800">Локальные данные не изменены. Повторите запрос.</p>
          </div>
          <button className="rounded-xl bg-rose-900 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-800" onClick={onRetry} type="button">
            Повторить
          </button>
        </div>
      </section>
    );
  }
  if (state.data.items.length === 0) {
    return (
      <section className="grid min-h-72 place-items-center rounded-2xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-elevated)] p-6 text-center shadow-[var(--shadow-panel)]">
        <div className="grid max-w-lg justify-items-center gap-3">
          <span className="grid size-12 place-items-center rounded-2xl bg-[var(--surface-muted)] text-[var(--text-secondary)]"><Boxes aria-hidden="true" size={23} /></span>
          <div>
            <h3 className="font-semibold">{filtered ? "GTIN по фильтрам не найдены" : "Каталог GTIN пока пуст"}</h3>
            <p className="mt-1 text-sm leading-5 text-[var(--text-secondary)]">
              {filtered ? "Измените запрос или сбросьте категории." : "Синхронизация товаров Znack появится в следующем шаге миграции."}
            </p>
          </div>
        </div>
      </section>
    );
  }

  const totals = state.data.items.reduce((result, item) => ({
    available: result.available + item.available,
    mapped: result.mapped + (item.mappingRuleCount > 0 ? 1 : 0),
  }), { available: 0, mapped: 0 });
  return (
    <section className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="flex flex-col gap-3 border-b border-[var(--border-subtle)] px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h3 className="font-semibold">Локальный каталог GTIN</h3>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">На странице {state.data.items.length} · с правилами {totals.mapped}</p>
        </div>
        <span className="inline-flex w-fit items-center gap-2 rounded-full bg-[var(--accent-soft)] px-3 py-1.5 text-xs font-semibold text-[var(--accent-strong)]">
          <PackageCheck aria-hidden="true" size={15} />
          {numberFormat.format(totals.available)} KIZ доступно
        </span>
      </div>
      <div className="divide-y divide-[var(--border-subtle)]">
        {state.data.items.map((item) => <GtinRow item={item} key={item.gtin} onEdit={() => onOpenEditor(item.gtin)} />)}
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-[var(--border-subtle)] bg-[var(--surface-muted)]/55 px-4 py-3">
        <button className="inline-flex items-center gap-2 rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-3 py-2 text-sm font-medium disabled:cursor-default disabled:opacity-40" disabled={state.data.page <= 1} onClick={onPrevious} type="button" aria-label="Предыдущая страница GTIN">
          <ChevronLeft aria-hidden="true" size={16} />
          Назад
        </button>
        <span className="text-sm font-semibold text-[var(--text-secondary)]">Страница {state.data.page}</span>
        <button className="inline-flex items-center gap-2 rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-3 py-2 text-sm font-medium disabled:cursor-default disabled:opacity-40" disabled={!state.data.hasMore} onClick={onNext} type="button" aria-label="Следующая страница GTIN">
          Далее
          <ChevronRight aria-hidden="true" size={16} />
        </button>
      </div>
    </section>
  );
}

function GtinRow({ item, onEdit }: { item: GtinItem; onEdit: () => void }) {
  const status = statusLabel(item.pipelineStage || item.orderStatus);
  return (
    <article className="grid gap-4 px-4 py-4 transition hover:bg-[var(--surface-muted)] xl:grid-cols-[minmax(14rem,1.25fr)_minmax(17rem,1fr)_minmax(12rem,.8fr)_auto] xl:items-center xl:px-5">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <code className="rounded-md bg-[var(--sidebar)] px-2 py-1 text-xs font-semibold text-white">{item.gtin}</code>
          {item.category ? <span className="rounded-full bg-[var(--surface-muted)] px-2.5 py-1 text-xs font-medium text-[var(--text-secondary)]">{item.category}</span> : null}
        </div>
        <h4 className="mt-2 truncate font-semibold tracking-[-0.01em]">{item.productName || "Без названия"}</h4>
        <p className="mt-1 text-xs text-[var(--text-muted)]">Обновлено: {formatDate(item.syncedAt)}</p>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <InventoryMetric tone="green" value={item.available} label="доступно" />
        <InventoryMetric tone="amber" value={item.reserved} label="в резерве" />
        <InventoryMetric tone="gray" value={item.consumed} label="использовано" />
      </div>
      <div className="grid gap-2">
        <span className={`inline-flex w-fit items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${item.mappingRuleCount > 0 ? "bg-emerald-50 text-emerald-800" : "bg-slate-100 text-slate-600"}`}>
          {item.mappingRuleCount > 0 ? <Check aria-hidden="true" size={13} /> : <CircleDot aria-hidden="true" size={13} />}
          {item.mappingRuleCount > 0 ? `${item.mappingRuleCount} правил` : "Не сопоставлен"}
        </span>
        {status ? <span className="text-xs font-medium text-[var(--text-secondary)]">{status}</span> : null}
        {item.errorMessage ? <p className="line-clamp-2 text-xs leading-4 text-rose-700" title={item.errorMessage}>{item.errorMessage}</p> : null}
      </div>
      <button className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] hover:bg-[var(--accent-soft)]" onClick={onEdit} type="button" aria-label={`Настроить соответствие для ${item.gtin}`}>
        <Layers3 aria-hidden="true" size={17} />
        Настроить
      </button>
    </article>
  );
}

function InventoryMetric({ value, label, tone }: { value: number; label: string; tone: "green" | "amber" | "gray" }) {
  const tones = {
    green: "bg-emerald-50 text-emerald-900",
    amber: "bg-amber-50 text-amber-900",
    gray: "bg-slate-100 text-slate-700",
  };
  return (
    <div className={`rounded-xl px-2 py-2.5 text-center ${tones[tone]}`}>
      <strong className="block text-sm">{numberFormat.format(value)} {label === "доступно" ? label : ""}</strong>
      {label !== "доступно" ? <span className="mt-0.5 block text-[0.65rem] leading-3 opacity-70">{label}</span> : null}
    </div>
  );
}

function EditorLoading({ gtin, onClose }: { gtin: string; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-[#0b1712]/55 p-4 backdrop-blur-[2px]">
      <section className="relative grid min-h-56 w-full max-w-xl place-items-center rounded-2xl bg-[var(--surface-elevated)] p-8 shadow-2xl" role="dialog" aria-label={`Соответствие GTIN ${gtin}`} aria-modal="true">
        <button className="absolute top-4 right-4 rounded-lg p-2 text-[var(--text-muted)] hover:bg-[var(--surface-muted)]" onClick={onClose} type="button" aria-label="Закрыть редактор"><X aria-hidden="true" size={18} /></button>
        <div className="grid justify-items-center gap-3 text-sm text-[var(--text-secondary)]">
          <LoaderCircle className="animate-spin text-[var(--accent-strong)]" aria-hidden="true" size={30} />
          Загружаем правила и владельцев…
        </div>
      </section>
    </div>
  );
}

function MappingEditor({
  state,
  onChange,
  onClose,
  onSave,
}: {
  state: Extract<EditorState, { status: "ready" }>;
  onChange: (state: EditorState) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  const dialogRef = useRef<HTMLElement>(null);
  const active = state.data.subjects.find((subject) => subject.subjectName === state.activeSubject) ?? null;
  const selectedCount = state.draft.size;
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const bodyOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    dialogRef.current?.focus();
    return () => {
      document.body.style.overflow = bodyOverflow;
      previous?.focus();
    };
  }, []);
  const updateDraft = (draft: Map<string, RuleDraft>, activeSubject = state.activeSubject) => {
    onChange({ ...state, draft, activeSubject, saveError: false });
  };

  const toggleSubject = (subject: SubjectOption) => {
    const next = cloneDraft(state.draft);
    if (next.has(subject.subjectName)) {
      next.delete(subject.subjectName);
    } else {
      const free = subject.genders.filter((gender) => !foreignOwner(gender.ownerGtin, state.data.gtin));
      const hasForeign = subject.genders.some((gender) => foreignOwner(gender.ownerGtin, state.data.gtin))
        || foreignOwner(subject.wildcardOwnerGtin, state.data.gtin);
      if (!hasForeign) next.set(subject.subjectName, { wildcard: true, genders: new Set() });
      else if (free.length > 0) next.set(subject.subjectName, { wildcard: false, genders: new Set(free.map((gender) => gender.value)) });
    }
    updateDraft(next, subject.subjectName);
  };

  const toggleWildcard = (subject: SubjectOption) => {
    const next = cloneDraft(state.draft);
    const current = next.get(subject.subjectName);
    if (!current) return;
    if (current.wildcard) {
      const free = subject.genders.filter((gender) => !foreignOwner(gender.ownerGtin, state.data.gtin));
      if (free.length === 0) next.delete(subject.subjectName);
      else next.set(subject.subjectName, { wildcard: false, genders: new Set(free.map((gender) => gender.value)) });
    } else {
      next.set(subject.subjectName, { wildcard: true, genders: new Set() });
    }
    updateDraft(next);
  };

  const toggleGender = (subject: SubjectOption, gender: GenderOption) => {
    const next = cloneDraft(state.draft);
    const current = next.get(subject.subjectName);
    if (!current) return;
    const genders = current.wildcard
      ? new Set(subject.genders
          .filter((candidate) => !foreignOwner(candidate.ownerGtin, state.data.gtin))
          .map((candidate) => candidate.value))
      : new Set(current.genders);
    if (genders.has(gender.value)) genders.delete(gender.value);
    else genders.add(gender.value);
    if (genders.size === 0) next.delete(subject.subjectName);
    else next.set(subject.subjectName, { wildcard: false, genders });
    updateDraft(next);
  };

  const removeSubject = (subjectName: string) => {
    const next = cloneDraft(state.draft);
    next.delete(subjectName);
    updateDraft(next);
  };

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-[#0b1712]/60 p-3 backdrop-blur-[2px] sm:p-6">
      <section className="mx-auto flex min-h-[calc(100vh-1.5rem)] w-full max-w-[82rem] flex-col overflow-hidden rounded-2xl bg-[var(--surface-elevated)] shadow-2xl sm:min-h-0 sm:max-h-[calc(100vh-3rem)]" role="dialog" aria-label={`Соответствие GTIN ${state.data.gtin}`} aria-modal="true" onKeyDown={(event) => {
        if (event.key === "Escape" && !state.saving) onClose();
      }} ref={dialogRef} tabIndex={-1}>
        <header className="flex items-start justify-between gap-4 border-b border-[var(--border-subtle)] bg-[linear-gradient(120deg,var(--surface-elevated),var(--accent-soft))] px-5 py-4 sm:px-6">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-[0.12em] text-[var(--accent-strong)] uppercase">Редактор соответствий</p>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-xl font-semibold tracking-[-0.025em]">Категории → GTIN</h3>
              <code className="rounded-md bg-[var(--sidebar)] px-2 py-1 text-xs font-semibold text-white">{state.data.gtin}</code>
            </div>
            <p className="mt-1 text-sm text-[var(--text-secondary)]">Один вариант категории и пола может принадлежать только одному GTIN.</p>
          </div>
          <button className="shrink-0 rounded-xl p-2 text-[var(--text-muted)] hover:bg-[var(--surface-muted)]" disabled={state.saving} onClick={onClose} type="button" aria-label="Закрыть редактор"><X aria-hidden="true" size={20} /></button>
        </header>

        <div className="grid min-h-0 flex-1 md:grid-cols-[minmax(14rem,.8fr)_minmax(16rem,1fr)_minmax(16rem,1fr)]">
          <EditorColumn title="Категории WB" subtitle={`${state.data.subjects.length} доступно`}>
            <div className="grid gap-1.5">
              {state.data.subjects.map((subject) => {
                const rule = state.draft.get(subject.subjectName);
                const owners = foreignOwners(subject, state.data.gtin);
                const blocked = fullyBlocked(subject, state.data.gtin);
                const ownerSuffix = blocked && owners[0] ? ` · занято ${owners[0]}` : "";
                return (
                  <div className={`flex items-center gap-2 rounded-xl border px-2 py-1.5 transition ${state.activeSubject === subject.subjectName ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-transparent hover:bg-[var(--surface-muted)]"}`} key={subject.subjectName}>
                    <input
                      aria-label={`Использовать категорию ${subject.subjectName}${ownerSuffix}`}
                      checked={Boolean(rule)}
                      disabled={blocked || state.saving}
                      onChange={() => toggleSubject(subject)}
                      type="checkbox"
                    />
                    <button className="min-w-0 flex-1 py-1 text-left" onClick={() => onChange({ ...state, activeSubject: subject.subjectName })} type="button" aria-label={`Выбрать категорию ${subject.subjectName}`}>
                      <span className="block truncate text-sm font-medium">{subject.subjectName}</span>
                      <span className="mt-0.5 block truncate text-[0.68rem] text-[var(--text-muted)]">{ruleLabel(rule, owners)}</span>
                    </button>
                  </div>
                );
              })}
            </div>
          </EditorColumn>

          <EditorColumn title="Значения пола" subtitle={active?.subjectName ?? "Выберите категорию"} accent>
            {active ? (
              <GenderEditor
                gtin={state.data.gtin}
                subject={active}
                rule={state.draft.get(active.subjectName)}
                saving={state.saving}
                onToggleSubject={() => toggleSubject(active)}
                onToggleWildcard={() => toggleWildcard(active)}
                onToggleGender={(gender) => toggleGender(active, gender)}
              />
            ) : (
              <EditorHint icon={<Tags aria-hidden="true" size={22} />} text="В локальном каталоге нет категорий для настройки." />
            )}
          </EditorColumn>

          <EditorColumn title="Выбранные правила" subtitle={`${selectedCount} категорий`}>
            {selectedCount === 0 ? (
              <EditorHint icon={<Layers3 aria-hidden="true" size={22} />} text="Выберите категории и значения пола. Пустой список очистит соответствие этого GTIN." />
            ) : (
              <div className="grid gap-2">
                {state.data.subjects.filter((subject) => state.draft.has(subject.subjectName)).map((subject) => {
                  const rule = state.draft.get(subject.subjectName);
                  if (!rule) return null;
                  return (
                    <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)]/55 p-3" key={subject.subjectName}>
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="truncate text-sm font-semibold">{subject.subjectName}</p>
                          <p className="mt-1 text-xs leading-4 text-[var(--text-secondary)]">{rule.wildcard ? "Все значения пола" : `${rule.genders.size} ${rule.genders.size === 1 ? "точное значение" : "точных значения"}`}</p>
                        </div>
                        <button className="rounded-lg p-1.5 text-[var(--text-muted)] hover:bg-[var(--surface-elevated)] hover:text-[var(--danger)]" disabled={state.saving} onClick={() => removeSubject(subject.subjectName)} type="button" aria-label={`Удалить правило ${subject.subjectName}`}><X aria-hidden="true" size={16} /></button>
                      </div>
                      {!rule.wildcard ? (
                        <div className="mt-2 flex flex-wrap gap-1.5">
                          {[...rule.genders].map((gender) => <span className="rounded-full bg-[var(--surface-elevated)] px-2 py-1 text-[0.68rem] font-medium text-[var(--text-secondary)]" key={gender}>{displayGender(gender)}</span>)}
                        </div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            )}
          </EditorColumn>
        </div>

        <footer className="flex flex-col gap-3 border-t border-[var(--border-subtle)] bg-[var(--surface-muted)] px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
          <div className="min-h-5">
            {state.saveError ? (
              <p className="flex items-center gap-2 text-sm font-medium text-rose-700" role="alert"><AlertCircle aria-hidden="true" size={17} />Не удалось сохранить соответствие</p>
            ) : (
              <p className="text-xs text-[var(--text-muted)]">Сохранение атомарно заменит правила только для этого GTIN.</p>
            )}
          </div>
          <div className="flex justify-end gap-2">
            <button className="h-10 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold hover:bg-[var(--surface-muted)] disabled:opacity-50" disabled={state.saving} onClick={onClose} type="button">Отмена</button>
            <button className="inline-flex h-10 items-center gap-2 rounded-xl bg-[var(--sidebar)] px-4 text-sm font-semibold text-white hover:bg-[#203b30] disabled:cursor-wait disabled:opacity-60" disabled={state.saving} onClick={onSave} type="button" aria-label="Сохранить соответствие">
              {state.saving ? <LoaderCircle className="animate-spin" aria-hidden="true" size={16} /> : <Check aria-hidden="true" size={16} />}
              {state.saving ? "Сохраняем…" : "Сохранить"}
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}

function EditorColumn({ title, subtitle, accent = false, children }: { title: string; subtitle: string; accent?: boolean; children: React.ReactNode }) {
  return (
    <section className={`min-h-0 overflow-y-auto border-b border-[var(--border-subtle)] p-4 md:border-r md:border-b-0 md:p-5 ${accent ? "bg-[var(--surface-muted)]" : "bg-[var(--surface-elevated)]"}`}>
      <div className="sticky top-0 z-10 mb-4 bg-[inherit] pb-2">
        <h4 className="text-sm font-semibold">{title}</h4>
        <p className="mt-0.5 truncate text-xs text-[var(--text-muted)]">{subtitle}</p>
      </div>
      {children}
    </section>
  );
}

function GenderEditor({
  gtin,
  subject,
  rule,
  saving,
  onToggleSubject,
  onToggleWildcard,
  onToggleGender,
}: {
  gtin: string;
  subject: SubjectOption;
  rule: RuleDraft | undefined;
  saving: boolean;
  onToggleSubject: () => void;
  onToggleWildcard: () => void;
  onToggleGender: (gender: GenderOption) => void;
}) {
  const owners = foreignOwners(subject, gtin);
  const wildcardBlocked = owners.length > 0;
  if (!rule) {
    return (
      <div className="grid gap-4">
        <EditorHint icon={<CircleDot aria-hidden="true" size={22} />} text={fullyBlocked(subject, gtin) ? `Все варианты уже принадлежат ${owners.join(", ")}.` : "Включите категорию, чтобы выбрать допустимые значения пола."} />
        {!fullyBlocked(subject, gtin) ? <button className="rounded-xl border border-[var(--accent)] bg-[var(--accent-soft)] px-4 py-2.5 text-sm font-semibold text-[var(--accent-strong)]" onClick={onToggleSubject} type="button">Включить категорию</button> : null}
      </div>
    );
  }
  return (
    <div className="grid gap-2">
      <label className={`flex items-start gap-3 rounded-xl border p-3 ${wildcardBlocked ? "border-[var(--border-subtle)] bg-[var(--surface-muted)] opacity-70" : "border-emerald-200 bg-emerald-50"}`}>
        <input aria-label="Все значения пола" checked={rule.wildcard} disabled={saving || wildcardBlocked} onChange={onToggleWildcard} type="checkbox" />
        <span className="min-w-0">
          <span className="block text-sm font-semibold">Все значения пола</span>
          <span className="mt-0.5 block text-xs leading-4 text-[var(--text-secondary)]">Будущие значения этой категории тоже получат этот GTIN.</span>
          {wildcardBlocked ? <span className="mt-1 block text-xs font-medium text-amber-800">Недоступно: часть вариантов занята {owners.join(", ")}</span> : null}
        </span>
      </label>
      {subject.genders.map((gender) => {
        const occupied = foreignOwner(gender.ownerGtin, gtin);
        const checked = rule.wildcard || rule.genders.has(gender.value);
        const ownerSuffix = occupied ? ` · занято ${gender.ownerGtin}` : "";
        return (
          <label className={`flex items-center gap-3 rounded-xl border px-3 py-3 ${occupied ? "border-[var(--border-subtle)] bg-[var(--surface-muted)] text-[var(--text-muted)]" : checked ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-[var(--border-subtle)] bg-[var(--surface-elevated)] hover:border-[var(--accent)]"}`} key={gender.value}>
            <input aria-label={`${displayGender(gender.value)}${ownerSuffix}`} checked={checked} disabled={saving || occupied} onChange={() => onToggleGender(gender)} type="checkbox" />
            <span className="min-w-0 flex-1 truncate text-sm font-medium">{displayGender(gender.value)}</span>
            {occupied ? <code className="text-[0.65rem]">{gender.ownerGtin}</code> : null}
          </label>
        );
      })}
      {subject.genders.length === 0 ? <p className="rounded-xl bg-[var(--surface-muted)] p-3 text-sm text-[var(--text-secondary)]">У категории нет сохранённых значений пола. Используйте правило «Все значения пола».</p> : null}
    </div>
  );
}

function EditorHint({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="grid justify-items-center gap-2 rounded-xl border border-dashed border-[var(--border-strong)] p-6 text-center text-[var(--text-muted)]">
      {icon}
      <p className="max-w-xs text-sm leading-5">{text}</p>
    </div>
  );
}

function matchesEditor(response: EditorResponse, shopId: number, gtin: string) {
  return response.shopId === shopId && response.gtin === gtin && response.subjects.length <= 500;
}

function draftFrom(response: EditorResponse) {
  const draft = new Map<string, RuleDraft>();
  for (const subject of response.subjects) {
    if (!subject.selected) continue;
    draft.set(subject.subjectName, {
      wildcard: subject.wildcardSelected,
      genders: new Set(subject.genders.filter((gender) => gender.selected).map((gender) => gender.value)),
    });
  }
  return draft;
}

function cloneDraft(current: Map<string, RuleDraft>) {
  return new Map([...current].map(([subject, rule]) => [subject, {
    wildcard: rule.wildcard,
    genders: new Set(rule.genders),
  }]));
}

function flattenDraft(response: EditorResponse, draft: Map<string, RuleDraft>): SelectionRequest[] {
  const selections: SelectionRequest[] = [];
  for (const subject of response.subjects) {
    const rule = draft.get(subject.subjectName);
    if (!rule) continue;
    if (rule.wildcard) {
      selections.push({ subjectName: subject.subjectName, genderValue: "", wildcardGender: true });
      continue;
    }
    for (const gender of subject.genders) {
      if (rule.genders.has(gender.value)) {
        selections.push({ subjectName: subject.subjectName, genderValue: gender.value, wildcardGender: false });
      }
    }
  }
  return selections;
}

function foreignOwner(owner: string, gtin: string) {
  return Boolean(owner) && owner !== gtin;
}

function foreignOwners(subject: SubjectOption, gtin: string) {
  return [...new Set([
    subject.wildcardOwnerGtin,
    ...subject.genders.map((gender) => gender.ownerGtin),
  ].filter((owner) => foreignOwner(owner, gtin)))];
}

function fullyBlocked(subject: SubjectOption, gtin: string) {
  if (foreignOwner(subject.wildcardOwnerGtin, gtin)) return true;
  return subject.genders.length > 0
    && subject.genders.every((gender) => foreignOwner(gender.ownerGtin, gtin));
}

function ruleLabel(rule: RuleDraft | undefined, owners: string[]) {
  if (rule?.wildcard) return "Все значения пола";
  if (rule) return `${rule.genders.size} выбрано`;
  if (owners.length > 0) return `Занято: ${owners.join(", ")}`;
  return "Не используется";
}

function displayGender(value: string) {
  return value === UNSPECIFIED_GENDER ? "Пол не указан" : value;
}

function statusLabel(value: string) {
  const labels: Record<string, string> = {
    VALIDATING: "Проверка",
    CREATING_ORDER: "Создание заказа",
    POLLING_ORDER: "Ожидание кодов",
    DOWNLOADING_CODES: "Загрузка кодов",
    CODES_READY: "Коды готовы",
    CODES_DOWNLOADED: "Коды загружены",
    WAITING_INTRODUCTION_READINESS: "Ожидает ввода в оборот",
    SUBMITTING_INTRODUCTION: "Отправка в оборот",
    POLLING_INTRODUCTION: "Проверка ввода",
    INTRODUCTION_FAILED: "Ошибка ввода",
    INTRODUCED: "Введено в оборот",
    COMPLETED: "Завершено",
    FAILED: "Ошибка",
    CANCELLED: "Отменено",
  };
  return labels[value] ?? "";
}

function formatDate(value: string) {
  if (!value) return "нет данных";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "нет данных"
    : new Intl.DateTimeFormat("ru-RU", { dateStyle: "short", timeStyle: "short" }).format(date);
}
