export type Language = "ru" | "en" | "zh" | "vi";
export type ThemeMode = "dark" | "light" | "system";

const ru = {
  common: { retry: "Повторить", cancel: "Отмена" },
  shell: {
    sellerDesktop: "Рабочее место продавца",
    navigationLabel: "Основная навигация",
    work: "Работа",
    preview: "jDesk preview · локальный режим",
    workspaceEyebrow: "Рабочее пространство",
    workspaceTitle: "Управление продажами",
    help: "Помощь",
    settings: "Настройки",
    localData: "Локальные данные WCode",
    localLibrary: "Локальная библиотека",
    shopIndependent: "Не зависит от выбранного магазина",
    nav: {
      dashboard: "Главная",
      packing: "Упаковка FBS",
      supplies: "Поставки FBS",
      templates: "Дизайн этикеток",
      fbo: "Поставки FBO",
      kizMapping: "GTIN и KIZ",
      znack: "Znack Automation",
      history: "История печати",
    },
    pages: {
      dashboard: { title: "Обзор магазина", description: "Быстрый срез каталога, новых заказов и активных поставок без раскрытия API-токена." },
      packing: { title: "Упаковка FBS", description: "Рабочая очередь новых заказов, поставок на сборке и готовых отгрузок из локальных данных WCode." },
      supplies: { title: "Поставки FBS", description: "Локальный реестр поставок Wildberries с быстрым поиском, статусами и точной пагинацией." },
      history: { title: "История печати", description: "Журнал локальных PDF-заданий с безопасным статусом, шаблоном и точным количеством этикеток." },
      fbo: { title: "Печать FBO", description: "Локальный каталог SKU с пакетной и быстрой печатью парных товарных этикеток и контролируемым списанием KIZ." },
      kizMapping: { title: "Соответствия GTIN", description: "Локальный каталог GTIN, остатков KIZ и точных правил соответствия категориям и значениям пола Wildberries." },
      znack: { title: "Znack Automation", description: "Настройки OMS, каталог GTIN, идемпотентные покупки КИЗ, ввод в оборот и безопасный журнал операций." },
      templates: { title: "Дизайн этикеток", description: "Локальные шаблоны FBS и FBO с точной геометрией 58 × 40 мм и визуальной проверкой каждого элемента." },
    },
  },
  shop: { label: "Магазин", empty: "Нет магазинов" },
  center: {
    loadingTitle: "Загружаем рабочее пространство",
    loadingDescription: "Проверяем локальную базу и список магазинов.",
    errorTitle: "Не удалось открыть рабочее пространство",
    errorDescription: "Данные не изменены. Проверьте подключение и повторите.",
  },
  settings: {
    title: "Настройки приложения",
    close: "Закрыть настройки",
    interfaceTitle: "Интерфейс",
    interfaceDescription: "Язык приложения и цветовая схема сохраняются для JavaFX и jDesk.",
    language: "Язык",
    theme: "Тема",
    dark: "Тёмная",
    light: "Светлая",
    system: "Системная",
    preferenceError: "Не удалось сохранить настройки интерфейса.",
    license: {
      title: "Лицензия WCode",
      description: "Подписка устройства и безопасный офлайн-доступ к покупке KIZ.",
      loading: "Загрузка лицензии",
      loadError: "Не удалось загрузить состояние лицензии",
      keyLabel: "Лицензионный ключ",
      keyHint: "Сохранённый ключ никогда не возвращается в WebView. Введите новый ключ только для активации или замены.",
      activate: "Активировать",
      activateLabel: "Активировать лицензию",
      check: "Проверить",
      checkLabel: "Проверить лицензию",
      unlink: "Отвязать это устройство",
      confirmTitle: "Отвязать лицензию от этого устройства?",
      confirmDescription: "Локальный ключ и подписанный файл будут удалены сразу. Если сервер недоступен, занятый слот может потребоваться освободить позже через поддержку.",
      confirm: "Подтвердить отвязку устройства",
      errors: {
        invalidLicense: "Лицензионный ключ недействителен или отозван.",
        deviceLimit: "Достигнут лимит устройств для этой лицензии.",
        network: "Не удалось связаться с сервером лицензий. Проверьте подключение к интернету.",
        unavailable: "Не удалось выполнить операцию. Повторите позже или обратитесь в поддержку.",
        generic: "Не удалось выполнить операцию с лицензией.",
      },
      status: {
        notActivatedTitle: "Лицензия не активирована",
        notActivatedDescription: "Введите ключ, полученный после оплаты подписки.",
        validTitle: "Лицензия активна",
        validUntil: "Действует до {date}",
        verified: "Подписка подтверждена сервером.",
        offlineTitle: "Офлайн-режим",
        offlineUntil: "Можно продолжать работу офлайн до {date}.",
        reconnect: "Подключитесь к интернету для повторной проверки.",
        expiredTitle: "Срок лицензии истёк",
        expiredOn: "Лицензия закончилась {date}.",
        renew: "Продлите подписку и повторите проверку.",
        invalidTitle: "Лицензия недействительна",
        invalidDescription: "Ключ отозван, повреждён или не прошёл проверку подписи.",
        deviceLimitTitle: "Достигнут лимит устройств",
        deviceLimitDescription: "Освободите слот на другом устройстве или обратитесь в поддержку.",
        clockTitle: "Проверьте системные часы",
        clockDescription: "Дата или время были переведены назад относительно подписанного файла.",
        networkTitle: "Нет подтверждения лицензии",
        networkDescription: "Сервер недоступен, а безопасный офлайн-период закончился.",
        daysRemaining: "{days} дней осталось",
      },
    },
  },
} as const;

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type AppCopy = DeepString<typeof ru>;

