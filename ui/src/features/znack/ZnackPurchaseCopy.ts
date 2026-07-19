export interface PurchaseDialogCopy {
  invalidQuantity: string;
  prepareError: string;
  startError: string;
  confirmTitle: string;
  prepareTitle: string;
  unnamedProduct: string;
  close: string;
  quantity: string;
  autoIntroductionTitle: string;
  autoIntroductionDescription: string;
  localOnly: string;
  quantityLabel: string;
  certificateRequired: string;
  paidWarning: string;
  cancel: string;
  confirmLabel: string;
  starting: string;
  confirm: string;
  prepareLabel: string;
  checking: string;
  prepare: string;
}

export const defaultPurchaseDialogCopy: PurchaseDialogCopy = {
  invalidQuantity: "Укажите целое количество от 1 до 10 000.", prepareError: "Не удалось подготовить покупку. Обновите данные и повторите.",
  startError: "Покупка не запущена. Проверьте состояние заказа перед повтором.", confirmTitle: "Подтверждение покупки КИЗ", prepareTitle: "Подготовить покупку КИЗ",
  unnamedProduct: "Товар без названия", close: "Закрыть покупку КИЗ", quantity: "Количество",
  autoIntroductionTitle: "Автоматический ввод в оборот включён", autoIntroductionDescription: "После загрузки кодов WCode отправит документ только при готовых данных и документах.",
  localOnly: "Коды будут загружены локально без автоматического ввода в оборот.", quantityLabel: "Количество КИЗ",
  certificateRequired: "Сохраните настройки и проверьте сертификат CryptoPro.", paidWarning: "Покупка может создать платный заказ Znack. UUID подтверждения сохраняется до первого сетевого вызова и блокирует повторное списание.",
  cancel: "Отмена", confirmLabel: "Подтвердить покупку КИЗ", starting: "Запуск…", confirm: "Подтвердить покупку КИЗ",
  prepareLabel: "Подготовить покупку", checking: "Проверка…", prepare: "Подготовить покупку",
};
