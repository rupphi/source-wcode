import type { Language } from "../../i18n";

const ru = {
  header: { title: "Соответствия SKU и GTIN", description: "Свяжите категории и значения пола из локального каталога Wildberries с GTIN для точного подбора KIZ при печати.", guarded: "Изменяются только локальные правила WCode" },
  notice: { saved: "Соответствие GTIN сохранено", close: "Закрыть уведомление" },
  search: { label: "Поиск GTIN", placeholder: "GTIN, название товара или категория", submitAria: "Найти GTIN", submit: "Найти", categoriesAria: "Категории GTIN", categories: "Категории", selected: "Выбрано: {count}", none: "Категорий пока нет", clear: "Сбросить" },
  editorError: { title: "Не удалось открыть редактор соответствий", retryAria: "Повторить открытие редактора", retry: "Повторить" },
  catalog: {
    loadingAria: "Загрузка каталога GTIN", loading: "Загружаем локальный каталог GTIN…",
    errorTitle: "Не удалось загрузить каталог GTIN", errorDescription: "Локальные данные не изменены. Повторите запрос.", retry: "Повторить",
    filteredEmptyTitle: "GTIN по фильтрам не найдены", emptyTitle: "Каталог GTIN пока пуст", filteredEmptyDescription: "Измените запрос или сбросьте категории.", emptyDescription: "Синхронизация товаров Znack появится в следующем шаге миграции.",
    title: "Локальный каталог GTIN", summary: "На странице {count} · с правилами {mapped}", available: "{count} KIZ доступно",
    previousAria: "Предыдущая страница GTIN", previous: "Назад", page: "Страница {page}", nextAria: "Следующая страница GTIN", next: "Далее",
  },
  row: { unnamed: "Без названия", updated: "Обновлено: {date}", noDate: "нет данных", available: "доступно", reserved: "в резерве", consumed: "использовано", unmapped: "Не сопоставлен", editAria: "Настроить соответствие для {gtin}", edit: "Настроить" },
  editor: {
    dialogAria: "Соответствие GTIN {gtin}", close: "Закрыть редактор", loading: "Загружаем правила и владельцев…",
    eyebrow: "Редактор соответствий", title: "Категории → GTIN", description: "Один вариант категории и пола может принадлежать только одному GTIN.",
    subjectsTitle: "Категории WB", available: "{count} доступно", useSubject: "Использовать категорию {subject}{owner}", chooseSubjectAria: "Выбрать категорию {subject}", occupiedSuffix: " · занято {gtin}",
    gendersTitle: "Значения пола", chooseSubject: "Выберите категорию", noSubjects: "В локальном каталоге нет категорий для настройки.",
    selectedTitle: "Выбранные правила", categories: "{count} категорий", emptySelection: "Выберите категории и значения пола. Пустой список очистит соответствие этого GTIN.", removeRule: "Удалить правило {subject}",
    saveError: "Не удалось сохранить соответствие", atomic: "Сохранение атомарно заменит правила только для этого GTIN.", cancel: "Отмена", saveAria: "Сохранить соответствие", saving: "Сохраняем…", save: "Сохранить",
  },
  rule: { wildcard: "Все значения пола", selected: "{count} выбрано", occupied: "Занято: {owners}", unused: "Не используется", exactOne: "точное значение", exactFew: "точных значения", exactMany: "точных значений", exactOther: "точных значений", rulesOne: "правило", rulesFew: "правила", rulesMany: "правил", rulesOther: "правил" },
  gender: { unspecified: "Пол не указан", allOwned: "Все варианты уже принадлежат {owners}.", enableHint: "Включите категорию, чтобы выбрать допустимые значения пола.", enable: "Включить категорию", wildcardDescription: "Будущие значения этой категории тоже получат этот GTIN.", wildcardBlocked: "Недоступно: часть вариантов занята {owners}", occupiedSuffix: " · занято {gtin}", empty: "У категории нет сохранённых значений пола. Используйте правило «Все значения пола»." },
  statuses: { VALIDATING: "Проверка", CREATING_ORDER: "Создание заказа", POLLING_ORDER: "Ожидание кодов", DOWNLOADING_CODES: "Загрузка кодов", CODES_READY: "Коды готовы", CODES_DOWNLOADED: "Коды загружены", WAITING_INTRODUCTION_READINESS: "Ожидает ввода в оборот", SUBMITTING_INTRODUCTION: "Отправка в оборот", POLLING_INTRODUCTION: "Проверка ввода", INTRODUCTION_FAILED: "Ошибка ввода", INTRODUCED: "Введено в оборот", COMPLETED: "Завершено", FAILED: "Ошибка", CANCELLED: "Отменено" },
} as const;

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type KizMappingCopy = DeepString<typeof ru>;

