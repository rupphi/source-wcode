import { AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, Clock3, RefreshCw, RotateCcw, ScrollText, ShoppingCart, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { commands } from "../../generated/commands";
import type { LogItem, LogsResponse, PurchaseItem, PurchasesResponse } from "../../generated/types";

const PAGE_SIZE = 50;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PURCHASE_STAGES = new Set([
  "validating", "creating_order", "polling_order", "downloading_codes",
  "waiting_introduction_readiness", "submitting_introduction", "polling_introduction",
  "introduction_failed", "introduction_skipped_missing_documents",
  "introduction_skipped_missing_metadata", "introduced", "completed", "failed",
]);
const PURCHASE_STATES = new Set(["running", "completed", "attention", "failed", "manual_review"]);
const ERROR_KINDS = new Set([
  "", "order_creation_ambiguous", "introduction_failed", "missing_documents", "missing_metadata",
  "authentication_failed", "rate_limited", "timeout", "certificate_unavailable", "upstream_error",
]);
const LOG_ACTIONS = new Set(["buy_kiz", "download_codes", "purchase_pipeline", "introduction", "product_sync", "operation"]);
const LOG_SEVERITIES = new Set(["info", "warning", "error"]);
const LOG_MESSAGES = new Set([
  "completed", "attention", "missing_documents", "missing_metadata", "authentication_failed",
  "rate_limited", "timeout", "upstream_error",
]);
const HTTP_CLASSES = new Set(["", "1xx", "2xx", "3xx", "4xx", "5xx"]);

type PurchaseState =
  | { status: "loading" | "error" }
  | { status: "ready"; data: PurchasesResponse };
type LogState =
  | { status: "loading" | "error" }
  | { status: "ready"; data: LogsResponse };

function validPurchase(item: PurchaseItem) {
  return item !== null
    && UUID.test(item.purchaseId)
    && /^\d{14}$/.test(item.gtin)
    && typeof item.productName === "string"
    && item.productName.length <= 160
    && !/[\p{Cc}\p{Cf}]/u.test(item.productName)
    && item.quantity > 0
    && item.quantity <= 10_000
    && item.downloadedCodes >= 0
    && item.downloadedCodes <= item.quantity
    && item.progress >= 0
    && item.progress <= 100
    && PURCHASE_STAGES.has(item.stage)
    && PURCHASE_STATES.has(item.state)
    && ERROR_KINDS.has(item.errorKind)
    && typeof item.createdAt === "string"
    && typeof item.updatedAt === "string"
    && !Number.isNaN(Date.parse(item.createdAt))
    && !Number.isNaN(Date.parse(item.updatedAt));
}

function validPurchases(response: PurchasesResponse, shopId: number, page: number) {
  return response !== null
    && response.shopId === shopId
    && response.page === page
    && response.pageSize === PAGE_SIZE
    && Array.isArray(response.items)
    && response.items.length <= PAGE_SIZE
    && response.items.every(validPurchase)
    && new Set(response.items.map((item) => item.purchaseId)).size === response.items.length;
}

function statusCopy(item: PurchaseItem) {
  if (item.stage === "introduction_failed") {
    return { title: "Ввод в оборот требует внимания", tone: "status-warning" };
  }
  if (item.stage === "creating_order") {
    return { title: "Нужна ручная проверка заказа", tone: "status-warning" };
  }
  if (item.stage === "introduction_skipped_missing_documents") {
    return { title: "Нужны документы для ввода", tone: "status-warning" };
  }
  if (item.stage === "introduction_skipped_missing_metadata") {
    return { title: "Нужен ТН ВЭД для ввода", tone: "status-warning" };
  }
  if (item.state === "completed") return { title: "Готово", tone: "status-success" };
  if (item.state === "failed") return { title: "Операция остановлена", tone: "status-danger" };
  return { title: "Выполняется", tone: "status-info" };
}

function stageCopy(stage: string) {
  return ({
    validating: "Проверка данных",
    creating_order: "Создание заказа",
    polling_order: "Ожидание кодов",
    downloading_codes: "Загрузка кодов",
    waiting_introduction_readiness: "Проверка готовности к вводу",
    submitting_introduction: "Отправка документа ввода",
    polling_introduction: "Проверка документа ввода",
    introduction_failed: "Документ ввода отклонён",
    introduction_skipped_missing_documents: "Ввод отложен: нет документов",
    introduction_skipped_missing_metadata: "Ввод отложен: нет данных GTIN",
    introduced: "Коды введены в оборот",
    completed: "Коды доступны",
    failed: "Операция остановлена",
  } as Record<string, string>)[stage] ?? "Обработка покупки";
}

function errorCopy(kind: string) {
  return ({
    order_creation_ambiguous: "Ответ на создание заказа неясен. Автоповтор отключён, чтобы исключить двойное списание.",
    introduction_failed: "Znack отклонил документ ввода. Исправьте настройки или документы и повторите только ввод.",
    missing_documents: "Коды загружены. Добавьте документ по умолчанию, чтобы продолжить ввод в оборот.",
    missing_metadata: "Коды загружены. Синхронизируйте ТН ВЭД, чтобы продолжить ввод в оборот.",
    authentication_failed: "Авторизация Znack отклонена. Проверьте сертификат и OMS.",
    rate_limited: "Znack временно ограничил запросы. Безопасный этап будет повторён позже.",
    timeout: "Znack не ответил вовремя. Состояние сохранено для безопасного продолжения.",
    certificate_unavailable: "CryptoPro или закрытый ключ сейчас недоступны.",
    upstream_error: "Внешний сервис временно недоступен. Состояние сохранено.",
  } as Record<string, string>)[kind] ?? "";
}

export function ZnackPurchasesPanel({
  shopId,
  settingsVersion,
  canMutate,
  refreshToken,
}: {
  shopId: number;
  settingsVersion: string;
  canMutate: boolean;
  refreshToken: number;
}) {
  const [page, setPage] = useState(1);
  const [state, setState] = useState<PurchaseState>({ status: "loading" });
  const [retrying, setRetrying] = useState<PurchaseItem | null>(null);
  const [submittingRetry, setSubmittingRetry] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const requestRef = useRef(0);

  useEffect(() => {
    const request = ++requestRef.current;
    let active = true;
    void commands.znack.purchases({ shopId, page, pageSize: PAGE_SIZE }).then(
      (response) => {
        if (!active || request !== requestRef.current) return;
        setState(validPurchases(response, shopId, page) ? { status: "ready", data: response } : { status: "error" });
      },
      () => {
        if (active && request === requestRef.current) setState({ status: "error" });
      },
    );
    return () => {
      active = false;
      requestRef.current += 1;
    };
  }, [page, refreshToken, reload, shopId]);

  const activeIds = useMemo(() => state.status === "ready"
    ? state.data.items.filter((item) => item.state === "running").map((item) => item.purchaseId).join(",")
    : "", [state]);

  useEffect(() => {
    if (!activeIds) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = () => {
      timer = setTimeout(() => {
        const ids = activeIds.split(",");
        void Promise.all(ids.map((purchaseId) => commands.znack.purchaseStatus({ shopId, purchaseId }))).then(
          (items) => {
            if (!active || items.some((item) => !validPurchase(item))) return;
            const byId = new Map(items.map((item) => [item.purchaseId, item]));
            setState((current) => current.status !== "ready" ? current : {
              status: "ready",
              data: { ...current.data, items: current.data.items.map((item) => byId.get(item.purchaseId) ?? item) },
            });
            if (items.some((item) => item.state === "running")) poll();
          },
          () => {
            if (active) setError("Не удалось обновить прогресс покупки. Повторите загрузку списка.");
          },
        );
      }, 300);
    };
    poll();
    return () => {
      active = false;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [activeIds, shopId]);

  const confirmRetry = async () => {
    if (!retrying || !canMutate || submittingRetry) return;
    setSubmittingRetry(true);
    setError("");
    try {
      const updated = await commands.znack.retryIntroduction({
        shopId,
        purchaseId: retrying.purchaseId,
        version: settingsVersion,
        confirmed: true,
      });
      if (!validPurchase(updated) || updated.purchaseId !== retrying.purchaseId) throw new Error("Unexpected retry response");
      setState((current) => current.status !== "ready" ? current : {
        status: "ready",
        data: { ...current.data, items: current.data.items.map((item) => item.purchaseId === updated.purchaseId ? updated : item) },
      });
      setRetrying(null);
    } catch {
      setError("Не удалось повторить ввод в оборот. Обновите покупку и проверьте настройки.");
    } finally {
      setSubmittingRetry(false);
    }
  };

  const data = state.status === "ready" && state.data.shopId === shopId && state.data.page === page
    ? state.data : null;
  const changePage = (next: number) => {
    setError("");
    setState({ status: "loading" });
    setPage(next);
  };
  const reloadPage = () => {
    setError("");
    setState({ status: "loading" });
    setReload((value) => value + 1);
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h4 className="text-base font-semibold">Покупки КИЗ и ввод в оборот</h4>
          <p className="mt-1 text-xs leading-5 text-[var(--text-muted)]">Сохранённый прогресс · один ключ защиты от повторной покупки · без показа исходных кодов.</p>
        </div>
        <button className="secondary-button" type="button" onClick={reloadPage} aria-label="Обновить покупки Znack">
          <RefreshCw aria-hidden="true" size={16} /> Обновить
        </button>
      </div>
      {error ? <div className="notice-error" role="alert"><span>{error}</span><button type="button" onClick={reloadPage}>Повторить</button></div> : null}
      {state.status === "loading" || (state.status === "ready" && data === null) ? <PanelState loading label="Загрузка покупок Znack" /> : null}
      {state.status === "error" ? <PanelState label="Не удалось загрузить покупки Znack" onRetry={reloadPage} /> : null}
      {data?.items.length === 0 ? (
        <div className="grid min-h-64 place-items-center rounded-xl border border-dashed border-[var(--border-subtle)] bg-[var(--surface-muted)] p-8 text-center">
          <div><ShoppingCart aria-hidden="true" className="mx-auto text-[var(--text-muted)]" size={28} /><h5 className="mt-3 font-semibold">Покупок пока нет</h5><p className="mt-1 text-sm text-[var(--text-muted)]">Откройте товары и подготовьте покупку нужного GTIN.</p></div>
        </div>
      ) : null}
      {data && data.items.length > 0 ? (
        <ul className="space-y-3">
          {data.items.map((item) => {
            const status = statusCopy(item);
            const detail = errorCopy(item.errorKind);
            return (
              <li key={item.purchaseId} className="rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-4">
                <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className={`status-pill ${status.tone}`}>{status.title}</span>
                      <code className="text-xs font-semibold">{item.gtin}</code>
                    </div>
                    <p className="mt-2 truncate text-sm font-semibold">{item.productName || "Товар без названия"}</p>
                    <p className="mt-1 text-xs text-[var(--text-muted)]">{stageCopy(item.stage)} · Заказано: {item.quantity}</p>
                  </div>
                  <div className="flex shrink-0 flex-col items-start gap-2 lg:items-end">
                    <p className="text-xs font-medium text-[var(--text-secondary)]">Коды загружены: {item.downloadedCodes} из {item.quantity}</p>
                    {item.canRetryIntroduction ? (
                      <button className="secondary-button" type="button" disabled={!canMutate} onClick={() => setRetrying(item)} aria-label={`Повторить ввод в оборот для ${item.gtin}`}>
                        <RotateCcw aria-hidden="true" size={15} /> Повторить только ввод
                      </button>
                    ) : null}
                  </div>
                </div>
                <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-[var(--surface-muted)]" aria-label={`Прогресс ${item.progress}%`} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={item.progress}>
                  <div className="h-full rounded-full bg-[var(--accent)] transition-[width]" style={{ width: `${item.progress}%` }} />
                </div>
                {detail ? <p className="mt-3 flex items-start gap-2 text-xs leading-5 text-[var(--text-secondary)]"><AlertTriangle aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--warning)]" size={14} />{detail}</p> : null}
                <p className="mt-3 flex items-center gap-1.5 text-[11px] text-[var(--text-muted)]"><Clock3 aria-hidden="true" size={13} />Обновлено {new Date(item.updatedAt).toLocaleString("ru-RU")}</p>
              </li>
            );
          })}
        </ul>
      ) : null}
      {data ? <Pagination page={page} hasMore={data.hasMore} onPage={changePage} label="покупок Znack" /> : null}
      {retrying ? (
        <div className="fixed inset-0 z-50 grid place-items-center bg-black/45 p-4" role="dialog" aria-modal="true" aria-labelledby="retry-introduction-title">
          <div className="w-full max-w-lg rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl">
            <div className="flex items-start justify-between gap-4"><div><h4 id="retry-introduction-title" className="text-lg font-semibold">Повторить ввод в оборот?</h4><p className="mt-1 text-sm text-[var(--text-muted)]">GTIN {retrying.gtin}</p></div><button className="icon-button" type="button" aria-label="Закрыть подтверждение ввода" disabled={submittingRetry} onClick={() => setRetrying(null)}><X aria-hidden="true" size={18} /></button></div>
            <p className="mt-4 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-3 text-sm leading-6"><strong className="block">Коды уже куплены и не будут заказаны повторно.</strong><span className="text-[var(--text-secondary)]">Будет повторно отправлен только документ ввода.</span></p>
            <div className="mt-5 flex justify-end gap-2"><button className="secondary-button" type="button" disabled={submittingRetry} onClick={() => setRetrying(null)}>Отмена</button><button className="primary-button" type="button" disabled={!canMutate || submittingRetry} onClick={() => void confirmRetry()}>{submittingRetry ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} /> : <CheckCircle2 aria-hidden="true" size={16} />}{submittingRetry ? "Запуск…" : "Подтвердить повтор ввода в оборот"}</button></div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

const ACTION_COPY: Record<string, string> = {
  buy_kiz: "Покупка КИЗ",
  download_codes: "Загрузка кодов",
  purchase_pipeline: "Пайплайн покупки",
  introduction: "Ввод в оборот",
  product_sync: "Синхронизация товаров",
  operation: "Операция Znack",
};
const MESSAGE_COPY: Record<string, string> = {
  completed: "Операция выполнена",
  attention: "Требуется внимание",
  missing_documents: "Не хватает документов",
  missing_metadata: "Не хватает данных GTIN",
  authentication_failed: "Ошибка авторизации",
  rate_limited: "Превышен лимит запросов",
  timeout: "Истекло время ожидания",
  upstream_error: "Ошибка внешнего сервиса",
};

function validLogs(response: LogsResponse, shopId: number, page: number) {
  return response !== null && response.shopId === shopId && response.page === page && response.pageSize === PAGE_SIZE
    && Array.isArray(response.items) && response.items.length <= PAGE_SIZE && response.items.every((item) =>
      item !== null
      && LOG_ACTIONS.has(item.action)
      && LOG_SEVERITIES.has(item.severity)
      && LOG_MESSAGES.has(item.messageKind)
      && HTTP_CLASSES.has(item.httpClass)
      && (item.entityGtin === "" || /^\d{14}$/.test(item.entityGtin))
      && typeof item.createdAt === "string"
      && !Number.isNaN(Date.parse(item.createdAt)));
}

export function ZnackLogsPanel({ shopId }: { shopId: number }) {
  const [page, setPage] = useState(1);
  const [reload, setReload] = useState(0);
  const [state, setState] = useState<LogState>({ status: "loading" });
  useEffect(() => {
    let active = true;
    void commands.znack.operationLogs({ shopId, page, pageSize: PAGE_SIZE }).then(
      (response) => {
        if (active) setState(validLogs(response, shopId, page) ? { status: "ready", data: response } : { status: "error" });
      },
      () => { if (active) setState({ status: "error" }); },
    );
    return () => { active = false; };
  }, [page, reload, shopId]);
  const data = state.status === "ready" && state.data.shopId === shopId && state.data.page === page
    ? state.data : null;
  const changePage = (next: number) => {
    setState({ status: "loading" });
    setPage(next);
  };
  const reloadPage = () => {
    setState({ status: "loading" });
    setReload((value) => value + 1);
  };
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-base font-semibold">Журнал операций Znack</h4>
          <p className="mt-1 text-xs text-[var(--text-muted)]">Без содержимого запросов, токенов, исходных ошибок и внутренних идентификаторов.</p>
        </div>
        <button className="secondary-button" type="button" onClick={reloadPage} aria-label="Обновить журнал Znack">
          <RefreshCw aria-hidden="true" size={16} />Обновить
        </button>
      </div>
      {state.status === "loading" || (state.status === "ready" && data === null) ? <PanelState loading label="Загрузка журнала Znack" /> : null}
      {state.status === "error" ? <PanelState label="Не удалось загрузить журнал Znack" onRetry={reloadPage} /> : null}
      {data?.items.length === 0 ? <div className="grid min-h-64 place-items-center rounded-xl border border-dashed border-[var(--border-subtle)] bg-[var(--surface-muted)] text-center"><div><ScrollText aria-hidden="true" className="mx-auto text-[var(--text-muted)]" size={28} /><h5 className="mt-3 font-semibold">Журнал пока пуст</h5></div></div> : null}
      {data && data.items.length > 0 ? <ul className="divide-y divide-[var(--border-subtle)] overflow-hidden rounded-xl border border-[var(--border-subtle)]">{data.items.map((item: LogItem, index) => <li key={`${item.createdAt}-${index}`} className="grid gap-3 bg-[var(--surface-elevated)] px-4 py-4 md:grid-cols-[minmax(10rem,.7fr)_minmax(12rem,1fr)_auto] md:items-center"><div><p className="text-sm font-semibold">{ACTION_COPY[item.action] ?? "Операция Znack"}</p><p className="mt-1 text-xs text-[var(--text-muted)]">{item.entityGtin || "Системная операция"}</p></div><div><p className="text-sm text-[var(--text-secondary)]">{MESSAGE_COPY[item.messageKind] ?? "Состояние обновлено"}</p><p className="mt-1 text-xs text-[var(--text-muted)]">{new Date(item.createdAt).toLocaleString("ru-RU")}</p></div><div className="flex items-center gap-2"><span className={`status-pill ${item.severity === "error" ? "status-danger" : item.severity === "warning" ? "status-warning" : "status-success"}`}>{item.severity === "error" ? "Ошибка" : item.severity === "warning" ? "Внимание" : "Информация"}</span>{item.httpClass ? <span className="text-xs font-semibold text-[var(--text-muted)]">HTTP {item.httpClass}</span> : null}</div></li>)}</ul> : null}
      {data ? <Pagination page={page} hasMore={data.hasMore} onPage={changePage} label="журнала Znack" /> : null}
    </div>
  );
}

function Pagination({ page, hasMore, onPage, label }: { page: number; hasMore: boolean; onPage: (page: number) => void; label: string }) {
  return <div className="flex items-center justify-between gap-3"><button className="secondary-button" type="button" disabled={page <= 1} onClick={() => onPage(page - 1)} aria-label={`Предыдущая страница ${label}`}><ChevronLeft aria-hidden="true" size={16} />Назад</button><span className="text-sm font-medium text-[var(--text-secondary)]">Страница {page}</span><button className="secondary-button" type="button" disabled={!hasMore} onClick={() => onPage(page + 1)} aria-label={`Следующая страница ${label}`}>Вперёд<ChevronRight aria-hidden="true" size={16} /></button></div>;
}

function PanelState({ loading = false, label, onRetry }: { loading?: boolean; label: string; onRetry?: () => void }) {
  return <div className="grid min-h-56 place-items-center text-center" role={loading ? "status" : "alert"} aria-label={label}><div>{loading ? <RefreshCw aria-hidden="true" className="mx-auto animate-spin text-[var(--accent)]" size={24} /> : <><p className="font-semibold">{label}</p><button className="secondary-button mt-4" type="button" onClick={onRetry}><RefreshCw aria-hidden="true" size={16} />Повторить</button></>}</div></div>;
}
