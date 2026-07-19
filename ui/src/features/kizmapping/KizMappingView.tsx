import {
  AlertCircle,
  Boxes,
  Check,
  ChevronDown,
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
import { useCallback, useMemo, useRef, useState, type FormEvent } from "react";
import { useModalFocus } from "../../components/useModalFocus";
import { Toast } from "../../components/Toast";
import { InfiniteLoadTrigger } from "../../components/InfiniteLoadTrigger";
import { useBoundedInfinitePages } from "../../components/useBoundedInfinitePages";
import { commands } from "../../generated/commands";
import type {
  CatalogResponse,
  EditorResponse,
  GenderOption,
  GtinItem,
  SelectionRequest,
  SubjectOption,
} from "../../generated/types";
import { interpolate } from "../../i18n";
import { matchesCatalogResponse } from "./kizCatalogContract";
import { defaultKizMappingCopy, formatKizCount, type KizMappingCopy } from "./kizMappingI18n";

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
const MAX_CATEGORY_FILTERS = 30;
const UNSPECIFIED_GENDER = "__UNSPECIFIED__";
export function KizMappingView({ shopId, copy = defaultKizMappingCopy, locale = "ru-RU" }: { shopId: number; copy?: KizMappingCopy; locale?: string }) {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [categoriesOpen, setCategoriesOpen] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
  const [editor, setEditor] = useState<EditorState>({ status: "closed" });
  const [savedNotice, setSavedNotice] = useState(false);
  const editorSequence = useRef(0);
  const editorTrigger = useRef<HTMLElement | null>(null);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const dateFormat = useMemo(() => new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" }), [locale]);

  const loadPage = useCallback(async (page: number) => {
    const response = await commands.kizMapping.catalog({
      shopId,
      query,
      categories,
      page,
      pageSize: PAGE_SIZE,
    });
    if (!matchesCatalogResponse(response, shopId, query, categories, page, PAGE_SIZE)) {
      throw new Error("Unexpected GTIN catalog response");
    }
    return { items: response.items, hasMore: response.hasMore, summary: response };
  }, [categories, query, shopId]);
  const pages = useBoundedInfinitePages<GtinItem, CatalogResponse>({
    resetKey: JSON.stringify([shopId, query, categories, retryKey]),
    loadPage,
    getId: (item) => item.gtin,
  });
  const visibleCatalog: CatalogState = pages.items.length === 0 && pages.status === "loading"
    ? { status: "loading", requestKey: String(pages.resetKey) }
    : pages.items.length === 0 && pages.status === "error"
      ? { status: "error", requestKey: String(pages.resetKey) }
      : pages.summary
        ? { status: "ready", requestKey: String(pages.resetKey), data: { ...pages.summary, items: [...pages.items] } }
        : { status: "loading", requestKey: String(pages.resetKey) };

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next = draftQuery.trim();
    if (next === query) setRetryKey((value) => value + 1);
    else setQuery(next);
  };

  const toggleCategory = (category: string) => {
    setCategories((current) => {
      if (current.includes(category)) return current.filter((value) => value !== category);
      return current.length >= MAX_CATEGORY_FILTERS ? current : [...current, category];
    });
  };

  const clearFilters = () => {
    setDraftQuery("");
    setQuery("");
    setCategories([]);
    setCategoriesOpen(false);
    if (!query && categories.length === 0) {
      setRetryKey((value) => value + 1);
    }
  };

  const openEditor = (gtin: string) => {
    if (editor.status === "closed" && document.activeElement instanceof HTMLElement) {
      editorTrigger.current = document.activeElement;
    }
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
    setTimeout(() => editorTrigger.current?.focus(), 0);
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
      setTimeout(() => editorTrigger.current?.focus(), 0);
    } catch {
      setEditor((value) => value.status === "ready" && value.data.gtin === current.data.gtin
        ? { ...value, saving: false, saveError: true }
        : value);
    }
  };

  return (
    <div className="grid gap-3">
      <section className="overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
        <div className="flex flex-col gap-3 bg-[var(--accent-soft)] p-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--sidebar)] text-white">
              <Link2 aria-hidden="true" size={20} />
            </span>
            <div>
              <h3 className="font-semibold tracking-[-0.01em]">{copy.header.title}</h3>
              <p className="mt-1 max-w-2xl text-xs leading-5 text-[var(--text-secondary)]">
                {copy.header.description}
              </p>
            </div>
          </div>
          <span className="inline-flex w-fit items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800">
            <ShieldCheck aria-hidden="true" size={15} />
            {copy.header.guarded}
          </span>
        </div>
      </section>

      {savedNotice ? <Toast message={copy.notice.saved} closeLabel={copy.notice.close} onDismiss={() => setSavedNotice(false)} /> : null}

      <section className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-3 shadow-[var(--shadow-panel)] md:p-4">
        <form className="flex flex-col gap-2 lg:flex-row" role="search" onSubmit={submitSearch}>
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{copy.search.label}</span>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-[var(--text-muted)]" aria-hidden="true" size={18} />
            <input
              aria-label={copy.search.label}
              className="h-9 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] pr-3 pl-10 text-xs shadow-[var(--shadow-control)] outline-none transition placeholder:text-[var(--text-muted)] hover:border-[var(--accent)] focus:border-[var(--accent)] focus:ring-3 focus:ring-[var(--accent-soft)]"
              maxLength={120}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder={copy.search.placeholder}
              type="search"
              value={draftQuery}
            />
          </label>
          <button className="h-9 rounded-lg bg-[var(--button-primary)] px-4 text-xs font-semibold text-white transition hover:brightness-110" type="submit" aria-label={copy.search.submitAria}>
            {copy.search.submit}
          </button>
          <div className="relative">
            <button
              aria-expanded={categoriesOpen}
              aria-label={copy.search.categoriesAria}
              className="flex h-9 w-full items-center justify-between gap-2 rounded-lg border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-3 text-xs font-medium text-[var(--text-primary)] shadow-[var(--shadow-control)] transition hover:border-[var(--accent)] lg:w-auto"
              onClick={() => setCategoriesOpen((value) => !value)}
              type="button"
            >
              <span className="flex items-center gap-2">
                <Tags aria-hidden="true" size={17} />
                {categories.length === 0 ? copy.search.categories : interpolate(copy.search.selected, { count: numberFormat.format(categories.length) })}
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
                  <p className="px-3 py-2 text-sm text-[var(--text-muted)]">{copy.search.none}</p>
                )}
              </div>
            ) : null}
          </div>
          {(query || categories.length > 0) ? (
            <button className="h-9 rounded-lg px-3 text-xs font-medium text-[var(--accent-strong)] hover:bg-[var(--accent-soft)]" onClick={clearFilters} type="button">
              {copy.search.clear}
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
            {copy.editorError.title}
          </span>
          <button className="rounded-lg border border-[var(--danger)]/35 bg-[var(--surface-elevated)] px-3 py-2 text-sm font-semibold text-[var(--danger)] hover:bg-[var(--danger-soft)]" onClick={() => openEditor(editor.gtin)} type="button" aria-label={copy.editorError.retryAria}>
            {copy.editorError.retry}
          </button>
        </section>
      ) : null}

      <CatalogContent
        copy={copy}
        dateFormat={dateFormat}
        numberFormat={numberFormat}
        state={visibleCatalog}
        filtered={Boolean(query || categories.length > 0)}
        onRetry={pages.retry}
        onOpenEditor={openEditor}
      />

      {pages.items.length > 0 ? (
        <InfiniteLoadTrigger
          status={pages.status}
          hasMore={pages.hasMore}
          copy={{ loading: copy.catalog.loadingMore, loadMore: copy.catalog.loadMore, loadError: copy.catalog.loadMoreError, retry: copy.catalog.retry, end: copy.catalog.end }}
          announcement={pages.addedCount > 0 ? interpolate(copy.catalog.added, { count: numberFormat.format(pages.addedCount) }) : ""}
          onLoadMore={pages.loadMore}
          onRetry={pages.retry}
        />
      ) : null}

      {editor.status === "loading" ? <EditorLoading copy={copy} gtin={editor.gtin} onClose={closeEditor} /> : null}
      {editor.status === "ready" ? (
        <MappingEditor
          copy={copy}
          locale={locale}
          numberFormat={numberFormat}
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
  copy,
  dateFormat,
  numberFormat,
  state,
  filtered,
  onRetry,
  onOpenEditor,
}: {
  copy: KizMappingCopy;
  dateFormat: Intl.DateTimeFormat;
  numberFormat: Intl.NumberFormat;
  state: CatalogState;
  filtered: boolean;
  onRetry: () => void;
  onOpenEditor: (gtin: string) => void;
}) {
  if (state.status === "loading") {
    return (
      <section className="grid min-h-72 place-items-center rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]" aria-label={copy.catalog.loadingAria}>
        <div className="grid justify-items-center gap-3 text-sm text-[var(--text-secondary)]">
          <LoaderCircle className="animate-spin text-[var(--accent-strong)]" aria-hidden="true" size={28} />
          {copy.catalog.loading}
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
            <h3 className="font-semibold text-rose-950">{copy.catalog.errorTitle}</h3>
            <p className="mt-1 text-sm leading-5 text-rose-800">{copy.catalog.errorDescription}</p>
          </div>
          <button className="rounded-xl bg-rose-900 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-800" onClick={onRetry} type="button">
            {copy.catalog.retry}
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
            <h3 className="font-semibold">{filtered ? copy.catalog.filteredEmptyTitle : copy.catalog.emptyTitle}</h3>
            <p className="mt-1 text-sm leading-5 text-[var(--text-secondary)]">
              {filtered ? copy.catalog.filteredEmptyDescription : copy.catalog.emptyDescription}
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
    <section className="overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-panel)]">
      <div className="flex flex-col gap-2 border-b border-[var(--border-subtle)] px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h3 className="font-semibold">{copy.catalog.title}</h3>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">{interpolate(copy.catalog.summary, { count: numberFormat.format(state.data.items.length), mapped: numberFormat.format(totals.mapped) })}</p>
        </div>
        <span className="inline-flex w-fit items-center gap-2 rounded-full bg-[var(--accent-soft)] px-3 py-1.5 text-xs font-semibold text-[var(--accent-strong)]">
          <PackageCheck aria-hidden="true" size={15} />
          {interpolate(copy.catalog.available, { count: numberFormat.format(totals.available) })}
        </span>
      </div>
      <div className="divide-y divide-[var(--border-subtle)]">
        {state.data.items.map((item) => <GtinRow copy={copy} dateFormat={dateFormat} item={item} key={item.gtin} numberFormat={numberFormat} onEdit={() => onOpenEditor(item.gtin)} />)}
      </div>
    </section>
  );
}

function GtinRow({ copy, dateFormat, item, numberFormat, onEdit }: { copy: KizMappingCopy; dateFormat: Intl.DateTimeFormat; item: GtinItem; numberFormat: Intl.NumberFormat; onEdit: () => void }) {
  const status = statusLabel(copy, item.pipelineStage || item.orderStatus);
  return (
    <article className="grid [content-visibility:auto] [contain-intrinsic-size:auto_9rem] gap-3 px-3 py-3 transition hover:bg-[var(--surface-muted)] xl:grid-cols-[minmax(14rem,1.25fr)_minmax(17rem,1fr)_minmax(12rem,.8fr)_auto] xl:items-center xl:px-4">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <code className="rounded-md bg-[var(--sidebar)] px-2 py-1 text-xs font-semibold text-white">{item.gtin}</code>
          {item.category ? <span className="rounded-full bg-[var(--surface-muted)] px-2.5 py-1 text-xs font-medium text-[var(--text-secondary)]">{item.category}</span> : null}
        </div>
        <h4 className="mt-2 truncate font-semibold tracking-[-0.01em]">{item.productName || copy.row.unnamed}</h4>
        <p className="mt-1 text-xs text-[var(--text-muted)]">{interpolate(copy.row.updated, { date: formatDate(copy, dateFormat, item.syncedAt) })}</p>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <InventoryMetric numberFormat={numberFormat} tone="green" value={item.available} label={copy.row.available} primary />
        <InventoryMetric numberFormat={numberFormat} tone="amber" value={item.reserved} label={copy.row.reserved} />
        <InventoryMetric numberFormat={numberFormat} tone="gray" value={item.consumed} label={copy.row.consumed} />
      </div>
      <div className="grid gap-2">
        <span className={`inline-flex w-fit items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${item.mappingRuleCount > 0 ? "bg-emerald-50 text-emerald-800" : "bg-slate-100 text-slate-600"}`}>
          {item.mappingRuleCount > 0 ? <Check aria-hidden="true" size={13} /> : <CircleDot aria-hidden="true" size={13} />}
          {item.mappingRuleCount > 0 ? formatKizCount(copy, numberFormat.resolvedOptions().locale, item.mappingRuleCount, "rules") : copy.row.unmapped}
        </span>
        {status ? <span className="text-xs font-medium text-[var(--text-secondary)]">{status}</span> : null}
        {item.errorMessage ? <p className="line-clamp-2 text-xs leading-4 text-rose-700" title={item.errorMessage}>{item.errorMessage}</p> : null}
      </div>
      <button className="icon-button justify-self-end" onClick={onEdit} type="button" title={copy.row.edit} aria-label={interpolate(copy.row.editAria, { gtin: item.gtin })}>
        <Layers3 aria-hidden="true" size={17} />
      </button>
    </article>
  );
}

function InventoryMetric({ value, label, numberFormat, tone, primary = false }: { value: number; label: string; numberFormat: Intl.NumberFormat; tone: "green" | "amber" | "gray"; primary?: boolean }) {
  const tones = {
    green: "bg-emerald-50 text-emerald-900",
    amber: "bg-amber-50 text-amber-900",
    gray: "bg-slate-100 text-slate-700",
  };
  return (
    <div className={`rounded-xl px-2 py-2.5 text-center ${tones[tone]}`}>
      <strong className="block text-sm">{numberFormat.format(value)} {primary ? label : ""}</strong>
      {!primary ? <span className="mt-0.5 block text-[0.65rem] leading-3 opacity-70">{label}</span> : null}
    </div>
  );
}

function EditorLoading({ copy, gtin, onClose }: { copy: KizMappingCopy; gtin: string; onClose: () => void }) {
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLElement>(false, onClose);
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-[#0b1712]/55 p-4 backdrop-blur-[2px]">
      <section ref={dialogRef} className="relative grid min-h-56 w-full max-w-xl place-items-center rounded-2xl bg-[var(--surface-elevated)] p-8 shadow-2xl" role="dialog" aria-label={interpolate(copy.editor.dialogAria, { gtin })} aria-modal="true">
        <button ref={initialFocusRef} className="absolute top-4 right-4 rounded-lg p-2 text-[var(--text-muted)] hover:bg-[var(--surface-muted)]" onClick={onClose} type="button" aria-label={copy.editor.close}><X aria-hidden="true" size={18} /></button>
        <div className="grid justify-items-center gap-3 text-sm text-[var(--text-secondary)]">
          <LoaderCircle className="animate-spin text-[var(--accent-strong)]" aria-hidden="true" size={30} />
          {copy.editor.loading}
        </div>
      </section>
    </div>
  );
}

function MappingEditor({
  copy,
  locale,
  numberFormat,
  state,
  onChange,
  onClose,
  onSave,
}: {
  copy: KizMappingCopy;
  locale: string;
  numberFormat: Intl.NumberFormat;
  state: Extract<EditorState, { status: "ready" }>;
  onChange: (state: EditorState) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLElement>(state.saving, onClose);
  const active = state.data.subjects.find((subject) => subject.subjectName === state.activeSubject) ?? null;
  const selectedCount = state.draft.size;
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
      <section className="mx-auto flex min-h-[calc(100vh-1.5rem)] w-full max-w-[82rem] flex-col overflow-hidden rounded-2xl bg-[var(--surface-elevated)] shadow-2xl sm:min-h-0 sm:max-h-[calc(100vh-3rem)]" role="dialog" aria-label={interpolate(copy.editor.dialogAria, { gtin: state.data.gtin })} aria-modal="true" onKeyDown={(event) => {
        if (event.key === "Escape" && !state.saving) onClose();
      }} ref={dialogRef} tabIndex={-1}>
        <header className="flex items-start justify-between gap-4 border-b border-[var(--border-subtle)] bg-[linear-gradient(120deg,var(--surface-elevated),var(--accent-soft))] px-5 py-4 sm:px-6">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-[0.12em] text-[var(--accent-strong)] uppercase">{copy.editor.eyebrow}</p>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-xl font-semibold tracking-[-0.025em]">{copy.editor.title}</h3>
              <code className="rounded-md bg-[var(--sidebar)] px-2 py-1 text-xs font-semibold text-white">{state.data.gtin}</code>
            </div>
            <p className="mt-1 text-sm text-[var(--text-secondary)]">{copy.editor.description}</p>
          </div>
          <button ref={initialFocusRef} className="shrink-0 rounded-xl p-2 text-[var(--text-muted)] hover:bg-[var(--surface-muted)]" disabled={state.saving} onClick={onClose} type="button" aria-label={copy.editor.close}><X aria-hidden="true" size={20} /></button>
        </header>

        <div className="grid min-h-0 flex-1 md:grid-cols-[minmax(14rem,.8fr)_minmax(16rem,1fr)_minmax(16rem,1fr)]">
          <EditorColumn title={copy.editor.subjectsTitle} subtitle={interpolate(copy.editor.available, { count: numberFormat.format(state.data.subjects.length) })}>
            <div className="grid gap-1.5">
              {state.data.subjects.map((subject) => {
                const rule = state.draft.get(subject.subjectName);
                const owners = foreignOwners(subject, state.data.gtin);
                const blocked = fullyBlocked(subject, state.data.gtin);
                const ownerSuffix = blocked && owners[0] ? interpolate(copy.editor.occupiedSuffix, { gtin: owners[0] }) : "";
                return (
                  <div className={`flex items-center gap-2 rounded-xl border px-2 py-1.5 transition ${state.activeSubject === subject.subjectName ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-transparent hover:bg-[var(--surface-muted)]"}`} key={subject.subjectName}>
                    <input
                      aria-label={interpolate(copy.editor.useSubject, { subject: subject.subjectName, owner: ownerSuffix })}
                      checked={Boolean(rule)}
                      disabled={blocked || state.saving}
                      onChange={() => toggleSubject(subject)}
                      type="checkbox"
                    />
                    <button className="min-w-0 flex-1 py-1 text-left" onClick={() => onChange({ ...state, activeSubject: subject.subjectName })} type="button" aria-label={interpolate(copy.editor.chooseSubjectAria, { subject: subject.subjectName })}>
                      <span className="block truncate text-sm font-medium">{subject.subjectName}</span>
                      <span className="mt-0.5 block truncate text-[0.68rem] text-[var(--text-muted)]">{ruleLabel(copy, locale, rule, owners)}</span>
                    </button>
                  </div>
                );
              })}
            </div>
          </EditorColumn>

          <EditorColumn title={copy.editor.gendersTitle} subtitle={active?.subjectName ?? copy.editor.chooseSubject} accent>
            {active ? (
              <GenderEditor
                copy={copy}
                gtin={state.data.gtin}
                subject={active}
                rule={state.draft.get(active.subjectName)}
                saving={state.saving}
                onToggleSubject={() => toggleSubject(active)}
                onToggleWildcard={() => toggleWildcard(active)}
                onToggleGender={(gender) => toggleGender(active, gender)}
              />
            ) : (
              <EditorHint icon={<Tags aria-hidden="true" size={22} />} text={copy.editor.noSubjects} />
            )}
          </EditorColumn>

          <EditorColumn title={copy.editor.selectedTitle} subtitle={interpolate(copy.editor.categories, { count: numberFormat.format(selectedCount) })}>
            {selectedCount === 0 ? (
              <EditorHint icon={<Layers3 aria-hidden="true" size={22} />} text={copy.editor.emptySelection} />
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
                          <p className="mt-1 text-xs leading-4 text-[var(--text-secondary)]">{rule.wildcard ? copy.rule.wildcard : formatKizCount(copy, locale, rule.genders.size, "exact")}</p>
                        </div>
                        <button className="rounded-lg p-1.5 text-[var(--text-muted)] hover:bg-[var(--surface-elevated)] hover:text-[var(--danger)]" disabled={state.saving} onClick={() => removeSubject(subject.subjectName)} type="button" aria-label={interpolate(copy.editor.removeRule, { subject: subject.subjectName })}><X aria-hidden="true" size={16} /></button>
                      </div>
                      {!rule.wildcard ? (
                        <div className="mt-2 flex flex-wrap gap-1.5">
                          {[...rule.genders].map((gender) => <span className="rounded-full bg-[var(--surface-elevated)] px-2 py-1 text-[0.68rem] font-medium text-[var(--text-secondary)]" key={gender}>{displayGender(copy, gender)}</span>)}
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
              <p className="flex items-center gap-2 text-sm font-medium text-rose-700" role="alert"><AlertCircle aria-hidden="true" size={17} />{copy.editor.saveError}</p>
            ) : (
              <p className="text-xs text-[var(--text-muted)]">{copy.editor.atomic}</p>
            )}
          </div>
          <div className="flex justify-end gap-2">
            <button className="h-10 rounded-xl border border-[var(--border-strong)] bg-[var(--surface-elevated)] px-4 text-sm font-semibold hover:bg-[var(--surface-muted)] disabled:opacity-50" disabled={state.saving} onClick={onClose} type="button">{copy.editor.cancel}</button>
            <button className="inline-flex h-10 items-center gap-2 rounded-xl bg-[var(--sidebar)] px-4 text-sm font-semibold text-white hover:bg-[#203b30] disabled:cursor-wait disabled:opacity-60" disabled={state.saving} onClick={onSave} type="button" aria-label={copy.editor.saveAria}>
              {state.saving ? <LoaderCircle className="animate-spin" aria-hidden="true" size={16} /> : <Check aria-hidden="true" size={16} />}
              {state.saving ? copy.editor.saving : copy.editor.save}
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
  copy,
  gtin,
  subject,
  rule,
  saving,
  onToggleSubject,
  onToggleWildcard,
  onToggleGender,
}: {
  copy: KizMappingCopy;
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
        <EditorHint icon={<CircleDot aria-hidden="true" size={22} />} text={fullyBlocked(subject, gtin) ? interpolate(copy.gender.allOwned, { owners: owners.join(", ") }) : copy.gender.enableHint} />
        {!fullyBlocked(subject, gtin) ? <button className="rounded-xl border border-[var(--accent)] bg-[var(--accent-soft)] px-4 py-2.5 text-sm font-semibold text-[var(--accent-strong)]" onClick={onToggleSubject} type="button">{copy.gender.enable}</button> : null}
      </div>
    );
  }
  return (
    <div className="grid gap-2">
      <label className={`flex items-start gap-3 rounded-xl border p-3 ${wildcardBlocked ? "border-[var(--border-subtle)] bg-[var(--surface-muted)] opacity-70" : "border-emerald-200 bg-emerald-50"}`}>
        <input aria-label={copy.rule.wildcard} checked={rule.wildcard} disabled={saving || wildcardBlocked} onChange={onToggleWildcard} type="checkbox" />
        <span className="min-w-0">
          <span className="block text-sm font-semibold">{copy.rule.wildcard}</span>
          <span className="mt-0.5 block text-xs leading-4 text-[var(--text-secondary)]">{copy.gender.wildcardDescription}</span>
          {wildcardBlocked ? <span className="mt-1 block text-xs font-medium text-amber-800">{interpolate(copy.gender.wildcardBlocked, { owners: owners.join(", ") })}</span> : null}
        </span>
      </label>
      {subject.genders.map((gender) => {
        const occupied = foreignOwner(gender.ownerGtin, gtin);
        const checked = rule.wildcard || rule.genders.has(gender.value);
        const ownerSuffix = occupied ? interpolate(copy.gender.occupiedSuffix, { gtin: gender.ownerGtin }) : "";
        return (
          <label className={`flex items-center gap-3 rounded-xl border px-3 py-3 ${occupied ? "border-[var(--border-subtle)] bg-[var(--surface-muted)] text-[var(--text-muted)]" : checked ? "border-[var(--accent)] bg-[var(--accent-soft)]" : "border-[var(--border-subtle)] bg-[var(--surface-elevated)] hover:border-[var(--accent)]"}`} key={gender.value}>
            <input aria-label={`${displayGender(copy, gender.value)}${ownerSuffix}`} checked={checked} disabled={saving || occupied} onChange={() => onToggleGender(gender)} type="checkbox" />
            <span className="min-w-0 flex-1 truncate text-sm font-medium">{displayGender(copy, gender.value)}</span>
            {occupied ? <code className="text-[0.65rem]">{gender.ownerGtin}</code> : null}
          </label>
        );
      })}
      {subject.genders.length === 0 ? <p className="rounded-xl bg-[var(--surface-muted)] p-3 text-sm text-[var(--text-secondary)]">{copy.gender.empty}</p> : null}
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

function ruleLabel(copy: KizMappingCopy, locale: string, rule: RuleDraft | undefined, owners: string[]) {
  if (rule?.wildcard) return copy.rule.wildcard;
  if (rule) return interpolate(copy.rule.selected, { count: new Intl.NumberFormat(locale).format(rule.genders.size) });
  if (owners.length > 0) return interpolate(copy.rule.occupied, { owners: owners.join(", ") });
  return copy.rule.unused;
}

function displayGender(copy: KizMappingCopy, value: string) {
  return value === UNSPECIFIED_GENDER ? copy.gender.unspecified : value;
}

function statusLabel(copy: KizMappingCopy, value: string) {
  return copy.statuses[value as keyof KizMappingCopy["statuses"]] ?? "";
}

function formatDate(copy: KizMappingCopy, dateFormat: Intl.DateTimeFormat, value: string) {
  if (!value) return copy.row.noDate;
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? copy.row.noDate
    : dateFormat.format(date);
}
