# FBS Barcode

**FBS Barcode** là ứng dụng desktop hỗ trợ người bán hàng trên sàn thương mại điện tử **Wildberries** (mô hình FBS - Fulfillment by Seller) trong việc:

- 🏪 Quản lý nhiều gian hàng (shop) Wildberries
- 📦 Đồng bộ đơn hàng, lô hàng (supply) từ Wildberries API
- 🏷️ In mã vạch đơn hàng kết hợp với mã **KIZ** (DataMatrix) theo chuẩn Честный ЗНАК
- ☁️ Tự động tick mã KIZ lên Wildberries sau khi in
- 🔐 Bán theo thuê bao: kích hoạt license để mở tính năng mua/tự động hóa KIZ

> **Liên hệ:** Zalo 0335407670

## Kho lưu trữ

| Repo | Mục đích |
|------|----------|
| 🔒 [tuanworlddev/wcode-new](https://github.com/tuanworlddev/wcode-new) | **Mã nguồn** (private) — code + workflow build |
| 📦 [tuanworlddev/wcode-relatest](https://github.com/tuanworlddev/wcode-relatest) | **Bản phát hành** (public) — installer, auto-update đọc từ đây |

> Mã nguồn và bản phát hành được tách riêng: source ở `wcode-new`, installer publish sang `wcode-relatest`.

## Ảnh chụp màn hình

<!-- TODO: Thêm ảnh chụp màn hình ứng dụng -->

## Tính năng chính

### Quản lý gian hàng
- Thêm, sửa, xóa nhiều gian hàng Wildberries
- Lưu API key cho từng shop

### Đồng bộ dữ liệu từ Wildberries
- Đồng bộ danh sách lô hàng (supplies), đơn hàng, thông tin sản phẩm tự động
- Hỗ trợ đồng bộ tăng dần (incremental sync) theo thời gian
- Hiển thị đơn hàng kèm ảnh sản phẩm, mã vạch, sticker

### Quản lý KIZ (Честный ЗНАК)
- Import mã DataMatrix KIZ từ file PDF
- Phân bổ mã KIZ cho đơn hàng theo cú pháp `ID:FROM-TO`
- Tự động upload mã KIZ lên Wildberries qua API

### In ấn
- In nhãn đơn hàng khổ **58×40mm** (chuẩn Wildberries)
- 3 kiểu layout in linh hoạt:
  - **Kiểu 1:** Trang KIZ → Trang sticker → Trang thông tin sản phẩm
  - **Kiểu 2:** Trang thông tin sản phẩm → Trang sticker → Trang KIZ
  - **Kiểu 3:** Trang kết hợp sản phẩm + KIZ → Trang sticker
- Tạo file PDF danh sách đơn hàng khổ A4 (phiếu nhặt hàng)
- Mã vạch Code 128 cho mã đơn hàng
- QR Code cho mã sticker
- DataMatrix cho mã KIZ

### Sắp xếp đơn hàng
- Sắp xếp theo danh mục, article, màu sắc, kích thước

## Công nghệ sử dụng

| Công nghệ | Mô tả |
|-----------|-------|
| **Java 25** | Ngôn ngữ lập trình chính |
| **JavaFX 25** | Framework giao diện desktop |
| **BootstrapFX** | Thư viện CSS giúp giao diện hiện đại |
| **iText 8** | Thư viện tạo file PDF |
| **Apache PDFBox** | Thư viện đọc file PDF (import KIZ) |
| **ZXing** | Thư viện tạo và đọc mã vạch (QR, Code 128, DataMatrix) |
| **SQLite** | Cơ sở dữ liệu nhúng |
| **OkHttp + Gson** | HTTP client và xử lý JSON cho Wildberries API |
| **Apache POI** | Đọc/ghi file Excel |

## Tải về

### Windows 🪟

| Loại | Link tải |
|------|----------|
| **EXE Installer** (khuyên dùng) | [WCode.exe](https://github.com/tuanworlddev/wcode-relatest/releases/latest/download/WCode.exe) |
| **MSI Installer** | [WCode.msi](https://github.com/tuanworlddev/wcode-relatest/releases/latest/download/WCode.msi) |
| **Portable (ZIP)** | [WCode-portable.zip](https://github.com/tuanworlddev/wcode-relatest/releases/latest/download/WCode-portable.zip) |

> 💡 Xem tất cả phiên bản tại [trang Releases](https://github.com/tuanworlddev/wcode-relatest/releases).
> Ứng dụng kiểm tra phiên bản mới trực tiếp từ GitHub Releases (repo `wcode-relatest`) và trên Windows có thể tải và mở installer cập nhật ngay trong app.

## License & thuê bao

Ứng dụng bán theo **thuê bao**. In tem cơ bản dùng tự do, nhưng **mua/tự động hóa mã KIZ** yêu cầu license hợp lệ:

1. Khách nhận **license key** (`WC-XXXXX-...`) sau khi thanh toán.
2. Nhập key trong mục **License** của app → kích hoạt theo máy.
3. App xác thực với máy chủ license `https://wcode.online`; hỗ trợ chạy offline có thời hạn (grace) nhờ file license ký **Ed25519**.
4. Admin tạo/gia hạn/thu hồi key tại **https://wcode.online/admin**.

> Server license là một dịch vụ Node riêng (repo `wcode-admin`), không nằm trong repo này.

## Cài đặt từ mã nguồn

### Yêu cầu
- **JDK 25** trở lên
- **Maven 3.8+** (hoặc sử dụng `mvnw` đính kèm)

### Build và chạy

```bash
# Clone repository (mã nguồn — private)
git clone https://github.com/tuanworlddev/wcode-new.git
cd wcode-new

# Chạy ứng dụng
./mvnw clean javafx:run       # macOS / Linux
mvnw.cmd clean javafx:run     # Windows

# Đóng gói JAR
./mvnw clean package
```

### Đóng gói native installer (Windows local)

```bash
build.bat exe      # Tạo file cài đặt EXE
build.bat msi      # Tạo file cài đặt MSI
build.bat app-image   # Tạo thư mục portable
```

### Build & Release

Workflow [release.yml](.github/workflows/release.yml) tự chạy khi push tag `v*`. Pipeline chạy kiểm thử + build trên `windows-latest`, đóng gói `WCode.exe` / `WCode.msi` / `WCode-portable.zip`, rồi **publish sang repo release riêng** `tuanworlddev/wcode-relatest` (dùng secret `RELEASE_TOKEN`).

Cắt bản phát hành mới:

```bash
# 1. Bump version trong pom.xml (<version> và <app.version>)
# 2. Commit rồi tag + push
git tag v1.1.0
git push origin dev
git push origin v1.1.0     # kích hoạt CI build + publish sang wcode-relatest
```

> Repo release phải có sẵn ít nhất 1 commit (README), nếu không `gh release create` sẽ báo lỗi *"Repository is empty"*.

## Hướng dẫn sử dụng

1. **Thêm shop:** Nhấn "Add Shop", nhập tên shop và API Key từ tài khoản Wildberries
2. **Đồng bộ dữ liệu:** Chọn shop, nhấn "Sync WB" để tải danh sách lô hàng và đơn hàng mới nhất
3. **Chọn lô hàng:** Chọn supply từ danh sách để xem các đơn hàng trong lô
4. **Import KIZ:** Nhấn "Add Category" tạo danh mục, sau đó import file PDF chứa mã DataMatrix KIZ
5. **Phân bổ KIZ:** Nhập lệnh vào ô KIZ Command (ví dụ: `3:1-10` — gán 10 mã KIZ từ danh mục 3 cho 10 đơn hàng đầu tiên)
6. **In ấn:** Chọn kiểu in (1/2/3), nhấn "Print" để tạo file PDF nhãn đơn hàng và phiếu nhặt hàng

## Giấy phép

Phần mềm thương mại — bản quyền © TuanDev. Mọi quyền được bảo lưu. Sử dụng theo mô hình thuê bao.

---

**Tác giả:** TuanDev | **Liên hệ:** Zalo 0335407670
