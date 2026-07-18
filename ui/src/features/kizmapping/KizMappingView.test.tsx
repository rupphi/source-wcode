import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { commands } from "../../generated/commands";
import { KizMappingView } from "./KizMappingView";

vi.mock("../../generated/commands", () => ({
  commands: {
    kizMapping: {
      catalog: vi.fn(),
      editor: vi.fn(),
      save: vi.fn(),
    },
  },
}));

const loadCatalog = vi.mocked(commands.kizMapping.catalog);
const loadEditor = vi.mocked(commands.kizMapping.editor);
const saveMapping = vi.mocked(commands.kizMapping.save);
const gtin = "04601234567890";
const otherGtin = "04601234567891";
const secret = "znack-secret-that-must-not-enter-the-dom";

function item(currentGtin = gtin, name = "Куртка Alpine", category = "Одежда") {
  return {
    gtin: currentGtin,
    productName: name,
    category,
    available: 12,
    reserved: 2,
    consumed: 8,
    mappingRuleCount: 2,
    orderStatus: "CODES_READY",
    pipelineStage: "COMPLETED",
    errorMessage: "",
    syncedAt: "2026-07-18T12:00:00Z",
  };
}

function editor() {
  return {
    shopId: 7,
    gtin,
    subjects: [
      {
        subjectName: "Куртки",
        selected: true,
        wildcardSelected: true,
        wildcardOwnerGtin: gtin,
        genders: [
          { value: "Женский", selected: false, ownerGtin: gtin },
          { value: "Мужской", selected: false, ownerGtin: gtin },
        ],
      },
      {
        subjectName: "Разделённая",
        selected: true,
        wildcardSelected: false,
        wildcardOwnerGtin: "",
        genders: [
          { value: "Женский", selected: true, ownerGtin: "" },
          { value: "Мужской", selected: false, ownerGtin: otherGtin },
        ],
      },
      {
        subjectName: "Занятая",
        selected: false,
        wildcardSelected: false,
        wildcardOwnerGtin: otherGtin,
        genders: [
          { value: "Женский", selected: false, ownerGtin: otherGtin },
          { value: "Мужской", selected: false, ownerGtin: otherGtin },
        ],
      },
    ],
  };
}

