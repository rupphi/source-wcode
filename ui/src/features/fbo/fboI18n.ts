import type { Language } from "../../i18n";

const ru = {
  header: {
    title: "Товарные этикетки FBO",
    description: "Выберите количество пар этикеток для SKU. Карточки и изображения берутся только из локального кэша WCode.",
    guarded: "KIZ списываются только при публикации PDF",
  },
  search: {
    label: "Поиск товаров FBO", placeholder: "nmID, артикул продавца или SKU", subjects: "Категории",
    subjectCount: "Категории · {count}", noSubjects: "Категорий пока нет", submit: "Найти", clear: "Сбросить фильтры",
  },
  selection: {
    label: "Выбор для печати FBO", summary: "{pairs} · {skus} SKU", clearedAfterSuccess: "После успешной пакетной печати выбор будет очищен.",
    clear: "Очистить", createAria: "Создать PDF для {pairs}", creating: "Создаём PDF…", create: "Создать PDF",
    limit: "Не более 500 SKU и 10 000 пар за одно задание.",
  },
  export: {
    errorTitle: "Не удалось создать PDF FBO.", errorDescription: "Выбор сохранён. Проверьте локальные KIZ и повторите действие.",
    success: "PDF FBO создан", pages: "{count} стр.", openError: "Не удалось открыть файл. Он остаётся в выбранной папке.",
    openAria: "Открыть PDF FBO", opening: "Открываем…", open: "Открыть PDF",
  },
  catalog: { label: "Товары FBO", loadMoreError: "Страницу загрузить не удалось. Уже выбранные количества сохранены." },
  pagination: { label: "Товары FBO", previousAria: "Предыдущая страница FBO", previous: "Назад", loading: "Загружаем ещё…", page: "Страница {page}", nextAria: "Следующая страница FBO", next: "Далее", loadMore: "Показать ещё", end: "Все товары загружены", added: "Добавлено: {count}" },
  product: {
    photo: "Фото товара {name}", unnamed: "Без названия", uncategorized: "Без категории", requiresKiz: "Требуется KIZ",
    article: "Артикул", size: "Размер", decrease: "Уменьшить количество {name}", quantity: "Количество для {name}, SKU {sku}",
    increase: "Увеличить количество {name}", quickPrintAria: "Быстрая печать {name}", quickPrint: "Быстрая печать",
  },
  loading: "Загрузка товаров FBO",
  error: { title: "Не удалось загрузить товары FBO", description: "Локальные данные не изменены. Повторите запрос.", retry: "Повторить" },
  empty: { filteredTitle: "Товары не найдены", title: "Каталог FBO пока пуст", filteredDescription: "Измените запрос или категории.", description: "Синхронизируйте каталог Wildberries на главной странице." },
  pairs: { one: "пара", few: "пары", many: "пар", other: "пар" },
} as const;

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type FboCopy = DeepString<typeof ru>;

const en: FboCopy = {
  header: { title: "FBO product labels", description: "Choose the number of label pairs for each SKU. Product cards and images come only from WCode local cache.", guarded: "KIZ codes are consumed only when the PDF is published" },
  search: { label: "Search FBO products", placeholder: "nmID, vendor article, or SKU", subjects: "Categories", subjectCount: "Categories · {count}", noSubjects: "No categories yet", submit: "Search", clear: "Clear filters" },
  selection: { label: "FBO print selection", summary: "{pairs} · {skus} SKU", clearedAfterSuccess: "The selection is cleared after a successful batch export.", clear: "Clear", createAria: "Create a PDF for {pairs}", creating: "Creating PDF…", create: "Create PDF", limit: "Use no more than 500 SKUs and 10,000 pairs in one job." },
  export: { errorTitle: "Could not create the FBO PDF.", errorDescription: "The selection was preserved. Check local KIZ stock and try again.", success: "FBO PDF created", pages: "{count} pages", openError: "Could not open the file. It remains in the selected folder.", openAria: "Open FBO PDF", opening: "Opening…", open: "Open PDF" },
  catalog: { label: "FBO products", loadMoreError: "Could not load the page. Previously selected quantities were preserved." },
  pagination: { label: "FBO products", previousAria: "Previous FBO page", previous: "Back", loading: "Loading more…", page: "Page {page}", nextAria: "Next FBO page", next: "Next", loadMore: "Show more", end: "All products are loaded", added: "Added: {count}" },
  product: { photo: "Product photo {name}", unnamed: "Unnamed product", uncategorized: "Uncategorized", requiresKiz: "KIZ required", article: "Article", size: "Size", decrease: "Decrease quantity for {name}", quantity: "Quantity for {name}, SKU {sku}", increase: "Increase quantity for {name}", quickPrintAria: "Quick print {name}", quickPrint: "Quick print" },
  loading: "Loading FBO products",
  error: { title: "Could not load FBO products", description: "Local data was not changed. Try the request again.", retry: "Retry" },
  empty: { filteredTitle: "No matching products", title: "The FBO catalog is empty", filteredDescription: "Change the query or categories.", description: "Synchronize the Wildberries catalog from the home page." },
  pairs: { one: "pair", few: "pairs", many: "pairs", other: "pairs" },
};

