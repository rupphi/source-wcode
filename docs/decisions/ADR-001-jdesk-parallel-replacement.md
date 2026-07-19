# ADR-001: Chuyển JavaFX sang jDesk bằng parallel replacement và canary cutover

## Status

Accepted

## Date

2026-07-18

## Context

WCode là ứng dụng Java 25/JavaFX có 180 Java source files, SQLite local data và nhiều tích hợp
nhạy cảm với Wildberries, Znack, CryptoPro, PDF/printing và updater. Khoảng 150 file không import
JavaFX trực tiếp. jDesk 0.1.3 cung cấp system WebView, typed command bridge, capabilities, native
packaging và phù hợp với Java core hiện có, nhưng framework vẫn pre-alpha.

Big-bang rewrite sẽ trộn rủi ro UI, business parity, packaging, printing và data migration trong
một release, khiến rollback khó và không thể dùng app hiện có làm oracle theo từng luồng.

## Decision

Tạo entry point jDesk và React/Tailwind song song trong repo WCode. Đây là **parallel
replacement**, không phải runtime Strangler: các slice mới được kiểm chứng trong preview app,
nhưng JavaFX không route từng màn hình sang jDesk. Maven/JavaFX tiếp tục là production baseline
trong lúc từng vertical slice được migrate.

Mỗi slice có command adapter deny-by-default, runtime validation, DTO output không chứa secret,
React UI và test. Production rollout diễn ra theo cohort/installer channel chỉ sau khi **mọi user
journey bắt buộc** trong spec đạt 100% parity evidence; nhãn “critical” chỉ dùng để ưu tiên thứ tự
triển khai, không được nới cutover gate. Không tuyên bố một slice riêng lẻ là
production-migrated chỉ vì preview pass. Chỉ sau canary, native Windows gate và rollback rehearsal
mới chuyển jDesk thành entry point mặc định. JavaFX/FXML/Maven chỉ được xóa sau rollback window
của release jDesk đầu tiên.

Foundation ban đầu dùng classpath template để tránh di chuyển 150 file. API jDesk chỉ xuất hiện
trong `src/jdesk`; domain/integration không phụ thuộc framework. Trước mutation, WCode đã chuyển
sang explicit open module `wcode.desktop`: package/dev runner dùng module path,
`--illegal-native-access=deny`, grant platform module + SQLite cho main và chỉ SQLite cho recovery;
verifier cấm `ALL-UNNAMED`. Seller-state mutation vẫn có **hai điều kiện độc lập**: gate kỹ thuật
đã pass, và người dùng phê duyệt rõ một test case cụ thể; điều kiện kỹ thuật không bao giờ ngầm cấp
quyền mutation.

## Data ownership and rollback

- JavaFX và jDesk dùng cùng app-data directory nhưng **không được chạy đồng thời**. Cả hai entry
  point phải lấy cùng một OS/file lock trước `Database.initDatabase()`; tiến trình thứ hai hiển thị
  thông báo và thoát. Test dùng `wcode.appdata.dir` riêng nên không tranh live database.
- Trong dual-build/canary window, schema migration chỉ được additive và backward-compatible với
  release JavaFX rollback. Không drop/rename column hoặc đổi meaning tại chỗ.
- `LocalDataMigrationGate` dùng chung cho JavaFX và jDesk trước bootstrap. Trước first launch (kể
  cả database rỗng), mỗi schema-changing migration và mỗi canary writer/app version mới, gate giữ
  shared app-data lock rồi tạo snapshot nhất quán bằng SQLite backup API (bao gồm trạng thái WAL),
  checksum và metadata schema/app version. Chỉ tiếp tục migrate sau khi snapshot được verify; giữ
  ít nhất bản pre-cutover và các bản pre-migration còn trong rollback window.
  Restore luôn là thao tác có xác nhận, chọn snapshot tương thích mới nhất và không tự ghi đè
  database mới hơn. Dữ liệu mới hơn phải được export/snapshot lại trước restore để tránh silent
  data loss.
- `PRAGMA user_version` là schema authority và chỉ publish sau toàn bộ idempotent DDL/data migration.
  Ready marker riêng của mỗi writer pin writer version, migration id, schema revision và verified
  snapshot SHA; revision cao hơn binary fail closed trước DDL/snapshot/marker. Schema migration chạy
  trong transaction khi SQLite cho phép. Nếu migration fail, tiến trình không mở app ở chế độ thường. Một recovery entry
  point `--restore-snapshot` nằm ngoài composition/database bootstrap thường phải list/verify và
  restore snapshot sau xác nhận, kể cả khi cả JavaFX và jDesk không khởi động được; nó snapshot
  lại file hỏng trước khi thay thế.
- Post-cutover rollback dùng installer JavaFX cuối cùng trong rollback window; database và secret
  layout phải còn đọc được. Sau window đó, rollback là về jDesk N-1 và vẫn yêu cầu backward-
  compatible schema. Chỉ khi cả hai rollback paths hết hạn mới xóa JavaFX và legacy credential.

## Secret boundary

“Secret không qua UI” chỉ áp dụng cho **Java → React**: Java không bao giờ trả API key/license
secret, kể cả sau save hoặc trong error. Người dùng vẫn có thể nhập token trong React và gửi một
lần qua command `shop.saveToken`; command này write-only, capability riêng, giới hạn độ dài,
không log request, ghi vào storage ở Java, rồi frontend xóa input khỏi state/DOM. Không có generic
secret `get` command. Trong rollback window, plaintext legacy là source-of-truth tạm thời: save
ghi legacy transaction + monotonic credential version trước, sau đó ghi OS store và verify bằng
fingerprint không đảo ngược. jDesk chỉ dùng OS entry khi version/fingerprint khớp; partial failure
giữ legacy authoritative, báo safe retry và reconciliation tự heal OS store mà không đưa token qua
bridge. Chỉ sau rollback window và full verification mới chuyển source-of-truth sang OS store,
rồi mới được xóa legacy credential bằng migration có phê duyệt.

