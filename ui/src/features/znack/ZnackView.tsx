import {
  Archive,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  EyeOff,
  FileText,
  PackageSearch,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Tag,
} from "lucide-react";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { commands } from "../../generated/commands";
import type { ProductsResponse, SettingsResponse } from "../../generated/types";

type Tab = "settings" | "products" | "deleted";
type SettingsState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: SettingsResponse };
type ProductState =
  | { status: "idle" | "loading" }
  | { status: "error" }
  | { status: "ready"; data: ProductsResponse };

type SettingsDraft = Pick<
  SettingsResponse,
  "omsId" | "omsConnection" | "documentNumber" | "documentDate" | "autoIntroduction"
>;

const PAGE_SIZE = 50;

function editable(settings: SettingsResponse): SettingsDraft {
  return {
    omsId: settings.omsId,
    omsConnection: settings.omsConnection,
    documentNumber: settings.documentNumber,
    documentDate: settings.documentDate,
    autoIntroduction: settings.autoIntroduction,
  };
}

function matchesSettings(response: SettingsResponse, shopId: number) {
  return response.shopId === shopId && /^[0-9a-f]{64}$/.test(response.version);
}

function matchesProducts(
  response: ProductsResponse,
  shopId: number,
  query: string,
  categories: string[],
  deleted: boolean,
  page: number,
) {
  return response.shopId === shopId
    && response.query === query
    && response.deleted === deleted
    && response.page === page
    && response.pageSize === PAGE_SIZE
    && response.categories.length === categories.length
    && response.categories.every((category, index) => category === categories[index]);
}

function signatureCopy(status: string) {
  switch (status) {
    case "VERIFIED":
      return { title: "Подпись проверена", detail: "Конфигурация готова для безопасных операций.", tone: "success" };
    case "EXPIRED":
      return { title: "Сертификат истёк", detail: "Выберите и проверьте действующий сертификат.", tone: "danger" };
    case "NOT_VERIFIED":
      return { title: "Подпись не проверена", detail: "Проверка CryptoPro будет доступна в следующем этапе.", tone: "warning" };
    default:
      return { title: "Сертификат не настроен", detail: "Настройка CryptoPro будет доступна в следующем этапе.", tone: "neutral" };
  }
}

function readiness(status: string, kind: "mark" | "turn") {
  const label = kind === "mark" ? "Маркировка" : "Оборот";
  if (status === "READY") return { label: `${label} ${kind === "mark" ? "готова" : "готов"}`, className: "status-pill status-success" };
  if (status === "NOT_READY") return { label: `${label} ${kind === "mark" ? "не готова" : "не готов"}`, className: "status-pill status-warning" };
  return { label: `${label}: нет данных`, className: "status-pill" };
}