const en: AppCopy = {
  common: { retry: "Retry", cancel: "Cancel" },
  shell: {
    sellerDesktop: "Seller desktop", navigationLabel: "Primary navigation", work: "Work",
    preview: "jDesk preview · local mode", workspaceEyebrow: "Workspace",
    workspaceTitle: "Sales workspace", help: "Help", settings: "Settings",
    localData: "Local WCode data", localLibrary: "Local library",
    shopIndependent: "Independent of the selected shop",
    nav: { dashboard: "Home", packing: "FBS packing", supplies: "FBS supplies", templates: "Label designer", fbo: "FBO supplies", kizMapping: "GTIN and KIZ", znack: "Znack Automation", history: "Print history" },
    pages: {
      dashboard: { title: "Shop overview", description: "A quick view of the catalog, new orders, and active supplies without exposing the API token." },
      packing: { title: "FBS packing", description: "A local work queue for new orders, supplies being assembled, and shipments ready for dispatch." },
      supplies: { title: "FBS supplies", description: "A local Wildberries supply register with fast search, statuses, and exact pagination." },
      history: { title: "Print history", description: "A local PDF job journal with safe status, template, and exact label counts." },
      fbo: { title: "FBO printing", description: "A local SKU catalog for batch and quick paired-label printing with controlled KIZ consumption." },
      kizMapping: { title: "GTIN mappings", description: "A local GTIN and KIZ inventory with exact Wildberries category and gender mapping rules." },
      znack: { title: "Znack Automation", description: "OMS settings, GTIN catalog, idempotent KIZ purchases, introduction, and a safe operation journal." },
      templates: { title: "Label designer", description: "Local FBS and FBO templates with exact 58 × 40 mm geometry and visual element verification." },
    },
  },
  shop: { label: "Shop", empty: "No shops" },
  center: { loadingTitle: "Loading workspace", loadingDescription: "Checking the local database and shop list.", errorTitle: "Could not open the workspace", errorDescription: "No data was changed. Check the connection and try again." },
  settings: {
    title: "Application settings", close: "Close settings", interfaceTitle: "Interface",
    interfaceDescription: "Application language and color scheme are shared by JavaFX and jDesk.",
    language: "Language", theme: "Theme", dark: "Dark", light: "Light", system: "System",
    preferenceError: "Could not save interface settings.",
    license: {
      title: "WCode license", description: "Device subscription and safe offline access to KIZ purchases.",
      loading: "Loading license", loadError: "Could not load license status", keyLabel: "License key",
      keyHint: "The stored key is never returned to the WebView. Enter a new key only to activate or replace it.",
      activate: "Activate", activateLabel: "Activate license", check: "Check", checkLabel: "Check license",
      unlink: "Unlink this device", confirmTitle: "Unlink the license from this device?",
      confirmDescription: "The local key and signed file will be removed immediately. If the server is unavailable, support may need to release the occupied slot later.",
      confirm: "Confirm unlink",
      errors: { invalidLicense: "The license key is invalid or revoked.", deviceLimit: "This license has reached its device limit.", network: "Could not reach the license server. Check your internet connection.", unavailable: "The operation could not be completed. Try again later or contact support.", generic: "The license operation could not be completed." },
      status: {
        notActivatedTitle: "License not activated", notActivatedDescription: "Enter the key received after paying for the subscription.",
        validTitle: "License active", validUntil: "Valid until {date}", verified: "The subscription was verified by the server.",
        offlineTitle: "Offline mode", offlineUntil: "You can continue offline until {date}.", reconnect: "Connect to the internet to verify again.",
        expiredTitle: "License expired", expiredOn: "The license expired on {date}.", renew: "Renew the subscription and check again.",
        invalidTitle: "License invalid", invalidDescription: "The key was revoked, damaged, or failed signature verification.",
        deviceLimitTitle: "Device limit reached", deviceLimitDescription: "Release a slot on another device or contact support.",
        clockTitle: "Check the system clock", clockDescription: "The date or time was moved backwards relative to the signed file.",
        networkTitle: "License not confirmed", networkDescription: "The server is unavailable and the safe offline period has ended.",
        daysRemaining: "Days remaining: {days}",
      },
    },
  },
};

