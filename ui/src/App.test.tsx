import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { JDeskError } from "jdesk-client";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { commands } from "./generated/commands";
import { exportFboPdf, exportSupplyPdf, reprintHistoryPdf } from "./features/printing/nativePrintCommands";
import { getCopy, type Language } from "./i18n";

vi.mock("./generated/commands", () => ({
  commands: {
    workspace: { bootstrap: vi.fn() },
    dashboard: { load: vi.fn() },
    diagnostics: { summary: vi.fn(), export: vi.fn() },
    fbo: {
      catalog: vi.fn(),
      export: vi.fn(),
      openExport: vi.fn(),
    },
    kizMapping: {
      catalog: vi.fn(),
      editor: vi.fn(),
      save: vi.fn(),
    },
    license: {
      activate: vi.fn(),
      deactivate: vi.fn(),
      refresh: vi.fn(),
      status: vi.fn(),
    },
    preferences: {
      load: vi.fn(),
      setLanguage: vi.fn(),
      setTheme: vi.fn(),
    },
    shops: {
      create: vi.fn(),
      delete: vi.fn(),
      list: vi.fn(),
      select: vi.fn(),
      update: vi.fn(),
    },
    znack: {
      products: vi.fn(),
      saveSettings: vi.fn(),
      setProductVisibility: vi.fn(),
      settings: vi.fn(),
    },
    supplies: {
      list: vi.fn(),
      detail: vi.fn(),
      refresh: vi.fn(),
      refreshStatus: vi.fn(),
      cancelRefresh: vi.fn(),
    },
    orders: {
      importExcel: vi.fn(),
      importedPage: vi.fn(),
    },
    packing: {
      board: vi.fn(),
    },
    printing: {
      setup: vi.fn(),
      saveOptions: vi.fn(),
      exportSupply: vi.fn(),
      openExport: vi.fn(),
      history: vi.fn(),
      reprintHistory: vi.fn(),
      openHistoryReprint: vi.fn(),
    },
    templates: {
      create: vi.fn(),
      createElement: vi.fn(),
      delete: vi.fn(),
      duplicate: vi.fn(),
      loadDesigner: vi.fn(),
      rename: vi.fn(),
      reset: vi.fn(),
      save: vi.fn(),
      setDefault: vi.fn(),
    },
    wildberries: {
      syncOverview: vi.fn(),
      syncStatus: vi.fn(),
      cancelSync: vi.fn(),
    },
  },
}));

vi.mock("./features/printing/nativePrintCommands", () => ({
  exportFboPdf: vi.fn(),
  exportSupplyPdf: vi.fn(),
  reprintHistoryPdf: vi.fn(),
}));

const bootstrap = vi.mocked(commands.workspace.bootstrap);
const loadDashboard = vi.mocked(commands.dashboard.load);
const loadDiagnostics = vi.mocked(commands.diagnostics.summary);
const loadFboCatalog = vi.mocked(commands.fbo.catalog);
const exportFbo = vi.mocked(exportFboPdf);
const openFboExport = vi.mocked(commands.fbo.openExport);
const loadKizMappingCatalog = vi.mocked(commands.kizMapping.catalog);
const loadLicenseStatus = vi.mocked(commands.license.status);
const refreshLicense = vi.mocked(commands.license.refresh);
const loadPreferences = vi.mocked(commands.preferences.load);
const setLanguage = vi.mocked(commands.preferences.setLanguage);
const setTheme = vi.mocked(commands.preferences.setTheme);
const selectShopCommand = vi.mocked(commands.shops.select);
const loadZnackSettings = vi.mocked(commands.znack.settings);
const listSupplies = vi.mocked(commands.supplies.list);
const loadSupplyDetail = vi.mocked(commands.supplies.detail);
const refreshSupply = vi.mocked(commands.supplies.refresh);
const refreshSupplyStatus = vi.mocked(commands.supplies.refreshStatus);
const cancelSupplyRefresh = vi.mocked(commands.supplies.cancelRefresh);
const importExcel = vi.mocked(commands.orders.importExcel);
const loadImportedOrders = vi.mocked(commands.orders.importedPage);
const loadPackingBoard = vi.mocked(commands.packing.board);
const loadPrintSetup = vi.mocked(commands.printing.setup);
const savePrintOptions = vi.mocked(commands.printing.saveOptions);
const exportSupplyPdfCommand = vi.mocked(exportSupplyPdf);
const openExportedPdf = vi.mocked(commands.printing.openExport);
const loadPrintHistory = vi.mocked(commands.printing.history);
const reprintHistory = vi.mocked(reprintHistoryPdf);
const openHistoryReprint = vi.mocked(commands.printing.openHistoryReprint);
const loadTemplateDesigner = vi.mocked(commands.templates.loadDesigner);
const createTemplate = vi.mocked(commands.templates.create);
const createTemplateElement = vi.mocked(commands.templates.createElement);
const deleteTemplate = vi.mocked(commands.templates.delete);
const duplicateTemplate = vi.mocked(commands.templates.duplicate);
const renameTemplate = vi.mocked(commands.templates.rename);
const resetTemplate = vi.mocked(commands.templates.reset);
const saveTemplate = vi.mocked(commands.templates.save);
const setDefaultTemplate = vi.mocked(commands.templates.setDefault);
const syncOverview = vi.mocked(commands.wildberries.syncOverview);
const syncStatus = vi.mocked(commands.wildberries.syncStatus);
const cancelSync = vi.mocked(commands.wildberries.cancelSync);
const secret = "wb-secret-that-must-not-enter-the-dom";

function editableDesigner(mode: string, id: string, name: string) {
  return {
    mode,
    pageWidthMm: 58,
    pageHeightMm: 40,
    maxTemplates: 100,
    maxElements: 100,
    templates: [{
      id,
      name,
      defaultTemplate: true,
      elements: [{
        id: "kiz",
        type: "kiz_datamatrix",
        fieldKey: "",
        label: "KIZ",
        prefix: "",
        content: "",
        xMm: 2,
        yMm: 3,
        widthMm: 18,
        heightMm: 18,
        visible: true,
        zIndex: 1,
        fontSizePt: 8,
        bold: false,
        align: "left",
        humanReadable: false,
      }, {
        id: "barcode",
        type: "barcode_code128",
        fieldKey: "",
        label: "Штрихкод",
        prefix: "",
        content: "",
        xMm: 2,
        yMm: 27,
        widthMm: 53,
        heightMm: 8,
        visible: true,
        zIndex: 2,
        fontSizePt: 8,
        bold: false,
        align: "center",
        humanReadable: true,
      }, {
        id: "tail",
        type: "sticker_tail",
        fieldKey: "",
        label: "Стикер",
        prefix: "",
        content: "",
        xMm: 47,
        yMm: 36,
        widthMm: 8,
        heightMm: 3,
        visible: true,
        zIndex: 3,
        fontSizePt: 9,
        bold: true,
        align: "left",
        humanReadable: false,
      }, {
        id: "article",
        type: "text_field",
        fieldKey: "article",
        label: "Артикул",
        prefix: "Арт. ",
        content: "",
        xMm: 22,
        yMm: 12,
        widthMm: 34,
        heightMm: 5,
        visible: true,
        zIndex: 4,
        fontSizePt: 8,
        bold: true,
        align: "left",
        humanReadable: false,
      }],
    }],
    palette: [{ key: "static_text", label: "Текст", type: "static_text", fieldKey: "" }],
  } as Awaited<ReturnType<typeof commands.templates.loadDesigner>>;
}