export function ZnackView({ shopId }: { shopId: number }) {
  const [tab, setTab] = useState<Tab>("settings");
  const [settingsState, setSettingsState] = useState<SettingsState>({ status: "loading" });
  const [draft, setDraft] = useState<SettingsDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [settingsNotice, setSettingsNotice] = useState("");
  const [settingsError, setSettingsError] = useState("");
  const [settingsRetry, setSettingsRetry] = useState(0);
  const [productState, setProductState] = useState<ProductState>({ status: "idle" });
  const [queryInput, setQueryInput] = useState("");
  const [query, setQuery] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [page, setPage] = useState(1);
  const [categoryOpen, setCategoryOpen] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [mutating, setMutating] = useState(false);
  const [productNotice, setProductNotice] = useState("");
  const [productError, setProductError] = useState("");
  const [productRetry, setProductRetry] = useState(0);
  const settingsRequest = useRef(0);
  const productsRequest = useRef(0);

  useEffect(() => {
    const request = ++settingsRequest.current;
    let active = true;
    void commands.znack.settings({ shopId }).then(
      (response) => {
        if (!active || request !== settingsRequest.current) return;
        if (!matchesSettings(response, shopId)) {
          setSettingsState({ status: "error" });
          return;
        }
        setSettingsState({ status: "ready", data: response });
        setDraft(editable(response));
      },
      () => {
        if (active && request === settingsRequest.current) setSettingsState({ status: "error" });
      },
    );
    return () => {
      active = false;
      settingsRequest.current += 1;
    };
  }, [settingsRetry, shopId]);

  const deleted = tab === "deleted";
  useEffect(() => {
    if (tab === "settings") return;
    const request = ++productsRequest.current;
    let active = true;
    void commands.znack.products({
      shopId,
      query,
      categories,
      deleted,
      page,
      pageSize: PAGE_SIZE,
    }).then(
      (response) => {
        if (!active || request !== productsRequest.current) return;
        if (!matchesProducts(response, shopId, query, categories, deleted, page)) {
          setProductState({ status: "error" });
          return;
        }
        setProductState({ status: "ready", data: response });
        setSelected(new Set());
      },
      () => {
        if (active && request === productsRequest.current) setProductState({ status: "error" });
      },
    );
    return () => {
      active = false;
      productsRequest.current += 1;
    };
  }, [categories, deleted, page, productRetry, query, shopId, tab]);

  const reloadSettings = () => {
    setSettingsState({ status: "loading" });
    setSettingsError("");
    setSettingsRetry((value) => value + 1);
  };

  const reloadProducts = () => {
    setProductState({ status: "loading" });
    setProductError("");
    setProductRetry((value) => value + 1);
  };

  const changeTab = (next: Tab) => {
    setTab(next);
    setQueryInput("");
    setQuery("");
    setCategories([]);
    setPage(1);
    setCategoryOpen(false);
    setSelected(new Set());
    setProductNotice("");
    setProductError("");
    if (next !== "settings") setProductState({ status: "loading" });
  };

  const settingsDirty = useMemo(() => {
    if (settingsState.status !== "ready" || draft === null) return false;
    return JSON.stringify(draft) !== JSON.stringify(editable(settingsState.data));
  }, [draft, settingsState]);
  const documentComplete = draft !== null
    && (draft.documentNumber.trim() === "") === (draft.documentDate.trim() === "");
  const settingsValid = draft !== null
    && draft.omsId.trim() !== ""
    && draft.omsConnection.trim() !== ""
    && documentComplete;

  const save = async () => {
    if (settingsState.status !== "ready" || draft === null || !settingsDirty || !settingsValid) return;
    setSaving(true);
    setSettingsNotice("");
    setSettingsError("");
    try {
      const response = await commands.znack.saveSettings({
        shopId,
        omsId: draft.omsId.trim(),
        omsConnection: draft.omsConnection.trim(),
        documentNumber: draft.documentNumber.trim(),
        documentDate: draft.documentDate.trim(),
        autoIntroduction: draft.autoIntroduction,
        version: settingsState.data.version,
      });
      if (!matchesSettings(response, shopId)) throw new Error("Unexpected Znack settings response");
      setSettingsState({ status: "ready", data: response });
      setDraft(editable(response));
      setSettingsNotice("Настройки Znack сохранены");
    } catch {
      setSettingsError("Не удалось сохранить настройки. Загрузите актуальные данные и повторите.");
    } finally {
      setSaving(false);
    }
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    setProductState({ status: "loading" });
    setSelected(new Set());
    setPage(1);
    setQuery(queryInput.trim());
  };

  const toggleCategory = (category: string) => {
    setProductState({ status: "loading" });
    setSelected(new Set());
    setPage(1);
    setCategories((current) => current.includes(category)
      ? current.filter((value) => value !== category)
      : current.length < 30 ? [...current, category] : current);
  };

  const toggleSelected = (gtin: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(gtin)) next.delete(gtin);
      else if (next.size < 100) next.add(gtin);
      return next;
    });
  };

  const changeVisibility = async () => {
    if (selected.size === 0 || mutating) return;
    setMutating(true);
    setProductNotice("");
    setProductError("");
    try {
      const response = await commands.znack.setProductVisibility({
        shopId,
        gtins: [...selected],
        deleted: !deleted,
      });
      if (response.shopId !== shopId || response.deleted !== !deleted || response.changed !== selected.size) {
        throw new Error("Unexpected Znack visibility response");
      }
      setProductNotice(deleted
        ? `Восстановлено GTIN: ${response.changed}`
        : `Скрыто GTIN: ${response.changed}`);
      reloadProducts();
    } catch {
      setProductError(deleted
        ? "Не удалось восстановить выбранные GTIN. Обновите список и повторите."
        : "Не удалось скрыть выбранные GTIN. Обновите список и повторите.");
    } finally {
      setMutating(false);
    }
  };

  const changePage = (next: number) => {
    setProductState({ status: "loading" });
    setSelected(new Set());
    setPage(next);
  };

  return (
    <section className="space-y-5" aria-label="Znack Automation">
      <div className="overflow-hidden rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-card)]">
        <div className="flex flex-col gap-4 border-b border-[var(--border-subtle)] bg-[linear-gradient(120deg,color-mix(in_srgb,var(--accent)_12%,transparent),transparent_58%)] p-5 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <div className="grid size-11 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
              <Tag aria-hidden="true" size={21} />
            </div>
            <div>
              <h3 className="text-lg font-semibold tracking-[-0.02em]">Локальный контур Znack</h3>
              <p className="mt-1 max-w-2xl text-sm leading-6 text-[var(--text-secondary)]">
                Настройки и локальный жизненный цикл GTIN. Секретные параметры подписи остаются в Java.
              </p>
            </div>
          </div>
          <div className="inline-flex w-fit items-center gap-2 rounded-full border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-3 py-1.5 text-xs font-semibold text-[var(--text-secondary)]">
            <ShieldCheck aria-hidden="true" size={14} /> Локальный контур
          </div>
        </div>

        <div className="flex gap-1 overflow-x-auto border-b border-[var(--border-subtle)] px-4 pt-3" role="tablist" aria-label="Разделы Znack">
          {([
            ["settings", "Настройки", SlidersHorizontal],
            ["products", "Товары", PackageSearch],
            ["deleted", "Скрытые", Archive],
          ] as const).map(([value, label, Icon]) => (
            <button
              key={value}
              role="tab"
              aria-selected={tab === value}
              aria-controls={`znack-panel-${value}`}
              className={`inline-flex min-w-fit items-center gap-2 rounded-t-xl border-b-2 px-4 py-3 text-sm font-semibold transition ${
                tab === value
                  ? "border-[var(--accent)] text-[var(--accent-strong)]"
                  : "border-transparent text-[var(--text-muted)] hover:text-[var(--text-primary)]"
              }`}
              type="button"
              onClick={() => changeTab(value)}
            >
              <Icon aria-hidden="true" size={16} /> {label}
            </button>
          ))}
        </div>

        {tab === "settings" ? (
          <div id="znack-panel-settings" role="tabpanel" aria-label="Настройки" className="p-5 lg:p-6">
            <SettingsPanel
              state={settingsState}
              draft={draft}
              dirty={settingsDirty}
              valid={settingsValid}
              saving={saving}
              notice={settingsNotice}
              error={settingsError}
              onDraft={setDraft}
              onSave={() => void save()}
              onRetry={reloadSettings}
            />
          </div>
        ) : (
          <div id={`znack-panel-${tab}`} role="tabpanel" aria-label={deleted ? "Скрытые" : "Товары"} className="p-4 lg:p-5">
            <ProductPanel
              deleted={deleted}
              state={productState}
              queryInput={queryInput}
              categories={categories}
              categoryOpen={categoryOpen}
              selected={selected}
              page={page}
              mutating={mutating}
              notice={productNotice}
              error={productError}
              onQueryInput={setQueryInput}
              onSearch={search}
              onToggleCategory={toggleCategory}
              onCategoryOpen={() => setCategoryOpen((open) => !open)}
              onToggleSelected={toggleSelected}
              onVisibility={() => void changeVisibility()}
              onPage={changePage}
              onRetry={reloadProducts}
            />
          </div>
        )}
      </div>
    </section>
  );
}

