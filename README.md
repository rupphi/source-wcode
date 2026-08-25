# WCode

WCode là ứng dụng desktop JavaFX cho người bán **Wildberries** và **Ozon FBS**.

- Quản lý nhiều shop theo marketplace, không dùng nhầm credential giữa WB và Ozon.
- Đồng bộ supply/order Wildberries và posting Ozon FBS.
- Quản lý kho KIZ/Znack, mapping GTIN, gán và gửi KIZ lên marketplace.
- In nhãn, barcode, trang KIZ và phiếu nhặt hàng PDF.
- Chuẩn bị đơn, ship và tải nhãn vận chuyển chính thức cho Ozon FBS Standard.
- Lưu lịch sử in, template và dữ liệu cục bộ trong SQLite.

Liên hệ: Zalo 0335407670.

## Công nghệ

| Thành phần | Công nghệ |
|---|---|
| Desktop UI | JavaFX 25, FXML, MaterialFX, Ikonli |
| Core | Java 25, Maven |
| Dữ liệu | SQLite |
| Marketplace API | OkHttp, Gson |
| PDF và barcode | iText 8, PDFBox, ZXing |
| Excel | Apache POI |

## Tải bản Windows

| Loại | Link |
|---|---|
| EXE installer | [WCode.exe](https://github.com/rupphi/relatest-wcode/releases/latest/download/WCode.exe) |
| MSI installer | [WCode.msi](https://github.com/rupphi/relatest-wcode/releases/latest/download/WCode.msi) |
| Portable ZIP | [WCode-portable.zip](https://github.com/rupphi/relatest-wcode/releases/latest/download/WCode-portable.zip) |

Bản portable có `check-portable.bat` để thu thập cấu trúc package và startup log khi cần hỗ trợ.

## Phát triển

Yêu cầu JDK 25. Node.js 22 chỉ cần cho các contract test của pipeline phát hành.

```bash
# Chạy toàn bộ Java/FXML test
./mvnw clean verify

# Chạy app JavaFX với app-data thật
./mvnw javafx:run

# Chạy app với dữ liệu thử cô lập
./mvnw -Dwcode.appdata.dir=/tmp/wcode-smoke javafx:run

# Kiểm tra contract build/release
node --test tools/*.test.mjs
```

Đóng gói local:

```bash
build.bat app-image   # Windows portable
build.bat exe         # Windows EXE chưa ký
build.bat msi         # Windows MSI chưa ký
./build.sh app-image  # macOS/Linux app-image
./build.sh dmg        # macOS DMG chưa ký/notarize
./build.sh deb        # Linux DEB
```

Artifact gửi người dùng phải lấy từ workflow [release.yml](.github/workflows/release.yml). Workflow
giữ nguyên Windows Upgrade UUID của 1.1.8/1.1.9 và kiểm tra cài đè bằng MSI thật. Authenticode và
signed update manifest được bật khi các secret tương ứng đã cấu hình đầy đủ; thiếu cả nhóm secret
không chặn build, nhưng cấu hình dở dang sẽ bị từ chối. Version duy nhất nằm trong `pom.xml`; tag
phát hành phải khớp `vMAJOR.MINOR.PATCH`.

Xem [runbook phát hành JavaFX](docs/javafx-release-runbook.md) và
[báo cáo triển khai Ozon](docs/ozon-marketplace-expansion-report.md).

## An toàn dữ liệu khi nâng cấp

- App vẫn dùng thư mục dữ liệu WCode hiện tại; cài 1.1.10 không tạo database mới.
- Migration schema chạy tăng dần và snapshot SQLite được tạo, verify trước khi ghi.
- Binary cũ fail closed khi gặp schema mới hơn; không tự hạ schema hoặc xóa dữ liệu.
- Marketplace của shop là bất biến; credential Ozon không bao giờ được gửi tới endpoint WB và ngược lại.
- Không đưa database, WAL, API key hay KIZ thật vào artifact/repository.

## Phạm vi Ozon hiện tại

Hỗ trợ Ozon FBS Standard, một package chứa đủ các item/quantity của posting. Chưa hỗ trợ rFBS, FBO,
partial package, multibox, price/stock và carriage/act. Các trường hợp ngoài phạm vi bị chặn thay vì
gửi mutation không chắc chắn.

## License

Phần mềm thương mại © TuanDev. In cơ bản dùng tự do; mua/tự động hóa KIZ cần license hợp lệ từ
`https://wcode.online`.
