import type { Language } from "../../i18n";

const ru = {
  mode: { aria: "Тип поставки шаблона", localCatalog: "Типизированный каталог · хранится локально" },
  toolbar: {
    aria: "Управление шаблоном", create: "Создать шаблон", duplicate: "Дублировать шаблон",
    rename: "Переименовать шаблон", makeDefault: "Сделать шаблоном по умолчанию",
    reset: "Сбросить шаблон", delete: "Удалить шаблон", discard: "Отменить изменения",
    save: "Сохранить шаблон",
  },
  notices: {
    dirtyGuard: "Сначала сохраните или отмените изменения.", mutationError: "Операция не выполнена. Локальные данные не изменены.",
    addError: "Элемент не добавлен. Попробуйте ещё раз.", elementLimit: "Достигнут лимит элементов в шаблоне.",
    requiredElement: "Этот обязательный элемент нельзя удалить.", saved: "Шаблон сохранён",
    defaultSet: "Шаблон выбран по умолчанию", created: "Шаблон создан", duplicated: "Копия шаблона создана",
    renamed: "Шаблон переименован", deleted: "Шаблон удалён", reset: "Шаблон сброшен",
  },
  load: { aria: "Загрузка шаблонов", errorTitle: "Не удалось загрузить шаблоны", errorDetail: "Локальная библиотека не изменена. Повторите запрос.", retry: "Повторить" },
  dirty: "Есть несохранённые изменения",
  canvasActions: { copy: "Копировать элемент", paste: "Вставить элемент", delete: "Удалить элемент", snap: "Шаг 1 мм", copySuffix: "копия" },
  catalog: {
    title: "Шаблоны", templateAria: "Шаблон {name}", elements: "Элементов: {count}", default: "По умолчанию",
    newElement: "Новый элемент", addElement: "Добавить элемент", layers: "Слои",
    selectLayer: "Выбрать слой {name}", visible: "Виден", hidden: "Скрыт",
  },
  inspector: {
    empty: "Выберите слой на макете, чтобы изменить его параметры.", title: "Параметры элемента",
    name: "Название", prefix: "Префикс", text: "Текст", visible: "Виден", geometry: "Геометрия",
    x: "X, мм", y: "Y, мм", width: "Ширина, мм", height: "Высота, мм", font: "Шрифт, pt",
    alignment: "Выравнивание", left: "Слева", center: "По центру", right: "Справа",
    bold: "Жирный", barcodeDigits: "Цифры штрихкода",
    geometryHint: "Координаты заданы в миллиметрах и ограничены рабочей областью.",
  },
  dialogs: {
    createTitle: "Создать шаблон", createSubmit: "Создать", duplicateTitle: "Дублировать шаблон", duplicateSubmit: "Дублировать",
    renameTitle: "Переименовать шаблон", renameSubmit: "Переименовать", name: "Название шаблона",
    resetTitle: "Сбросить шаблон?", deleteTitle: "Удалить шаблон?",
    resetDetail: "Макет «{name}» вернётся к системной раскладке.", deleteDetail: "Шаблон «{name}» будет удалён без возможности восстановления.",
    reset: "Сбросить", delete: "Удалить", close: "Закрыть", cancel: "Отмена",
  },
  empty: { title: "Шаблонов пока нет", detail: "Создайте первый макет 58 × 40 мм для этого режима." },
  canvas: {
    hint: "Перетаскивайте элементы; маркер меняет размер", unit: "мм", previewAria: "Предпросмотр шаблона {name}",
    selectElement: "Выбрать элемент {name}", sample: { color: "Графит", name: "Базовая футболка", subject: "Футболки" },
  },
};

type DeepString<T> = { [K in keyof T]: T[K] extends string ? string : DeepString<T[K]> };
export type TemplateDesignerCopy = DeepString<typeof ru>;
export type TemplateNoticeKey = keyof TemplateDesignerCopy["notices"];