function SettingsPanel({
  state,
  draft,
  dirty,
  valid,
  saving,
  notice,
  error,
  onDraft,
  onSave,
  onRetry,
}: {
  state: SettingsState;
  draft: SettingsDraft | null;
  dirty: boolean;
  valid: boolean;
  saving: boolean;
  notice: string;
  error: string;
  onDraft: (draft: SettingsDraft) => void;
  onSave: () => void;
  onRetry: () => void;
}) {
  if (state.status === "loading") return <PanelLoading label="Загрузка настроек Znack" />;
  if (state.status === "error" || draft === null) {
    return <PanelError message="Не удалось загрузить настройки Znack" button="Повторить загрузку настроек" onRetry={onRetry} />;
  }
  const signature = signatureCopy(state.data.signatureStatus);
  const update = <K extends keyof SettingsDraft>(key: K, value: SettingsDraft[K]) => onDraft({ ...draft, [key]: value });
  return (
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1.4fr)_minmax(18rem,.6fr)]">
      <div className="space-y-5">
        <div className="grid gap-4 md:grid-cols-2">
          <label className="field-label">
            <span>OMS ID</span>
            <input className="text-input" maxLength={100} value={draft.omsId} onChange={(event) => update("omsId", event.target.value)} />
          </label>
          <label className="field-label">
            <span>Соединение OMS</span>
            <input className="text-input" maxLength={120} value={draft.omsConnection} onChange={(event) => update("omsConnection", event.target.value)} />
          </label>
        </div>
        <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
          <div className="mb-4 flex items-center gap-2">
            <FileText aria-hidden="true" size={17} className="text-[var(--accent-strong)]" />
            <h4 className="text-sm font-semibold">Документ по умолчанию</h4>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <label className="field-label">
              <span>Номер документа</span>
              <input className="text-input" maxLength={120} value={draft.documentNumber} onChange={(event) => update("documentNumber", event.target.value)} />
            </label>
            <label className="field-label">
              <span>Дата документа</span>
              <input className="text-input" maxLength={10} placeholder="дд.мм.гггг" value={draft.documentDate} onChange={(event) => update("documentDate", event.target.value)} />
            </label>
          </div>
          <label className="mt-4 flex cursor-pointer items-start gap-3 text-sm text-[var(--text-secondary)]">
            <input type="checkbox" className="mt-0.5 size-4 accent-[var(--accent)]" checked={draft.autoIntroduction} onChange={(event) => update("autoIntroduction", event.target.checked)} />
            <span><strong className="block text-[var(--text-primary)]">Автоматический ввод в оборот</strong>Запускать только когда документы и metadata GTIN готовы.</span>
          </label>
        </div>
        {!valid && dirty ? <p role="alert" className="text-sm text-[var(--danger)]">Заполните OMS-поля и оба поля документа либо оставьте оба пустыми.</p> : null}
        {notice ? <p className="notice-success" role="status"><CheckCircle2 aria-hidden="true" size={16} />{notice}</p> : null}
        {error ? <div className="notice-error" role="alert"><span>{error}</span><button type="button" onClick={onRetry}>Загрузить актуальные</button></div> : null}
        <div className="flex justify-end">
          <button className="primary-button" type="button" disabled={!dirty || !valid || saving} onClick={onSave}>
            <Save aria-hidden="true" size={16} /> {saving ? "Сохранение…" : "Сохранить настройки Znack"}
          </button>
        </div>
      </div>
      <aside className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
        <div className="flex items-start gap-3">
          <div className={`grid size-9 shrink-0 place-items-center rounded-lg ${signature.tone === "success" ? "bg-[var(--success-soft)] text-[var(--success)]" : "bg-[var(--warning-soft)] text-[var(--warning)]"}`}>
            <ShieldCheck aria-hidden="true" size={18} />
          </div>
          <div>
            <h4 className="text-sm font-semibold">{signature.title}</h4>
            <p className="mt-1 text-xs leading-5 text-[var(--text-muted)]">{signature.detail}</p>
          </div>
        </div>
        {state.data.certificateLabel ? <p className="mt-4 text-sm font-medium">{state.data.certificateLabel}</p> : null}
        {state.data.certificateValidTo ? <p className="mt-1 text-xs text-[var(--text-muted)]">Действует до {state.data.certificateValidTo}</p> : null}
        <p className="mt-4 border-t border-[var(--border-subtle)] pt-4 text-xs leading-5 text-[var(--text-muted)]">
          Selector, thumbprint, executable paths и metadata сертификата не передаются в WebView.
        </p>
      </aside>
    </div>
  );
}