const vi: AppCopy = {
  common: { retry: "Thử lại", cancel: "Hủy" },
  shell: {
    sellerDesktop: "Bàn làm việc người bán", navigationLabel: "Điều hướng chính", work: "Công việc",
    preview: "Bản xem trước jDesk · chế độ cục bộ", workspaceEyebrow: "Không gian làm việc",
    workspaceTitle: "Quản lý bán hàng", help: "Trợ giúp", settings: "Cài đặt",
    localData: "Dữ liệu WCode cục bộ", localLibrary: "Thư viện cục bộ",
    shopIndependent: "Không phụ thuộc cửa hàng đang chọn",
    nav: { dashboard: "Trang chủ", packing: "Đóng gói FBS", supplies: "Lô hàng FBS", templates: "Thiết kế nhãn", fbo: "Lô hàng FBO", kizMapping: "GTIN và KIZ", znack: "Tự động hóa Znack", history: "Lịch sử in" },
    pages: {
      dashboard: { title: "Tổng quan cửa hàng", description: "Tổng hợp nhanh danh mục, đơn mới và lô hàng đang hoạt động mà không làm lộ API token." },
      packing: { title: "Đóng gói FBS", description: "Hàng đợi cục bộ cho đơn mới, lô hàng đang đóng gói và lô sẵn sàng bàn giao." },
      supplies: { title: "Lô hàng FBS", description: "Danh sách lô Wildberries cục bộ với tìm kiếm nhanh, trạng thái và phân trang chính xác." },
      history: { title: "Lịch sử in", description: "Nhật ký tác vụ PDF cục bộ với trạng thái an toàn, mẫu và số lượng nhãn chính xác." },
      fbo: { title: "In FBO", description: "Danh mục SKU cục bộ để in nhanh hoặc hàng loạt cặp nhãn với kiểm soát sử dụng KIZ." },
      kizMapping: { title: "Ánh xạ GTIN", description: "Kho GTIN/KIZ cục bộ cùng quy tắc ánh xạ chính xác danh mục và giới tính Wildberries." },
      znack: { title: "Tự động hóa Znack", description: "Cài đặt OMS, danh mục GTIN, mua KIZ idempotent, đưa vào lưu thông và nhật ký an toàn." },
      templates: { title: "Thiết kế nhãn", description: "Mẫu FBS/FBO cục bộ với hình học chính xác 58 × 40 mm và kiểm tra trực quan từng phần tử." },
    },
  },
  shop: { label: "Cửa hàng", empty: "Chưa có cửa hàng" },
  center: { loadingTitle: "Đang tải không gian làm việc", loadingDescription: "Đang kiểm tra cơ sở dữ liệu cục bộ và danh sách cửa hàng.", errorTitle: "Không thể mở không gian làm việc", errorDescription: "Dữ liệu không bị thay đổi. Hãy kiểm tra kết nối và thử lại." },
  settings: {
    title: "Cài đặt ứng dụng", close: "Đóng cài đặt", interfaceTitle: "Giao diện",
    interfaceDescription: "Ngôn ngữ và bảng màu của ứng dụng được dùng chung cho JavaFX và jDesk.",
    language: "Ngôn ngữ", theme: "Giao diện", dark: "Tối", light: "Sáng", system: "Theo hệ thống",
    preferenceError: "Không thể lưu cài đặt giao diện.",
    license: {
      title: "Bản quyền WCode", description: "Gói thuê bao thiết bị và quyền mua KIZ ngoại tuyến an toàn.",
      loading: "Đang tải bản quyền", loadError: "Không thể tải trạng thái bản quyền", keyLabel: "Khóa bản quyền",
      keyHint: "Khóa đã lưu không bao giờ được trả về WebView. Chỉ nhập khóa mới để kích hoạt hoặc thay thế.",
      activate: "Kích hoạt", activateLabel: "Kích hoạt bản quyền", check: "Kiểm tra", checkLabel: "Kiểm tra bản quyền",
      unlink: "Gỡ liên kết thiết bị", confirmTitle: "Gỡ bản quyền khỏi thiết bị này?",
      confirmDescription: "Khóa cục bộ và tệp đã ký sẽ bị xóa ngay. Nếu máy chủ không khả dụng, bộ phận hỗ trợ có thể phải giải phóng slot sau.",
      confirm: "Xác nhận gỡ liên kết",
      errors: { invalidLicense: "Khóa bản quyền không hợp lệ hoặc đã bị thu hồi.", deviceLimit: "Bản quyền đã đạt giới hạn thiết bị.", network: "Không thể kết nối máy chủ bản quyền. Hãy kiểm tra internet.", unavailable: "Không thể hoàn tất thao tác. Hãy thử lại sau hoặc liên hệ hỗ trợ.", generic: "Không thể hoàn tất thao tác bản quyền." },
      status: {
        notActivatedTitle: "Chưa kích hoạt bản quyền", notActivatedDescription: "Nhập khóa nhận được sau khi thanh toán gói thuê bao.",
        validTitle: "Bản quyền đang hoạt động", validUntil: "Có hiệu lực đến {date}", verified: "Gói thuê bao đã được máy chủ xác minh.",
        offlineTitle: "Chế độ ngoại tuyến", offlineUntil: "Có thể tiếp tục ngoại tuyến đến {date}.", reconnect: "Kết nối internet để xác minh lại.",
        expiredTitle: "Bản quyền đã hết hạn", expiredOn: "Bản quyền hết hạn ngày {date}.", renew: "Gia hạn gói thuê bao rồi kiểm tra lại.",
        invalidTitle: "Bản quyền không hợp lệ", invalidDescription: "Khóa đã bị thu hồi, hỏng hoặc không qua xác minh chữ ký.",
        deviceLimitTitle: "Đã đạt giới hạn thiết bị", deviceLimitDescription: "Giải phóng một slot trên thiết bị khác hoặc liên hệ hỗ trợ.",
        clockTitle: "Kiểm tra đồng hồ hệ thống", clockDescription: "Ngày hoặc giờ đã bị lùi so với tệp đã ký.",
        networkTitle: "Chưa xác nhận bản quyền", networkDescription: "Máy chủ không khả dụng và thời gian ngoại tuyến an toàn đã kết thúc.",
        daysRemaining: "Số ngày còn lại: {days}",
      },
    },
  },
};

