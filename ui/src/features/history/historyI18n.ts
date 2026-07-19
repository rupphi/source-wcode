import type { Language } from "../../i18n";

const ru = {
  summaryAria: "Сводка истории печати", total: "Всего заданий", success: "Успешно", failed: "С ошибкой",
  searchLabel: "Поиск истории печати", searchPlaceholder: "Поставка, ID задания или шаблон", search: "Найти",
  filterAria: "Фильтр истории печати", filters: { all: "Все", success: "Успешные", failed: "Ошибки" },
  reprintErrorTitle: "Повторная печать не завершена.", reprintErrorDetail: "Файлы истории не изменены. Попробуйте снова.",
  pagination: { aria: "История печати", previous: "Предыдущая страница истории", next: "Следующая страница истории", found: "Найдено", pageOf: "Страница {page} из {total}", loadMore: "Показать ещё", loading: "Загружаем ещё…", loadError: "Не удалось загрузить ещё", end: "Вся история загружена", added: "Добавлено: {count}" },
  table: { date: "Дата печати", supply: "Поставка", template: "Шаблон", labels: "Этикеток", status: "Статус", action: "Действие", job: "Задание #{id}", defaultTemplate: "По умолчанию", reprintAria: "Повторить печать {supply}", creating: "Создаём PDF", reprint: "Повторить", unavailable: "Повторная печать недоступна" },
  result: { title: "PDF созданы повторно", saved: "Сохранено этикеток: {count}. Открытие доступно только для файлов этой операции.", labels: "Этикетки", details: "Лист комплектации", openLabels: "Открыть этикетки", openDetails: "Открыть лист комплектации", openError: "Не удалось открыть PDF. Файл остаётся сохранённым." },
  status: { success: "Успешно", failed: "Ошибка" }, loading: "Загрузка истории печати",
  loadErrorTitle: "Не удалось загрузить историю", loadErrorDetail: "Локальные задания не изменены. Повторите запрос.", retry: "Повторить",
  empty: { filteredTitle: "Задания не найдены", title: "История печати пока пуста", filteredDetail: "Измените запрос или статус.", detail: "Успешные и неудачные PDF-задания появятся здесь автоматически." },
};

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type HistoryCopy = DeepString<typeof ru>;

const en: HistoryCopy = {
  summaryAria: "Print history summary", total: "Total jobs", success: "Successful", failed: "Failed",
  searchLabel: "Search print history", searchPlaceholder: "Supply, job ID, or template", search: "Search",
  filterAria: "Print history filter", filters: { all: "All", success: "Successful", failed: "Errors" },
  reprintErrorTitle: "Reprint did not finish.", reprintErrorDetail: "History files were not changed. Try again.",
  pagination: { aria: "Print history", previous: "Previous history page", next: "Next history page", found: "Found", pageOf: "Page {page} of {total}", loadMore: "Show more", loading: "Loading more…", loadError: "Could not load more", end: "The full history is loaded", added: "Added: {count}" },
  table: { date: "Printed", supply: "Supply", template: "Template", labels: "Labels", status: "Status", action: "Action", job: "Job #{id}", defaultTemplate: "Default", reprintAria: "Reprint {supply}", creating: "Creating PDF", reprint: "Reprint", unavailable: "Reprint unavailable" },
  result: { title: "PDFs recreated", saved: "Labels saved: {count}. Only files from this operation can be opened.", labels: "Labels", details: "Packing list", openLabels: "Open labels", openDetails: "Open packing list", openError: "Could not open the PDF. The file remains saved." },
  status: { success: "Successful", failed: "Error" }, loading: "Loading print history",
  loadErrorTitle: "Could not load print history", loadErrorDetail: "Local jobs were not changed. Retry the request.", retry: "Retry",
  empty: { filteredTitle: "No jobs found", title: "Print history is empty", filteredDetail: "Change the query or status.", detail: "Successful and failed PDF jobs will appear here automatically." },
};