const en: KizMappingCopy = {
  header: { title: "SKU and GTIN mappings", description: "Map categories and gender values from the local Wildberries catalog to GTINs so WCode can select the right KIZ codes when printing.", guarded: "Only local WCode rules are changed" },
  notice: { saved: "GTIN mapping saved", close: "Dismiss notification" },
  search: { label: "Search GTINs", placeholder: "GTIN, product name, or category", submitAria: "Search GTINs", submit: "Search", categoriesAria: "GTIN categories", categories: "Categories", selected: "Selected: {count}", none: "No categories yet", clear: "Clear" },
  editorError: { title: "Could not open the mapping editor", retryAria: "Retry opening the mapping editor", retry: "Retry" },
  catalog: { loadingAria: "Loading the GTIN catalog", loading: "Loading the local GTIN catalog…", errorTitle: "Could not load the GTIN catalog", errorDescription: "Local data was not changed. Try the request again.", retry: "Retry", filteredEmptyTitle: "No matching GTINs", emptyTitle: "The GTIN catalog is empty", filteredEmptyDescription: "Change the query or clear the categories.", emptyDescription: "Synchronize the Znack product catalog to populate this workspace.", title: "Local GTIN catalog", summary: "{count} on this page · {mapped} mapped", available: "{count} KIZ available", previousAria: "Previous GTIN page", previous: "Back", page: "Page {page}", nextAria: "Next GTIN page", next: "Next" },
  row: { unnamed: "Unnamed product", updated: "Updated: {date}", noDate: "no data", available: "available", reserved: "reserved", consumed: "used", unmapped: "Not mapped", editAria: "Configure mapping for {gtin}", edit: "Configure" },
  editor: { dialogAria: "GTIN mapping {gtin}", close: "Close editor", loading: "Loading rules and owners…", eyebrow: "Mapping editor", title: "Categories → GTIN", description: "Each category and gender combination can belong to only one GTIN.", subjectsTitle: "WB categories", available: "{count} available", useSubject: "Use category {subject}{owner}", chooseSubjectAria: "Select category {subject}", occupiedSuffix: " · owned by {gtin}", gendersTitle: "Gender values", chooseSubject: "Select a category", noSubjects: "There are no local catalog categories to configure.", selectedTitle: "Selected rules", categories: "{count} categories", emptySelection: "Select categories and gender values. An empty list clears this GTIN mapping.", removeRule: "Remove rule {subject}", saveError: "Could not save the mapping", atomic: "Saving atomically replaces rules for this GTIN only.", cancel: "Cancel", saveAria: "Save mapping", saving: "Saving…", save: "Save" },
  rule: { wildcard: "All gender values", selected: "{count} selected", occupied: "Owned by: {owners}", unused: "Not used", exactOne: "exact value", exactFew: "exact values", exactMany: "exact values", exactOther: "exact values", rulesOne: "rule", rulesFew: "rules", rulesMany: "rules", rulesOther: "rules" },
  gender: { unspecified: "Gender unspecified", allOwned: "All values already belong to {owners}.", enableHint: "Enable the category to select allowed gender values.", enable: "Enable category", wildcardDescription: "Future values in this category will also receive this GTIN.", wildcardBlocked: "Unavailable: some values belong to {owners}", occupiedSuffix: " · owned by {gtin}", empty: "This category has no saved gender values. Use the “All gender values” rule." },
  statuses: { VALIDATING: "Validating", CREATING_ORDER: "Creating order", POLLING_ORDER: "Waiting for codes", DOWNLOADING_CODES: "Downloading codes", CODES_READY: "Codes ready", CODES_DOWNLOADED: "Codes downloaded", WAITING_INTRODUCTION_READINESS: "Waiting for introduction", SUBMITTING_INTRODUCTION: "Submitting introduction", POLLING_INTRODUCTION: "Checking introduction", INTRODUCTION_FAILED: "Introduction failed", INTRODUCED: "Introduced", COMPLETED: "Completed", FAILED: "Failed", CANCELLED: "Cancelled" },
};