const vi: FboCopy = {
  header: { title: "Nhãn sản phẩm FBO", description: "Chọn số cặp nhãn cho từng SKU. Thẻ sản phẩm và hình ảnh chỉ lấy từ bộ nhớ đệm cục bộ của WCode.", guarded: "KIZ chỉ được sử dụng khi PDF được xuất" },
  search: { label: "Tìm sản phẩm FBO", placeholder: "nmID, mã hàng của người bán hoặc SKU", subjects: "Danh mục", subjectCount: "Danh mục · {count}", noSubjects: "Chưa có danh mục", submit: "Tìm", clear: "Xóa bộ lọc" },
  selection: { label: "Lựa chọn in FBO", summary: "{pairs} · {skus} SKU", clearedAfterSuccess: "Lựa chọn sẽ được xóa sau khi xuất hàng loạt thành công.", clear: "Xóa", createAria: "Tạo PDF cho {pairs}", creating: "Đang tạo PDF…", create: "Tạo PDF", limit: "Mỗi tác vụ không quá 500 SKU và 10.000 cặp." },
  export: { errorTitle: "Không thể tạo PDF FBO.", errorDescription: "Lựa chọn đã được giữ lại. Hãy kiểm tra KIZ cục bộ và thử lại.", success: "Đã tạo PDF FBO", pages: "{count} trang", openError: "Không thể mở tệp. Tệp vẫn nằm trong thư mục đã chọn.", openAria: "Mở PDF FBO", opening: "Đang mở…", open: "Mở PDF" },
  catalog: { label: "Sản phẩm FBO", loadMoreError: "Không thể tải trang. Các số lượng đã chọn vẫn được giữ lại." },
  pagination: { label: "Sản phẩm FBO", previousAria: "Trang FBO trước", previous: "Trước", loading: "Đang tải thêm…", page: "Trang {page}", nextAria: "Trang FBO sau", next: "Sau", loadMore: "Hiện thêm", end: "Đã tải toàn bộ sản phẩm", added: "Đã thêm: {count}" },
  product: { photo: "Ảnh sản phẩm {name}", unnamed: "Sản phẩm chưa có tên", uncategorized: "Chưa phân loại", requiresKiz: "Cần KIZ", article: "Mã sản phẩm", size: "Kích cỡ", decrease: "Giảm số lượng {name}", quantity: "Số lượng cho {name}, SKU {sku}", increase: "Tăng số lượng {name}", quickPrintAria: "In nhanh {name}", quickPrint: "In nhanh" },
  loading: "Đang tải sản phẩm FBO",
  error: { title: "Không thể tải sản phẩm FBO", description: "Dữ liệu cục bộ không thay đổi. Hãy thử lại.", retry: "Thử lại" },
  empty: { filteredTitle: "Không tìm thấy sản phẩm", title: "Danh mục FBO đang trống", filteredDescription: "Hãy đổi nội dung tìm kiếm hoặc danh mục.", description: "Hãy đồng bộ danh mục Wildberries từ trang chính." },
  pairs: { one: "cặp", few: "cặp", many: "cặp", other: "cặp" },
};

const zh: FboCopy = {
  header: { title: "FBO 商品标签", description: "为每个 SKU 选择标签对数量。商品卡片和图片仅来自 WCode 本地缓存。", guarded: "仅在发布 PDF 时消耗 KIZ" },
  search: { label: "搜索 FBO 商品", placeholder: "nmID、卖家商品编号或 SKU", subjects: "类别", subjectCount: "类别 · {count}", noSubjects: "暂无类别", submit: "搜索", clear: "清除筛选" },
  selection: { label: "FBO 打印选择", summary: "{pairs} · {skus} SKU", clearedAfterSuccess: "批量导出成功后将清除选择。", clear: "清除", createAria: "为 {pairs} 创建 PDF", creating: "正在创建 PDF…", create: "创建 PDF", limit: "单个任务最多 500 个 SKU 和 10,000 对。" },
  export: { errorTitle: "无法创建 FBO PDF。", errorDescription: "已保留选择。请检查本地 KIZ 后重试。", success: "FBO PDF 已创建", pages: "{count} 页", openError: "无法打开文件。文件仍保留在所选文件夹中。", openAria: "打开 FBO PDF", opening: "正在打开…", open: "打开 PDF" },
  catalog: { label: "FBO 商品", loadMoreError: "无法加载页面，已选择的数量仍被保留。" },
  pagination: { label: "FBO 商品", previousAria: "上一页 FBO", previous: "上一页", loading: "正在加载更多…", page: "第 {page} 页", nextAria: "下一页 FBO", next: "下一页", loadMore: "显示更多", end: "已加载全部商品", added: "已添加：{count}" },
  product: { photo: "商品图片 {name}", unnamed: "未命名商品", uncategorized: "未分类", requiresKiz: "需要 KIZ", article: "商品编号", size: "尺码", decrease: "减少 {name} 的数量", quantity: "{name} 的数量，SKU {sku}", increase: "增加 {name} 的数量", quickPrintAria: "快速打印 {name}", quickPrint: "快速打印" },
  loading: "正在加载 FBO 商品",
  error: { title: "无法加载 FBO 商品", description: "本地数据未被修改，请重试。", retry: "重试" },
  empty: { filteredTitle: "未找到商品", title: "FBO 目录为空", filteredDescription: "请修改搜索内容或类别。", description: "请从主页同步 Wildberries 目录。" },
  pairs: { one: "对", few: "对", many: "对", other: "对" },
};

const copies: Record<Language, FboCopy> = { ru, en, vi, zh };

export function getFboCopy(language: Language): FboCopy {
  return copies[language];
}

export function formatFboPairs(copy: FboCopy, locale: string, value: number): string {
  const category = new Intl.PluralRules(locale).select(value) as keyof FboCopy["pairs"];
  const noun = copy.pairs[category] ?? copy.pairs.other;
  return `${new Intl.NumberFormat(locale).format(value)} ${noun}`;
}

export const defaultFboCopy = ru;