const zh: AppCopy = {
  common: { retry: "重试", cancel: "取消" },
  shell: {
    sellerDesktop: "卖家工作台", navigationLabel: "主导航", work: "工作",
    preview: "jDesk 预览 · 本地模式", workspaceEyebrow: "工作区", workspaceTitle: "销售管理",
    help: "帮助", settings: "设置", localData: "WCode 本地数据", localLibrary: "本地模板库",
    shopIndependent: "不依赖当前所选店铺",
    nav: { dashboard: "首页", packing: "FBS 打包", supplies: "FBS 供货", templates: "标签设计", fbo: "FBO 供货", kizMapping: "GTIN 与 KIZ", znack: "Znack 自动化", history: "打印历史" },
    pages: {
      dashboard: { title: "店铺概览", description: "快速查看商品、新订单和进行中的供货，同时不暴露 API 令牌。" },
      packing: { title: "FBS 打包", description: "本地显示新订单、组装中的供货和待发运任务。" },
      supplies: { title: "FBS 供货", description: "本地 Wildberries 供货列表，支持快速搜索、状态和精确分页。" },
      history: { title: "打印历史", description: "本地 PDF 任务日志，包含安全状态、模板和准确标签数量。" },
      fbo: { title: "FBO 打印", description: "本地 SKU 目录，支持批量或快速打印成对标签并受控使用 KIZ。" },
      kizMapping: { title: "GTIN 映射", description: "本地 GTIN/KIZ 库以及精确的 Wildberries 类目和性别映射规则。" },
      znack: { title: "Znack 自动化", description: "OMS 设置、GTIN 目录、幂等 KIZ 购买、投入流通和安全操作日志。" },
      templates: { title: "标签设计", description: "本地 FBS/FBO 模板，采用精确的 58 × 40 mm 几何尺寸并可视化检查元素。" },
    },
  },
  shop: { label: "店铺", empty: "暂无店铺" },
  center: { loadingTitle: "正在加载工作区", loadingDescription: "正在检查本地数据库和店铺列表。", errorTitle: "无法打开工作区", errorDescription: "数据未被更改。请检查连接后重试。" },
  settings: {
    title: "应用设置", close: "关闭设置", interfaceTitle: "界面",
    interfaceDescription: "应用语言和配色方案由 JavaFX 与 jDesk 共用。", language: "语言", theme: "主题",
    dark: "深色", light: "浅色", system: "跟随系统", preferenceError: "无法保存界面设置。",
    license: {
      title: "WCode 许可证", description: "设备订阅以及安全的离线 KIZ 购买权限。", loading: "正在加载许可证",
      loadError: "无法加载许可证状态", keyLabel: "许可证密钥",
      keyHint: "已保存的密钥绝不会返回 WebView。仅在激活或替换时输入新密钥。",
      activate: "激活", activateLabel: "激活许可证", check: "检查", checkLabel: "检查许可证",
      unlink: "解绑此设备", confirmTitle: "从此设备解绑许可证？",
      confirmDescription: "本地密钥和签名文件将立即删除。如果服务器不可用，之后可能需要联系支持释放占用的设备名额。",
      confirm: "确认解绑",
      errors: { invalidLicense: "许可证密钥无效或已被撤销。", deviceLimit: "此许可证已达到设备上限。", network: "无法连接许可证服务器。请检查网络。", unavailable: "无法完成操作。请稍后重试或联系支持。", generic: "无法完成许可证操作。" },
      status: {
        notActivatedTitle: "许可证未激活", notActivatedDescription: "请输入订阅付款后收到的密钥。",
        validTitle: "许可证有效", validUntil: "有效期至 {date}", verified: "订阅已由服务器验证。",
        offlineTitle: "离线模式", offlineUntil: "可离线使用至 {date}。", reconnect: "请连接网络重新验证。",
        expiredTitle: "许可证已过期", expiredOn: "许可证已于 {date} 过期。", renew: "请续订后再次检查。",
        invalidTitle: "许可证无效", invalidDescription: "密钥已撤销、损坏或签名验证失败。",
        deviceLimitTitle: "已达到设备上限", deviceLimitDescription: "请在其他设备释放名额或联系支持。",
        clockTitle: "请检查系统时间", clockDescription: "日期或时间相对于签名文件被向后调整。",
        networkTitle: "许可证未确认", networkDescription: "服务器不可用，且安全离线期限已结束。",
        daysRemaining: "剩余天数：{days}",
      },
    },
  },
};

const copies: Record<Language, AppCopy> = { ru, en, vi, zh };

export function getCopy(language: Language): AppCopy {
  return copies[language];
}

export function interpolate(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{([a-z]+)\}/gi, (match, key: string) => (
    Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match
  ));
}

export function isLanguage(value: unknown): value is Language {
  return value === "ru" || value === "en" || value === "zh" || value === "vi";
}

export function isTheme(value: unknown): value is ThemeMode {
  return value === "dark" || value === "light" || value === "system";
}
