import type { Language } from "../../i18n";

const ru = {
  tokenConnected: "Токен подключён",
  tokenMissing: "Токен не настроен",
  syncCancel: "Отменить синхронизацию",
  syncStart: "Синхронизировать с Wildberries",
  loadError: "Не удалось загрузить показатели. Повторите попытку.",
  metricsAria: "Ключевые показатели",
  products: "Товаров в каталоге",
  orders: "Новых заказов",
  supplies: "Открытых поставок",
  loading: "Загрузка",
  sync: {
    stopping: "Останавливаем после безопасного завершения текущего шага…",
    running: "Получаем актуальные данные Wildberries в фоновом режиме…",
    completed: "Синхронизация завершена",
    completedDetail: "Обновлено: товаров {products}, поставок {supplies}.",
    cancelled: "Синхронизация остановлена. Уже сохранённые страницы данных оставлены без изменений.",
    tokenInvalid: "Токен Wildberries недействителен или не имеет нужных прав. Проверьте настройки магазина.",
    rateLimited: "Wildberries временно ограничил запросы. Повторите синхронизацию позже.",
    retryable: "Wildberries не завершил синхронизацию. Локальные данные сохранены — можно повторить попытку.",
    blocked: "Синхронизацию нельзя запустить с текущими настройками магазина.",
  },
};

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type DashboardCopy = DeepString<typeof ru>;

const en: DashboardCopy = {
  tokenConnected: "Token connected", tokenMissing: "Token not configured",
  syncCancel: "Cancel synchronization", syncStart: "Synchronize with Wildberries",
  loadError: "Could not load the metrics. Try again.", metricsAria: "Key metrics",
  products: "Products in catalog", orders: "New orders", supplies: "Open supplies", loading: "Loading",
  sync: {
    stopping: "Stopping after the current step finishes safely…",
    running: "Loading current Wildberries data in the background…",
    completed: "Synchronization completed",
    completedDetail: "Updated products: {products} · updated supplies: {supplies}.",
    cancelled: "Synchronization stopped. Data pages already saved were left unchanged.",
    tokenInvalid: "The Wildberries token is invalid or lacks the required permissions. Check the shop settings.",
    rateLimited: "Wildberries temporarily limited requests. Try synchronizing again later.",
    retryable: "Wildberries did not finish synchronization. Local data was preserved, so you can try again.",
    blocked: "Synchronization cannot start with the current shop settings.",
  },
};

const vi: DashboardCopy = {
  tokenConnected: "Token đã kết nối", tokenMissing: "Chưa cấu hình token",
  syncCancel: "Hủy đồng bộ", syncStart: "Đồng bộ với Wildberries",
  loadError: "Không thể tải số liệu. Hãy thử lại.", metricsAria: "Số liệu chính",
  products: "Sản phẩm trong danh mục", orders: "Đơn hàng mới", supplies: "Lô hàng đang mở", loading: "Đang tải",
  sync: {
    stopping: "Đang dừng sau khi bước hiện tại kết thúc an toàn…",
    running: "Đang tải dữ liệu Wildberries mới nhất trong nền…",
    completed: "Đồng bộ hoàn tất",
    completedDetail: "Đã cập nhật: {products} sản phẩm, {supplies} lô hàng.",
    cancelled: "Đã dừng đồng bộ. Các trang dữ liệu đã lưu được giữ nguyên.",
    tokenInvalid: "Token Wildberries không hợp lệ hoặc thiếu quyền cần thiết. Hãy kiểm tra cài đặt cửa hàng.",
    rateLimited: "Wildberries đang tạm giới hạn yêu cầu. Hãy đồng bộ lại sau.",
    retryable: "Wildberries chưa hoàn tất đồng bộ. Dữ liệu cục bộ đã được giữ lại để bạn có thể thử lại.",
    blocked: "Không thể bắt đầu đồng bộ với cài đặt cửa hàng hiện tại.",
  },
};

const zh: DashboardCopy = {
  tokenConnected: "令牌已连接", tokenMissing: "令牌未配置",
  syncCancel: "取消同步", syncStart: "与 Wildberries 同步",
  loadError: "无法加载指标，请重试。", metricsAria: "关键指标",
  products: "目录商品", orders: "新订单", supplies: "进行中的供货", loading: "正在加载",
  sync: {
    stopping: "当前步骤安全结束后将停止…",
    running: "正在后台获取最新的 Wildberries 数据…",
    completed: "同步已完成",
    completedDetail: "已更新：{products} 个商品，{supplies} 个供货。",
    cancelled: "同步已停止，已保存的数据页保持不变。",
    tokenInvalid: "Wildberries 令牌无效或缺少所需权限，请检查店铺设置。",
    rateLimited: "Wildberries 暂时限制了请求，请稍后重试同步。",
    retryable: "Wildberries 未完成同步。本地数据已保留，可以重试。",
    blocked: "当前店铺设置无法启动同步。",
  },
};

const copies: Record<Language, DashboardCopy> = { ru, en, vi, zh };

export function getDashboardCopy(language: Language): DashboardCopy {
  return copies[language];
}