describe("App", () => {
  beforeEach(() => {
    document.documentElement.lang = "ru";
    document.documentElement.dataset.theme = "dark";
    bootstrap.mockReset();
    loadDashboard.mockReset();
    loadDiagnostics.mockReset();
    loadFboCatalog.mockReset();
    exportFbo.mockReset();
    openFboExport.mockReset();
    loadKizMappingCatalog.mockReset();
    loadLicenseStatus.mockReset();
    refreshLicense.mockReset();
    loadPreferences.mockReset();
    setLanguage.mockReset();
    setTheme.mockReset();
    selectShopCommand.mockReset();
    loadZnackSettings.mockReset();
    listSupplies.mockReset();
    loadSupplyDetail.mockReset();
    refreshSupply.mockReset();
    refreshSupplyStatus.mockReset();
    cancelSupplyRefresh.mockReset();
    importExcel.mockReset();
    loadImportedOrders.mockReset();
    loadPackingBoard.mockReset();
    loadPrintSetup.mockReset();
    savePrintOptions.mockReset();
    exportSupplyPdfCommand.mockReset();
    openExportedPdf.mockReset();
    loadPrintHistory.mockReset();
    reprintHistory.mockReset();
    openHistoryReprint.mockReset();
    loadTemplateDesigner.mockReset();
    createTemplate.mockReset();
    createTemplateElement.mockReset();
    deleteTemplate.mockReset();
    duplicateTemplate.mockReset();
    renameTemplate.mockReset();
    resetTemplate.mockReset();
    saveTemplate.mockReset();
    setDefaultTemplate.mockReset();
    syncOverview.mockReset();
    syncStatus.mockReset();
    cancelSync.mockReset();
    loadLicenseStatus.mockResolvedValue({
      status: "not_activated",
      kizAllowed: false,
      hasStoredKey: false,
      plan: "",
      issuedAt: "",
      expiresAt: "",
      offlineGraceEndsAt: "",
      daysRemaining: 0,
      errorKind: "",
    });
    refreshLicense.mockResolvedValue({
      status: "not_activated",
      kizAllowed: false,
      hasStoredKey: false,
      plan: "",
      issuedAt: "",
      expiresAt: "",
      offlineGraceEndsAt: "",
      daysRemaining: 0,
      errorKind: "",
    });
    loadPreferences.mockResolvedValue({ language: "ru", theme: "dark" });
    setLanguage.mockImplementation(async ({ language }) => ({ language, theme: "dark" }));
    setTheme.mockImplementation(async ({ theme }) => ({ language: "ru", theme }));
    loadKizMappingCatalog.mockImplementation(async (request) => ({
      ...request,
      hasMore: false,
      availableCategories: [],
      items: [],
    }));
    loadZnackSettings.mockImplementation(async ({ shopId }) => ({
      shopId,
      omsId: "",
      omsConnection: "",
      documentNumber: "",
      documentDate: "",
      autoIntroduction: false,
      signatureStatus: "UNCONFIGURED",
      certificateLabel: "",
      certificateValidTo: "",
      version: "a".repeat(64),
    }));
  });

  it("keeps implementation terminology out of everyday interface copy", () => {
    const forbidden = /jdesk|javafx|webview|java bridge|selector|thumbprint|dto|capability|idempotent|идемпотент|幂等|pagination|phân trang/i;

    for (const language of ["ru", "en", "vi", "zh"] satisfies Language[]) {
      const copy = getCopy(language);
      const everydayCopy = JSON.stringify({
        shell: copy.shell,
        interfaceDescription: copy.settings.interfaceDescription,
        licenseKeyHint: copy.settings.license.keyHint,
      });
      expect(everydayCopy).not.toMatch(forbidden);
    }
  });

  it("opens and closes compact navigation from an accessible mobile menu button", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    render(<App />);

    const openMenu = await screen.findByRole("button", { name: "Открыть меню" });
    expect(openMenu).toHaveAttribute("aria-expanded", "false");
    await user.click(openMenu);

    const closeMenu = screen.getByRole("button", { name: "Закрыть меню" });
    expect(closeMenu).toHaveAttribute("aria-expanded", "true");
    await user.click(screen.getByRole("button", { name: "Главная" }));
    const reopenedMenu = screen.getByRole("button", { name: "Открыть меню" });
    expect(reopenedMenu).toHaveAttribute("aria-expanded", "false");
    await waitFor(() => expect(reopenedMenu).toHaveFocus());
  });

  it("opens and closes the application license settings from the header", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 3, openSupplyCount: 1 });
    render(<App />);

    await screen.findByText("Обзор магазина");
    await waitFor(() => expect(refreshLicense).toHaveBeenCalledWith({}));
    await user.click(screen.getByRole("button", { name: "Настройки" }));
    expect(await screen.findByRole("dialog", { name: "Настройки приложения" })).toBeVisible();
    expect(await screen.findByText("Лицензия не активирована")).toBeVisible();
    expect(loadLicenseStatus).toHaveBeenCalledWith({});
    await user.click(screen.getByRole("button", { name: "Закрыть настройки" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Настройки приложения" })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: "Настройки" })).toHaveFocus());
  });

  it("opens local diagnostics from Help and restores focus after closing", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    loadDiagnostics.mockResolvedValue({
      appVersion: "1.1.7", jdeskVersion: "0.1.3", javaVersion: "25",
      osFamily: "macos", osVersion: "26.5.1", architecture: "arm64",
      databaseStatus: "healthy", shopCount: 0, supplyCount: 0, printJobCount: 0,
      pendingCredentialCount: 0, pendingTombstoneCount: 0,
    });
    render(<App />);

    const help = await screen.findByRole("button", { name: "Помощь" });
    expect(loadDiagnostics).not.toHaveBeenCalled();
    await user.click(help);
    expect(await screen.findByRole("dialog", { name: "Диагностика и поддержка" })).toBeVisible();
    expect(loadDiagnostics).toHaveBeenCalledWith({});
    await user.click(screen.getByRole("button", { name: "Закрыть диагностику" }));
    await waitFor(() => expect(help).toHaveFocus());
  });

  it("applies persisted language and theme then saves system mode from translated settings", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "light" });
    setTheme.mockResolvedValue({ language: "en", theme: "system" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Sales workspace" })).toBeVisible();
    expect(document.documentElement).toHaveAttribute("lang", "en");
    expect(document.documentElement).toHaveAttribute("data-theme", "light");
    await user.click(screen.getByRole("button", { name: "Settings" }));
    expect(await screen.findByRole("dialog", { name: "Application settings" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "System" }));

    expect(setTheme).toHaveBeenCalledWith({ theme: "system" });
    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-theme", "system"));
  });

  it("switches the shared shell and settings to Vietnamese immediately", async () => {
    const user = userEvent.setup();
    setLanguage.mockResolvedValue({ language: "vi", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Настройки" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Язык" }), "vi");

    expect(setLanguage).toHaveBeenCalledWith({ language: "vi" });
    expect(await screen.findByRole("dialog", { name: "Cài đặt ứng dụng" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Quản lý bán hàng", hidden: true })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute("lang", "vi");
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
  });

  it("rejects untrusted persisted preferences and malformed mutation responses", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "<script>", theme: "neon" } as never);
    setTheme.mockResolvedValue({ language: secret, theme: "system" } as never);
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Управление продажами" })).toBeVisible();
    expect(document.documentElement).toHaveAttribute("lang", "ru");
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(document.body).not.toHaveTextContent("<script>");
    await user.click(screen.getByRole("button", { name: "Настройки" }));
    await user.click(await screen.findByRole("button", { name: "Системная" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось сохранить настройки интерфейса");
    expect(document.documentElement).toHaveAttribute("lang", "ru");
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("opens the typed FBS and FBO template catalogs without raw layout data", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 3,
      openSupplyCount: 1,
    });
    loadTemplateDesigner.mockImplementation(async ({ mode }) => ({
      mode,
      pageWidthMm: 58,
      pageHeightMm: 40,
      maxTemplates: 100,
      maxElements: 100,
      templates: [{
        id: mode === "fbs" ? "1" : "2",
        name: mode === "fbs" ? "Базовый FBS" : "Основной FBO",
        defaultTemplate: true,
        elements: [{
          id: `${mode}-article`,
          type: "text_field",
          fieldKey: "article",
          label: "Артикул",
          prefix: "Арт. ",
          content: "",
          xMm: 2,
          yMm: 3,
          widthMm: 24,
          heightMm: 5,
          visible: true,
          zIndex: 3,
          fontSizePt: 8,
          bold: true,
          align: "left",
          humanReadable: false,
        }, {
          id: `${mode}-barcode`,
          type: "barcode_code128",
          fieldKey: "",
          label: "Штрихкод",
          prefix: "",
          content: "",
          xMm: 30,
          yMm: 3,
          widthMm: 25,
          heightMm: 13,
          visible: true,
          zIndex: 2,
          fontSizePt: 8,
          bold: false,
          align: "center",
          humanReadable: true,
        }],
      }],
      palette: [{ key: "text_field:article", label: "Артикул", type: "text_field", fieldKey: "article" }],
      rawLayoutJson: secret,
    } as unknown as Awaited<ReturnType<typeof commands.templates.loadDesigner>>));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Дизайн этикеток" }));

    expect(await screen.findByRole("heading", { name: "Дизайн этикеток" })).toBeVisible();
    await waitFor(() => expect(loadTemplateDesigner).toHaveBeenLastCalledWith({ mode: "fbs" }));
    expect(screen.getByRole("button", { name: "Шаблон Базовый FBS" })).toBeVisible();
    expect(screen.getByText("58 × 40 мм")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Выбрать элемент Артикул" }));
    expect(screen.getByRole("spinbutton", { name: "X, мм" })).toHaveValue(2);
    expect(screen.getByRole("spinbutton", { name: "Ширина, мм" })).toHaveValue(24);

    await user.click(screen.getByRole("tab", { name: "FBO" }));
    await waitFor(() => expect(loadTemplateDesigner).toHaveBeenLastCalledWith({ mode: "fbo" }));
    expect(await screen.findByRole("button", { name: "Шаблон Основной FBO" })).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("keeps the local template designer available without a shop and retries safely", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    loadTemplateDesigner
      .mockRejectedValueOnce(new Error(`sqlite ${secret}`))
      .mockResolvedValueOnce({
        mode: "fbs",
        pageWidthMm: 58,
        pageHeightMm: 40,
        maxTemplates: 100,
        maxElements: 100,
        templates: [],
        palette: [],
      });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Дизайн этикеток" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить шаблоны");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Шаблонов пока нет")).toBeVisible();
    expect(loadTemplateDesigner).toHaveBeenCalledTimes(2);
  });

  it("localizes the active template designer without reloading or mutating local templates", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    loadTemplateDesigner.mockResolvedValue(editableDesigner("fbs", "1", "Persisted template"));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Label designer" }));

    expect(await screen.findByText("Typed catalog · stored locally")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Templates" })).toBeVisible();
    const xField = screen.getByRole("spinbutton", { name: "X, mm" });
    await user.clear(xField);
    await user.type(xField, "4");
    expect(screen.getByText("Unsaved changes")).toBeVisible();
    await user.click(screen.getByRole("tab", { name: "FBO" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Save or discard your changes first.");
    const readsBeforeLanguageChange = loadTemplateDesigner.mock.calls.length;

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByText("Danh mục định kiểu · lưu cục bộ")).toBeVisible();
    expect(screen.getByText("Có thay đổi chưa lưu")).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent("Hãy lưu hoặc hủy các thay đổi trước.");

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByText("类型化目录 · 本地存储")).toBeVisible();
    expect(screen.getByText("有未保存的更改")).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent("请先保存或放弃更改。");
    expect(screen.getAllByText("Persisted template")).toHaveLength(2);
    expect(screen.getByRole("spinbutton", { name: "X，毫米" })).toHaveValue(4);
    expect(loadTemplateDesigner).toHaveBeenCalledTimes(readsBeforeLanguageChange);
    expect(createTemplate).not.toHaveBeenCalled();
    expect(createTemplateElement).not.toHaveBeenCalled();
    expect(deleteTemplate).not.toHaveBeenCalled();
    expect(duplicateTemplate).not.toHaveBeenCalled();
    expect(renameTemplate).not.toHaveBeenCalled();
    expect(resetTemplate).not.toHaveBeenCalled();
    expect(saveTemplate).not.toHaveBeenCalled();
    expect(setDefaultTemplate).not.toHaveBeenCalled();
  });

  it("edits, adds, copies, removes, and saves a typed template draft", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    const designer = editableDesigner("fbs", "1", "Рабочий FBS");
    loadTemplateDesigner.mockResolvedValue(designer);
    createTemplateElement.mockResolvedValue({
      id: "static-1",
      type: "static_text",
      fieldKey: "",
      label: "Текст",
      prefix: "",
      content: "Новый текст",
      xMm: 25,
      yMm: 11,
      widthMm: 30,
      heightMm: 4,
      visible: true,
      zIndex: 5,
      fontSizePt: 8,
      bold: false,
      align: "left",
      humanReadable: false,
    });
    const savedBase = designer.templates[0];
    if (!savedBase) throw new Error("editable designer fixture requires a template");
    saveTemplate.mockImplementation(async ({ template }) => ({
      designer: {
        ...designer,
        templates: [{ ...savedBase, name: template.name, elements: template.elements }],
      },
      selectedTemplateId: template.id,
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Дизайн этикеток" }));
    await user.click(await screen.findByRole("button", { name: "Выбрать слой Артикул" }));
    const xField = screen.getByRole("spinbutton", { name: "X, мм" });
    await user.clear(xField);
    await user.type(xField, "4");
    await user.click(screen.getByRole("checkbox", { name: "Виден" }));
    expect(screen.getByText("Есть несохранённые изменения")).toBeVisible();

    await user.selectOptions(screen.getByRole("combobox", { name: "Новый элемент" }), "static_text");
    await user.click(screen.getByRole("button", { name: "Добавить элемент" }));
    await waitFor(() => expect(createTemplateElement).toHaveBeenCalledWith({
      mode: "fbs",
      paletteKey: "static_text",
      zIndex: 5,
    }));
    expect(await screen.findByRole("button", { name: "Выбрать слой Текст" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Копировать элемент" }));
    await user.click(screen.getByRole("button", { name: "Вставить элемент" }));
    await user.click(screen.getByRole("button", { name: "Удалить элемент" }));

    await user.click(screen.getByRole("button", { name: "Сохранить шаблон" }));
    await waitFor(() => expect(saveTemplate).toHaveBeenCalledWith({
      mode: "fbs",
      template: expect.objectContaining({
        id: "1",
        name: "Рабочий FBS",
        elements: expect.arrayContaining([
          expect.objectContaining({ id: "article", xMm: 4, visible: false }),
          expect.objectContaining({ id: "static-1" }),
        ]),
      }),
    }));
    expect(await screen.findByText("Шаблон сохранён")).toBeVisible();
  });

  it("runs template CRUD, default, and reset through explicit dialogs", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });
    const initial = editableDesigner("fbs", "1", "Основной");
    const initialTemplate = initial.templates[0];
    if (!initialTemplate) throw new Error("editable designer fixture requires a template");
    const createdDesigner = {
      ...initial,
      templates: [...initial.templates, { ...initialTemplate, id: "2", name: "Новый макет", defaultTemplate: false }],
    };
    loadTemplateDesigner.mockResolvedValue(initial);
    createTemplate.mockResolvedValue({ designer: createdDesigner, selectedTemplateId: "2" });
    renameTemplate.mockResolvedValue({
      designer: { ...createdDesigner, templates: createdDesigner.templates.map((item) => item.id === "2" ? { ...item, name: "Переименован" } : item) },
      selectedTemplateId: "2",
    });
    const createdTemplate = createdDesigner.templates[1];
    if (!createdTemplate) throw new Error("created designer fixture requires the new template");
    duplicateTemplate.mockResolvedValue({
      designer: { ...createdDesigner, templates: [...createdDesigner.templates, { ...createdTemplate, id: "3", name: "Копия" }] },
      selectedTemplateId: "3",
    });
    setDefaultTemplate.mockResolvedValue({ designer: createdDesigner, selectedTemplateId: "2" });
    resetTemplate.mockResolvedValue({ designer: createdDesigner, selectedTemplateId: "2" });
    deleteTemplate.mockResolvedValue({ designer: initial, selectedTemplateId: "1" });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Дизайн этикеток" }));
    const createTemplateButton = await screen.findByRole("button", { name: "Создать шаблон" });
    await user.click(createTemplateButton);
    expect(screen.getByRole("button", { name: "Закрыть" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    await user.type(screen.getByRole("textbox", { name: "Название шаблона" }), "Новый макет");
    await user.click(screen.getByRole("button", { name: "Создать" }));
    await waitFor(() => expect(createTemplate).toHaveBeenCalledWith({ mode: "fbs", name: "Новый макет" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Создать шаблон" })).toHaveFocus());
    expect(document.body.style.overflow).toBe("");

    await user.click(screen.getByRole("button", { name: "Переименовать шаблон" }));
    const nameField = screen.getByRole("textbox", { name: "Название шаблона" });
    await user.clear(nameField);
    await user.type(nameField, "Переименован");
    await user.click(screen.getByRole("button", { name: "Переименовать" }));
    await waitFor(() => expect(renameTemplate).toHaveBeenCalledWith({ mode: "fbs", templateId: "2", name: "Переименован" }));

    await user.click(screen.getByRole("button", { name: "Сделать шаблоном по умолчанию" }));
    await waitFor(() => expect(setDefaultTemplate).toHaveBeenCalledWith({ mode: "fbs", templateId: "2" }));
    await user.click(screen.getByRole("button", { name: "Сбросить шаблон" }));
    expect(await screen.findByRole("dialog", { name: "Сбросить шаблон?" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Сбросить" }));
    await waitFor(() => expect(resetTemplate).toHaveBeenCalledWith({ mode: "fbs", templateId: "2" }));

    await user.click(screen.getByRole("button", { name: "Дублировать шаблон" }));
    await user.type(screen.getByRole("textbox", { name: "Название шаблона" }), "Копия");
    await user.click(screen.getByRole("button", { name: "Дублировать" }));
    await waitFor(() => expect(duplicateTemplate).toHaveBeenCalledWith({ mode: "fbs", templateId: "2", name: "Копия" }));

    await user.click(screen.getByRole("button", { name: "Удалить шаблон" }));
    expect(await screen.findByRole("dialog", { name: "Удалить шаблон?" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Удалить" }));
    await waitFor(() => expect(deleteTemplate).toHaveBeenCalledWith({ mode: "fbs", templateId: "3" }));
  });

  it("opens the read-only FBS packing board and keeps tab filters scoped", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 3,
      openSupplyCount: 1,
    });
    loadPackingBoard.mockImplementation(async (request) => ({
      ...request,
      totalItems: request.tab === "new" ? 1 : 1,
      totalPages: 1,
      newOrderCount: 3,
      preparationCount: 1,
      dispatchCount: 1,
      availableCategories: ["Обувь", "Сумки"],
      orders: request.tab === "new"
        ? [{
            orderId: "9007199254741001",
            nmId: "1001",
            name: "Кроссовки",
            brand: "WCode",
            subject: "Обувь",
            article: "ART-1",
            color: "Чёрный",
            size: "M",
            russianSize: "42",
            barcode: "SKU-1",
            createdAt: "2026-07-18T10:00:00Z",
            priceKopecks: 12345,
            requiresKiz: true,
            imagePath: "",
            apiKey: secret,
          }]
        : [],
      supplies: request.tab === "preparation"
        ? [{
            id: "WB-GI-1",
            name: "Поставка Москва",
            status: "open",
            mode: "consumer",
            createdAt: "2026-07-18T10:00:00Z",
            itemCount: 5,
          }]
        : [],
    } as unknown as Awaited<ReturnType<typeof commands.packing.board>>));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Упаковка FBS" }));

    expect(await screen.findByRole("heading", { name: "Упаковка FBS" })).toBeVisible();
    await waitFor(() => expect(loadPackingBoard).toHaveBeenLastCalledWith({
      shopId: 7,
      tab: "new",
      query: "",
      categories: [],
      page: 1,
      pageSize: 20,
    }));
    expect(screen.getByText("Кроссовки")).toBeVisible();
    expect(screen.getByText("#9007199254741001")).toBeVisible();
    expect(screen.getByText("Требуется KIZ")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Обувь" }));
    await waitFor(() => expect(loadPackingBoard).toHaveBeenLastCalledWith(expect.objectContaining({
      tab: "new",
      categories: ["Обувь"],
      page: 1,
    })));

    await user.click(screen.getByRole("tab", { name: /На сборке.*1/ }));
    await waitFor(() => expect(loadPackingBoard).toHaveBeenLastCalledWith({
      shopId: 7,
      tab: "preparation",
      query: "",
      categories: [],
      page: 1,
      pageSize: 20,
    }));
    expect(await screen.findByText("Поставка Москва")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("switches the active FBS packing board from English to Vietnamese and Chinese", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 0, openSupplyCount: 0 });
    loadPackingBoard.mockImplementation(async (request) => ({
      ...request,
      totalItems: 0,
      totalPages: 0,
      newOrderCount: 0,
      preparationCount: 0,
      dispatchCount: 0,
      availableCategories: [],
      orders: [],
      supplies: [],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "FBS packing" }));

    expect(await screen.findByRole("heading", { name: "Packing queue" })).toBeVisible();
    expect(screen.getByRole("tab", { name: /New orders.*0/ })).toBeVisible();
    expect(screen.getByText("No new orders yet")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByRole("heading", { name: "Hàng đợi đóng gói" })).toBeVisible();
    expect(screen.getByText("Chưa có đơn hàng mới")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByRole("heading", { name: "打包队列" })).toBeVisible();
    expect(screen.getByText("暂无新订单")).toBeVisible();
    expect(loadPackingBoard).toHaveBeenCalledTimes(1);
  });

  it("opens the typed local FBO packing catalog from the primary navigation", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 3,
      openSupplyCount: 1,
    });
    loadFboCatalog.mockResolvedValue({
      shopId: 7,
      query: "",
      subjects: [],
      page: 1,
      pageSize: 50,
      hasMore: false,
      availableSubjects: ["Обувь"],
      items: [{
        nmId: "9007199254740993",
        vendorCode: "ART-1",
        subject: "Обувь",
        brand: "WCode",
        title: "Кроссовки FBO",
        color: "Чёрный",
        size: "M",
        russianSize: "42",
        sku: "SKU-FBO-1",
        requiresKiz: true,
        imagePath: "",
        imageUrl: `https://untrusted.example/${secret}`,
      }],
    } as unknown as Awaited<ReturnType<typeof commands.fbo.catalog>>);

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBO" }));

    expect(await screen.findByRole("heading", { name: "Печать FBO" })).toBeVisible();
    await waitFor(() => expect(loadFboCatalog).toHaveBeenCalledWith({
      shopId: 7,
      query: "",
      subjects: [],
      page: 1,
      pageSize: 50,
    }));
    expect(screen.getByText("Кроссовки FBO")).toBeVisible();
    expect(screen.getByText("9007199254740993")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("switches the active FBO label catalog from English to Vietnamese and Chinese", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 0, newOrderCount: 0, openSupplyCount: 0 });
    loadFboCatalog.mockImplementation(async (request) => ({
      ...request,
      hasMore: false,
      availableSubjects: [],
      items: [],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "FBO supplies" }));

    expect(await screen.findByRole("heading", { name: "FBO product labels" })).toBeVisible();
    expect(screen.getByText("The FBO catalog is empty")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByRole("heading", { name: "Nhãn sản phẩm FBO" })).toBeVisible();
    expect(screen.getByText("Danh mục FBO đang trống")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByRole("heading", { name: "FBO 商品标签" })).toBeVisible();
    expect(screen.getByText("FBO 目录为空")).toBeVisible();
    expect(loadFboCatalog).toHaveBeenCalledTimes(1);
  });

  it("opens the bounded local GTIN mapping catalog from primary navigation", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 3,
      openSupplyCount: 1,
    });
    loadKizMappingCatalog.mockResolvedValue({
      shopId: 7,
      query: "",
      categories: [],
      page: 1,
      pageSize: 50,
      hasMore: false,
      availableCategories: ["Одежда"],
      items: [{
        gtin: "04601234567890",
        productName: "Куртка Alpine",
        category: "Одежда",
        available: 12,
        reserved: 2,
        consumed: 8,
        mappingRuleCount: 2,
        orderStatus: "CODES_READY",
        pipelineStage: "COMPLETED",
        errorMessage: "",
        syncedAt: "2026-07-18T12:00:00Z",
      }],
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "GTIN и KIZ" }));

    expect(await screen.findByRole("heading", { name: "Соответствия GTIN" })).toBeVisible();
    await waitFor(() => expect(loadKizMappingCatalog).toHaveBeenCalledWith({
      shopId: 7,
      query: "",
      categories: [],
      page: 1,
      pageSize: 50,
    }));
    expect(screen.getByText("Куртка Alpine")).toBeVisible();
    expect(screen.getByText("04601234567890")).toBeVisible();
  });

  it("switches the active GTIN mapping catalog from English to Vietnamese and Chinese", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 0, newOrderCount: 0, openSupplyCount: 0 });
    loadKizMappingCatalog.mockResolvedValue({
      shopId: 7,
      query: "",
      categories: [],
      page: 1,
      pageSize: 50,
      hasMore: false,
      availableCategories: [],
      items: [],
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "GTIN and KIZ" }));

    expect(await screen.findByRole("heading", { name: "GTIN mappings" })).toBeVisible();
    expect(screen.getByText("The GTIN catalog is empty")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByRole("heading", { name: "Ánh xạ GTIN" })).toBeVisible();
    expect(screen.getByText("Danh mục GTIN đang trống")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByRole("heading", { name: "GTIN 映射" })).toBeVisible();
    expect(screen.getByText("GTIN 目录为空")).toBeVisible();
    expect(loadKizMappingCatalog).toHaveBeenCalledTimes(1);
  });

  it("opens the safe local Znack settings workspace from primary navigation", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 3,
      openSupplyCount: 1,
    });
    loadZnackSettings.mockResolvedValue({
      shopId: 7,
      omsId: "OMS-7",
      omsConnection: "CONNECTION-7",
      documentNumber: "",
      documentDate: "",
      autoIntroduction: false,
      signatureStatus: "VERIFIED",
      certificateLabel: "ООО Маркировка",
      certificateValidTo: "2027-07-18",
      version: "a".repeat(64),
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Znack Automation" }));

    expect(await screen.findByRole("heading", { name: "Znack Automation" })).toBeVisible();
    await waitFor(() => expect(loadZnackSettings).toHaveBeenCalledWith({ shopId: 7 }));
    expect(screen.getByDisplayValue("OMS-7")).toBeVisible();
    expect(screen.getByText("Подпись проверена")).toBeVisible();
  });

  it("switches the active Znack settings workspace from English to Vietnamese and Chinese", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 0, newOrderCount: 0, openSupplyCount: 0 });
    loadZnackSettings.mockResolvedValue({
      shopId: 7,
      omsId: "OMS-7",
      omsConnection: "CONNECTION-7",
      documentNumber: "",
      documentDate: "",
      autoIntroduction: false,
      signatureStatus: "UNCONFIGURED",
      certificateLabel: "",
      certificateValidTo: "",
      version: "a".repeat(64),
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Znack Automation" }));

    expect(await screen.findByRole("heading", { name: "Znack marking workspace" })).toBeVisible();
    expect(screen.getByText("Default document")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByRole("heading", { name: "Khu vực đánh dấu Znack" })).toBeVisible();
    expect(screen.getByText("Tài liệu mặc định")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByRole("heading", { name: "Znack 标记工作区" })).toBeVisible();
    expect(screen.getByText("默认文档")).toBeVisible();
    expect(loadZnackSettings).toHaveBeenCalledTimes(1);
  });

  it("opens, searches, and filters the bounded print history", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 3,
      openSupplyCount: 1,
    });
    loadPrintHistory.mockImplementation(async (request) => ({
      ...request,
      totalItems: 2,
      totalPages: 2,
      successfulItems: 4,
      failedItems: 1,
      items: [{
        jobId: request.page === 1 ? "9007199254741001" : "9007199254741002",
        supplyId: request.page === 1 ? "WB-GI-1" : "WB-GI-2",
        supplyName: request.page === 1 ? "Поставка Москва" : "Поставка Казань",
        printedAt: "2026-07-18T10:00:00Z",
        itemCount: 5,
        templateName: "58 × 40",
        status: request.status === "failed" ? "failed" : "success",
        canReprint: request.status !== "failed",
        errorMessage: secret,
      }],
    } as unknown as Awaited<ReturnType<typeof commands.printing.history>>));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "История печати" }));

    expect(await screen.findByRole("heading", { name: "История печати" })).toBeVisible();
    await waitFor(() => expect(loadPrintHistory).toHaveBeenLastCalledWith({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
    }));
    expect(screen.getByText("Поставка Москва")).toBeVisible();
    expect(screen.getByText("WB-GI-1")).toBeVisible();
    expect(screen.getByText("58 × 40")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Следующая страница истории" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Показать ещё" }));
    expect(await screen.findByText("Поставка Казань")).toBeVisible();
    expect(screen.getByText("Поставка Москва")).toBeVisible();
    expect(loadPrintHistory).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, pageSize: 25 }));

    reprintHistory.mockResolvedValue({
      cancelled: false,
      exportId: "9a59c3c2-55dc-4bb1-90e7-3b5dba0eaa43",
      labelsFileName: "WCODE-REPRINT-WB-GI-1.pdf",
      detailsFileName: "NHAT_HANG-WCODE-REPRINT-WB-GI-1.pdf",
      jobId: "9007199254741001",
      itemCount: 5,
    });
    openHistoryReprint.mockResolvedValue({
      opened: true,
      fileName: "WCODE-REPRINT-WB-GI-1.pdf",
    });
    await user.click(screen.getByRole("button", { name: "Повторить печать Поставка Москва" }));
    await waitFor(() => expect(reprintHistory).toHaveBeenCalledWith({
      shopId: 7,
      jobId: "9007199254741001",
    }));
    expect(await screen.findByText("WCODE-REPRINT-WB-GI-1.pdf")).toBeVisible();
    expect(screen.getByText("NHAT_HANG-WCODE-REPRINT-WB-GI-1.pdf")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Открыть этикетки" }));
    await waitFor(() => expect(openHistoryReprint).toHaveBeenCalledWith({
      shopId: 7,
      exportId: "9a59c3c2-55dc-4bb1-90e7-3b5dba0eaa43",
      fileKind: "labels",
    }));

    await user.type(screen.getByRole("searchbox", { name: "Поиск истории печати" }), "  Москва  ");
    await user.click(screen.getByRole("button", { name: "Найти" }));
    await waitFor(() => expect(loadPrintHistory).toHaveBeenLastCalledWith(expect.objectContaining({
      query: "Москва",
      status: "all",
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: /Ошибки.*1/ }));
    await waitFor(() => expect(loadPrintHistory).toHaveBeenLastCalledWith({
      shopId: 7,
      query: "Москва",
      status: "failed",
      page: 1,
      pageSize: 25,
    }));
    expect(await screen.findByText("Ошибка")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("localizes print history at runtime without reloading or creating files", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 0, newOrderCount: 0, openSupplyCount: 0 });
    loadPrintHistory.mockResolvedValue({
      shopId: 7, query: "", status: "all", page: 1, pageSize: 25,
      totalItems: 1, totalPages: 1, successfulItems: 1, failedItems: 0,
      items: [{
        jobId: "9007199254741001", supplyId: "WB-1", supplyName: "Moscow supply",
        printedAt: "2026-07-18T10:00:00Z", itemCount: 5, templateName: "58 × 40",
        status: "success", canReprint: true,
      }],
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Print history" }));

    expect(await screen.findByText("Total jobs")).toBeVisible();
    expect(screen.getByRole("searchbox", { name: "Search print history" })).toBeVisible();
    const readsBeforeLanguageChange = loadPrintHistory.mock.calls.length;

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByText("Tổng tác vụ")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByText("任务总数")).toBeVisible();
    expect(loadPrintHistory).toHaveBeenCalledTimes(readsBeforeLanguageChange);
    expect(reprintHistory).not.toHaveBeenCalled();
    expect(openHistoryReprint).not.toHaveBeenCalled();
  });

  it("loads sanitized shops and the selected shop dashboard", async () => {
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true, apiKey: secret }],
      hasSelectedShop: true,
      selectedShopId: 7,
    } as unknown as Awaited<ReturnType<typeof commands.workspace.bootstrap>>);
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 17_922,
      newOrderCount: 31,
      openSupplyCount: 8,
    });

    render(<App />);

    expect(screen.getByRole("status")).toHaveTextContent("Загружаем рабочее пространство");
    expect(await screen.findByRole("heading", { name: "Обзор магазина" })).toBeVisible();
    expect(screen.getByRole("combobox", { name: "Магазин" })).toHaveValue("7");
    expect(screen.getByText("17 922")).toBeVisible();
    expect(screen.getByText("31")).toBeVisible();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("localizes the dashboard at runtime without reloading or starting synchronization", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 17_922,
      newOrderCount: 31,
      openSupplyCount: 8,
    });

    render(<App />);

    expect(await screen.findByText("Products in catalog")).toBeVisible();
    expect(screen.getByText("Access connected")).toBeVisible();
    const readsBeforeLanguageChange = loadDashboard.mock.calls.length;

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByText("Sản phẩm trong danh mục")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByText("目录商品")).toBeVisible();
    expect(document.documentElement.lang).toBe("zh");
    expect(loadDashboard).toHaveBeenCalledTimes(readsBeforeLanguageChange);
    expect(syncOverview).not.toHaveBeenCalled();
  });

  it("loads the newly selected shop and ignores no interaction state", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [
        { id: 7, name: "Основной магазин", tokenConfigured: true },
        { id: 9, name: "Второй магазин", tokenConfigured: false },
      ],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    selectShopCommand.mockResolvedValue({
      shops: [
        { id: 7, name: "Основной магазин", tokenConfigured: true },
        { id: 9, name: "Второй магазин", tokenConfigured: false },
      ],
      hasSelectedShop: true,
      selectedShopId: 9,
    });
    loadDashboard.mockImplementation(async ({ shopId }) => ({
      shopId,
      productCount: shopId === 7 ? 10 : 20,
      newOrderCount: shopId === 7 ? 1 : 2,
      openSupplyCount: shopId === 7 ? 3 : 4,
    }));

    render(<App />);
    const picker = await screen.findByRole("combobox", { name: "Магазин" });
    await user.selectOptions(picker, "9");

    expect(selectShopCommand).toHaveBeenCalledWith({ shopId: 9 });
    await waitFor(() => expect(loadDashboard).toHaveBeenLastCalledWith({ shopId: 9 }));
    expect(await screen.findByText("20")).toBeVisible();
    expect(screen.getByText("Доступ не настроен")).toBeVisible();
  });

  it("shows a safe retry state when bootstrap fails", async () => {
    const user = userEvent.setup();
    bootstrap.mockRejectedValueOnce(new Error("raw database details"));
    bootstrap.mockResolvedValueOnce({
      app: { name: "WCode", version: "1.1.7" },
      shops: [],
      hasSelectedShop: false,
      selectedShopId: 0,
    });

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось открыть рабочее пространство");
    expect(document.body).not.toHaveTextContent("raw database details");
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Добавьте магазин, чтобы начать работу")).toBeVisible();
  });

  it("localizes completed Wildberries synchronization and reloads local KPIs", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard
      .mockResolvedValueOnce({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 2 })
      .mockResolvedValueOnce({ shopId: 7, productCount: 12, newOrderCount: 1, openSupplyCount: 3 });
    syncOverview.mockResolvedValue({ accepted: true, shopId: 7, jobId: "job-7" });
    syncStatus.mockResolvedValue({
      jobId: "job-7",
      shopId: 7,
      state: "completed",
      products: 2,
      supplies: 1,
      orders: 0,
      statuses: 0,
      completedAt: "2026-07-18T10:20:00Z",
      errorKind: "",
      httpStatus: 0,
      retryable: false,
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Synchronize with Wildberries" }));

    await waitFor(() => expect(syncOverview).toHaveBeenCalledWith({ shopId: 7 }));
    await waitFor(() => expect(syncStatus).toHaveBeenCalledWith({ shopId: 7, jobId: "job-7" }));
    await waitFor(() => expect(loadDashboard).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Synchronization completed")).toBeVisible();
    expect(screen.getByText("Updated products: 2 · updated supplies: 1.")).toBeVisible();
    expect(screen.getByText("12")).toBeVisible();
  });

  it("shows an actionable token error without upstream details", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 1,
      openSupplyCount: 2,
    });
    syncOverview.mockResolvedValue({ accepted: true, shopId: 7, jobId: "job-7" });
    syncStatus.mockResolvedValue({
      jobId: "job-7",
      shopId: 7,
      state: "failed",
      products: 0,
      supplies: 0,
      orders: 0,
      statuses: 0,
      completedAt: "2026-07-18T10:20:00Z",
      errorKind: "token_invalid",
      httpStatus: 401,
      retryable: false,
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Синхронизировать с Wildberries" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Не удалось подключиться к Wildberries. Проверьте ключ доступа",
    );
    expect(document.body).not.toHaveTextContent("401");
  });

  it("opens a bounded local supply workspace without exposing secrets", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({
      shopId: 7,
      productCount: 10,
      newOrderCount: 1,
      openSupplyCount: 20,
    });
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 26,
      totalPages: 2,
      openItems: 20,
      closedItems: 6,
      items: [
        {
          id: "WB-GI-1",
          name: "Поставка Москва",
          status: "open",
          mode: "b2b",
          createdAt: "2026-07-18T10:00:00Z",
          itemCount: 12,
          apiKey: secret,
        },
      ],
    } as unknown as Awaited<ReturnType<typeof commands.supplies.list>>);

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));

    expect(await screen.findByRole("heading", { name: "Поставки FBS" })).toBeVisible();
    await waitFor(() =>
      expect(listSupplies).toHaveBeenCalledWith({
        shopId: 7,
        query: "",
        status: "all",
        page: 1,
        pageSize: 25,
      }),
    );
    expect(screen.getByText("Поставка Москва")).toBeVisible();
    expect(screen.getByText("WB-GI-1")).toBeVisible();
    expect(screen.getByRole("button", { name: /Открытые.*20/ })).toBeVisible();
    expect(screen.getByRole("button", { name: "Показать ещё" })).toBeVisible();
    expect(screen.queryByText(/Страница 1 из/)).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("applies the persisted language to the complete supply-list surface", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 0 });
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 0,
      totalPages: 0,
      openItems: 0,
      closedItems: 0,
      items: [],
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "FBS supplies" }));

    expect(await screen.findByRole("searchbox", { name: "Search supplies" })).toBeVisible();
    expect(screen.getByRole("button", { name: /All.*0/ })).toBeVisible();
    expect(screen.getByText("No supplies yet")).toBeVisible();
    expect(screen.queryByText("Поставок пока нет")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Settings" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "vi");
    await user.click(await screen.findByRole("button", { name: "Đóng cài đặt" }));
    expect(await screen.findByRole("searchbox", { name: "Tìm kiếm lô giao hàng" })).toBeVisible();
    expect(screen.getByText("Chưa có lô giao hàng")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Cài đặt" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Ngôn ngữ" }), "zh");
    await user.click(await screen.findByRole("button", { name: "关闭设置" }));
    expect(await screen.findByRole("searchbox", { name: "搜索供货" })).toBeVisible();
    expect(screen.getByText("暂无供货")).toBeVisible();
  });

  it("keeps persisted English across the supply detail and local inventory", async () => {
    const user = userEvent.setup();
    loadPreferences.mockResolvedValue({ language: "en", theme: "dark" });
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Main shop", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 1, newOrderCount: 1, openSupplyCount: 1 });
    const supply = { id: "SUP-EN", name: "English fixture", status: "open", mode: "consumer", createdAt: "2026-07-19T10:00:00Z", itemCount: 1 };
    listSupplies.mockResolvedValue({ shopId: 7, query: "", status: "all", page: 1, pageSize: 25, totalItems: 1, totalPages: 1, openItems: 1, closedItems: 0, items: [supply] });
    loadSupplyDetail.mockResolvedValue({
      supply,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 0,
      totalPages: 0,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [],
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "FBS supplies" }));
    await user.click(await screen.findByRole("button", { name: "Open supply English fixture" }));

    expect(await screen.findByRole("button", { name: "Back to supplies" })).toBeVisible();
    expect(screen.getByText("Orders in supply")).toBeVisible();
    expect(screen.getByRole("searchbox", { name: "Search orders" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "GTIN and local KIZ stock" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Print setup" })).toBeVisible();
    expect(screen.queryByText("К списку поставок")).not.toBeInTheDocument();
  });

  it("searches, filters, and appends supplies from the local bridge", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 30 });
    listSupplies.mockImplementation(async (request) => ({
      ...request,
      totalItems: 30,
      totalPages: 2,
      openItems: 30,
      closedItems: 4,
      items: [
        {
          id: `SUPPLY-${request.page}`,
          name: `Поставка ${request.page}`,
          status: request.status === "closed" ? "closed" : "open",
          mode: "consumer",
          createdAt: "2026-07-18T10:00:00Z",
          itemCount: 5,
        },
      ],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: /Открытые.*30/ }));

    await waitFor(() =>
      expect(listSupplies).toHaveBeenLastCalledWith({
        shopId: 7,
        query: "",
        status: "open",
        page: 1,
        pageSize: 25,
      }),
    );

    await user.type(screen.getByRole("searchbox", { name: "Поиск поставок" }), "  Москва  ");
    await user.click(screen.getByRole("button", { name: "Найти" }));
    await waitFor(() =>
      expect(listSupplies).toHaveBeenLastCalledWith({
        shopId: 7,
        query: "Москва",
        status: "open",
        page: 1,
        pageSize: 25,
      }),
    );

    expect(await screen.findByText("SUPPLY-1")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Следующая страница" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Показать ещё" }));
    await waitFor(() =>
      expect(listSupplies).toHaveBeenLastCalledWith({
        shopId: 7,
        query: "Москва",
        status: "open",
        page: 2,
        pageSize: 25,
      }),
    );
    expect(await screen.findByText("SUPPLY-2")).toBeVisible();
    expect(screen.getByText("SUPPLY-1")).toBeVisible();
    expect(screen.getByText("Все поставки загружены")).toBeVisible();
  });

  it("shows a safe retry state when the supply query fails", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 2 });
    listSupplies
      .mockRejectedValueOnce(new Error(`sqlite failure ${secret}`))
      .mockResolvedValueOnce({
        shopId: 7,
        query: "",
        status: "all",
        page: 1,
        pageSize: 25,
        totalItems: 0,
        totalPages: 0,
        openItems: 0,
        closedItems: 0,
        items: [],
      });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить поставки");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Поставок пока нет")).toBeVisible();
    expect(listSupplies).toHaveBeenCalledTimes(2);
  });

  it("opens a supply order detail with precise identifiers and no remote image data", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [{
        id: "WB-GI-1",
        name: "Поставка Москва",
        status: "open",
        mode: "b2b",
        createdAt: "2026-07-18T10:00:00Z",
        itemCount: 1,
      }],
    });
    loadSupplyDetail.mockResolvedValue({
      supply: {
        id: "WB-GI-1",
        name: "Поставка Москва",
        status: "open",
        mode: "b2b",
        createdAt: "2026-07-18T10:00:00Z",
        itemCount: 1,
      },
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [{
        orderId: "9007199254740993",
        nmId: "1001",
        name: "Куртка",
        brand: "WCode Brand",
        subject: "Одежда",
        article: "ART-1",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "SKU-1",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 12_345,
        supplierStatus: "confirm",
        wbStatus: "sorted",
        requiresKiz: true,
        imagePath: `jdesk://app/order-images/${"A".repeat(43)}.png`,
        imageUrl: `https://untrusted.example/${secret}`,
      }],
    } as unknown as Awaited<ReturnType<typeof commands.supplies.detail>>);

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));

    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenCalledWith({
        shopId: 7,
        supplyId: "WB-GI-1",
        query: "",
        page: 1,
        pageSize: 25,
        sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      }),
    );
    expect(await screen.findByRole("heading", { name: "Поставка Москва" })).toBeVisible();
    expect(screen.getByText("9007199254740993")).toBeVisible();
    expect(screen.getByText("Куртка")).toBeVisible();
    expect(screen.getByText("123,45 ₽")).toBeVisible();
    expect(screen.getByText("Требуется КИЗ")).toBeVisible();
    expect(screen.getByRole("img", { name: "Фото товара Куртка" })).toHaveAttribute(
      "src",
      `jdesk://app/order-images/${"A".repeat(43)}.png`,
    );
    expect(document.body).not.toHaveTextContent(secret);

    await user.click(screen.getByRole("button", { name: "К списку поставок" }));
    expect(await screen.findByText("Поставка Москва")).toBeVisible();
  });

  it("searches, sorts, and appends supply orders through the typed detail command", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 30,
    };
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [supply],
    });
    loadSupplyDetail.mockImplementation(async (request) => ({
      supply,
      query: request.query,
      page: request.page,
      pageSize: request.pageSize,
      totalItems: 30,
      totalPages: 2,
      sort: request.sort,
      items: [{
        orderId: `ORDER-${request.page}`,
        nmId: "1001",
        name: `Куртка ${request.page}`,
        brand: "Brand",
        subject: "Одежда",
        article: "ART-1",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "SKU-1",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 10_000,
        supplierStatus: "confirm",
        wbStatus: "sorted",
        requiresKiz: false,
        imagePath: "",
      }],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await screen.findByText("ORDER-1");

    await user.click(screen.getByRole("checkbox", { name: "Размер" }));
    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({
        page: 1,
        sort: { bySubject: true, byArticle: true, byColor: true, bySize: false },
      })),
    );

    await user.type(screen.getByRole("searchbox", { name: "Поиск заказов" }), "  SKU-1  ");
    await user.click(screen.getByRole("button", { name: "Найти заказ" }));
    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({ query: "SKU-1", page: 1 })),
    );

    expect(screen.queryByRole("button", { name: "Следующая страница заказов" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Показать ещё заказов" }));
    await waitFor(() =>
      expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({ query: "SKU-1", page: 2 })),
    );
    expect(await screen.findByText("ORDER-2")).toBeVisible();
    expect(screen.getByText("ORDER-1")).toBeVisible();
  });

  it("imports an Excel workbook through a native dialog and keeps its path out of the UI", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 1,
    };
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [supply],
    });
    loadSupplyDetail.mockResolvedValue({
      supply,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [{
        orderId: "LOCAL-1",
        nmId: "1001",
        name: "Локальный товар",
        brand: "Brand",
        subject: "Одежда",
        article: "LOCAL-ART",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "LOCAL-SKU",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 10_000,
        supplierStatus: "confirm",
        wbStatus: "sorted",
        requiresKiz: false,
        imagePath: "",
      }],
    });
    const sessionId = "00000000-0000-4000-8000-000000000001";
    importExcel
      .mockRejectedValueOnce(new JDeskError(
        "INTERNAL_ERROR",
        "safe public message",
        { kind: "rate_limited", httpStatus: 429, retryable: true },
      ))
      .mockResolvedValueOnce({
      cancelled: false,
      sessionId,
      fileName: "orders.xlsx",
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 30,
      totalPages: 2,
      importedItems: 30,
      stickerItems: 29,
      items: [{
        orderId: "9007199254740993",
        name: "Импортированный товар",
        brand: "Excel Brand",
        article: "ART-EXCEL",
        color: "Чёрный",
        size: "L",
        barcode: "SKU-EXCEL",
        sticker: "12 34",
        stickerAvailable: true,
        imagePath: `jdesk://app/order-images/${"B".repeat(43)}.jpg`,
      }],
      });
    loadImportedOrders.mockImplementation(async (request) => ({
      cancelled: false,
      sessionId,
      fileName: "orders.xlsx",
      query: request.query,
      page: request.page,
      pageSize: request.pageSize,
      totalItems: request.query ? 1 : 30,
      totalPages: request.query ? 1 : 2,
      importedItems: 30,
      stickerItems: 29,
      items: [{
        orderId: request.page === 1 ? "9007199254740993" : "9007199254740994",
        name: `Импортированный товар ${request.page}`,
        brand: "Excel Brand",
        article: "ART-EXCEL",
        color: "Чёрный",
        size: "L",
        barcode: "SKU-EXCEL",
        sticker: "12 34",
        stickerAvailable: true,
        imagePath: `jdesk://app/order-images/${"B".repeat(43)}.jpg`,
      }],
    }));

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await screen.findByText("LOCAL-1");
    await user.click(screen.getByRole("button", { name: "Импортировать Excel" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Wildberries ограничил частоту запросов стикеров",
    );
    expect(document.body).not.toHaveTextContent("safe public message");
    await user.click(screen.getByRole("button", { name: "Импортировать Excel" }));

    await waitFor(() => expect(importExcel).toHaveBeenCalledTimes(2));
    expect(importExcel).toHaveBeenLastCalledWith({ shopId: 7, pageSize: 25 });
    expect(await screen.findByRole("heading", { name: "Заказы из orders.xlsx" })).toBeVisible();
    expect(screen.getByText("9007199254740993")).toBeVisible();
    expect(screen.queryByText("LOCAL-1")).not.toBeInTheDocument();
    expect(screen.getByText("29 из 30")).toBeVisible();
    expect(screen.getByRole("img", { name: "Фото товара Импортированный товар" })).toHaveAttribute(
      "src",
      `jdesk://app/order-images/${"B".repeat(43)}.jpg`,
    );

    expect(screen.queryByRole("button", { name: "Следующая страница импортированных заказов" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Показать ещё импортированных заказов" }));
    expect(await screen.findByText("9007199254740994")).toBeVisible();
    expect(screen.getByText("9007199254740993")).toBeVisible();

    await user.type(screen.getByRole("searchbox", { name: "Поиск импортированных заказов" }), "  ART-EXCEL  ");
    await user.click(screen.getByRole("button", { name: "Найти импортированный заказ" }));
    await waitFor(() => expect(loadImportedOrders).toHaveBeenCalledWith({
      shopId: 7,
      sessionId,
      query: "ART-EXCEL",
      page: 1,
      pageSize: 25,
    }));
    expect(screen.queryByText("9007199254740994")).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("/private/operator");
    expect(document.body).not.toHaveTextContent(secret);

    importExcel.mockResolvedValueOnce({
      cancelled: true,
      sessionId: "",
      fileName: "",
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 0,
      totalPages: 0,
      importedItems: 0,
      stickerItems: 0,
      items: [],
    });
    await user.click(screen.getByRole("button", { name: "Другой файл" }));
    expect(await screen.findByRole("heading", { name: "Заказы из orders.xlsx" })).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Вернуться к заказам поставки" }));
    expect(await screen.findByText("LOCAL-1")).toBeVisible();
  });

  it("loads and persists the bounded print setup for the selected supply", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 2,
    };
    listSupplies.mockResolvedValue({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [supply],
    });
    loadSupplyDetail.mockResolvedValue({
      supply,
      query: "",
      page: 1,
      pageSize: 25,
      totalItems: 2,
      totalPages: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      items: [],
    });
    loadPrintSetup.mockResolvedValue({
      shopId: 7,
      pageOrder: "barcode_then_sticker",
      barcodeCopies: 2,
      defaultTemplateId: 9,
      pageWidthMm: 58,
      pageHeightMm: 40,
      templates: [
        { id: 9, name: "Основной шаблон", defaultTemplate: true },
        { id: 10, name: "Компактный", defaultTemplate: false },
      ],
    });
    savePrintOptions.mockResolvedValue({
      shopId: 7,
      pageOrder: "sticker_then_barcode",
      barcodeCopies: 4,
      defaultTemplateId: 9,
      pageWidthMm: 58,
      pageHeightMm: 40,
      templates: [
        { id: 9, name: "Основной шаблон", defaultTemplate: true },
        { id: 10, name: "Компактный", defaultTemplate: false },
      ],
    });
    exportSupplyPdfCommand.mockResolvedValue({
      cancelled: false,
      exportId: "00000000-0000-4000-8000-000000000009",
      labelsFileName: "labels.pdf",
      detailsFileName: "NHAT_HANG-labels.pdf",
      printJobId: "9007199254740993",
      itemCount: 2,
      pageCount: 10,
      kizAttachmentCount: 1,
    });
    openExportedPdf.mockResolvedValue({ opened: true, fileName: "NHAT_HANG-labels.pdf" });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    const printSetupButton = await screen.findByRole("button", { name: "Настроить печать" });
    await user.click(printSetupButton);

    await waitFor(() => expect(loadPrintSetup).toHaveBeenCalledWith({ shopId: 7 }));
    expect(await screen.findByRole("dialog", { name: "Настройка печати" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Закрыть настройку печати" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    expect(screen.getByText("Основной шаблон")).toBeVisible();
    expect(screen.getByText("58 × 40 мм")).toBeVisible();
    expect(screen.getByText("6 страниц PDF")).toBeVisible();

    await user.click(screen.getByRole("radio", { name: "Стикер WB, затем этикетка" }));
    const copies = screen.getByRole("spinbutton", { name: "Копий этикетки" });
    await user.clear(copies);
    await user.type(copies, "4");
    await user.click(screen.getByRole("button", { name: "Сохранить настройки" }));

    await waitFor(() => expect(savePrintOptions).toHaveBeenCalledWith({
      shopId: 7,
      pageOrder: "sticker_then_barcode",
      barcodeCopies: 4,
    }));
    expect(await screen.findByText("Настройки сохранены")).toBeVisible();
    expect(screen.getByText("10 страниц PDF")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Создать PDF" }));
    await waitFor(() => expect(exportSupplyPdfCommand).toHaveBeenCalledWith({
      shopId: 7,
      supplyId: "WB-GI-1",
      query: "",
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      pageOrder: "sticker_then_barcode",
      barcodeCopies: 4,
    }));
    expect(await screen.findByText("PDF готовы")).toBeVisible();
    expect(screen.getByText("labels.pdf")).toBeVisible();
    expect(screen.getByText("NHAT_HANG-labels.pdf")).toBeVisible();
    expect(document.body).not.toHaveTextContent("/private/operator");

    await user.click(screen.getByRole("button", { name: "Открыть лист подбора" }));
    await waitFor(() => expect(openExportedPdf).toHaveBeenCalledWith({
      shopId: 7,
      exportId: "00000000-0000-4000-8000-000000000009",
      fileKind: "details",
    }));
    expect(document.body).not.toHaveTextContent(secret);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Настройка печати" })).not.toBeInTheDocument();
    expect(printSetupButton).toHaveFocus();
    expect(document.body.style.overflow).toBe("");
  });

  it("refreshes supply orders in the background without losing detail selection", async () => {
    const user = userEvent.setup();
    bootstrap.mockResolvedValue({
      app: { name: "WCode", version: "1.1.7" },
      shops: [{ id: 7, name: "Основной магазин", tokenConfigured: true }],
      hasSelectedShop: true,
      selectedShopId: 7,
    });
    loadDashboard.mockResolvedValue({ shopId: 7, productCount: 10, newOrderCount: 1, openSupplyCount: 1 });
    const supply = {
      id: "WB-GI-1",
      name: "Поставка Москва",
      status: "open",
      mode: "consumer",
      createdAt: "2026-07-18T10:00:00Z",
      itemCount: 30,
    };
    let refreshed = false;
    listSupplies.mockImplementation(async () => ({
      shopId: 7,
      query: "",
      status: "all",
      page: 1,
      pageSize: 25,
      totalItems: 1,
      totalPages: 1,
      openItems: 1,
      closedItems: 0,
      items: [{ ...supply, itemCount: refreshed ? 31 : 30 }],
    }));
    loadSupplyDetail.mockImplementation(async (request) => ({
      supply: { ...supply, itemCount: refreshed ? 31 : 30 },
      query: request.query,
      page: request.page,
      pageSize: request.pageSize,
      totalItems: 30,
      totalPages: 2,
      sort: request.sort,
      items: [{
        orderId: `ORDER-${request.page}`,
        nmId: "1001",
        name: "Куртка",
        brand: "Brand",
        subject: "Одежда",
        article: "ART-1",
        color: "Синий",
        size: "M",
        russianSize: "44",
        barcode: "SKU-1",
        createdAt: "2026-07-18T10:00:00Z",
        priceKopecks: 10_000,
        supplierStatus: refreshed ? "complete" : "confirm",
        wbStatus: refreshed ? "sorted" : "waiting",
        requiresKiz: false,
        imagePath: "",
      }],
    }));
    refreshSupply
      .mockRejectedValueOnce(new JDeskError(
        "INVALID_REQUEST",
        "safe public message",
        { kind: "shop_busy", retryable: true },
      ))
      .mockResolvedValueOnce({
        accepted: true,
        shopId: 7,
        supplyId: "WB-GI-1",
        jobId: "00000000-0000-0000-0000-000000000001",
      });
    refreshSupplyStatus.mockImplementation(async () => {
      refreshed = true;
      return {
        jobId: "00000000-0000-0000-0000-000000000001",
        shopId: 7,
        supplyId: "WB-GI-1",
        state: "completed",
        localOrders: 31,
        completedAt: "2026-07-18T11:00:00Z",
        errorKind: "",
        httpStatus: 0,
        retryable: false,
      };
    });

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Поставки FBS" }));
    await user.click(await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" }));
    await user.click(await screen.findByRole("checkbox", { name: "Размер" }));
    await user.type(screen.getByRole("searchbox", { name: "Поиск заказов" }), "SKU-1");
    await user.click(screen.getByRole("button", { name: "Найти заказ" }));
    await user.click(await screen.findByRole("button", { name: "Показать ещё заказов" }));
    await screen.findByText("ORDER-2");

    await user.click(screen.getByRole("button", { name: "Обновить из Wildberries" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Для этого магазина уже обновляется другая поставка",
    );
    expect(document.body).not.toHaveTextContent("safe public message");
    await user.click(screen.getByRole("button", { name: "Обновить из Wildberries" }));

    await waitFor(() => expect(refreshSupply).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(refreshSupplyStatus).toHaveBeenCalledWith({
      shopId: 7,
      supplyId: "WB-GI-1",
      jobId: "00000000-0000-0000-0000-000000000001",
    }));
    await waitFor(() => expect(loadSupplyDetail).toHaveBeenLastCalledWith(expect.objectContaining({
      shopId: 7,
      supplyId: "WB-GI-1",
      query: "SKU-1",
      page: 1,
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: false },
    })));
    await waitFor(() => expect(listSupplies).toHaveBeenCalledTimes(3));
    expect(await screen.findByText("Данные поставки обновлены")).toBeVisible();
    expect(screen.getByText("В доставке")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Поставка Москва" })).toBeVisible();

    await user.click(screen.getByRole("button", { name: "К списку поставок" }));
    const openSupply = await screen.findByRole("button", { name: "Открыть поставку Поставка Москва" });
    expect(openSupply.closest("tr")).toHaveTextContent("31");
  });
});
