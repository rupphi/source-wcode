export interface PrintSetupCopy {
  button: string; eyebrow: string; title: string; description: string; close: string;
  activeTemplate: string; defaultTemplate: string; dimensions: string; jobVolume: string; orders: string; pages: string;
  pageOrder: string; labelFirst: string; labelFirstDescription: string; stickerFirst: string; stickerFirstDescription: string;
  copies: string; copiesHint: string; copiesInvalid: string; saved: string; saveError: string; saving: string; save: string; preparingPdf: string; createPdf: string;
  loading: string; loadError: string; unchanged: string; retry: string; cancelled: string; exportFailed: string; pdfReady: string; pagesCreated: string;
  openLabels: string; openDetails: string; kizBackground: string; openError: string; opening: string;
  errors: { tokenInvalid: string; rateLimited: string; preflightFailed: string; unavailable: string };
}

export const defaultPrintSetupCopy: PrintSetupCopy = {
  button: "Настроить печать", eyebrow: "PDF и этикетки", title: "Настройка печати", description: "Проверьте макет и порядок страниц перед созданием файлов.", close: "Закрыть настройку печати",
  activeTemplate: "Активный шаблон", defaultTemplate: "Основной шаблон", dimensions: "{width} × {height} мм", jobVolume: "Объём задания", orders: "{count} заказов", pages: "{count} страниц PDF",
  pageOrder: "Порядок страниц", labelFirst: "Этикетка, затем стикер WB", labelFirstDescription: "Сначала товарная этикетка, затем стикер задания WB.", stickerFirst: "Стикер WB, затем этикетка", stickerFirstDescription: "Сначала стикер задания WB, затем товарная этикетка.",
  copies: "Копий этикетки", copiesHint: "От 1 до 100 копий на один заказ.", copiesInvalid: "Введите целое число от 1 до 100.", saved: "Настройки сохранены", saveError: "Не удалось сохранить. Проверьте настройки и повторите.", saving: "Сохраняем…", save: "Сохранить настройки", preparingPdf: "Готовим PDF…", createPdf: "Создать PDF",
  loading: "Загружаем настройки печати…", loadError: "Не удалось загрузить настройки", unchanged: "Локальные данные не изменены. Повторите запрос.", retry: "Повторить",
  cancelled: "Сохранение отменено. Файлы и история печати не создавались.", exportFailed: "Не удалось создать PDF.", pdfReady: "PDF готовы", pagesCreated: "Создано {count} страниц. Файлы сохранены в выбранной папке.",
  openLabels: "Открыть этикетки", openDetails: "Открыть лист подбора", kizBackground: "KIZ отправляются в Wildberries в фоне: {count}.", openError: "Не удалось открыть файл. Он уже сохранён; откройте его из выбранной папки.", opening: "Открываем…",
  errors: { tokenInvalid: "Проверьте ключ доступа и право Marketplace выбранного магазина.", rateLimited: "Wildberries ограничил запросы. Подождите несколько минут и повторите.", preflightFailed: "Проверьте сопоставление GTIN и доступные KIZ перед печатью.", unavailable: "Файлы не опубликованы. Проверьте соединение, доступ к папке и повторите." },
};