const vi: HistoryCopy = {
  summaryAria: "Tóm tắt lịch sử in", total: "Tổng tác vụ", success: "Thành công", failed: "Có lỗi",
  searchLabel: "Tìm lịch sử in", searchPlaceholder: "Lô hàng, ID tác vụ hoặc mẫu", search: "Tìm",
  filterAria: "Bộ lọc lịch sử in", filters: { all: "Tất cả", success: "Thành công", failed: "Lỗi" },
  reprintErrorTitle: "In lại chưa hoàn tất.", reprintErrorDetail: "Các tệp lịch sử không thay đổi. Hãy thử lại.",
  pagination: { aria: "Lịch sử in", previous: "Trang lịch sử trước", next: "Trang lịch sử sau", found: "Tìm thấy", pageOf: "Trang {page} / {total}", loadMore: "Hiện thêm", loading: "Đang tải thêm…", loadError: "Không thể tải thêm", end: "Đã tải toàn bộ lịch sử", added: "Đã thêm: {count}" },
  table: { date: "Ngày in", supply: "Lô hàng", template: "Mẫu", labels: "Nhãn", status: "Trạng thái", action: "Thao tác", job: "Tác vụ #{id}", defaultTemplate: "Mặc định", reprintAria: "In lại {supply}", creating: "Đang tạo PDF", reprint: "In lại", unavailable: "Không thể in lại" },
  result: { title: "Đã tạo lại PDF", saved: "Số nhãn đã lưu: {count}. Chỉ có thể mở các tệp của thao tác này.", labels: "Nhãn", details: "Phiếu đóng gói", openLabels: "Mở nhãn", openDetails: "Mở phiếu đóng gói", openError: "Không thể mở PDF. Tệp vẫn được lưu." },
  status: { success: "Thành công", failed: "Lỗi" }, loading: "Đang tải lịch sử in",
  loadErrorTitle: "Không thể tải lịch sử in", loadErrorDetail: "Các tác vụ cục bộ không thay đổi. Hãy thử lại yêu cầu.", retry: "Thử lại",
  empty: { filteredTitle: "Không tìm thấy tác vụ", title: "Lịch sử in đang trống", filteredDetail: "Hãy đổi truy vấn hoặc trạng thái.", detail: "Các tác vụ PDF thành công và thất bại sẽ tự động xuất hiện tại đây." },
};

const zh: HistoryCopy = {
  summaryAria: "打印历史摘要", total: "任务总数", success: "成功", failed: "失败",
  searchLabel: "搜索打印历史", searchPlaceholder: "供货、任务 ID 或模板", search: "搜索",
  filterAria: "打印历史筛选", filters: { all: "全部", success: "成功", failed: "错误" },
  reprintErrorTitle: "重新打印未完成。", reprintErrorDetail: "历史文件未更改，请重试。",
  pagination: { aria: "打印历史", previous: "上一页打印历史", next: "下一页打印历史", found: "找到", pageOf: "第 {page} 页，共 {total} 页", loadMore: "显示更多", loading: "正在加载更多…", loadError: "无法加载更多", end: "已加载全部历史", added: "已添加：{count}" },
  table: { date: "打印日期", supply: "供货", template: "模板", labels: "标签数", status: "状态", action: "操作", job: "任务 #{id}", defaultTemplate: "默认", reprintAria: "重新打印 {supply}", creating: "正在创建 PDF", reprint: "重新打印", unavailable: "无法重新打印" },
  result: { title: "PDF 已重新创建", saved: "已保存标签：{count}。只能打开本次操作的文件。", labels: "标签", details: "装箱单", openLabels: "打开标签", openDetails: "打开装箱单", openError: "无法打开 PDF，文件仍已保存。" },
  status: { success: "成功", failed: "错误" }, loading: "正在加载打印历史",
  loadErrorTitle: "无法加载打印历史", loadErrorDetail: "本地任务未更改，请重试请求。", retry: "重试",
  empty: { filteredTitle: "未找到任务", title: "打印历史为空", filteredDetail: "请更改查询或状态。", detail: "成功和失败的 PDF 任务会自动显示在此处。" },
};

const copies: Record<Language, HistoryCopy> = { ru, en, vi, zh };

export function getHistoryCopy(language: Language): HistoryCopy {
  return copies[language];
}
