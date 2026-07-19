# Nghiên cứu jDesk cho WCode

Ngày đánh giá: 2026-07-18
Phiên bản được kiểm chứng: jDesk `0.1.3`, React `19.2.x`, Tailwind CSS `4.x`

Updater source checkpoint (2026-07-19): GitHub's official release response exposes asset size and
`sha256:` digest, but WCode still uses its own signed manifest as the trust root. GitHub Actions
secrets are injected explicitly and should use least privilege; missing secrets resolve empty, so
the release workflow must test and fail rather than silently publish unsigned artifacts. Oracle
JDK 25 documents `--win-upgrade-uuid` for Windows package upgrades and JCA `Signature` provides
signature authentication/integrity; Microsoft documents Authenticode verification with the default
authentication policy. Sources: https://docs.github.com/en/rest/releases/releases,
https://docs.github.com/en/actions/concepts/security/secrets,
https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html,
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/Signature.html,
https://learn.microsoft.com/en-us/windows/win32/seccrypto/using-signtool-to-verify-a-file-signature.

## Kết luận

jDesk phù hợp để thay lớp JavaFX của WCode vì framework giữ Java 25 làm lõi ứng dụng,
render React trong WebView của hệ điều hành và cung cấp bridge Java/TypeScript có type. WCode
đã có 150/180 lớp Java không import JavaFX trực tiếp, nên có thể tái sử dụng phần lớn domain,
SQLite, Wildberries, Znack và PDF thay vì viết lại.

Không nên thay toàn bộ JavaFX trong một lần. jDesk vẫn ở trạng thái pre-alpha và mỗi hệ điều
hành dùng một WebView khác nhau. WCode sẽ dùng parallel replacement: phát triển entry point jDesk
song song, chuyển từng luồng người dùng, kiểm chứng parity, rollout theo canary cohort, rồi mới xóa
JavaFX sau rollback window. Đây không phải runtime Strangler vì hai UI không route slice cho nhau.

## Mô hình jDesk

- Java 25 giữ business logic, lifecycle, tích hợp hệ điều hành và đóng gói.
- React/Vite/Tailwind tạo static assets; production không chạy Node hoặc localhost server.
- `@DesktopCommand` tạo command registry và TypeScript bindings ở compile time.
- Command chạy trên virtual thread; I/O blocking không chạy trên UI thread.
- Mọi command bị từ chối mặc định và cần capability được grant theo cửa sổ.
- Java phát event cho React; file lớn dùng binary pull-stream có backpressure.
- `jlink`/`jpackage` tạo app image và installer theo đúng hệ điều hành build.

Nguồn chính thức:

- [JDesk website](https://jdesk.dev/)
- [JDesk repository và README](https://github.com/tuanworlddev/jdesk)
- [Introduction](https://github.com/tuanworlddev/jdesk/blob/main/docs/getting-started/introduction.md)
- [Project structure](https://github.com/tuanworlddev/jdesk/blob/main/docs/getting-started/project-structure.md)
- [Defining commands](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/defining-commands.md)
- [Capabilities and permissions](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/capabilities-and-permissions.md)
- [Security model](https://github.com/tuanworlddev/jdesk/blob/main/docs/concepts/security-model.md)
- [TypeScript bindings](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/generating-typescript-bindings.md)
- [Automation and E2E](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/automation-and-e2e.md)
- [Packaging](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/packaging-your-app.md)
- [OS secret store](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/storing-secrets.md)
- [Native dialogs and printing](https://github.com/tuanworlddev/jdesk/blob/main/docs/guides/dialogs-and-printing.md)

## Bằng chứng public-consumer

Ngày 2026-07-18 đã scaffold một ứng dụng độc lập bằng:

```bash
npx create-jdesk-app@0.1.3 wcode-probe \
  --template react \
  --package dev.wcode.probe \
  --yes
npm install --prefix ui
./gradlew clean classes jdeskFrontendBuild
./gradlew jdeskDoctor
```

Kết quả:

- Maven Central resolve được `dev.jdesk:*:0.1.3`.
- Gradle Plugin Portal resolve được `dev.jdesk.application:0.1.3`.
- React/Vite production build thành công.
- `jdeskDoctor` xác nhận JDK 25, `jlink`, `jpackage` và WKWebView trên máy phát triển.
- `npm audit` của scaffold không có vulnerability tại thời điểm probe.

Điều này thay thế phương án phụ thuộc local composite checkout. WCode sẽ pin `0.1.3` và
commit lockfiles để build tái lập được.

## Đối chiếu WCode hiện tại

| Khu vực | Hiện trạng | Hướng migration |
| --- | --- | --- |
| Domain/SQLite/WB/Znack/PDF | Java 25, phần lớn độc lập JavaFX | Tái sử dụng trực tiếp |
| Shell, navigation, dialog, table | JavaFX/FXML | React + semantic HTML + Tailwind |
| Async UI | JavaFX `Task`/`AppTaskExecutor` | jDesk command virtual thread + event |
| Shop API key | Plaintext SQLite, model có getter và `toString` lộ key | Không trả key qua bridge; migrate sang OS secret store ở phase riêng |
| i18n | `.properties` qua `I18nService` | Giữ Java source-of-truth ban đầu, sau đó sinh catalog frontend |
| Theme | JavaFX CSS | Tailwind semantic tokens, light/dark/system |
| Printing | iText tạo PDF, JavaFX/OS mở/in | Giữ PDF engine; dùng file dialog/OS integration có kiểm chứng |
| Packaging | Maven + jpackage Windows | Gradle jDesk package song song, cắt sang sau parity |

Database live hiện có 8 shop, 17.922 product rows, 8.938 supply rows và 52.713 order rows.
Các số này chỉ dùng để chứng minh có dữ liệu migration/live-test; API key không được đọc ra log
hoặc tài liệu.

## Rủi ro và giới hạn đã xác nhận

1. **Pre-alpha/API drift.** jDesk có thể breaking trước 1.0. Giảm thiểu bằng pin version, một
   composition root và bridge adapter nhỏ, không để API jDesk lan vào domain.
2. **WebView khác nhau theo OS.** Kiểm thử responsive/keyboard trên browser chưa đủ; cần native
   E2E trên Windows, macOS và Linux trước release.
3. **Windows printing gap.** Tài liệu 0.1.3 nói `window.print()` chưa được wire trên WebView2 và
   named-printer `paperSize` chưa được hỗ trợ đầy đủ. Không thay engine in hiện có trước khi có
   parity test thật trên Windows.
4. **Secret migration.** jDesk có OS credential store nhưng không có plaintext fallback. Phải
   migrate có rollback, xác minh đọc lại rồi mới xóa `shops.api_key`.
5. **Classpath native access.** Template đơn module dùng `ALL-UNNAMED`. Giai đoạn đầu chấp nhận
   để giảm churn; trước production cutover phải đánh giá chuyển composition root sang JPMS để
   thu hẹp native access.
6. **Payload bridge 1 MiB.** Dashboard/list dùng DTO phân trang; PDF/image/file dùng binary stream
   hoặc app assets, không nhét base64 vào command response.

## Quyết định frontend

- React 19 + TypeScript để dùng bindings sinh từ Java và state union rõ ràng.
- Vite theo template chính thức jDesk.
- Tailwind CSS 4 qua `@tailwindcss/vite`, đúng hướng dẫn chính thức:
  [Tailwind with Vite](https://tailwindcss.com/docs/installation/using-vite).
- Không dùng component framework nặng ở foundation. Semantic HTML, Lucide icons và component
  nhỏ giúp giảm bundle, CSP và supply-chain surface.
- UI dùng typography/spacing/color token có chủ đích; tránh gradient, shadow và card grid đồng
  loạt. Mọi state loading/error/empty phải hiện rõ và dùng được bằng bàn phím.

## Adversarial architecture review

Fresh-context review ngày 2026-07-18 được reconcile như sau:

| Finding | Classification | Resolution |
| --- | --- | --- |
| Không có rollback sau cutover | Valid + actionable | Thêm JavaFX first-cutover rollback, jDesk N-1 và schema compatibility window |
| Token form mâu thuẫn “secret không qua WebView” | Contract misread do câu chữ quá tuyệt đối | Cho phép write-only React → Java; cấm Java → React/log/DOM retention |
| Hai entry point cùng ghi SQLite | Valid + actionable | Shared single-instance/app-data lock trước DB init |
| `ALL-UNNAMED` quá rộng | Valid trade-off tạm thời | Chỉ preview/read-only; JPMS hard gate trước mutation/beta/release |
| Không phải runtime Strangler | Valid + actionable | Đổi thành parallel replacement + installer-cohort canary |
| Typed DTO thiếu runtime validation | Valid + actionable | Semantic Java validation + untrusted WB response policy |
| Execution/cancellation/recovery chưa rõ | Valid + actionable | Virtual-thread, bounded event, timeout/idempotency/recovery contract |
| “Không import JavaFX” không chứng minh độc lập | Valid + actionable | Compile, `jdeps`, package scan và repository zero-reference gates |
| Maven/Gradle có thể drift | Valid + actionable | Dependency/resource alignment và shared compatibility tests |
| Capability isolation thiếu chi tiết | Valid + actionable | Per-window feature grants, compile-time namespace rejection |
| Windows risk kiểm tra quá muộn | Valid + actionable | Early Windows feasibility task ngay sau foundation |

Vòng fresh-context thứ hai tiếp tục khóa các điều kiện phát hành:

| Finding | Resolution |
| --- | --- |
| Cutover chỉ đòi parity luồng “critical” | Entry point mặc định đòi 100% mọi journey bắt buộc; priority chỉ quyết định thứ tự làm |
| Technical gate có thể bị hiểu là cấp quyền mutation | Tách hai điều kiện độc lập: technical gate và user-approved test case cụ thể |
| Unexpected exception có thể lộ qua bridge | Central allowlisted error mapper + redaction + fault-injection tests |
| Remote navigation có thể kế thừa capability của `main` | Bundled-content-only window; chặn/hand-off external navigation và revoke trước origin change |
| First-launch backup bị stale, không đảm bảo WAL consistency | Snapshot bằng SQLite backup API trước mỗi migration/writer canary, checksum/verify/retention/restore rehearsal |

Vòng fresh-context cuối khóa thêm ba failure mode: cohort progression/rollback có threshold và
thời gian cụ thể; failed migration có recovery CLI ngoài normal startup; credential dual-write có
legacy source-of-truth, monotonic version/fingerprint và partial-write reconciliation. Sau ba vòng,
không mở thêm review cycle để tránh review loop không giới hạn; các finding này trở thành release
gate trong ADR/spec/plan.

Cross-model review không được gọi trong lượt continuation tự động; không có external CLI nào được
chạy khi chưa có ủy quyền riêng của người dùng.