const en: TemplateDesignerCopy = {
  mode: { aria: "Template supply type", localCatalog: "Typed catalog · stored locally" },
  toolbar: {
    aria: "Template controls", create: "Create template", duplicate: "Duplicate template", rename: "Rename template",
    makeDefault: "Make default template", reset: "Reset template", delete: "Delete template",
    discard: "Discard changes", save: "Save template",
  },
  notices: {
    dirtyGuard: "Save or discard your changes first.", mutationError: "The operation failed. Local data was not changed.",
    addError: "The element was not added. Try again.", elementLimit: "This template has reached its element limit.",
    requiredElement: "This required element cannot be deleted.", saved: "Template saved", defaultSet: "Default template selected",
    created: "Template created", duplicated: "Template copy created", renamed: "Template renamed", deleted: "Template deleted", reset: "Template reset",
  },
  load: { aria: "Loading templates", errorTitle: "Could not load templates", errorDetail: "The local library was not changed. Retry the request.", retry: "Retry" },
  dirty: "Unsaved changes",
  canvasActions: { copy: "Copy element", paste: "Paste element", delete: "Delete element", snap: "1 mm step", copySuffix: "copy" },
  catalog: {
    title: "Templates", templateAria: "Template {name}", elements: "Elements: {count}", default: "Default",
    newElement: "New element", addElement: "Add element", layers: "Layers", selectLayer: "Select layer {name}", visible: "Visible", hidden: "Hidden",
  },
  inspector: {
    empty: "Select a layer on the layout to edit its properties.", title: "Element properties", name: "Name", prefix: "Prefix", text: "Text",
    visible: "Visible", geometry: "Geometry", x: "X, mm", y: "Y, mm", width: "Width, mm", height: "Height, mm", font: "Font, pt",
    alignment: "Alignment", left: "Left", center: "Center", right: "Right", bold: "Bold", barcodeDigits: "Barcode digits",
    geometryHint: "Coordinates are in millimeters and constrained to the working area.",
  },
  dialogs: {
    createTitle: "Create template", createSubmit: "Create", duplicateTitle: "Duplicate template", duplicateSubmit: "Duplicate",
    renameTitle: "Rename template", renameSubmit: "Rename", name: "Template name", resetTitle: "Reset template?", deleteTitle: "Delete template?",
    resetDetail: "The layout “{name}” will return to the system arrangement.", deleteDetail: "The template “{name}” will be deleted permanently.",
    reset: "Reset", delete: "Delete", close: "Close", cancel: "Cancel",
  },
  empty: { title: "No templates yet", detail: "Create the first 58 × 40 mm layout for this mode." },
  canvas: {
    hint: "Drag elements; use the handle to resize", unit: "mm", previewAria: "Template preview {name}",
    selectElement: "Select element {name}", sample: { color: "Graphite", name: "Basic T-shirt", subject: "T-shirts" },
  },
};

const vi: TemplateDesignerCopy = {
  mode: { aria: "Loại lô hàng của mẫu", localCatalog: "Danh mục định kiểu · lưu cục bộ" },
  toolbar: {
    aria: "Điều khiển mẫu", create: "Tạo mẫu", duplicate: "Nhân bản mẫu", rename: "Đổi tên mẫu",
    makeDefault: "Đặt làm mẫu mặc định", reset: "Đặt lại mẫu", delete: "Xóa mẫu", discard: "Hủy thay đổi", save: "Lưu mẫu",
  },
  notices: {
    dirtyGuard: "Hãy lưu hoặc hủy các thay đổi trước.", mutationError: "Thao tác không thành công. Dữ liệu cục bộ không thay đổi.",
    addError: "Chưa thêm phần tử. Hãy thử lại.", elementLimit: "Mẫu đã đạt giới hạn phần tử.", requiredElement: "Không thể xóa phần tử bắt buộc này.",
    saved: "Đã lưu mẫu", defaultSet: "Đã chọn mẫu mặc định", created: "Đã tạo mẫu", duplicated: "Đã tạo bản sao mẫu",
    renamed: "Đã đổi tên mẫu", deleted: "Đã xóa mẫu", reset: "Đã đặt lại mẫu",
  },
  load: { aria: "Đang tải mẫu", errorTitle: "Không thể tải mẫu", errorDetail: "Thư viện cục bộ không thay đổi. Hãy thử lại yêu cầu.", retry: "Thử lại" },
  dirty: "Có thay đổi chưa lưu",
  canvasActions: { copy: "Sao chép phần tử", paste: "Dán phần tử", delete: "Xóa phần tử", snap: "Bước 1 mm", copySuffix: "bản sao" },
  catalog: {
    title: "Mẫu", templateAria: "Mẫu {name}", elements: "Phần tử: {count}", default: "Mặc định", newElement: "Phần tử mới",
    addElement: "Thêm phần tử", layers: "Lớp", selectLayer: "Chọn lớp {name}", visible: "Hiển thị", hidden: "Đã ẩn",
  },
  inspector: {
    empty: "Chọn một lớp trên bố cục để sửa thuộc tính.", title: "Thuộc tính phần tử", name: "Tên", prefix: "Tiền tố", text: "Văn bản",
    visible: "Hiển thị", geometry: "Hình học", x: "X, mm", y: "Y, mm", width: "Chiều rộng, mm", height: "Chiều cao, mm", font: "Phông chữ, pt",
    alignment: "Căn chỉnh", left: "Trái", center: "Giữa", right: "Phải", bold: "Đậm", barcodeDigits: "Số mã vạch",
    geometryHint: "Tọa độ dùng milimét và được giới hạn trong vùng làm việc.",
  },
  dialogs: {
    createTitle: "Tạo mẫu", createSubmit: "Tạo", duplicateTitle: "Nhân bản mẫu", duplicateSubmit: "Nhân bản", renameTitle: "Đổi tên mẫu",
    renameSubmit: "Đổi tên", name: "Tên mẫu", resetTitle: "Đặt lại mẫu?", deleteTitle: "Xóa mẫu?",
    resetDetail: "Bố cục “{name}” sẽ trở về cách sắp xếp hệ thống.", deleteDetail: "Mẫu “{name}” sẽ bị xóa vĩnh viễn.",
    reset: "Đặt lại", delete: "Xóa", close: "Đóng", cancel: "Hủy",
  },
  empty: { title: "Chưa có mẫu", detail: "Tạo bố cục 58 × 40 mm đầu tiên cho chế độ này." },
  canvas: {
    hint: "Kéo phần tử; dùng tay nắm để đổi kích thước", unit: "mm", previewAria: "Xem trước mẫu {name}",
    selectElement: "Chọn phần tử {name}", sample: { color: "Than chì", name: "Áo thun cơ bản", subject: "Áo thun" },
  },
};

