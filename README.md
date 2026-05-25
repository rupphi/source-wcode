# FBS Barcode

**FBS Barcode** là ứng dụng desktop hỗ trợ người bán hàng trên sàn thương mại điện tử **Wildberries** (mô hình FBS - Fulfillment by Seller) trong việc:

- 🏪 Quản lý nhiều gian hàng (shop) Wildberries
- 📦 Đồng bộ đơn hàng, lô hàng (supply) từ Wildberries API
- 🏷️ In mã vạch đơn hàng kết hợp với mã **KIZ** (DataMatrix) theo chuẩn Честный ЗНАК
- ☁️ Tự động tick mã KIZ lên Wildberries sau khi in

> **Liên hệ:** Zalo 0335407670

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
| **EXE Installer** (khuyên dùng) | [WCode.exe](https://github.com/tuanworlddev/WCode/releases/latest/download/WCode.exe) |
| **MSI Installer** | [WCode.msi](https://github.com/tuanworlddev/WCode/releases/latest/download/WCode.msi) |
| **Portable (ZIP)** | [WCode-portable.zip](https://github.com/tuanworlddev/WCode/releases/latest/download/WCode-portable.zip) |

### macOS 🍎

| Loại | Link tải |
|------|----------|
| **DMG Apple Silicon** | [WCode-mac-arm64.dmg](https://github.com/tuanworlddev/WCode/releases/latest/download/WCode-mac-arm64.dmg) |

### Linux 🐧

| Loại | Link tải |
|------|----------|
| **DEB Package** | [WCode-linux-amd64.deb](https://github.com/tuanworlddev/WCode/releases/latest/download/WCode-linux-amd64.deb) |

> 💡 Xem tất cả phiên bản tại [trang Releases](https://github.com/tuanworlddev/WCode/releases).
> Ứng dụng kiểm tra phiên bản mới trực tiếp từ GitHub Releases và trên Windows có thể tải và mở installer cập nhật ngay trong app.

## Cài đặt từ mã nguồn

### Yêu cầu
- **JDK 25** trở lên
- **Maven 3.8+** (hoặc sử dụng `mvnw` đính kèm)

### Build và chạy

```bash
# Clone repository
git clone https://github.com/tuanworlddev/FBSBarcode.git
cd FBSBarcode

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

Workflow [release.yml](.github/workflows/release.yml) tự chạy khi push tag theo dạng `v*`. Pipeline dùng JDK 25 và Maven wrapper, sau đó build native package trên runner riêng của từng nền tảng:
- `windows-latest`: tạo `WCode.exe`, `WCode.msi`, `WCode-portable.zip`
- `macos-latest`: tạo `WCode-mac-arm64.dmg` bằng `logo.icns`
- `ubuntu-latest`: tạo `WCode-linux-amd64.deb` bằng `jpackage`

Mỗi nền tảng được upload thành artifact riêng, sau đó job publish sẽ đẩy toàn bộ asset lên GitHub Release public tại `tuanworlddev/WCode`.

```bash
git tag v1.0.22
git push origin main --tags
```

## Hướng dẫn sử dụng

1. **Thêm shop:** Nhấn "Add Shop", nhập tên shop và API Key từ tài khoản Wildberries
2. **Đồng bộ dữ liệu:** Chọn shop, nhấn "Sync WB" để tải danh sách lô hàng và đơn hàng mới nhất
3. **Chọn lô hàng:** Chọn supply từ danh sách để xem các đơn hàng trong lô
4. **Import KIZ:** Nhấn "Add Category" tạo danh mục, sau đó import file PDF chứa mã DataMatrix KIZ
5. **Phân bổ KIZ:** Nhập lệnh vào ô KIZ Command (ví dụ: `3:1-10` — gán 10 mã KIZ từ danh mục 3 cho 10 đơn hàng đầu tiên)
6. **In ấn:** Chọn kiểu in (1/2/3), nhấn "Print" để tạo file PDF nhãn đơn hàng và phiếu nhặt hàng

## Giấy phép

MIT License

---

**Tác giả:** TuanDev | **Liên hệ:** Zalo 0335407670