const vi: KizMappingCopy = {
  header: { title: "Ánh xạ SKU và GTIN", description: "Liên kết danh mục và giá trị giới tính từ danh mục Wildberries cục bộ với GTIN để chọn đúng KIZ khi in.", guarded: "Chỉ thay đổi quy tắc WCode cục bộ" },
  notice: { saved: "Đã lưu ánh xạ GTIN", close: "Đóng thông báo" },
  search: { label: "Tìm GTIN", placeholder: "GTIN, tên sản phẩm hoặc danh mục", submitAria: "Tìm GTIN", submit: "Tìm", categoriesAria: "Danh mục GTIN", categories: "Danh mục", selected: "Đã chọn: {count}", none: "Chưa có danh mục", clear: "Xóa" },
  editorError: { title: "Không thể mở trình sửa ánh xạ", retryAria: "Thử mở lại trình sửa ánh xạ", retry: "Thử lại" },
  catalog: { loadingAria: "Đang tải danh mục GTIN", loading: "Đang tải danh mục GTIN cục bộ…", errorTitle: "Không thể tải danh mục GTIN", errorDescription: "Dữ liệu cục bộ không thay đổi. Hãy thử lại.", retry: "Thử lại", filteredEmptyTitle: "Không tìm thấy GTIN phù hợp", emptyTitle: "Danh mục GTIN đang trống", filteredEmptyDescription: "Hãy đổi nội dung tìm kiếm hoặc xóa danh mục.", emptyDescription: "Hãy đồng bộ danh mục sản phẩm Znack để điền dữ liệu cho khu vực này.", title: "Danh mục GTIN cục bộ", summary: "{count} trên trang · {mapped} đã ánh xạ", available: "Có {count} KIZ", previousAria: "Trang GTIN trước", previous: "Trước", page: "Trang {page}", nextAria: "Trang GTIN sau", next: "Sau" },
  row: { unnamed: "Sản phẩm chưa có tên", updated: "Cập nhật: {date}", noDate: "không có dữ liệu", available: "có sẵn", reserved: "đã giữ", consumed: "đã dùng", unmapped: "Chưa ánh xạ", editAria: "Cấu hình ánh xạ cho {gtin}", edit: "Cấu hình" },
  editor: { dialogAria: "Ánh xạ GTIN {gtin}", close: "Đóng trình sửa", loading: "Đang tải quy tắc và chủ sở hữu…", eyebrow: "Trình sửa ánh xạ", title: "Danh mục → GTIN", description: "Mỗi tổ hợp danh mục và giới tính chỉ có thể thuộc về một GTIN.", subjectsTitle: "Danh mục WB", available: "Có {count}", useSubject: "Dùng danh mục {subject}{owner}", chooseSubjectAria: "Chọn danh mục {subject}", occupiedSuffix: " · thuộc {gtin}", gendersTitle: "Giá trị giới tính", chooseSubject: "Chọn một danh mục", noSubjects: "Không có danh mục cục bộ để cấu hình.", selectedTitle: "Quy tắc đã chọn", categories: "{count} danh mục", emptySelection: "Chọn danh mục và giá trị giới tính. Danh sách trống sẽ xóa ánh xạ của GTIN này.", removeRule: "Xóa quy tắc {subject}", saveError: "Không thể lưu ánh xạ", atomic: "Việc lưu chỉ thay thế nguyên tử các quy tắc của GTIN này.", cancel: "Hủy", saveAria: "Lưu ánh xạ", saving: "Đang lưu…", save: "Lưu" },
  rule: { wildcard: "Mọi giá trị giới tính", selected: "Đã chọn {count}", occupied: "Thuộc về: {owners}", unused: "Không dùng", exactOne: "giá trị chính xác", exactFew: "giá trị chính xác", exactMany: "giá trị chính xác", exactOther: "giá trị chính xác", rulesOne: "quy tắc", rulesFew: "quy tắc", rulesMany: "quy tắc", rulesOther: "quy tắc" },
  gender: { unspecified: "Chưa xác định giới tính", allOwned: "Mọi giá trị đã thuộc về {owners}.", enableHint: "Bật danh mục để chọn các giá trị giới tính hợp lệ.", enable: "Bật danh mục", wildcardDescription: "Các giá trị mới trong danh mục này cũng sẽ nhận GTIN này.", wildcardBlocked: "Không khả dụng: một số giá trị thuộc về {owners}", occupiedSuffix: " · thuộc {gtin}", empty: "Danh mục này chưa có giá trị giới tính đã lưu. Hãy dùng quy tắc “Mọi giá trị giới tính”." },
  statuses: { VALIDATING: "Đang xác thực", CREATING_ORDER: "Đang tạo đơn", POLLING_ORDER: "Đang chờ mã", DOWNLOADING_CODES: "Đang tải mã", CODES_READY: "Mã đã sẵn sàng", CODES_DOWNLOADED: "Đã tải mã", WAITING_INTRODUCTION_READINESS: "Đang chờ đưa vào lưu thông", SUBMITTING_INTRODUCTION: "Đang gửi vào lưu thông", POLLING_INTRODUCTION: "Đang kiểm tra lưu thông", INTRODUCTION_FAILED: "Đưa vào lưu thông thất bại", INTRODUCED: "Đã đưa vào lưu thông", COMPLETED: "Hoàn tất", FAILED: "Thất bại", CANCELLED: "Đã hủy" },
};