const zh: TemplateDesignerCopy = {
  mode: { aria: "模板供货类型", localCatalog: "类型化目录 · 本地存储" },
  toolbar: {
    aria: "模板操作", create: "创建模板", duplicate: "复制模板", rename: "重命名模板", makeDefault: "设为默认模板",
    reset: "重置模板", delete: "删除模板", discard: "放弃更改", save: "保存模板",
  },
  notices: {
    dirtyGuard: "请先保存或放弃更改。", mutationError: "操作失败，本地数据未更改。", addError: "未添加元素，请重试。",
    elementLimit: "此模板已达到元素数量上限。", requiredElement: "无法删除此必需元素。", saved: "模板已保存",
    defaultSet: "已选择默认模板", created: "模板已创建", duplicated: "模板副本已创建", renamed: "模板已重命名", deleted: "模板已删除", reset: "模板已重置",
  },
  load: { aria: "正在加载模板", errorTitle: "无法加载模板", errorDetail: "本地模板库未更改，请重试。", retry: "重试" },
  dirty: "有未保存的更改",
  canvasActions: { copy: "复制元素", paste: "粘贴元素", delete: "删除元素", snap: "步长 1 毫米", copySuffix: "副本" },
  catalog: {
    title: "模板", templateAria: "模板 {name}", elements: "元素：{count}", default: "默认", newElement: "新元素",
    addElement: "添加元素", layers: "图层", selectLayer: "选择图层 {name}", visible: "可见", hidden: "已隐藏",
  },
  inspector: {
    empty: "请选择布局中的图层以编辑其属性。", title: "元素属性", name: "名称", prefix: "前缀", text: "文本", visible: "可见", geometry: "几何",
    x: "X，毫米", y: "Y，毫米", width: "宽度，毫米", height: "高度，毫米", font: "字体，pt", alignment: "对齐",
    left: "左对齐", center: "居中", right: "右对齐", bold: "粗体", barcodeDigits: "条码数字",
    geometryHint: "坐标以毫米为单位，并限制在工作区域内。",
  },
  dialogs: {
    createTitle: "创建模板", createSubmit: "创建", duplicateTitle: "复制模板", duplicateSubmit: "复制", renameTitle: "重命名模板",
    renameSubmit: "重命名", name: "模板名称", resetTitle: "重置模板？", deleteTitle: "删除模板？",
    resetDetail: "布局“{name}”将恢复为系统排列。", deleteDetail: "模板“{name}”将被永久删除。", reset: "重置", delete: "删除", close: "关闭", cancel: "取消",
  },
  empty: { title: "暂无模板", detail: "为此模式创建第一个 58 × 40 毫米布局。" },
  canvas: {
    hint: "拖动元素；使用手柄调整大小", unit: "毫米", previewAria: "模板预览 {name}",
    selectElement: "选择元素 {name}", sample: { color: "石墨色", name: "基础 T 恤", subject: "T 恤" },
  },
};

const copies: Record<Language, TemplateDesignerCopy> = { ru, en, vi, zh };

export function getTemplateDesignerCopy(language: Language): TemplateDesignerCopy {
  return copies[language];
}
