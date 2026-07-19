import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { ZnackView } from "./ZnackView";
import { getZnackCopy } from "./znackI18n";

vi.mock("../../generated/commands", () => ({
  commands: {
    znack: {
      products: vi.fn(),
      discoverCertificates: vi.fn(),
      testCertificate: vi.fn(),
      startProductSync: vi.fn(),
      productSyncStatus: vi.fn(),
      cancelProductSync: vi.fn(),
      operationLogs: vi.fn(),
      preparePurchase: vi.fn(),
      purchaseStatus: vi.fn(),
      purchases: vi.fn(),
      retryIntroduction: vi.fn(),
      saveSettings: vi.fn(),
      setProductVisibility: vi.fn(),
      startPurchase: vi.fn(),
      settings: vi.fn(),
    },
  },
}));

const loadSettings = vi.mocked(commands.znack.settings);
const saveSettings = vi.mocked(commands.znack.saveSettings);
const loadProducts = vi.mocked(commands.znack.products);
const setVisibility = vi.mocked(commands.znack.setProductVisibility);
const discoverCertificates = vi.mocked(commands.znack.discoverCertificates);
const testCertificate = vi.mocked(commands.znack.testCertificate);
const startProductSync = vi.mocked(commands.znack.startProductSync);
const productSyncStatus = vi.mocked(commands.znack.productSyncStatus);
const cancelProductSync = vi.mocked(commands.znack.cancelProductSync);
const operationLogs = vi.mocked(commands.znack.operationLogs);
const preparePurchase = vi.mocked(commands.znack.preparePurchase);
const purchaseStatus = vi.mocked(commands.znack.purchaseStatus);
const loadPurchases = vi.mocked(commands.znack.purchases);
const retryIntroduction = vi.mocked(commands.znack.retryIntroduction);
const startPurchase = vi.mocked(commands.znack.startPurchase);
const gtin = "04601234567890";
const secondGtin = "04601234567891";
const secret = "znack-private-selector-must-not-enter-the-dom";
const purchaseId = "44444444-4444-4444-8444-444444444444";

function settings(version = "a".repeat(64)) {
  return {
    shopId: 7,
    omsId: "OMS-7",
    omsConnection: "CONNECTION-7",
    documentNumber: "DOC-7",
    documentDate: "18.07.2026",
    autoIntroduction: true,
    signatureStatus: "VERIFIED",
    certificateLabel: "ООО Маркировка",
    certificateValidTo: "2027-07-18",
    version,
  };
}

function product(currentGtin = gtin, name = "Ботинки Alpine", deleted = false) {
  return {
    gtin: currentGtin,
    productName: name,
    category: "Обувь",
    tnVed: "6403",
    cisType: "UNIT",
    goodMarkStatus: "READY",
    goodTurnStatus: "NOT_READY",
    readinessCheckedAt: "2026-07-18T00:00:00Z",
    deleted,
  };
}

function purchase(stage = "polling_order", state = "running") {
  return {
    purchaseId,
    gtin,
    productName: "Ботинки Alpine",
    quantity: 2,
    stage,
    state,
    downloadedCodes: state === "completed" ? 2 : 0,
    progress: state === "completed" ? 100 : 35,
    errorKind: "",
    retryable: state === "running",
    canRetryIntroduction: false,
    createdAt: "2026-07-18T00:00:00Z",
    updatedAt: "2026-07-18T00:01:00Z",
  };
}

