import {
  Archive,
  CheckCircle2,
  EyeOff,
  FileText,
  KeyRound,
  PackageSearch,
  RefreshCw,
  ScrollText,
  ShoppingCart,
  RotateCcw,
  Save,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Tag,
} from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { InfiniteLoadTrigger } from "../../components/InfiniteLoadTrigger";
import { useBoundedInfinitePages, type InfinitePagesStatus } from "../../components/useBoundedInfinitePages";
import { commands } from "../../generated/commands";
import type { CertificateDiscoveryResponse, ProductItem, ProductsResponse, SettingsResponse } from "../../generated/types";
import { interpolate } from "../../i18n";
import { ZnackLogsPanel, ZnackPurchasesPanel } from "./ZnackOperationsPanel";
import { ZnackPurchaseDialog } from "./ZnackPurchaseDialog";
import { defaultZnackCopy, type ZnackCopy } from "./znackI18n";

type Tab = "settings" | "products" | "deleted" | "purchases" | "logs";
type SettingsState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; data: SettingsResponse };
type ProductState =
  | { status: "idle" | "loading" }
  | { status: "error" }
  | { status: "ready"; data: ProductsResponse };
type CertificateState =
  | { status: "idle" | "loading" | "error" }
  | { status: "ready"; data: CertificateDiscoveryResponse };
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

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function matchesDiscovery(response: CertificateDiscoveryResponse, shopId: number) {
  return response.shopId === shopId
    && UUID.test(response.sessionId)
    && !Number.isNaN(Date.parse(response.expiresAt))
    && response.items.length <= 100
    && response.items.every((item) => UUID.test(item.certificateId)
      && ["SELECTABLE", "EXPIRED", "NO_PRIVATE_KEY"].includes(item.status));
}

function signatureCopy(copy: ZnackCopy, status: string) {
  switch (status) {
    case "VERIFIED":
      return { title: copy.settings.signature.verifiedTitle, detail: copy.settings.signature.verifiedDetail, tone: "success" };
    case "EXPIRED":
      return { title: copy.settings.signature.expiredTitle, detail: copy.settings.signature.expiredDetail, tone: "danger" };
    case "NOT_VERIFIED":
      return { title: copy.settings.signature.notVerifiedTitle, detail: copy.settings.signature.notVerifiedDetail, tone: "warning" };
    default:
      return { title: copy.settings.signature.unconfiguredTitle, detail: copy.settings.signature.unconfiguredDetail, tone: "neutral" };
  }
}

function readiness(copy: ZnackCopy, status: string, kind: "mark" | "turn") {
  const label = copy.products.readiness[kind];
  if (status === "READY") return { label: copy.products.readiness[kind === "mark" ? "markReady" : "turnReady"], className: "status-pill status-success" };
  if (status === "NOT_READY") return { label: copy.products.readiness[kind === "mark" ? "markNotReady" : "turnNotReady"], className: "status-pill status-warning" };
  return { label: interpolate(copy.products.readiness.noData, { label }), className: "status-pill" };
}

