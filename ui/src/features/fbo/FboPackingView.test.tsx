import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { exportFboPdf } from "../printing/nativePrintCommands";
import { FboPackingView } from "./FboPackingView";

vi.mock("../../generated/commands", () => ({
  commands: {
    fbo: {
      catalog: vi.fn(),
      export: vi.fn(),
      openExport: vi.fn(),
    },
  },
}));

vi.mock("../printing/nativePrintCommands", () => ({
  exportFboPdf: vi.fn(),
}));

const loadCatalog = vi.mocked(commands.fbo.catalog);
const exportPdf = vi.mocked(exportFboPdf);
const openExport = vi.mocked(commands.fbo.openExport);
const secret = "wb-secret-that-must-not-enter-the-fbo-dom";

function product(sku: string, title: string, subject = "Обувь") {
  return {
    nmId: sku === "SKU-1" ? "9007199254740993" : "9007199254740995",
    vendorCode: `ART-${sku}`,
    subject,
    brand: "WCode",
    title,
    color: "Чёрный",
    size: "M",
    russianSize: "42",
    sku,
    requiresKiz: sku === "SKU-1",
    imagePath: "",
  };
}

describe("FboPackingView", () => {
  beforeEach(() => {
    loadCatalog.mockReset();
    exportPdf.mockReset();
    openExport.mockReset();
    loadCatalog.mockImplementation(async (request) => ({
      ...request,
      hasMore: request.page === 1,
      availableSubjects: ["Обувь", "Сумки"],
      items: request.page === 1
        ? [product("SKU-1", "Кроссовки")]
        : [product("SKU-2", "Ботинки")],
    }));
  });

  it("loads bounded pages, filters subjects, and preserves quantities between loaded pages", async () => {
    const user = userEvent.setup();
    render(<FboPackingView shopId={7} />);

    expect(await screen.findByText("Кроссовки")).toBeVisible();
    expect(loadCatalog).toHaveBeenLastCalledWith({
      shopId: 7,
      query: "",
      subjects: [],
      page: 1,
      pageSize: 50,
    });
    const quantity = screen.getByRole("spinbutton", { name: "Количество для Кроссовки, SKU SKU-1" });
    await user.clear(quantity);
    await user.type(quantity, "2");
    expect(screen.getByText("2 пары · 1 SKU")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Следующая страница FBO" }));
    expect(await screen.findByText("Ботинки")).toBeVisible();
    expect(loadCatalog).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }));
    expect(screen.queryByText("Кроссовки")).not.toBeInTheDocument();
    expect(screen.getByText("Страница 2")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Предыдущая страница FBO" }));
    expect(await screen.findByText("Кроссовки")).toBeVisible();
    expect(screen.getByRole("spinbutton", { name: "Количество для Кроссовки, SKU SKU-1" })).toHaveValue(2);

    await user.click(screen.getByRole("button", { name: "Категории" }));
    await user.click(screen.getByRole("checkbox", { name: "Сумки" }));
    await waitFor(() => expect(loadCatalog).toHaveBeenLastCalledWith(expect.objectContaining({
      subjects: ["Сумки"],
      page: 1,
    })));
    expect(screen.getByText("2 пары · 1 SKU")).toBeVisible();
  });

  it("runs quick and batch PDF exports, clears a successful batch, and opens the opaque result", async () => {
    const user = userEvent.setup();
    exportPdf
      .mockResolvedValueOnce({
        cancelled: false,
        exportId: "c93806de-6bca-43e9-8c15-31f29719ed8f",
        fileName: "quick.pdf",
        pairCount: 1,
        pageCount: 2,
        kizCount: 1,
      })
      .mockResolvedValueOnce({
        cancelled: false,
        exportId: "bd3a6fd0-7701-42f9-b4a2-645e6b775ae7",
        fileName: "batch.pdf",
        pairCount: 3,
        pageCount: 6,
        kizCount: 1,
      });
    openExport.mockResolvedValue({ opened: true, fileName: "batch.pdf" });
    render(<FboPackingView shopId={7} />);
    await screen.findByText("Кроссовки");

    await user.click(screen.getByRole("button", { name: "Быстрая печать Кроссовки" }));
    await waitFor(() => expect(exportPdf).toHaveBeenNthCalledWith(1, {
      shopId: 7,
      items: [{ sku: "SKU-1", quantity: 1 }],
    }));

    const quantity = screen.getByRole("spinbutton", { name: "Количество для Кроссовки, SKU SKU-1" });
    await user.clear(quantity);
    await user.type(quantity, "3");
    await user.click(screen.getByRole("button", { name: "Создать PDF для 3 пары" }));
    await waitFor(() => expect(exportPdf).toHaveBeenNthCalledWith(2, {
      shopId: 7,
      items: [{ sku: "SKU-1", quantity: 3 }],
    }));
    expect(await screen.findByText("batch.pdf")).toBeVisible();
    expect(quantity).toHaveValue(0);

    await user.click(screen.getByRole("button", { name: "Открыть PDF FBO" }));
    await waitFor(() => expect(openExport).toHaveBeenCalledWith({
      shopId: 7,
      exportId: "bd3a6fd0-7701-42f9-b4a2-645e6b775ae7",
    }));
  });

  it("keeps a cancelled selection and redacts bridge failures", async () => {
    const user = userEvent.setup();
    exportPdf
      .mockResolvedValueOnce({
        cancelled: true,
        exportId: "",
        fileName: "",
        pairCount: 0,
        pageCount: 0,
        kizCount: 0,
      })
      .mockRejectedValueOnce(new Error(`failed with ${secret}`));
    render(<FboPackingView shopId={7} />);
    await screen.findByText("Кроссовки");
    const quantity = screen.getByRole("spinbutton", { name: "Количество для Кроссовки, SKU SKU-1" });
    await user.clear(quantity);
    await user.type(quantity, "2");

    await user.click(screen.getByRole("button", { name: "Создать PDF для 2 пары" }));
    await waitFor(() => expect(exportPdf).toHaveBeenCalledTimes(1));
    expect(quantity).toHaveValue(2);
    expect(screen.queryByText("PDF FBO создан")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Создать PDF для 2 пары" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось создать PDF FBO");
    expect(document.body).not.toHaveTextContent(secret);
    expect(quantity).toHaveValue(2);
  });

  it("retries a redacted catalog failure and renders the local empty state", async () => {
    const user = userEvent.setup();
    loadCatalog
      .mockRejectedValueOnce(new Error(`sqlite failed with ${secret}`))
      .mockResolvedValueOnce({
        shopId: 7,
        query: "",
        subjects: [],
        page: 1,
        pageSize: 50,
        hasMore: false,
        availableSubjects: [],
        items: [],
      });

    render(<FboPackingView shopId={7} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить товары FBO");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Каталог FBO пока пуст")).toBeVisible();
    expect(loadCatalog).toHaveBeenCalledTimes(2);
  });
});