function ProductPanel({
  deleted,
  state,
  queryInput,
  categories,
  categoryOpen,
  selected,
  page,
  mutating,
  notice,
  error,
  onQueryInput,
  onSearch,
  onToggleCategory,
  onCategoryOpen,
  onToggleSelected,
  onVisibility,
  onPage,
  onRetry,
}: {
  deleted: boolean;
  state: ProductState;
  queryInput: string;
  categories: string[];
  categoryOpen: boolean;
  selected: Set<string>;
  page: number;
  mutating: boolean;
  notice: string;
  error: string;
  onQueryInput: (value: string) => void;
  onSearch: (event: FormEvent) => void;
  onToggleCategory: (category: string) => void;
  onCategoryOpen: () => void;
  onToggleSelected: (gtin: string) => void;
  onVisibility: () => void;
  onPage: (page: number) => void;
  onRetry: () => void;
}) {
  const data = state.status === "ready" ? state.data : null;
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div>
          <h4 className="text-base font-semibold">{deleted ? "Скрытые GTIN" : "Локальный каталог товаров"}</h4>
          <p className="mt-1 text-xs text-[var(--text-muted)]">До 50 GTIN на странице · до 100 в одной операции</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <form className="flex min-w-0 flex-1 gap-2 sm:min-w-80" role="search" onSubmit={onSearch}>
            <label className="relative min-w-0 flex-1">
              <span className="sr-only">Поиск товаров Znack</span>
              <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--text-muted)]" size={16} />
              <input type="search" className="text-input w-full pl-9" maxLength={120} value={queryInput} onChange={(event) => onQueryInput(event.target.value)} placeholder="GTIN, название, категория, ТН ВЭД" />
            </label>
            <button className="secondary-button" type="submit" aria-label="Найти товар Znack">Найти</button>
          </form>
          <div className="relative">
            <button className="secondary-button" type="button" aria-label="Категории товаров Znack" aria-expanded={categoryOpen} onClick={onCategoryOpen}>
              <SlidersHorizontal aria-hidden="true" size={16} /> Категории{categories.length ? ` · ${categories.length}` : ""}
            </button>
            {categoryOpen ? (
              <div className="absolute right-0 z-10 mt-2 max-h-64 min-w-56 overflow-auto rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-2 shadow-[var(--shadow-popover)]">
                {(data?.availableCategories ?? []).length === 0 ? <p className="px-2 py-2 text-xs text-[var(--text-muted)]">Категорий нет</p> : (data?.availableCategories ?? []).map((category) => (
                  <label key={category} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 text-sm hover:bg-[var(--surface-muted)]">
                    <input type="checkbox" checked={categories.includes(category)} onChange={() => onToggleCategory(category)} /> {category}
                  </label>
                ))}
              </div>
            ) : null}
          </div>
          <button className={deleted ? "secondary-button" : "danger-button"} type="button" disabled={selected.size === 0 || mutating} onClick={onVisibility} aria-label={deleted ? "Восстановить выбранные GTIN" : "Скрыть выбранные GTIN"}>
            {deleted ? <RotateCcw aria-hidden="true" size={16} /> : <EyeOff aria-hidden="true" size={16} />}
            {mutating ? "Обработка…" : deleted ? `Восстановить · ${selected.size}` : `Скрыть · ${selected.size}`}
          </button>
        </div>
      </div>

      {notice ? <p className="notice-success" role="status"><CheckCircle2 aria-hidden="true" size={16} />{notice}</p> : null}
      {error ? <div className="notice-error" role="alert"><span>{error}</span><button type="button" onClick={onRetry}>Обновить список</button></div> : null}
      {state.status === "loading" || state.status === "idle" ? <PanelLoading label="Загрузка товаров Znack" /> : null}
      {state.status === "error" ? <PanelError message="Не удалось загрузить каталог Znack" button="Повторить" onRetry={onRetry} /> : null}
      {data && data.items.length === 0 ? (
        <div className="grid min-h-64 place-items-center rounded-xl border border-dashed border-[var(--border-subtle)] bg-[var(--surface-muted)] p-8 text-center">
          <div><PackageSearch aria-hidden="true" className="mx-auto text-[var(--text-muted)]" size={28} /><h5 className="mt-3 font-semibold">{deleted ? "Скрытых GTIN нет" : "Локальный каталог Znack пока пуст"}</h5><p className="mt-1 text-sm text-[var(--text-muted)]">{deleted ? "Скрытые товары появятся здесь." : "Синхронизация с Znack будет подключена следующим этапом."}</p></div>
        </div>
      ) : null}
      {data && data.items.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-[var(--border-subtle)]">
          <div className="hidden grid-cols-[2.2rem_9.5rem_minmax(15rem,1.3fr)_minmax(8rem,.7fr)_minmax(12rem,.8fr)] gap-3 border-b border-[var(--border-subtle)] bg-[var(--surface-muted)] px-4 py-3 text-xs font-semibold tracking-wide text-[var(--text-muted)] uppercase lg:grid">
            <span /><span>GTIN</span><span>Товар</span><span>Классификация</span><span>Готовность</span>
          </div>
          <ul className="divide-y divide-[var(--border-subtle)]">
            {data.items.map((item) => {
              const mark = readiness(item.goodMarkStatus, "mark");
              const turn = readiness(item.goodTurnStatus, "turn");
              return (
                <li key={item.gtin} className="grid gap-3 px-4 py-4 lg:grid-cols-[2.2rem_9.5rem_minmax(15rem,1.3fr)_minmax(8rem,.7fr)_minmax(12rem,.8fr)] lg:items-center">
                  <input type="checkbox" className="size-4 accent-[var(--accent)]" aria-label={`Выбрать GTIN ${item.gtin}`} checked={selected.has(item.gtin)} onChange={() => onToggleSelected(item.gtin)} />
                  <code className="text-xs font-semibold text-[var(--text-primary)]">{item.gtin}</code>
                  <div className="min-w-0"><p className="truncate text-sm font-semibold">{item.productName || "Без названия"}</p><p className="mt-1 truncate text-xs text-[var(--text-muted)]">{item.category || "Без категории"}</p></div>
                  <div className="text-xs text-[var(--text-secondary)]"><p>ТН ВЭД {item.tnVed || "—"}</p><p className="mt-1">CIS {item.cisType || "—"}</p></div>
                  <div className="flex flex-wrap gap-1.5"><span className={mark.className}>{mark.label}</span><span className={turn.className}>{turn.label}</span></div>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
      {data ? (
        <div className="flex items-center justify-between gap-3">
          <button className="secondary-button" type="button" disabled={page <= 1 || state.status === "loading"} onClick={() => onPage(page - 1)} aria-label="Предыдущая страница товаров Znack"><ChevronLeft aria-hidden="true" size={16} />Назад</button>
          <span className="text-sm font-medium text-[var(--text-secondary)]">Страница {page}</span>
          <button className="secondary-button" type="button" disabled={!data.hasMore || state.status === "loading"} onClick={() => onPage(page + 1)} aria-label="Следующая страница товаров Znack">Вперёд<ChevronRight aria-hidden="true" size={16} /></button>
        </div>
      ) : null}
    </div>
  );
}

function PanelLoading({ label }: { label: string }) {
  return <div className="grid min-h-56 place-items-center" role="status" aria-label={label}><RefreshCw aria-hidden="true" className="animate-spin text-[var(--accent)]" size={24} /></div>;
}

function PanelError({ message, button, onRetry }: { message: string; button: string; onRetry: () => void }) {
  return <div className="grid min-h-56 place-items-center text-center" role="alert"><div><p className="font-semibold">{message}</p><button className="secondary-button mt-4" type="button" onClick={onRetry}><RefreshCw aria-hidden="true" size={16} />{button}</button></div></div>;
}