## Command and execution contract

- Type generation không thay runtime validation. Mỗi command kiểm tra semantic range, length,
  enum và resource ownership ở Java boundary; malformed/unknown/oversized envelopes được jDesk
  runtime từ chối. WB/Znack responses tiếp tục được parse/validate như untrusted data trước khi ghi.
- Command chạy trên jDesk virtual thread. Không bọc blocking I/O trong common ForkJoinPool và
  không block UI dispatcher.
- Long workflow định nghĩa timeout/cancellation/idempotency; progress dùng bounded/coalesced event,
  file lớn dùng binary stream. Existing persisted Znack recovery remains source-of-truth.
- Mọi command đi qua một exception boundary duy nhất: chỉ allowlist stable error code, safe
  user-message và correlation id được trả qua bridge. Raw exception message, cause, SQL, WB/Znack
  response, stack trace và secret không được serialize. Log phía Java cũng qua redaction policy;
  test fault-injection phải chứng minh cả expected lẫn unexpected exception đều an toàn.
- Foundation có một window `main`; mỗi command có capability feature-specific được grant chỉ cho
  `main`. `main` chỉ được load bundled app content; navigation/redirect tới origin khác bị chặn và
  mở bằng system browser không có bridge. Nếu trusted origin bị mất, capability bị revoke hoặc
  window đóng trước khi nội dung mới chạy. CSP khóa script/connect source theo build/dev profile.
  Window mới phải có grant list riêng. Annotation processor/compiler rejects missing, duplicate
  hoặc namespace-conflicting commands.

## Build equivalence and artifact proof

- Maven và Gradle pin cùng dependency/toolchain/resource values trong dual-build window.
- Shared domain/repository tests chạy dưới cả build graph khi chúng được đưa vào jDesk app; database
  migration compatibility là gate của cả hai.
- `jdeps`, packaged-classpath scan và repository search phải chứng minh production jDesk artifact
  không chứa JavaFX/FXML/MaterialFX/Ikonli trước cutover. “Không import trực tiếp” chỉ là inventory
  ban đầu, không phải proof.
- Windows native probes cho printing, file dialogs, CryptoPro và updater chạy sớm sau foundation,
  trước khi đầu tư vào các UI slice phụ thuộc chúng.

## Canary and rollback exit criteria

- Rollout theo ba cohort: internal, tối đa 10%, rồi tối đa 50% trước 100%. Mỗi cohort giữ ít nhất
  7 ngày và có ít nhất 20 cold launches trên tối thiểu 2 máy Windows đại diện.
- Chỉ tăng cohort khi mọi journey bắt buộc đã được exercise trong cohort, không có critical/high
  incident, không có data/credential/secret incident và upgrade + rollback rehearsal giữ checksum,
  row counts và credential-version consistency.
- Bất kỳ data loss/corruption, credential divergence, secret exposure, security critical/high,
  launch failure lặp lại hoặc mandatory journey bị block đều dừng rollout và kích hoạt rollback;
  không được “accept” để tiến cohort trong cùng release.
- Rollback window chỉ kết thúc sau 30 ngày kể từ 100% rollout với các điều kiện trên tiếp tục pass
  và một rehearsal cuối. JavaFX artifact/legacy credential không được xóa trước mốc này.

## Alternatives Considered

### Big-bang rewrite trong một project jDesk mới

- Ưu: cây source sạch ngay, không có dual build.
- Nhược: mất incremental parity, duplicate logic, rủi ro cao với dữ liệu và printing.
- Rejected: không đáp ứng yêu cầu giữ logic chính xác và live-test liên tục.

### Tiếp tục JavaFX và chỉ restyle CSS

- Ưu: ít thay đổi build/runtime.
- Nhược: không đáp ứng yêu cầu React/Tailwind/jDesk và tiếp tục coupling controller lớn.
- Rejected: sai framework đích.

### Chuyển desktop thành client của WCode SaaS Next/Spring

- Ưu: dùng lại web UI/backend hiện có.
- Nhược: thay đổi mô hình offline/local SQLite, CryptoPro, printing và quyền sở hữu dữ liệu;
  jDesk không còn là Java core tại chỗ.
- Rejected: là thay đổi sản phẩm lớn hơn yêu cầu và phá khả năng offline.

### Full multi-module JPMS ngay từ foundation

- Ưu: boundary rõ, native access tối thiểu.
- Nhược: churn lớn với dependency hiện có và làm chậm vertical proof đầu tiên.
- Deferred as a full domain/application/infrastructure split. A single explicit `wcode.desktop`
  composition root is now adopted as the production native-access boundary; further module splits
  remain optional refactoring after cutover.

## Consequences

- Có hai build/entry point trong giai đoạn migration và CI phải kiểm tra cả hai.
- Cần một danh sách source JavaFX bị exclude khỏi build jDesk; danh sách này phải giảm dần.
- Có rollback trước và sau first cutover; đổi lại schema/credential cleanup bị trì hoãn qua
  rollback window.
- Business logic không bị copy sang TypeScript; React chỉ orchestration/presentation.
- Secret có thể đi React → Java qua write-only command, nhưng không bao giờ được trả Java → React.
- Framework pre-alpha được cô lập, pin version và có upgrade test riêng.