describe("ZnackView", () => {
  beforeEach(() => {
    loadSettings.mockReset();
    saveSettings.mockReset();
    loadProducts.mockReset();
    setVisibility.mockReset();
    discoverCertificates.mockReset();
    testCertificate.mockReset();
    startProductSync.mockReset();
    productSyncStatus.mockReset();
    cancelProductSync.mockReset();
    operationLogs.mockReset();
    preparePurchase.mockReset();
    purchaseStatus.mockReset();
    loadPurchases.mockReset();
    retryIntroduction.mockReset();
    startPurchase.mockReset();
    loadSettings.mockResolvedValue(settings());
    saveSettings.mockResolvedValue(settings("b".repeat(64)));
    loadProducts.mockImplementation(async (request) => ({
      ...request,
      hasMore: request.page === 1,
      availableCategories: ["Обувь", "Одежда"],
      items: request.deleted
        ? [product(secondGtin, "Скрытая куртка", true)]
        : request.page === 1 ? [product()] : [product(secondGtin, "Кеды North")],
    }));
    setVisibility.mockImplementation(async (request) => ({
      shopId: request.shopId,
      deleted: request.deleted,
      changed: request.gtins.length,
    }));
    discoverCertificates.mockResolvedValue({
      shopId: 7,
      sessionId: "11111111-1111-4111-8111-111111111111",
      expiresAt: "2026-07-18T00:10:00Z",
      items: [{
        certificateId: "22222222-2222-4222-8222-222222222222",
        label: "ООО Новый владелец",
        inn: "7700000000",
        validFrom: "2025-07-18",
        validTo: "2027-07-18",
        hasPrivateKey: true,
        status: "SELECTABLE",
      }],
    });
    testCertificate.mockResolvedValue({
      ...settings("c".repeat(64)),
      certificateLabel: "ООО Новый владелец",
    });
    startProductSync.mockResolvedValue({
      accepted: true,
      shopId: 7,
      jobId: "33333333-3333-4333-8333-333333333333",
    });
    productSyncStatus.mockResolvedValue({
      jobId: "33333333-3333-4333-8333-333333333333",
      shopId: 7,
      state: "completed",
      phase: "completed",
      products: 42,
      completedAt: "2026-07-18T00:01:00Z",
      errorKind: "",
      retryable: false,
    });
    cancelProductSync.mockResolvedValue({
      cancelRequested: true,
      shopId: 7,
      jobId: "33333333-3333-4333-8333-333333333333",
    });
    preparePurchase.mockResolvedValue({
      shopId: 7,
      purchaseId,
      gtin,
      productName: "Ботинки Alpine",
      quantity: 2,
      autoIntroduction: true,
      warnings: ["automatic_introduction"],
      expiresAt: "2026-07-18T00:10:00Z",
      version: "a".repeat(64),
    });
    startPurchase.mockResolvedValue({ accepted: true, purchase: purchase() });
    loadPurchases.mockResolvedValue({
      shopId: 7,
      page: 1,
      pageSize: 50,
      hasMore: false,
      items: [purchase()],
    });
    purchaseStatus.mockResolvedValue(purchase("completed", "completed"));
    retryIntroduction.mockResolvedValue(purchase("waiting_introduction_readiness", "running"));
    operationLogs.mockResolvedValue({
      shopId: 7,
      page: 1,
      pageSize: 50,
      hasMore: false,
      items: [{
        action: "purchase_pipeline",
        entityGtin: gtin,
        severity: "error",
        messageKind: "upstream_error",
        httpClass: "5xx",
        createdAt: "2026-07-18T00:02:00Z",
      }],
    });
  });

  it("loads safe settings and saves only editable fields with the opaque version", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);

    expect(await screen.findByDisplayValue("OMS-7")).toBeVisible();
    expect(screen.getByText("Подпись проверена")).toBeVisible();
    expect(screen.getByText("ООО Маркировка")).toBeVisible();
    const save = screen.getByRole("button", { name: "Сохранить настройки Znack" });
    expect(save).toBeDisabled();

    const oms = screen.getByRole("textbox", { name: "OMS ID" });
    await user.clear(oms);
    await user.type(oms, "OMS-8");
    await user.clear(screen.getByRole("textbox", { name: "Соединение OMS" }));
    await user.type(screen.getByRole("textbox", { name: "Соединение OMS" }), "CONNECTION-8");
    await user.click(save);

    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith({
      shopId: 7,
      omsId: "OMS-8",
      omsConnection: "CONNECTION-8",
      documentNumber: "DOC-7",
      documentDate: "18.07.2026",
      autoIntroduction: true,
      version: "a".repeat(64),
    }));
    expect(await screen.findByText("Настройки Znack сохранены")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("loads bounded active product pages with exact search and category filters", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));

    expect(await screen.findByText("Ботинки Alpine")).toBeVisible();
    expect(loadProducts).toHaveBeenLastCalledWith({
      shopId: 7,
      query: "",
      categories: [],
      deleted: false,
      page: 1,
      pageSize: 50,
    });
    expect(screen.getByText("Маркировка готова")).toBeVisible();
    expect(screen.getByText("Оборот не готов")).toBeVisible();

    await user.type(screen.getByRole("searchbox", { name: "Поиск товаров Znack" }), " Alpine ");
    await user.click(screen.getByRole("button", { name: "Найти товар Znack" }));
    await waitFor(() => expect(loadProducts).toHaveBeenLastCalledWith(expect.objectContaining({
      query: "Alpine",
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: "Категории товаров Znack" }));
    await user.click(screen.getByRole("checkbox", { name: "Одежда" }));
    await waitFor(() => expect(loadProducts).toHaveBeenLastCalledWith(expect.objectContaining({
      categories: ["Одежда"],
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: "Следующая страница товаров Znack" }));
    expect(await screen.findByText("Кеды North")).toBeVisible();
    expect(screen.getByText("Страница 2")).toBeVisible();
  });

  it("discovers and tests a certificate using only opaque ids and the current version", async () => {
    const user = userEvent.setup();
    loadSettings.mockResolvedValue(settings());
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");

    await user.click(screen.getByRole("button", { name: "Найти сертификаты CryptoPro" }));
    expect(await screen.findByText("ООО Новый владелец")).toBeVisible();
    expect(screen.getByText("ИНН 7700000000")).toBeVisible();
    await user.click(screen.getByRole("radio", { name: /ООО Новый владелец/ }));
    await user.click(screen.getByRole("button", { name: "Проверить выбранный сертификат" }));

    expect(testCertificate).toHaveBeenCalledWith({
      shopId: 7,
      sessionId: "11111111-1111-4111-8111-111111111111",
      certificateId: "22222222-2222-4222-8222-222222222222",
      version: "a".repeat(64),
    });
    expect(await screen.findByText("Сертификат проверен и сохранён")).toBeVisible();
    expect(screen.getByText("ООО Новый владелец")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("runs participant product sync as a polled job and refreshes the local catalog", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    await screen.findByText("Ботинки Alpine");
    const readsBeforeSync = loadProducts.mock.calls.length;

    await user.click(screen.getByRole("button", { name: "Синхронизировать товары Znack" }));

    expect(startProductSync).toHaveBeenCalledWith({ shopId: 7, version: "a".repeat(64) });
    await waitFor(() => expect(productSyncStatus).toHaveBeenCalledWith({
      shopId: 7,
      jobId: "33333333-3333-4333-8333-333333333333",
    }));
    expect(await screen.findByText("Синхронизировано товаров: 42")).toBeVisible();
    await waitFor(() => expect(loadProducts.mock.calls.length).toBeGreaterThan(readsBeforeSync));
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("requests cooperative cancellation for a running product sync job", async () => {
    const user = userEvent.setup();
    productSyncStatus.mockResolvedValue({
      jobId: "33333333-3333-4333-8333-333333333333",
      shopId: 7,
      state: "running",
      phase: "downloading",
      products: 0,
      completedAt: "",
      errorKind: "",
      retryable: false,
    });
    const view = render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    await screen.findByText("Ботинки Alpine");
    await user.click(screen.getByRole("button", { name: "Синхронизировать товары Znack" }));

    const stop = await screen.findByRole("button", { name: "Остановить синхронизацию товаров Znack" });
    await user.click(stop);

    expect(cancelProductSync).toHaveBeenCalledWith({
      shopId: 7,
      jobId: "33333333-3333-4333-8333-333333333333",
    });
    view.unmount();
  });

  it("hides and restores bounded selections then refreshes the current list", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    await screen.findByText("Ботинки Alpine");

    await user.click(screen.getByRole("checkbox", { name: `Выбрать GTIN ${gtin}` }));
    await user.click(screen.getByRole("button", { name: "Скрыть выбранные GTIN" }));
    await waitFor(() => expect(setVisibility).toHaveBeenCalledWith({
      shopId: 7,
      gtins: [gtin],
      deleted: true,
    }));
    expect(await screen.findByText("Скрыто GTIN: 1")).toBeVisible();

    await user.click(screen.getByRole("tab", { name: "Скрытые" }));
    expect(await screen.findByText("Скрытая куртка")).toBeVisible();
    const hiddenPanel = screen.getByRole("tabpanel", { name: "Скрытые" });
    await user.click(within(hiddenPanel).getByRole("checkbox", { name: `Выбрать GTIN ${secondGtin}` }));
    await user.click(within(hiddenPanel).getByRole("button", { name: "Восстановить выбранные GTIN" }));
    await waitFor(() => expect(setVisibility).toHaveBeenLastCalledWith({
      shopId: 7,
      gtins: [secondGtin],
      deleted: false,
    }));
    expect(await screen.findByText("Восстановлено GTIN: 1")).toBeVisible();
  });

  it("shows generic retry states and a safe empty product state", async () => {
    const user = userEvent.setup();
    loadSettings
      .mockRejectedValueOnce(new Error(`settings failed ${secret}`))
      .mockResolvedValueOnce(settings());
    render(<ZnackView shopId={7} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить настройки Znack");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить загрузку настроек" }));
    expect(await screen.findByDisplayValue("OMS-7")).toBeVisible();

    loadProducts.mockResolvedValueOnce({
      shopId: 7,
      query: "",
      categories: [],
      deleted: false,
      page: 1,
      pageSize: 50,
      hasMore: false,
      availableCategories: [],
      items: [],
    });
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    expect(await screen.findByText("Локальный каталог Znack пока пуст")).toBeVisible();
  });

  it("previews and explicitly confirms a KIZ purchase before showing persisted progress", async () => {
    const user = userEvent.setup();
    purchaseStatus
      .mockResolvedValueOnce(purchase("downloading_codes", "running"))
      .mockResolvedValueOnce(purchase("completed", "completed"));
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));
    await screen.findByText("Ботинки Alpine");

    await user.click(screen.getByRole("button", { name: `Купить КИЗ для ${gtin}` }));
    const quantity = screen.getByRole("spinbutton", { name: "Количество КИЗ" });
    await user.clear(quantity);
    await user.type(quantity, "2");
    await user.click(screen.getByRole("button", { name: "Подготовить покупку" }));

    expect(preparePurchase).toHaveBeenCalledWith({
      shopId: 7,
      gtin,
      quantity: 2,
      version: "a".repeat(64),
    });
    expect(await screen.findByText("Автоматический ввод в оборот включён")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Подтвердить покупку КИЗ" }));
    expect(startPurchase).toHaveBeenCalledWith({
      shopId: 7,
      purchaseId,
      version: "a".repeat(64),
      confirmed: true,
    });

    expect(await screen.findByRole("tab", { name: "Покупки" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByText("Коды загружены: 2 из 2")).toBeVisible();
    await waitFor(() => expect(purchaseStatus).toHaveBeenCalledTimes(2));
    expect(purchaseStatus).toHaveBeenLastCalledWith({ shopId: 7, purchaseId });
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("keeps paid KIZ purchase unavailable when the shared license oracle denies it", async () => {
    const user = userEvent.setup();
    render(<ZnackView shopId={7} licenseAllowed={false} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Товары" }));

    const buy = await screen.findByRole("button", { name: `Купить КИЗ для ${gtin}` });
    expect(buy).toBeDisabled();
    expect(preparePurchase).not.toHaveBeenCalled();
  });

  it("shows bounded purchase recovery and sanitized operation journal", async () => {
    const user = userEvent.setup();
    loadPurchases.mockResolvedValueOnce({
      shopId: 7,
      page: 1,
      pageSize: 50,
      hasMore: false,
      items: [{
        ...purchase("introduction_failed", "attention"),
        downloadedCodes: 2,
        progress: 100,
        errorKind: "introduction_failed",
        retryable: true,
        canRetryIntroduction: true,
      }],
    });
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");
    await user.click(screen.getByRole("tab", { name: "Покупки" }));

    expect(await screen.findByText("Ввод в оборот требует внимания")).toBeVisible();
    await user.click(screen.getByRole("button", { name: `Повторить ввод в оборот для ${gtin}` }));
    expect(await screen.findByText("Коды уже куплены и не будут заказаны повторно.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Подтвердить повтор ввода в оборот" }));
    expect(retryIntroduction).toHaveBeenCalledWith({
      shopId: 7,
      purchaseId,
      version: "a".repeat(64),
      confirmed: true,
    });

    await user.click(screen.getByRole("tab", { name: "Журнал" }));
    expect(await screen.findByText("Ошибка внешнего сервиса")).toBeVisible();
    expect(screen.getByText("HTTP 5xx")).toBeVisible();
    expect(operationLogs).toHaveBeenCalledWith({ shopId: 7, page: 1, pageSize: 50 });
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("rejects malformed purchase and journal responses before rendering bridge data", async () => {
    const user = userEvent.setup();
    loadPurchases.mockResolvedValueOnce({
      shopId: 7,
      page: 1,
      pageSize: 50,
      hasMore: false,
      items: [{ ...purchase(), stage: secret }],
    });
    operationLogs.mockResolvedValueOnce({
      shopId: 7,
      page: 1,
      pageSize: 50,
      hasMore: false,
      items: [{
        action: "purchase_pipeline",
        entityGtin: secret,
        severity: "error",
        messageKind: "upstream_error",
        httpClass: "5xx",
        createdAt: "2026-07-18T00:02:00Z",
      }],
    });
    render(<ZnackView shopId={7} />);
    await screen.findByDisplayValue("OMS-7");

    await user.click(screen.getByRole("tab", { name: "Покупки" }));
    expect(await screen.findByText("Не удалось загрузить покупки Znack")).toBeVisible();
    await user.click(screen.getByRole("tab", { name: "Журнал" }));
    expect(await screen.findByText("Не удалось загрузить журнал Znack")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("localizes the English product workspace and keeps paid preparation explicit", async () => {
    const user = userEvent.setup();
    render(<ZnackView copy={getZnackCopy("en")} locale="en-US" shopId={7} />);

    expect(await screen.findByRole("heading", { name: "Secure Znack workspace" })).toBeVisible();
    expect(screen.getByText("Signature verified")).toBeVisible();
    await user.click(screen.getByRole("tab", { name: "Products" }));

    expect(await screen.findByText("Ботинки Alpine")).toBeVisible();
    expect(screen.getByText("Marking ready")).toBeVisible();
    expect(screen.getByText("Introduction not ready")).toBeVisible();
    await user.click(screen.getByRole("button", { name: `Buy KIZ for ${gtin}` }));
    const dialog = await screen.findByRole("dialog", { name: "Prepare KIZ purchase" });
    expect(within(dialog).getByText(/may create a paid Znack order/)).toBeVisible();
    expect(preparePurchase).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole("button", { name: "Close KIZ purchase" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(preparePurchase).not.toHaveBeenCalled();
    expect(startProductSync).not.toHaveBeenCalled();
    expect(discoverCertificates).not.toHaveBeenCalled();
  });

  it("localizes the English persisted purchases and sanitized operation log", async () => {
    const user = userEvent.setup();
    render(<ZnackView copy={getZnackCopy("en")} locale="en-US" shopId={7} />);
    await screen.findByRole("heading", { name: "Secure Znack workspace" });

    await user.click(screen.getByRole("tab", { name: "Purchases" }));
    expect(await screen.findByRole("heading", { name: "KIZ purchases and introduction" })).toBeVisible();
    expect(screen.getByText("Waiting for codes · Ordered: 2")).toBeVisible();

    await user.click(screen.getByRole("tab", { name: "Log" }));
    expect(await screen.findByRole("heading", { name: "Znack operation log" })).toBeVisible();
    expect(screen.getByText("External service error")).toBeVisible();
    expect(retryIntroduction).not.toHaveBeenCalled();
    expect(preparePurchase).not.toHaveBeenCalled();
    expect(startPurchase).not.toHaveBeenCalled();
  });
});