describe("KizMappingView", () => {
  beforeEach(() => {
    loadCatalog.mockReset();
    loadEditor.mockReset();
    saveMapping.mockReset();
    loadCatalog.mockImplementation(async (request) => ({
      ...request,
      hasMore: request.page === 1,
      availableCategories: ["Одежда", "Обувь"],
      items: request.page === 1
        ? [item()]
        : [item("04601234567892", "Ботинки North", "Обувь")],
    }));
    loadEditor.mockResolvedValue(editor());
    saveMapping.mockResolvedValue(editor());
  });

  it("loads bounded pages and applies exact search and category filters", async () => {
    const user = userEvent.setup();
    render(<KizMappingView shopId={7} />);

    expect(await screen.findByText("Куртка Alpine")).toBeVisible();
    expect(loadCatalog).toHaveBeenLastCalledWith({
      shopId: 7,
      query: "",
      categories: [],
      page: 1,
      pageSize: 50,
    });
    expect(screen.getByText("12 доступно")).toBeVisible();
    expect(screen.getByText("Завершено")).toBeVisible();

    await user.type(screen.getByRole("searchbox", { name: "Поиск GTIN" }), "  Alpine  ");
    await user.click(screen.getByRole("button", { name: "Найти GTIN" }));
    await waitFor(() => expect(loadCatalog).toHaveBeenLastCalledWith(expect.objectContaining({
      query: "Alpine",
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: "Категории GTIN" }));
    await user.click(screen.getByRole("checkbox", { name: "Обувь" }));
    await waitFor(() => expect(loadCatalog).toHaveBeenLastCalledWith(expect.objectContaining({
      categories: ["Обувь"],
      page: 1,
    })));

    await user.click(screen.getByRole("button", { name: "Следующая страница GTIN" }));
    expect(await screen.findByText("Ботинки North")).toBeVisible();
    expect(screen.getByText("Страница 2")).toBeVisible();
    expect(screen.queryByText("Куртка Alpine")).not.toBeInTheDocument();
  });

  it("retries a redacted load failure and renders the local empty state", async () => {
    const user = userEvent.setup();
    loadCatalog
      .mockRejectedValueOnce(new Error(`sqlite failed with ${secret}`))
      .mockResolvedValueOnce({
        shopId: 7,
        query: "",
        categories: [],
        page: 1,
        pageSize: 50,
        hasMore: false,
        availableCategories: [],
        items: [],
      });
    render(<KizMappingView shopId={7} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить каталог GTIN");
    expect(document.body).not.toHaveTextContent(secret);
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByText("Каталог GTIN пока пуст")).toBeVisible();
    expect(loadCatalog).toHaveBeenCalledTimes(2);
  });

  it("edits wildcard and exact rules while keeping foreign ownership disabled", async () => {
    const user = userEvent.setup();
    render(<KizMappingView shopId={7} />);
    await screen.findByText("Куртка Alpine");

    await user.click(screen.getByRole("button", { name: `Настроить соответствие для ${gtin}` }));
    const dialog = await screen.findByRole("dialog", { name: `Соответствие GTIN ${gtin}` });
    await user.click(within(dialog).getByRole("button", { name: "Выбрать категорию Разделённая" }));
    expect(within(dialog).getByRole("checkbox", { name: `Мужской · занято ${otherGtin}` })).toBeDisabled();
    const femaleSplit = within(dialog).getByRole("checkbox", { name: "Женский" });
    expect(femaleSplit).toBeChecked();
    await user.click(femaleSplit);

    await user.click(within(dialog).getByRole("button", { name: "Выбрать категорию Куртки" }));
    const wildcard = within(dialog).getByRole("checkbox", { name: "Все значения пола" });
    expect(wildcard).toBeChecked();
    await user.click(wildcard);
    const male = within(dialog).getByRole("checkbox", { name: "Мужской" });
    expect(male).toBeChecked();
    await user.click(male);
    expect(within(dialog).getByText("1 точное значение")).toBeVisible();
    expect(within(dialog).getByRole("checkbox", { name: `Использовать категорию Занятая · занято ${otherGtin}` })).toBeDisabled();

    await user.click(within(dialog).getByRole("button", { name: "Сохранить соответствие" }));
    await waitFor(() => expect(saveMapping).toHaveBeenCalledWith({
      shopId: 7,
      gtin,
      selections: [{ subjectName: "Куртки", genderValue: "Женский", wildcardGender: false }],
    }));
    expect(await screen.findByText("Соответствие GTIN сохранено")).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("keeps cancelled edits local and shows only generic editor/save failures", async () => {
    const user = userEvent.setup();
    render(<KizMappingView shopId={7} />);
    await screen.findByText("Куртка Alpine");
    const open = screen.getByRole("button", { name: `Настроить соответствие для ${gtin}` });

    await user.click(open);
    let dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Выбрать категорию Разделённая" }));
    await user.click(within(dialog).getByRole("checkbox", { name: "Женский" }));
    await user.click(within(dialog).getByRole("button", { name: "Отмена" }));
    expect(saveMapping).not.toHaveBeenCalled();

    loadEditor.mockRejectedValueOnce(new Error(`editor failed ${secret}`));
    await user.click(open);
    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось открыть редактор соответствий");
    expect(document.body).not.toHaveTextContent(secret);

    loadEditor.mockResolvedValueOnce(editor());
    saveMapping.mockRejectedValueOnce(new Error(`save failed ${secret}`));
    await user.click(screen.getByRole("button", { name: "Повторить открытие редактора" }));
    dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Сохранить соответствие" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Не удалось сохранить соответствие");
    expect(document.body).not.toHaveTextContent(secret);
  });
});