const zh: KizMappingCopy = {
  header: { title: "SKU 与 GTIN 映射", description: "将本地 Wildberries 目录中的类别和性别值映射到 GTIN，以便打印时准确选择 KIZ。", guarded: "仅更改 WCode 本地规则" },
  notice: { saved: "GTIN 映射已保存", close: "关闭通知" },
  search: { label: "搜索 GTIN", placeholder: "GTIN、商品名称或类别", submitAria: "搜索 GTIN", submit: "搜索", categoriesAria: "GTIN 类别", categories: "类别", selected: "已选：{count}", none: "暂无类别", clear: "清除" },
  editorError: { title: "无法打开映射编辑器", retryAria: "重试打开映射编辑器", retry: "重试" },
  catalog: { loadingAria: "正在加载 GTIN 目录", loading: "正在加载本地 GTIN 目录…", errorTitle: "无法加载 GTIN 目录", errorDescription: "本地数据未被修改，请重试。", retry: "重试", filteredEmptyTitle: "未找到匹配的 GTIN", emptyTitle: "GTIN 目录为空", filteredEmptyDescription: "请修改搜索内容或清除类别。", emptyDescription: "请同步 Znack 商品目录以填充此工作区。", title: "本地 GTIN 目录", summary: "本页 {count} 项 · 已映射 {mapped} 项", available: "{count} 个 KIZ 可用", previousAria: "上一页 GTIN", previous: "上一页", page: "第 {page} 页", nextAria: "下一页 GTIN", next: "下一页" },
  row: { unnamed: "未命名商品", updated: "更新于：{date}", noDate: "无数据", available: "可用", reserved: "已预留", consumed: "已使用", unmapped: "未映射", editAria: "配置 {gtin} 的映射", edit: "配置" },
  editor: { dialogAria: "GTIN 映射 {gtin}", close: "关闭编辑器", loading: "正在加载规则和所有者…", eyebrow: "映射编辑器", title: "类别 → GTIN", description: "每个类别和性别组合只能属于一个 GTIN。", subjectsTitle: "WB 类别", available: "{count} 个可用", useSubject: "使用类别 {subject}{owner}", chooseSubjectAria: "选择类别 {subject}", occupiedSuffix: " · 属于 {gtin}", gendersTitle: "性别值", chooseSubject: "请选择类别", noSubjects: "本地目录中没有可配置的类别。", selectedTitle: "已选规则", categories: "{count} 个类别", emptySelection: "请选择类别和性别值。空列表将清除此 GTIN 的映射。", removeRule: "删除规则 {subject}", saveError: "无法保存映射", atomic: "保存操作只会原子替换此 GTIN 的规则。", cancel: "取消", saveAria: "保存映射", saving: "正在保存…", save: "保存" },
  rule: { wildcard: "所有性别值", selected: "已选 {count} 个", occupied: "属于：{owners}", unused: "未使用", exactOne: "个精确值", exactFew: "个精确值", exactMany: "个精确值", exactOther: "个精确值", rulesOne: "条规则", rulesFew: "条规则", rulesMany: "条规则", rulesOther: "条规则" },
  gender: { unspecified: "未指定性别", allOwned: "所有值已属于 {owners}。", enableHint: "启用类别后可选择允许的性别值。", enable: "启用类别", wildcardDescription: "此类别将来的值也会获得该 GTIN。", wildcardBlocked: "不可用：部分值属于 {owners}", occupiedSuffix: " · 属于 {gtin}", empty: "此类别没有已保存的性别值，请使用“所有性别值”规则。" },
  statuses: { VALIDATING: "正在验证", CREATING_ORDER: "正在创建订单", POLLING_ORDER: "正在等待码", DOWNLOADING_CODES: "正在下载码", CODES_READY: "码已就绪", CODES_DOWNLOADED: "码已下载", WAITING_INTRODUCTION_READINESS: "正在等待投入流通", SUBMITTING_INTRODUCTION: "正在提交投入流通", POLLING_INTRODUCTION: "正在检查流通状态", INTRODUCTION_FAILED: "投入流通失败", INTRODUCED: "已投入流通", COMPLETED: "已完成", FAILED: "失败", CANCELLED: "已取消" },
};

const copies: Record<Language, KizMappingCopy> = { ru, en, vi, zh };

export function getKizMappingCopy(language: Language): KizMappingCopy {
  return copies[language];
}

export function formatKizCount(copy: KizMappingCopy, locale: string, value: number, kind: "exact" | "rules"): string {
  const category = new Intl.PluralRules(locale).select(value);
  const prefix = kind === "exact" ? "exact" : "rules";
  const key = `${prefix}${category.charAt(0).toUpperCase()}${category.slice(1)}` as keyof KizMappingCopy["rule"];
  const fallback = kind === "exact" ? copy.rule.exactOther : copy.rule.rulesOther;
  return `${new Intl.NumberFormat(locale).format(value)} ${copy.rule[key] ?? fallback}`;
}

export const defaultKizMappingCopy = ru;