export function ZnackView({ shopId, licenseAllowed = true, copy = defaultZnackCopy, locale = "ru-RU" }: { shopId: number; licenseAllowed?: boolean; copy?: ZnackCopy; locale?: string }) {
  const [tab, setTab] = useState<Tab>("settings");
  const [settingsState, setSettingsState] = useState<SettingsState>({ status: "loading" });
  const [draft, setDraft] = useState<SettingsDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [settingsNotice, setSettingsNotice] = useState("");
  const [settingsError, setSettingsError] = useState("");
  const [settingsRetry, setSettingsRetry] = useState(0);
  const [certificateState, setCertificateState] = useState<CertificateState>({ status: "idle" });
  const [selectedCertificate, setSelectedCertificate] = useState("");
  const [testingCertificate, setTestingCertificate] = useState(false);
  const [queryInput, setQueryInput] = useState("");
  const [query, setQuery] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [categoryOpen, setCategoryOpen] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [mutating, setMutating] = useState(false);
  const [productNotice, setProductNotice] = useState("");
  const [productError, setProductError] = useState("");
  const [productRetry, setProductRetry] = useState(0);
  const [syncStarting, setSyncStarting] = useState(false);
  const [syncJob, setSyncJob] = useState<{ jobId: string; cancelling: boolean } | null>(null);
  const [purchaseTarget, setPurchaseTarget] = useState<ProductItem | null>(null);
  const [operationsRefresh, setOperationsRefresh] = useState(0);
  const settingsRequest = useRef(0);
  const numberFormat = useMemo(() => new Intl.NumberFormat(locale), [locale]);

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
  const productsActive = tab === "products" || tab === "deleted";
  const loadProductPage = useCallback(async (page: number) => {
    if (!productsActive) return { items: [], hasMore: false };
    const response = await commands.znack.products({
      shopId,
      query,
      categories,
      deleted,
      page,
      pageSize: PAGE_SIZE,
    });
    if (!matchesProducts(response, shopId, query, categories, deleted, page)) {
      throw new Error("Unexpected Znack products response");
    }
    return { items: response.items, hasMore: response.hasMore, summary: response };
  }, [categories, deleted, productsActive, query, shopId]);
  const productPages = useBoundedInfinitePages<ProductItem, ProductsResponse>({
    resetKey: JSON.stringify([shopId, tab, query, categories, productRetry]),
    loadPage: loadProductPage,
    getId: (item) => item.gtin,
  });
  const productState: ProductState = !productsActive
    ? { status: "idle" }
    : productPages.items.length === 0 && productPages.status === "loading"
      ? { status: "loading" }
      : productPages.items.length === 0 && productPages.status === "error"
        ? { status: "error" }
        : productPages.summary
          ? { status: "ready", data: { ...productPages.summary, items: [...productPages.items] } }
          : { status: "loading" };

  const activeSyncJobId = syncJob?.jobId ?? "";
  useEffect(() => {
    if (!activeSyncJobId) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      try {
        const response = await commands.znack.productSyncStatus({ shopId, jobId: activeSyncJobId });
        if (!active) return;
        if (response.shopId !== shopId || response.jobId !== activeSyncJobId
          || !["running", "completed", "failed", "cancelled"].includes(response.state)
          || response.products < 0) {
          throw new Error("Unexpected Znack sync status");
        }
        if (response.state === "running") {
          timer = setTimeout(() => void poll(), 600);
          return;
        }
        setSyncJob(null);
        if (response.state === "completed") {
          setProductNotice(interpolate(copy.products.synced, { count: numberFormat.format(response.products) }));
          setProductRetry((value) => value + 1);
        } else if (response.state === "cancelled") {
          setProductNotice(copy.products.syncCancelled);
        } else {
          setProductError(response.retryable
            ? copy.products.syncRetryable
            : copy.products.syncRejected);
        }
      } catch {
        if (!active) return;
        setSyncJob(null);
        setProductError(copy.products.syncStatusError);
      }
    };
    void poll();
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [activeSyncJobId, copy, numberFormat, shopId]);

  const reloadSettings = () => {
    setSettingsState({ status: "loading" });
    setSettingsError("");
    setSettingsRetry((value) => value + 1);
  };

  const reloadProducts = () => {
    setProductError("");
    setSelected(new Set());
    setProductRetry((value) => value + 1);
  };

  const changeTab = (next: Tab) => {
    setTab(next);
    setQueryInput("");
    setQuery("");
    setCategories([]);
    setCategoryOpen(false);
    setSelected(new Set());
    setProductNotice("");
    setProductError("");
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
      setSettingsNotice(copy.settings.saved);
    } catch {
      setSettingsError(copy.settings.saveError);
    } finally {
      setSaving(false);
    }
  };

  const discover = async () => {
    if (settingsDirty || certificateState.status === "loading" || testingCertificate) return;
    setCertificateState({ status: "loading" });
    setSelectedCertificate("");
    setSettingsNotice("");
    setSettingsError("");
    try {
      const response = await commands.znack.discoverCertificates({ shopId });
      if (!matchesDiscovery(response, shopId)) throw new Error("Unexpected certificate discovery response");
      setCertificateState({ status: "ready", data: response });
    } catch {
      setCertificateState({ status: "error" });
      setSettingsError(copy.settings.certificatesError);
    }
  };

  const testSelectedCertificate = async () => {
    if (settingsState.status !== "ready" || certificateState.status !== "ready"
      || !selectedCertificate || settingsDirty || testingCertificate) return;
    setTestingCertificate(true);
    setSettingsNotice("");
    setSettingsError("");
    try {
      const response = await commands.znack.testCertificate({
        shopId,
        sessionId: certificateState.data.sessionId,
        certificateId: selectedCertificate,
        version: settingsState.data.version,
      });
      if (!matchesSettings(response, shopId) || response.signatureStatus !== "VERIFIED") {
        throw new Error("Unexpected certificate test response");
      }
      setSettingsState({ status: "ready", data: response });
      setDraft(editable(response));
      setCertificateState({ status: "idle" });
      setSelectedCertificate("");
      setSettingsNotice(copy.settings.certificateSaved);
    } catch {
      setCertificateState({ status: "idle" });
      setSelectedCertificate("");
      setSettingsError(copy.settings.certificateTestError);
    } finally {
      setTestingCertificate(false);
    }
  };

  const startSync = async () => {
    if (settingsState.status !== "ready" || settingsState.data.signatureStatus !== "VERIFIED"
      || settingsDirty || syncStarting || syncJob) return;
    setSyncStarting(true);
    setProductNotice("");
    setProductError("");
    try {
      const response = await commands.znack.startProductSync({ shopId, version: settingsState.data.version });
      if (response.shopId !== shopId || !UUID.test(response.jobId)) throw new Error("Unexpected sync response");
      setSyncJob({ jobId: response.jobId, cancelling: false });
    } catch {
      setProductError(copy.products.syncStartError);
    } finally {
      setSyncStarting(false);
    }
  };

  const cancelSync = async () => {
    if (!syncJob || syncJob.cancelling) return;
    const jobId = syncJob.jobId;
    setSyncJob({ jobId, cancelling: true });
    try {
      const response = await commands.znack.cancelProductSync({ shopId, jobId });
      if (response.shopId !== shopId || response.jobId !== jobId) throw new Error("Unexpected cancel response");
    } catch {
      setSyncJob({ jobId, cancelling: false });
      setProductError(copy.products.syncCancelError);
    }
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    setSelected(new Set());
    setQuery(queryInput.trim());
  };

  const toggleCategory = (category: string) => {
    setSelected(new Set());
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
      setProductNotice(interpolate(deleted ? copy.products.restored : copy.products.hidden, { count: numberFormat.format(response.changed) }));
      reloadProducts();
    } catch {
      setProductError(deleted ? copy.products.restoreError : copy.products.hideError);
    } finally {
      setMutating(false);
    }
  };

  const openPurchase = (product: ProductItem) => {
    setPurchaseTarget(product);
  };

  return (
    <section className="space-y-3" aria-label={copy.header.aria}>
      <div className="overflow-hidden rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] shadow-[var(--shadow-card)]">
        <div className="flex flex-col gap-3 border-b border-[var(--border-subtle)] bg-[var(--accent-soft)] p-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <div className="grid size-11 shrink-0 place-items-center rounded-xl bg-[var(--accent-soft)] text-[var(--accent-strong)]">
              <Tag aria-hidden="true" size={21} />
            </div>
            <div>
              <h3 className="text-base font-semibold tracking-[-0.02em]">{copy.header.title}</h3>
              <p className="mt-1 max-w-2xl text-xs leading-5 text-[var(--text-secondary)]">
                {copy.header.description}
              </p>
            </div>
          </div>
          <div className="inline-flex w-fit items-center gap-2 rounded-full border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-3 py-1.5 text-xs font-semibold text-[var(--text-secondary)]">
            <ShieldCheck aria-hidden="true" size={14} /> {copy.header.badge}
          </div>
        </div>

        <div className="flex gap-1 overflow-x-auto border-b border-[var(--border-subtle)] px-4 pt-3" role="tablist" aria-label={copy.tabs.aria}>
          {([
            ["settings", copy.tabs.settings, SlidersHorizontal],
            ["products", copy.tabs.products, PackageSearch],
            ["deleted", copy.tabs.deleted, Archive],
            ["purchases", copy.tabs.purchases, ShoppingCart],
            ["logs", copy.tabs.logs, ScrollText],
          ] as const).map(([value, label, Icon]) => (
            <button
              key={value}
              role="tab"
              aria-selected={tab === value}
              aria-controls={`znack-panel-${value}`}
              className={`inline-flex min-w-fit items-center gap-1.5 rounded-t-lg border-b-2 px-3 py-2.5 text-xs font-semibold transition ${
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
          <div id="znack-panel-settings" role="tabpanel" aria-label={copy.tabs.settings} className="p-5 lg:p-6">
            <SettingsPanel
              copy={copy}
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
              certificateState={certificateState}
              selectedCertificate={selectedCertificate}
              testingCertificate={testingCertificate}
              onDiscover={() => void discover()}
              onSelectCertificate={setSelectedCertificate}
              onTestCertificate={() => void testSelectedCertificate()}
            />
          </div>
        ) : tab === "products" || tab === "deleted" ? (
          <div id={`znack-panel-${tab}`} role="tabpanel" aria-label={deleted ? copy.tabs.deleted : copy.tabs.products} className="p-3 lg:p-4">
            <ProductPanel
              copy={copy}
              numberFormat={numberFormat}
              deleted={deleted}
              state={productState}
              queryInput={queryInput}
              categories={categories}
              categoryOpen={categoryOpen}
              selected={selected}
              loadStatus={productPages.status}
              hasMore={productPages.hasMore}
              addedCount={productPages.addedCount}
              mutating={mutating}
              notice={productNotice}
              error={productError}
              canSync={settingsState.status === "ready"
                && settingsState.data.signatureStatus === "VERIFIED" && !settingsDirty}
              syncStarting={syncStarting}
              syncJob={syncJob}
              onQueryInput={setQueryInput}
              onSearch={search}
              onToggleCategory={toggleCategory}
              onCategoryOpen={() => setCategoryOpen((open) => !open)}
              onToggleSelected={toggleSelected}
              onVisibility={() => void changeVisibility()}
              onLoadMore={productPages.loadMore}
              onLoadRetry={productPages.retry}
              onRetry={reloadProducts}
              onSync={() => void startSync()}
              onCancelSync={() => void cancelSync()}
              canPurchase={settingsState.status === "ready"
                && settingsState.data.signatureStatus === "VERIFIED" && !settingsDirty && licenseAllowed}
              onBuy={openPurchase}
            />
          </div>
        ) : tab === "purchases" ? (
          <div id="znack-panel-purchases" role="tabpanel" aria-label={copy.tabs.purchases} className="p-4 lg:p-5">
            <ZnackPurchasesPanel
              copy={copy.operations}
              locale={locale}
              shopId={shopId}
              settingsVersion={settingsState.status === "ready" ? settingsState.data.version : ""}
              canMutate={settingsState.status === "ready"
                && settingsState.data.signatureStatus === "VERIFIED" && !settingsDirty}
              refreshToken={operationsRefresh}
            />
          </div>
        ) : (
          <div id="znack-panel-logs" role="tabpanel" aria-label={copy.tabs.logs} className="p-4 lg:p-5">
            <ZnackLogsPanel copy={copy.operations} locale={locale} shopId={shopId} />
          </div>
        )}
      </div>
      {purchaseTarget ? (
        <ZnackPurchaseDialog
          copy={copy.purchase}
          shopId={shopId}
          product={purchaseTarget}
          settingsVersion={settingsState.status === "ready" ? settingsState.data.version : ""}
          canPurchase={settingsState.status === "ready"
            && settingsState.data.signatureStatus === "VERIFIED" && !settingsDirty && licenseAllowed}
          onClose={() => setPurchaseTarget(null)}
          onStarted={() => {
            setPurchaseTarget(null);
            setOperationsRefresh((value) => value + 1);
            changeTab("purchases");
          }}
        />
      ) : null}
    </section>
  );
}

function SettingsPanel({
  copy,
  state,
  draft,
  dirty,
  valid,
  saving,
  notice,
  error,
  certificateState,
  selectedCertificate,
  testingCertificate,
  onDraft,
  onSave,
  onRetry,
  onDiscover,
  onSelectCertificate,
  onTestCertificate,
}: {
  copy: ZnackCopy;
  state: SettingsState;
  draft: SettingsDraft | null;
  dirty: boolean;
  valid: boolean;
  saving: boolean;
  notice: string;
  error: string;
  certificateState: CertificateState;
  selectedCertificate: string;
  testingCertificate: boolean;
  onDraft: (draft: SettingsDraft) => void;
  onSave: () => void;
  onRetry: () => void;
  onDiscover: () => void;
  onSelectCertificate: (certificateId: string) => void;
  onTestCertificate: () => void;
}) {
  if (state.status === "loading") return <PanelLoading label={copy.settings.loading} />;
  if (state.status === "error" || draft === null) {
    return <PanelError message={copy.settings.loadError} button={copy.settings.retryLoad} onRetry={onRetry} />;
  }
  const signature = signatureCopy(copy, state.data.signatureStatus);
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
            <span>{copy.settings.omsConnection}</span>
            <input className="text-input" maxLength={120} value={draft.omsConnection} onChange={(event) => update("omsConnection", event.target.value)} />
          </label>
        </div>
        <div className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
          <div className="mb-4 flex items-center gap-2">
            <FileText aria-hidden="true" size={17} className="text-[var(--accent-strong)]" />
            <h4 className="text-sm font-semibold">{copy.settings.defaultDocument}</h4>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <label className="field-label">
              <span>{copy.settings.documentNumber}</span>
              <input className="text-input" maxLength={120} value={draft.documentNumber} onChange={(event) => update("documentNumber", event.target.value)} />
            </label>
            <label className="field-label">
              <span>{copy.settings.documentDate}</span>
              <input className="text-input" maxLength={10} placeholder={copy.settings.datePlaceholder} value={draft.documentDate} onChange={(event) => update("documentDate", event.target.value)} />
            </label>
          </div>
          <label className="mt-4 flex cursor-pointer items-start gap-3 text-sm text-[var(--text-secondary)]">
            <input type="checkbox" className="mt-0.5 size-4 accent-[var(--accent)]" checked={draft.autoIntroduction} onChange={(event) => update("autoIntroduction", event.target.checked)} />
            <span><strong className="block text-[var(--text-primary)]">{copy.settings.autoIntroduction}</strong>{copy.settings.autoIntroductionHint}</span>
          </label>
        </div>
        {!valid && dirty ? <p role="alert" className="text-sm text-[var(--danger)]">{copy.settings.invalid}</p> : null}
        {notice ? <p className="notice-success" role="status"><CheckCircle2 aria-hidden="true" size={16} />{notice}</p> : null}
        {error ? <div className="notice-error" role="alert"><span>{error}</span><button type="button" onClick={onRetry}>{copy.settings.reloadCurrent}</button></div> : null}
        <div className="flex justify-end">
          <button className="primary-button" type="button" disabled={!dirty || !valid || saving} onClick={onSave}>
            <Save aria-hidden="true" size={16} /> {saving ? copy.settings.saving : copy.settings.save}
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
        {state.data.certificateValidTo ? <p className="mt-1 text-xs text-[var(--text-muted)]">{interpolate(copy.settings.validUntil, { date: state.data.certificateValidTo })}</p> : null}
        <div className="mt-4 border-t border-[var(--border-subtle)] pt-4">
          <button
            className="secondary-button w-full justify-center"
            type="button"
            aria-label={copy.settings.discoverAria}
            disabled={dirty || certificateState.status === "loading" || testingCertificate}
            onClick={onDiscover}
          >
            {certificateState.status === "loading"
              ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} />
              : <KeyRound aria-hidden="true" size={16} />}
            {certificateState.status === "loading" ? copy.settings.discovering : copy.settings.discover}
          </button>
          {dirty ? <p className="mt-2 text-xs leading-5 text-[var(--warning)]">{copy.settings.saveFirst}</p> : null}
          {certificateState.status === "error" ? <p className="mt-2 text-xs text-[var(--danger)]">{copy.settings.discoveryFailed}</p> : null}
          {certificateState.status === "ready" ? (
            <div className="mt-3 space-y-2">
              {certificateState.data.items.length === 0 ? (
                <p className="rounded-lg border border-dashed border-[var(--border-subtle)] p-3 text-xs leading-5 text-[var(--text-muted)]">
                  {copy.settings.none}
                </p>
              ) : certificateState.data.items.map((certificate) => {
                const selectable = certificate.status === "SELECTABLE";
                return (
                  <label
                    key={certificate.certificateId}
                    className={`block rounded-lg border p-3 transition ${selectedCertificate === certificate.certificateId
                      ? "border-[var(--accent)] bg-[var(--accent-soft)]"
                      : "border-[var(--border-subtle)] bg-[var(--surface-elevated)]"}`}
                  >
                    <span className="flex items-start gap-2">
                      <input
                        type="radio"
                        name="znack-certificate"
                        className="mt-0.5 accent-[var(--accent)]"
                        disabled={!selectable || testingCertificate}
                        checked={selectedCertificate === certificate.certificateId}
                        onChange={() => onSelectCertificate(certificate.certificateId)}
                        aria-label={`${certificate.label}${certificate.inn ? `, ${copy.settings.inn} ${certificate.inn}` : ""}`}
                      />
                      <span className="min-w-0">
                        <strong className="block truncate text-xs text-[var(--text-primary)]">{certificate.label}</strong>
                        {certificate.inn ? <span className="mt-1 block text-[11px] text-[var(--text-muted)]">{copy.settings.inn} {certificate.inn}</span> : null}
                        <span className="mt-1 block text-[11px] text-[var(--text-muted)]">
                          {certificate.status === "EXPIRED" ? copy.settings.expired
                            : certificate.status === "NO_PRIVATE_KEY" ? copy.settings.noPrivateKey
                              : certificate.validTo ? interpolate(copy.settings.validUntil, { date: certificate.validTo }) : copy.settings.ready}
                        </span>
                      </span>
                    </span>
                  </label>
                );
              })}
              {certificateState.data.items.length > 0 ? (
                <button
                  className="primary-button w-full justify-center"
                  type="button"
                  aria-label={copy.settings.testAria}
                  disabled={!selectedCertificate || testingCertificate}
                  onClick={onTestCertificate}
                >
                  {testingCertificate ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} /> : <ShieldCheck aria-hidden="true" size={16} />}
                  {testingCertificate ? copy.settings.testing : copy.settings.test}
                </button>
              ) : null}
            </div>
          ) : null}
        </div>
        <p className="mt-4 border-t border-[var(--border-subtle)] pt-4 text-xs leading-5 text-[var(--text-muted)]">
          {copy.settings.privacy}
        </p>
      </aside>
    </div>
  );
}

function ProductPanel({
  copy,
  numberFormat,
  deleted,
  state,
  queryInput,
  categories,
  categoryOpen,
  selected,
  loadStatus,
  hasMore,
  addedCount,
  mutating,
  notice,
  error,
  canSync,
  syncStarting,
  syncJob,
  onQueryInput,
  onSearch,
  onToggleCategory,
  onCategoryOpen,
  onToggleSelected,
  onVisibility,
  onLoadMore,
  onLoadRetry,
  onRetry,
  onSync,
  onCancelSync,
  canPurchase,
  onBuy,
}: {
  copy: ZnackCopy;
  numberFormat: Intl.NumberFormat;
  deleted: boolean;
  state: ProductState;
  queryInput: string;
  categories: string[];
  categoryOpen: boolean;
  selected: Set<string>;
  loadStatus: InfinitePagesStatus;
  hasMore: boolean;
  addedCount: number;
  mutating: boolean;
  notice: string;
  error: string;
  canSync: boolean;
  syncStarting: boolean;
  syncJob: { jobId: string; cancelling: boolean } | null;
  onQueryInput: (value: string) => void;
  onSearch: (event: FormEvent) => void;
  onToggleCategory: (category: string) => void;
  onCategoryOpen: () => void;
  onToggleSelected: (gtin: string) => void;
  onVisibility: () => void;
  onLoadMore: () => void;
  onLoadRetry: () => void;
  onRetry: () => void;
  onSync: () => void;
  onCancelSync: () => void;
  canPurchase: boolean;
  onBuy: (product: ProductItem) => void;
}) {
  const data = state.status === "ready" ? state.data : null;
  return (
    <div className="space-y-4">
      {!deleted || syncJob ? (
        <div className="flex flex-col gap-3 rounded-xl border border-[var(--border-subtle)] bg-[linear-gradient(120deg,color-mix(in_srgb,var(--accent)_9%,var(--surface-muted)),var(--surface-muted))] p-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-sm font-semibold">{copy.products.syncTitle}</p>
            <p className="mt-1 text-xs leading-5 text-[var(--text-muted)]">
              {copy.products.syncDescription}
            </p>
          </div>
          {syncJob ? (
            <button
              className="secondary-button shrink-0"
              type="button"
              disabled={syncJob.cancelling}
              onClick={onCancelSync}
              aria-label={copy.products.stopAria}
            >
              <RefreshCw aria-hidden="true" className="animate-spin" size={16} />
              {syncJob.cancelling ? copy.products.stopping : copy.products.stop}
            </button>
          ) : (
            <button
              className="primary-button shrink-0"
              type="button"
              disabled={!canSync || syncStarting}
              onClick={onSync}
              aria-label={copy.products.syncAria}
            >
              <RefreshCw aria-hidden="true" className={syncStarting ? "animate-spin" : ""} size={16} />
              {syncStarting ? copy.products.starting : copy.products.sync}
            </button>
          )}
        </div>
      ) : null}
      <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div>
          <h4 className="text-base font-semibold">{deleted ? copy.products.deletedTitle : copy.products.title}</h4>
          <p className="mt-1 text-xs text-[var(--text-muted)]">{copy.products.limits}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <form className="flex min-w-0 flex-1 gap-2 sm:min-w-80" role="search" onSubmit={onSearch}>
            <label className="relative min-w-0 flex-1">
              <span className="sr-only">{copy.products.searchLabel}</span>
              <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--text-muted)]" size={16} />
              <input aria-label={copy.products.searchLabel} type="search" className="text-input w-full pl-9" maxLength={120} value={queryInput} onChange={(event) => onQueryInput(event.target.value)} placeholder={copy.products.searchPlaceholder} />
            </label>
            <button className="secondary-button" type="submit" aria-label={copy.products.searchAria}>{copy.products.search}</button>
          </form>
          <div className="relative">
            <button className="secondary-button" type="button" aria-label={copy.products.categoriesAria} aria-expanded={categoryOpen} onClick={onCategoryOpen}>
              <SlidersHorizontal aria-hidden="true" size={16} /> {copy.products.categories}{categories.length ? ` · ${numberFormat.format(categories.length)}` : ""}
            </button>
            {categoryOpen ? (
              <div className="absolute right-0 z-10 mt-2 max-h-64 min-w-56 overflow-auto rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-2 shadow-[var(--shadow-popover)]">
                {(data?.availableCategories ?? []).length === 0 ? <p className="px-2 py-2 text-xs text-[var(--text-muted)]">{copy.products.noCategories}</p> : (data?.availableCategories ?? []).map((category) => (
                  <label key={category} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 text-sm hover:bg-[var(--surface-muted)]">
                    <input type="checkbox" checked={categories.includes(category)} onChange={() => onToggleCategory(category)} /> {category}
                  </label>
                ))}
              </div>
            ) : null}
          </div>
          <button className={deleted ? "secondary-button" : "danger-button"} type="button" disabled={selected.size === 0 || mutating} onClick={onVisibility} aria-label={deleted ? copy.products.restoreAria : copy.products.hideAria}>
            {deleted ? <RotateCcw aria-hidden="true" size={16} /> : <EyeOff aria-hidden="true" size={16} />}
            {mutating ? copy.products.processing : interpolate(deleted ? copy.products.restore : copy.products.hide, { count: numberFormat.format(selected.size) })}
          </button>
        </div>
      </div>

      {notice ? <p className="notice-success" role="status"><CheckCircle2 aria-hidden="true" size={16} />{notice}</p> : null}
      {error ? <div className="notice-error" role="alert"><span>{error}</span><button type="button" onClick={onRetry}>{copy.products.refreshList}</button></div> : null}
      {state.status === "loading" || state.status === "idle" ? <PanelLoading label={copy.products.loading} /> : null}
      {state.status === "error" ? <PanelError message={copy.products.loadError} button={copy.products.retry} onRetry={onRetry} /> : null}
      {data && data.items.length === 0 ? (
        <div className="grid min-h-64 place-items-center rounded-xl border border-dashed border-[var(--border-subtle)] bg-[var(--surface-muted)] p-8 text-center">
          <div><PackageSearch aria-hidden="true" className="mx-auto text-[var(--text-muted)]" size={28} /><h5 className="mt-3 font-semibold">{deleted ? copy.products.deletedEmpty : copy.products.empty}</h5><p className="mt-1 text-sm text-[var(--text-muted)]">{deleted ? copy.products.deletedEmptyHint : copy.products.emptyHint}</p></div>
        </div>
      ) : null}
      {data && data.items.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-[var(--border-subtle)]">
          <div className="hidden grid-cols-[2.2rem_9.5rem_minmax(14rem,1.2fr)_minmax(8rem,.6fr)_minmax(11rem,.8fr)_3rem] gap-3 border-b border-[var(--border-subtle)] bg-[var(--surface-muted)] px-4 py-2.5 text-xs font-semibold tracking-wide text-[var(--text-muted)] uppercase lg:grid">
            <span /><span>GTIN</span><span>{copy.products.columns.product}</span><span>{copy.products.columns.classification}</span><span>{copy.products.columns.readiness}</span><span>{copy.products.columns.kiz}</span>
          </div>
          <ul className="divide-y divide-[var(--border-subtle)]">
            {data.items.map((item) => {
              const mark = readiness(copy, item.goodMarkStatus, "mark");
              const turn = readiness(copy, item.goodTurnStatus, "turn");
              return (
                <li key={item.gtin} className="grid [content-visibility:auto] [contain-intrinsic-size:auto_7rem] gap-3 px-3 py-3 lg:grid-cols-[2.2rem_9.5rem_minmax(14rem,1.2fr)_minmax(8rem,.6fr)_minmax(11rem,.8fr)_3rem] lg:items-center lg:px-4">
                  <input type="checkbox" className="size-4 accent-[var(--accent)]" aria-label={interpolate(copy.products.selectAria, { gtin: item.gtin })} checked={selected.has(item.gtin)} onChange={() => onToggleSelected(item.gtin)} />
                  <code className="text-xs font-semibold text-[var(--text-primary)]">{item.gtin}</code>
                  <div className="min-w-0"><p className="truncate text-sm font-semibold">{item.productName || copy.products.unnamed}</p><p className="mt-1 truncate text-xs text-[var(--text-muted)]">{item.category || copy.products.uncategorized}</p></div>
                  <div className="text-xs text-[var(--text-secondary)]"><p>{copy.products.customsCode} {item.tnVed || "—"}</p><p className="mt-1">CIS {item.cisType || "—"}</p></div>
                  <div className="flex flex-wrap gap-1.5"><span className={mark.className}>{mark.label}</span><span className={turn.className}>{turn.label}</span></div>
                  {!deleted ? <button className="icon-button justify-self-end" type="button" title={copy.products.buy} disabled={!canPurchase} onClick={() => onBuy(item)} aria-label={interpolate(copy.products.buyAria, { gtin: item.gtin })}><ShoppingCart aria-hidden="true" size={15} /></button> : <span />}
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
      {data ? (
        <InfiniteLoadTrigger
          status={loadStatus}
          hasMore={hasMore}
          copy={{ loading: copy.products.loadingMore, loadMore: copy.products.loadMore, loadError: copy.products.loadMoreError, retry: copy.products.retry, end: copy.products.end }}
          announcement={addedCount > 0 ? interpolate(copy.products.added, { count: numberFormat.format(addedCount) }) : ""}
          onLoadMore={onLoadMore}
          onRetry={onLoadRetry}
        />
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
