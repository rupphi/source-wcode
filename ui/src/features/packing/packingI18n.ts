import type { Language } from "../../i18n";
import { defaultSupplyCopy, getSupplyCopy } from "../supplies/supplyI18n";
import { defaultPackingMutationCopy, getPackingMutationCopy } from "./PackingMutationCopy";

const ru = {
  header: {
    title: "Очередь комплектации",
    description: "Создание, наполнение и передача поставки выполняются только после отдельной проверки и подтверждения.",
    guarded: "Подтверждение перед записью",
  },
  tabs: { label: "Этапы упаковки FBS", new: "Новые заказы", preparation: "На сборке", dispatch: "К отгрузке" },
  selection: { label: "Действия с выбранными заказами", selected: "Выбрано:", add: "Добавить в поставку", create: "Создать поставку" },
  search: {
    label: "Поиск в очереди упаковки", orderPlaceholder: "Заказ, товар, артикул или штрихкод",
    supplyPlaceholder: "ID или название поставки", submit: "Найти", categoriesLabel: "Категории новых заказов", categories: "Категории",
  },
  pagination: { label: "Очередь упаковки", previous: "Предыдущая страница очереди", next: "Следующая страница очереди", loadMore: "Показать ещё", loading: "Загружаем ещё…", loadError: "Не удалось загрузить ещё", end: "Вся очередь загружена", added: "Добавлено: {count}" },
  notices: { created: "Поставка {id} создана", added: "Заказы добавлены в поставку {id}", delivered: "Поставка {id} передана в доставку" },
  orders: {
    label: "Новые заказы", select: "Выбрать заказ #{id}", uncategorized: "Без категории", size: "Размер {value}", requiresKiz: "Требуется KIZ",
  },
  supplies: {
    columns: { supply: "Поставка", mode: "Схема", created: "Создана", orders: "Заказов", action: "Действие" },
    modeUnknown: "Не указана", prepareDelivery: "Проверить передачу {name}", deliver: "Передать", open: "Открыть", openLabel: "Открыть поставку {name}",
  },
  loading: "Загрузка очереди упаковки",
  error: { title: "Не удалось открыть очередь упаковки", description: "Локальные данные не изменены. Повторите запрос.", retry: "Повторить" },
  empty: {
    filtered: "Ничего не найдено", new: "Новых заказов пока нет", preparation: "Поставок на сборке пока нет", dispatch: "Готовых отгрузок пока нет",
    filteredDescription: "Измените запрос или категории.", description: "Обновите данные Wildberries на главной странице.",
  },
  shipmentPrefix: "Shipment",
  mutation: defaultPackingMutationCopy,
  supply: defaultSupplyCopy,
} as const;

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type PackingCopy = DeepString<typeof ru>;

const en: PackingCopy = {
  header: { title: "Packing queue", description: "Creating, filling, and delivering a supply each require a separate check and confirmation.", guarded: "Confirmation before changes" },
  tabs: { label: "FBS packing stages", new: "New orders", preparation: "Packing", dispatch: "Ready to dispatch" },
  selection: { label: "Selected order actions", selected: "Selected:", add: "Add to supply", create: "Create supply" },
  search: { label: "Search the packing queue", orderPlaceholder: "Order, product, article, or barcode", supplyPlaceholder: "Supply ID or name", submit: "Search", categoriesLabel: "New order categories", categories: "Categories" },
  pagination: { label: "Packing queue", previous: "Previous queue page", next: "Next queue page", loadMore: "Show more", loading: "Loading more…", loadError: "Could not load more", end: "The full queue is loaded", added: "Added: {count}" },
  notices: { created: "Supply {id} was created", added: "Orders were added to supply {id}", delivered: "Supply {id} was delivered" },
  orders: { label: "New orders", select: "Select order #{id}", uncategorized: "Uncategorized", size: "Size {value}", requiresKiz: "KIZ required" },
  supplies: { columns: { supply: "Supply", mode: "Mode", created: "Created", orders: "Orders", action: "Action" }, modeUnknown: "Not specified", prepareDelivery: "Check delivery of {name}", deliver: "Deliver", open: "Open", openLabel: "Open supply {name}" },
  loading: "Loading the packing queue",
  error: { title: "Could not open the packing queue", description: "Local data was not changed. Try the request again.", retry: "Retry" },
  empty: { filtered: "No matches", new: "No new orders yet", preparation: "No supplies are being packed", dispatch: "No dispatches are ready", filteredDescription: "Change the query or categories.", description: "Refresh Wildberries data from the home page." },
  shipmentPrefix: "Shipment",
  mutation: getPackingMutationCopy("en"),
  supply: getSupplyCopy("en"),
};

