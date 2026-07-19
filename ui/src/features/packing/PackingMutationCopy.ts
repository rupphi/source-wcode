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
