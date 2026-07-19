import type { Language } from "../../i18n";

export interface PackingMutationCopy {
  changedAfterConfirmation: string;
  close: string;
  supplyName: string;
  operationError: string;
  cancel: string;
  checking: string;
  check: string;
  executing: string;
  searchSupply: string;
  searchPlaceholder: string;
  search: string;
  loadingOpenSupplies: string;
  loadOpenSuppliesError: string;
  noOpenSupplies: string;
  orderCount: string;
  kizOrderCount: string;
  labelsMissing: string;
  kizMissing: string;
  supplyNotReady: string;
  titles: { create: string; add: string; blocked: string; confirmCreate: string; confirmAdd: string; confirmDeliver: string };
  actions: { create: string; add: string; deliver: string };
}

export const defaultPackingMutationCopy: PackingMutationCopy = {
  changedAfterConfirmation: "Wildberries изменится только после финального подтверждения.", close: "Закрыть", supplyName: "Название поставки",
  operationError: "Операция не выполнена. Обновите данные и повторите проверку.", cancel: "Отмена", checking: "Проверка…", check: "Проверить", executing: "Выполнение…",
  searchSupply: "Поиск поставки", searchPlaceholder: "ID или название", search: "Найти", loadingOpenSupplies: "Загрузка открытых поставок…",
  loadOpenSuppliesError: "Не удалось загрузить открытые поставки.", noOpenSupplies: "Открытых поставок нет.",
  orderCount: "Заказов: {count}", kizOrderCount: "{count} заказ требует KIZ",
  labelsMissing: "Сначала распечатайте этикетки поставки", kizMissing: "Не все обязательные KIZ прикреплены", supplyNotReady: "Поставка пуста, закрыта или уже передана",
  titles: { create: "Новая поставка", add: "Выберите поставку", blocked: "Поставка не готова к передаче", confirmCreate: "Подтвердить создание поставки", confirmAdd: "Подтвердить добавление заказов", confirmDeliver: "Подтвердить передачу поставки" },
  actions: { create: "Создать в Wildberries", add: "Добавить заказы", deliver: "Передать в доставку" },
};

const en: PackingMutationCopy = {
  changedAfterConfirmation: "Wildberries changes only after final confirmation.", close: "Close", supplyName: "Supply name",
  operationError: "The operation failed. Refresh the data and check again.", cancel: "Cancel", checking: "Checking…", check: "Check", executing: "Working…",
  searchSupply: "Search supplies", searchPlaceholder: "ID or name", search: "Search", loadingOpenSupplies: "Loading open supplies…",
  loadOpenSuppliesError: "Could not load open supplies.", noOpenSupplies: "No open supplies.", orderCount: "Orders: {count}", kizOrderCount: "Orders requiring KIZ: {count}",
  labelsMissing: "Print the supply labels first", kizMissing: "Some required KIZ codes are not attached", supplyNotReady: "The supply is empty, closed, or already delivered",
  titles: { create: "New supply", add: "Choose a supply", blocked: "Supply is not ready for delivery", confirmCreate: "Confirm supply creation", confirmAdd: "Confirm adding orders", confirmDeliver: "Confirm supply delivery" },
  actions: { create: "Create in Wildberries", add: "Add orders", deliver: "Deliver supply" },
};

const vi: PackingMutationCopy = {
  changedAfterConfirmation: "Wildberries chỉ thay đổi sau lần xác nhận cuối.", close: "Đóng", supplyName: "Tên lô giao hàng",
  operationError: "Thao tác không thành công. Hãy cập nhật dữ liệu và kiểm tra lại.", cancel: "Hủy", checking: "Đang kiểm tra…", check: "Kiểm tra", executing: "Đang thực hiện…",
  searchSupply: "Tìm lô giao hàng", searchPlaceholder: "ID hoặc tên", search: "Tìm", loadingOpenSupplies: "Đang tải các lô đang mở…",
  loadOpenSuppliesError: "Không thể tải các lô đang mở.", noOpenSupplies: "Không có lô đang mở.", orderCount: "Đơn hàng: {count}", kizOrderCount: "{count} đơn cần KIZ",
  labelsMissing: "Hãy in nhãn của lô trước", kizMissing: "Một số KIZ bắt buộc chưa được đính kèm", supplyNotReady: "Lô trống, đã đóng hoặc đã giao",
  titles: { create: "Lô giao hàng mới", add: "Chọn lô giao hàng", blocked: "Lô chưa sẵn sàng để giao", confirmCreate: "Xác nhận tạo lô", confirmAdd: "Xác nhận thêm đơn", confirmDeliver: "Xác nhận giao lô" },
  actions: { create: "Tạo trên Wildberries", add: "Thêm đơn hàng", deliver: "Giao lô hàng" },
};

const zh: PackingMutationCopy = {
  changedAfterConfirmation: "只有最终确认后才会更改 Wildberries。", close: "关闭", supplyName: "供货名称",
  operationError: "操作失败。请刷新数据后重新检查。", cancel: "取消", checking: "正在检查…", check: "检查", executing: "正在执行…",
  searchSupply: "搜索供货", searchPlaceholder: "ID 或名称", search: "搜索", loadingOpenSupplies: "正在加载进行中的供货…",
  loadOpenSuppliesError: "无法加载进行中的供货。", noOpenSupplies: "没有进行中的供货。", orderCount: "订单：{count}", kizOrderCount: "{count} 个订单需要 KIZ",
  labelsMissing: "请先打印供货标签", kizMissing: "部分必需的 KIZ 尚未附加", supplyNotReady: "供货为空、已关闭或已经交付",
  titles: { create: "新建供货", add: "选择供货", blocked: "供货尚未准备好交付", confirmCreate: "确认创建供货", confirmAdd: "确认添加订单", confirmDeliver: "确认交付供货" },
  actions: { create: "在 Wildberries 创建", add: "添加订单", deliver: "交付供货" },
};

const copies: Record<Language, PackingMutationCopy> = { ru: defaultPackingMutationCopy, en, vi, zh };

export function getPackingMutationCopy(language: Language): PackingMutationCopy {
  return copies[language];
}