const vi: PackingCopy = {
  header: { title: "Hàng đợi đóng gói", description: "Việc tạo, thêm đơn và giao lô đều cần được kiểm tra và xác nhận riêng.", guarded: "Xác nhận trước khi thay đổi" },
  tabs: { label: "Các bước đóng gói FBS", new: "Đơn hàng mới", preparation: "Đang đóng gói", dispatch: "Sẵn sàng giao" },
  selection: { label: "Thao tác với đơn đã chọn", selected: "Đã chọn:", add: "Thêm vào lô", create: "Tạo lô giao hàng" },
  search: { label: "Tìm trong hàng đợi đóng gói", orderPlaceholder: "Đơn hàng, sản phẩm, mã hàng hoặc mã vạch", supplyPlaceholder: "ID hoặc tên lô giao hàng", submit: "Tìm", categoriesLabel: "Danh mục đơn hàng mới", categories: "Danh mục" },
  pagination: { label: "Hàng đợi đóng gói", previous: "Trang hàng đợi trước", next: "Trang hàng đợi sau", loadMore: "Hiện thêm", loading: "Đang tải thêm…", loadError: "Không thể tải thêm", end: "Đã tải toàn bộ hàng đợi", added: "Đã thêm: {count}" },
  notices: { created: "Đã tạo lô {id}", added: "Đã thêm đơn vào lô {id}", delivered: "Đã chuyển lô {id} sang giao hàng" },
  orders: { label: "Đơn hàng mới", select: "Chọn đơn #{id}", uncategorized: "Chưa phân loại", size: "Kích cỡ {value}", requiresKiz: "Cần KIZ" },
  supplies: { columns: { supply: "Lô giao hàng", mode: "Mô hình", created: "Đã tạo", orders: "Đơn hàng", action: "Thao tác" }, modeUnknown: "Chưa xác định", prepareDelivery: "Kiểm tra giao lô {name}", deliver: "Giao hàng", open: "Mở", openLabel: "Mở lô {name}" },
  loading: "Đang tải hàng đợi đóng gói",
  error: { title: "Không thể mở hàng đợi đóng gói", description: "Dữ liệu cục bộ không thay đổi. Hãy thử lại.", retry: "Thử lại" },
  empty: { filtered: "Không tìm thấy kết quả", new: "Chưa có đơn hàng mới", preparation: "Chưa có lô đang đóng gói", dispatch: "Chưa có lô sẵn sàng giao", filteredDescription: "Hãy đổi nội dung tìm kiếm hoặc danh mục.", description: "Hãy cập nhật dữ liệu Wildberries từ trang chính." },
  shipmentPrefix: "Lô hàng",
  mutation: getPackingMutationCopy("vi"),
  supply: getSupplyCopy("vi"),
};

const zh: PackingCopy = {
  header: { title: "打包队列", description: "创建、添加订单和交付供货都需要单独检查并确认。", guarded: "更改前确认" },
  tabs: { label: "FBS 打包阶段", new: "新订单", preparation: "打包中", dispatch: "待发货" },
  selection: { label: "已选订单操作", selected: "已选择：", add: "添加到供货", create: "创建供货" },
  search: { label: "搜索打包队列", orderPlaceholder: "订单、商品、商品编号或条码", supplyPlaceholder: "供货 ID 或名称", submit: "搜索", categoriesLabel: "新订单类别", categories: "类别" },
  pagination: { label: "打包队列", previous: "上一页队列", next: "下一页队列", loadMore: "显示更多", loading: "正在加载更多…", loadError: "无法加载更多", end: "已加载全部队列", added: "已添加：{count}" },
  notices: { created: "已创建供货 {id}", added: "订单已添加到供货 {id}", delivered: "供货 {id} 已转入交付" },
  orders: { label: "新订单", select: "选择订单 #{id}", uncategorized: "未分类", size: "尺码 {value}", requiresKiz: "需要 KIZ" },
  supplies: { columns: { supply: "供货", mode: "模式", created: "创建时间", orders: "订单", action: "操作" }, modeUnknown: "未指定", prepareDelivery: "检查供货 {name} 的交付", deliver: "交付", open: "打开", openLabel: "打开供货 {name}" },
  loading: "正在加载打包队列",
  error: { title: "无法打开打包队列", description: "本地数据未被修改，请重试。", retry: "重试" },
  empty: { filtered: "未找到结果", new: "暂无新订单", preparation: "暂无打包中的供货", dispatch: "暂无待发货供货", filteredDescription: "请修改搜索内容或类别。", description: "请从主页刷新 Wildberries 数据。" },
  shipmentPrefix: "供货",
  mutation: getPackingMutationCopy("zh"),
  supply: getSupplyCopy("zh"),
};

const copies: Record<Language, PackingCopy> = { ru, en, vi, zh };

export function getPackingCopy(language: Language): PackingCopy {
  return copies[language];
}

export const defaultPackingCopy = ru;
