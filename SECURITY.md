# Bảo mật mã nguồn & chống crack — WCode

Tóm tắt kết luận nghiên cứu (07/2026) và cách WCode được bảo vệ.

## Nguyên tắc cốt lõi: bảo vệ nằm ở server, không ở client

Bytecode Java **luôn** decompile được (Vineflower, CFR, JADX...). Obfuscation chỉ **nâng chi
phí** reverse-engineering, không chống được người quyết tâm. Ai đó có thể:
- Decompile app và đọc logic.
- Patch `LicenseFileVerifier.verify(...)` để luôn trả về hợp lệ, hoặc thay
  `DEFAULT_PUBLIC_KEY_B64` bằng khóa của họ để tự ký license.
- Bản cài `--win-per-user-install` nằm ở `%LOCALAPPDATA%` → jar ghi được, không cần quyền admin.

→ Vì vậy **tính năng đáng tiền (mua KIZ / gọi Znack) phải được gate ở server của mình**, kiểm tra
license + device fingerprint trên **mỗi** lời gọi. Một bản crack không mua được KIZ chỉ là bản demo.
Đây là lớp bảo vệ thật; các lớp dưới chỉ là phụ trợ.

Hiện trạng WCode: `LicenseService` xác thực với `license-server` (Ed25519, offline grace 14 ngày,
chống lùi đồng hồ) và gate hai nút mua KIZ. Bước củng cố tiếp theo (khuyến nghị): cho pipeline
mua KIZ đi qua server của mình thay vì gọi Znack trực tiếp từ client.

## Obfuscation (đã cấu hình, opt-in)

- `proguard.conf` + profile `-Pobfuscate` trong `pom.xml` đã sẵn sàng: chỉ **rename** phần nội bộ
  (`-dontshrink -dontoptimize`), giữ nguyên FXML controller, DTO Gson, model, enum, `layout_json`.
- Chạy: `./mvnw clean package -Pobfuscate` (đừng quên lưu `target/proguard-mapping.txt` mỗi release
  để giải mã stack trace bằng `retrace`).
- ⚠️ **Chặn hiện tại**: ProGuard bản mới nhất (7.9.1) **chưa hỗ trợ bytecode Java 25** (class file
  69), kể cả jmods runtime JDK 25. Nên profile này **chưa** được wire vào `build.sh`/CI (sẽ làm hỏng
  release Windows chạy JDK 25). Bật lại khi ProGuard hỗ trợ Java 25 — xem hướng dẫn trong `proguard.conf`.
- Muốn có string encryption + control-flow obfuscation (ProGuard free không có): cần công cụ trả phí
  **Zelix KlassMaster** hoặc **Allatori** (~vài trăm USD/dev). Chỉ đáng đầu tư nếu quan sát thấy bị crack.

## Việc nên làm khi phát hành

1. Thay `LicenseFileVerifier.DEFAULT_PUBLIC_KEY_B64` bằng public key sinh trên server production.
2. Ký Authenticode cho EXE/MSI (chống cảnh báo SmartScreen + chống sửa installer).
3. Cân nhắc đưa call mua KIZ qua server (điểm 1 phần trên) — giá trị lớn nhất.
