# Spec: WCode trên jDesk

Trạng thái: Accepted from user objective; living specification
Ngày: 2026-07-18
Sửa đổi UX được người dùng phê duyệt: 2026-07-19

## Objective

Chuyển ứng dụng desktop WCode từ JavaFX sang jDesk với React, TypeScript và Tailwind CSS,
giữ chính xác logic nghiệp vụ hiện có và cải thiện rõ rệt trải nghiệm người bán Wildberries.

Người dùng chính là seller/operator cần quản lý nhiều shop, đồng bộ WB, thao tác supply/order,
in barcode + KIZ, đóng gói FBS/FBO và vận hành Znack. Thành công nghĩa là họ hoàn thành được mọi
luồng hiện tại trong UI mới, nhanh, rõ trạng thái, dùng được bằng bàn phím và không mất dữ liệu.

### User journeys bắt buộc

1. Mở ứng dụng, thấy các shop hiện có mà không lộ API key.
2. Chọn shop, xem dashboard và đồng bộ dữ liệu thật từ Wildberries.
3. Quản lý shop và token bằng write-only token command, với validation và lưu secret an toàn.
4. Duyệt supply, order, filter/sort, xem sản phẩm/sticker và refresh trạng thái.
5. Import/quản lý KIZ, mapping GTIN và chạy Znack automation có progress/recovery.
6. Tạo/in barcode FBS/FBO, template designer, print history và reprint.
7. Quản lý license, ngôn ngữ, theme, update và diagnostics.
8. Đóng gói Windows EXE/MSI/portable; giữ dữ liệu của bản JavaFX khi nâng cấp.

### Frontend content and navigation boundary

- Production entry cố định `jdesk://app/index.html`; launcher pin `wcode.production=true`, xóa dev/
  directory/module asset override trước jDesk bootstrap và ép classpath `web`. Không có runtime
  Node/localhost fallback hay shell/browser capability. Main-frame URL khác app origin cùng popup/
  new-window đều bị chặn, nên nội dung ngoài không kế thừa bridge/capability của cửa sổ `main`.
- Development server chỉ được bật khi có flag dev explicit và URL đúng một origin
  `http://127.0.0.1:<port>` hoặc `http://localhost:<port>`. HTTPS, remote host, implicit port,
  credential, path, query, fragment, IPv6 và URI mơ hồ đều làm startup fail closed; khi dev flag
  tắt, URL property không thể thay production bundle.
- Regression test pin policy của đúng jDesk version đang dùng cho `https`, loopback `http`,
  `file`, `data` và `javascript` main-frame. Native test phải thử redirect cùng popup trên WebView
  thật và chứng minh location/React root còn ở app origin, console sạch và log policy ghi `BLOCK`.

### Shop management and token boundary

- `shops.list`, `shops.select`, `shops.create`, `shops.update` và `shops.delete` chỉ trả một
  `ShopState` bounded gồm tối đa 500 summary `{id, name, tokenConfigured}` cùng selection hợp lệ.
  API key, fingerprint, credential version, SQL/exception, config khác và object `Shop` legacy
  không được trả qua bridge, `toString`, event hoặc log.
- Tên shop được trim, dài 1–120 ký tự và không chứa control character. Token create bắt buộc,
  token thay thế dài tối đa 16 KiB và không chứa control character; token trống khi update có nghĩa
  giữ token hiện có. Java không echo request trong validation/error. React xóa token khỏi state và
  unmount input ngay sau success/cancel; edit luôn mở với input token trống và chỉ hiển thị trạng
  thái “đã cấu hình”, không hiển thị mask có thể suy ra secret.
- Create/update/select/delete được serialize. SQLite mutation và `last_selected_shop_id` liên quan
  commit trong cùng transaction: create chọn shop mới; select chỉ chấp nhận shop hiện hữu; update
  phải fail nếu shop biến mất; delete chọn shop ID nhỏ nhất còn lại nếu selection cũ không còn.
  Generated ID lấy từ cùng SQLite connection, không dùng `MAX(id)`.
- Delete là local destructive cascade qua dữ liệu shop. Command yêu cầu `confirmed=true`, re-resolve
  shop ngay trước transaction và từ chối nếu bất kỳ WB/supply/Znack async job đã biết nào còn chạy
  cho shop. React dùng dialog xác nhận riêng nêu rõ dữ liệu cục bộ liên quan sẽ bị xóa; live/native
  delete chỉ chạy trên app-data cô lập nếu chưa có approval cụ thể.
- Trong rollback window, SQLite `shops.api_key` tiếp tục là source-of-truth để JavaFX luôn đọc token
  mới nhất. Mỗi token có metadata additive `{version, fingerprint, mirroredVersion,
  mirroredFingerprint}`; version tăng đơn điệu khi và chỉ khi token thay đổi, fingerprint là
  SHA-256 lowercase và không bao giờ đi qua bridge/log.
- SecretStore dùng một key ổn định, bounded theo shop và một envelope versioned chứa credential
  version, fingerprint và token. Save commit SQLite trước, sau đó `put` vào OS store, `get` đọc lại
  và chỉ ack mirrored metadata khi envelope khớp hoàn toàn. Lỗi/crash sau bất kỳ bước nào vẫn để
  legacy token dùng được và một pending state idempotent cho lần reconcile sau; command không được
  báo thất bại theo cách khiến người dùng retry tạo shop trùng.
- Reconcile được serialize cùng shop mutation. Missing/corrupt/stale OS entry luôn bị sửa từ nguồn
  legacy authoritative; một lỗi OS-store không được chặn list/read local hay làm thay đổi version.
  Xóa shop ghi tombstone không chứa token trong cùng SQLite transaction trước khi cascade, rồi xóa
  OS entry và chỉ dọn tombstone sau khi đọc lại xác nhận entry đã biến mất.
- Schema credential mirror chỉ được tạo sau pre-migration snapshot của jDesk, là additive để bản
  JavaFX rollback bỏ qua an toàn. Đây vẫn là dual-write foundation, chưa phải secret-at-rest parity:
  không xóa/blank plaintext column trước khi rollback window đóng và Windows credential-store
  cutover đã được rehearsal.

### Diagnostics and support boundary

- Help mở một diagnostics dialog local. `diagnostics.summary` chỉ trả app/jDesk/Java version đã
  sanitize, platform family/version/architecture allowlist, SQLite health enum và bounded aggregate
  counts. Không trả absolute path, hostname/user, shop ID/name, API/license key, device fingerprint,
  credential fingerprint/version, raw log, SQL, exception hoặc stack trace.
- `diagnostics.export` luôn mở native save dialog sau hành động rõ ràng của người dùng. Cancel là
  success không ghi file; target phải là regular non-symlink trong writable directory. Bundle ZIP
  được ghi qua temporary sibling + atomic replace, có giới hạn kích thước/entry và chỉ chứa manifest
  redacted cùng hướng dẫn; bridge chỉ nhận `{exported, cancelled}` và không nhận cả basename.
- jDesk không kế thừa hành vi legacy tự upload report chứa license/device/shop. Network support upload
  chỉ được thêm sau khi có contract server mới, consent riêng và payload allowlist được test; export
  local là authority trong migration hiện tại.
- Collector/database/save/ZIP lỗi chỉ trả error kind allowlist; React không render public message
  tùy ý. Test phải dùng canary secret/path/shop name trong SQLite/system properties và chứng minh
  chúng không xuất hiện trong DTO, ZIP manifest, DOM, console hoặc error.

### Signed update boundary

- Update trust root là Ed25519 key riêng, nhúng trong app và tách hoàn toàn license key. GitHub
  `releases/latest` chỉ giúp tìm asset; app chỉ tin `update-manifest.json` có format
  `wcode-update-envelope-v1` sau khi verify
  signature trên payload bytes trước khi parse. Payload tối đa 64 KiB, schema/version/publishedAt/
  notes/asset đều bounded và chỉ nhận HTTPS asset đúng release repo/tag/version.
- Signed asset descriptor cố định `windows-x64` + MSI, gồm basename, byte size 1–512 MiB, SHA-256
  lowercase và download URL nội bộ Java. `updates.check/status/startDownload/cancelDownload/install`
  không trả URL, path, hash, signature, raw manifest/HTTP body hay exception qua bridge; React chỉ
  render version/date/notes bounded, state/progress và safe error kind allowlist.
- Download chỉ bắt đầu sau hành động explicit, ghi file owner-only trong app temp qua sibling part,
  giới hạn Content-Length và streamed bytes theo signed size, verify SHA-256 constant-time rồi mới
  atomic publish. Cancel/interruption xóa part/artifact và không tạo install-ready session. Một job
  mỗi process, UUID opaque, progress bounded; renderer phải chờ backend xác nhận terminal cleanup
  trước khi báo cancel/cho đóng dialog, và job verified không được download lặp để tích tụ MSI.
  Restart không tự chạy installer còn sót.
- Install là xác nhận explicit thứ hai và chỉ có trên Windows x64. Java re-resolve session/file,
  kiểm tra lại size/hash, yêu cầu Authenticode `Valid` với publisher allowlist, tạo fresh verified
  local-data snapshot, rồi mới launch helper qua argv cố định và request app stop. Helper chờ đúng
  PID WCode thoát (deadline hữu hạn) trước khi chạy MSI. MSI dùng một
  `--win-upgrade-uuid` ổn định; installer fail/cancel phải relaunch bản hiện tại, còn startup/data
  recovery dùng snapshot đã verify. Không shell-concatenate giá trị từ manifest/WebView.
- Release workflow tạo SHA-256 + canonical manifest, ký bằng dedicated Actions environment secret,
  Authenticode-sign/time-stamp MSI và verify cả hai chữ ký trước upload. Thiếu Ed25519 secret,
  code-signing certificate, publisher hoặc stable upgrade UUID làm release fail closed. Key
  provisioning và Windows install/rollback rehearsal là release gate; không dùng development key
  hay unsigned fallback.
- `skipVersion` vẫn dùng shared `ConfigService` để JavaFX rollback hiểu cùng lựa chọn; mandatory
  update không được skip nhưng cũng không tự download/install. Automatic check có thể chạy sau
  startup, mọi network/download/install failure đều giữ app và local data đang chạy.

### Template designer contract

- Designer phục vụ hai kho template tách biệt `fbs` và `fbo`, cùng khổ cố định 58×40 mm.
- Catalog bridge chỉ trả DTO allowlist: template/element ID dạng string, tên/thuộc tính đã giới
  hạn, enum typed và geometry hữu hạn theo millimeter; raw `layout_json` không qua frontend.
- Mỗi mode tối đa 100 template, mỗi template tối đa 100 element. Element ID phải không rỗng,
  không trùng trong template; type/field/alignment phải thuộc enum legacy.
- UI giữ parity create/duplicate/rename/delete/default/reset/save; canvas hỗ trợ chọn, thêm,
  copy/paste/delete, drag/resize, snap 1 mm và chỉnh x/y/width/height theo mm, font theo point.
- Save boundary chuyển mm về PDF point, validate quota/text/numeric/required KIZ + Code128 +
  sticker-tail trước khi gọi `PrintTemplateService`; mọi lỗi repository/JSON chỉ trả safe envelope.
- Write bridge dùng command typed riêng `create`, `duplicate`, `rename`, `delete`, `setDefault`,
  `reset`, `save` và `createElement`; tất cả yêu cầu `templates:write`, kiểm tra target/name/quota
  trước mutation và trả catalog mới cùng selected template ID. `save` không nhận default flag từ
  frontend mà giữ trạng thái hiện có trong SQLite.
- Template là dữ liệu SQLite cục bộ, không phụ thuộc shop và không gọi WB/Znack. Native test writer
  dùng bản sao/isolated app-data; live catalog smoke không làm seller-state mutation.

### FBO packing and print contract

- `fbo.catalog` là read-only local command. Request bắt buộc có shop thuộc workspace, query tối đa
  120 ký tự không có control character, tối đa 50 subject duy nhất, page dương và page-size 10–100.
  Java query tối đa `pageSize + 1` SKU để trả `hasMore`; WebView không được yêu cầu offset/limit tùy
  ý hoặc nhận toàn bộ catalog (~37k SKU hiện tại).
- Product DTO chỉ cho phép nmId dạng string, vendor code, subject, brand, title, color, size/RU size,
  barcode SKU, `requiresKiz` và opaque local image path. Remote image URL, image blob, token shop,
  raw DB error và object legacy không được qua bridge. Ảnh list chỉ đọc từ bounded local cache;
  cache-miss không tự tải URL trong command.
- UI giữ quantity theo SKU qua page/filter, chỉ nhận số nguyên 0–10.000, hiển thị tổng label/pair
  trước export và có quick-print quantity 1. Batch request tối đa 500 SKU duy nhất và 10.000 pair;
  Java re-resolve mọi SKU từ `(shopId, sku)` thay vì tin metadata sản phẩm từ WebView.
- `fbo.export` dùng capability riêng, native save dialog và một transaction interruptible: validate
  → re-resolve → reserve KIZ → render staging PDF → consume KIZ ngay trước atomic publish. Cancel,
  dialog/export failure trước publish và interrupt phải release toàn bộ reservation; response chỉ
  trả cancelled, UUID session, basename và bounded pair/page counts. `fbo.openExport` chỉ mở file
  cùng shop qua opaque session có TTL.
- Live catalog smoke được phép trên shop có sẵn vì chỉ đọc local SQLite/cache. Native export/KIZ
  smoke chỉ chạy trên isolated app-data hoặc artifact không cần KIZ; không consume live KIZ nếu chưa
  có approval riêng của người dùng.

### FBS packing mutation contract

- `packing.prepareCreate`, `packing.prepareAdd` và `packing.prepareDeliver` re-resolve shop thuộc
  workspace cùng local board hiện tại trong Java. Order ID qua bridge dưới dạng decimal string để
  không mất chính xác ngoài safe-integer JavaScript, phải unique, tối đa 1.000 và vẫn thuộc tập new
  order. Tên shipment/supply ID là printable bounded value; add/deliver chỉ nhận open preparation
  supply hiện tại.
- Prepare thành công trả random one-use preview, expiry, action, item/KIZ count và chỉ warning/
  blocker kind trong allowlist. Preview sống tối đa mười phút, bind với shop + request đã normalize
  và bị consume trước execute. `packing.execute` còn bắt buộc `confirmed=true`, đọc lại state và
  fail closed nếu selection/supply đổi; replay, cross-shop, expiry và cancellation không gọi WB.
- Delivery preview cho biết label đã in và mọi KIZ bắt buộc cục bộ đã attach hay chưa. Preview bị
  block không được execute. Legacy Java workflow vẫn recheck print history, KIZ state và metadata
  IMEI/UIN/SGTIN/GTIN hiện tại từ WB ngay trước API deliver.
- Supply detail chỉ render action cho supply `open`, gọi preview với đúng `(shopId, supplyId)` đang
  hiển thị và dùng chung allowlisted response validator/dialog với packing board. Preview sai shop,
  action hoặc supply fail closed trước confirmation; receipt phải khớp action/supply/item count rồi
  mới publish success và reload local detail/list.
- GTIN inventory trong supply detail chỉ đọc page nhỏ từ `kizMapping.catalog`, search bounded và
  persisted `znack.settings`; mở supply không tự sync Znack hoặc gọi remote. Count/status/string phải
  qua cùng catalog validator trước render. Buy chỉ mở shared `ZnackPurchaseDialog`, bị disable nếu
  license/certificate/settings chưa ready và vẫn cần prepare + explicit paid confirmation; success
  reload local inventory, còn mapping/editor và purchase recovery tiếp tục ở workspace chuyên biệt.
- Mutation dùng capability riêng `packing:write`, giữ shared shop-activity lease, serialize packing
  writes, trả structured retryable error kind không chứa token/path/exception rồi reload board.
  Automated test chỉ inject runner; create/add/deliver thật là opt-in và cần shop/supply/order dùng
  thử đã được người dùng phê duyệt rõ ràng.

### GTIN mapping contract

- `kizMapping.catalog` chỉ đọc local SQLite và yêu cầu shop hiện hữu, query tối đa 120 ký tự,
  tối đa 30 category filter, page dương và page-size 10–100. Query database lấy tối đa
  `pageSize + 1` GTIN để trả `hasMore`; frontend không nhận toàn bộ Znack catalog.
- Catalog DTO chỉ gồm GTIN chuẩn hoá, product/category đã giới hạn, số KIZ available/reserved/
  consumed, số mapping rule, trạng thái order/pipeline đã allowlist, lỗi đã redaction và thời điểm
  sync. Raw KIZ, Znack response, certificate/token, SQL/stack trace và object repository không qua
  bridge.
- `kizMapping.editor` trả tối đa 500 subject KIZ hiện có, tối đa 100 gender/subject, current rule và
  owner GTIN cho mỗi lựa chọn. Technical GTIN `029…`, subject/gender không thuộc catalog, duplicate,
  quota vượt giới hạn hoặc shop/GTIN mismatch phải bị từ chối tại Java boundary.
- `kizMapping.save` là local mutation riêng với capability `kiz-mapping:write`; request tối đa 500
  rule. Java re-resolve shop, registered GTIN, subject/gender và owner conflict trước khi gọi một
  transaction `BEGIN IMMEDIATE` thay toàn bộ rule của GTIN. Empty selection có nghĩa clear mapping;
  response trả editor/catalog summary mới, không echo dữ liệu không tin cậy.
- React giữ parity search/category/page, inventory/status/error state và editor ba vùng
  subject → gender → selected rules. Wildcard “mọi giới tính” và exact gender loại trừ nhau; lựa chọn
  đang thuộc GTIN khác vẫn hiển thị nhưng disabled, và save/error/cancel có trạng thái rõ ràng.
- Sync sản phẩm, buy KIZ, retry introduction và CryptoPro là remote/mutation workflow riêng trong
  các increment Phase 7 tiếp theo. Native test của editor chỉ ghi isolated app-data; live database
  chỉ được smoke read-only nếu chưa có approval mutation riêng.

### Znack settings and product contract

- `znack.settings` chỉ trả các field người dùng được phép chỉnh (`omsId`, `omsConnection`, default
  goods document, auto-introduction), trạng thái chữ ký allowlist và label/ngày hết hạn certificate
  đã sanitize. API hosts, executable path/arguments, certificate selector/thumbprint/metadata JSON,
  participant identity, token, signature và raw error không qua bridge.
- Settings response mang version SHA-256 opaque tính ở Java từ toàn bộ persisted settings.
  `znack.saveSettings` yêu cầu shop hiện hữu, version hiện hành, OMS fields hợp lệ và goods-document
  đầy đủ với ngày `dd.MM.yyyy`; Java merge trên snapshot mới nhất để giữ nguyên mọi field private.
  Stale version bị từ chối trước mutation và command dùng capability `znack:configure` riêng.
- `znack.products` đọc local SQLite theo page, tối đa `pageSize + 1`, query tối đa 120 ký tự, tối đa
  30 category filter và page-size 10–100. Active/deleted là hai tập riêng; DTO không chứa raw KIZ,
  certificate fields, pipeline payload, deletion timestamp hay remote response.
- `znack.setProductVisibility` nhận tối đa 100 production GTIN, re-resolve toàn bộ ownership và
  expected active/deleted state trong một `BEGIN IMMEDIATE` transaction rồi mới ẩn/khôi phục và ghi
  audit log. Partial/stale/cross-shop batch phải rollback; permanent purge là command riêng ở
  increment sau.
- `znack.discoverCertificates` re-resolve shop/settings ở Java, chạy CryptoPro ngoài UI thread và
  trả tối đa 100 certificate summary đã sanitize. Mỗi certificate dùng UUID opaque, shop-scoped,
  single-use discovery session TTL 10 phút; selector, thumbprint, subject/issuer đầy đủ, raw output,
  provider path và executable diagnostics không qua bridge. Summary chỉ gồm owner label, INN,
  validity dates, private-key/selectable flags và trạng thái allowlist.
- `znack.testCertificate` yêu cầu discovery session/certificate UUID, settings version hiện hành và
  capability `znack:certificate`. Java re-resolve opaque ID, expiry/private-key/settings, ký payload
  test bằng selector nội bộ rồi mới atomically lưu selection/metadata/test timestamp cùng audit.
  Failed/cancelled/stale tests không thay settings; public error chỉ dùng kind allowlist. Command có
  timeout 10 phút vì CryptoPro có thể mở PIN prompt.
- `znack.startProductSync`, `znack.productSyncStatus` và `znack.cancelProductSync` dùng capability
  `znack:sync`, UUID job shop-scoped và tối đa một job đang chạy trên mỗi shop. Worker re-resolve
  verified settings, signer và participant API hoàn toàn ở Java; response chỉ có state/phase/count,
  completion time và safe error kind. Cancel là cooperative: phải ngăn mutation nếu nhận trước khi
  sync bắt đầu, interrupt network wait khi có thể, nhưng không hứa rollback các batch local đã commit
  atomically bởi legacy sync. Completed/failed/cancelled job giữ bounded trong bộ nhớ và retry tạo job
  mới; token, API response, participant identity và raw error không qua bridge.
- `znack.preparePurchase` chỉ đọc local state, yêu cầu production GTIN đang active, quantity 1–10.000,
  settings version hiện hành, verified certificate và không có pipeline active. Response là preview
  shop-scoped TTL 10 phút với UUID purchase opaque, tên/GTIN/quantity, auto-introduction flag và
  warning allowlist; không gọi Znack và không tạo đơn.
- `znack.startPurchase` dùng capability `znack:purchase`, yêu cầu preview UUID còn hạn, version hiện
  hành và xác nhận explicit từ dialog. Purchase UUID được persist thành unique idempotency key trước
  khi worker gọi remote; replay cùng UUID trả cùng pipeline, kể cả sau restart, và không thể tạo đơn
  thứ hai. Worker re-resolve settings/product trước mutation. `CREATING_ORDER` sau kết quả mơ hồ là
  terminal-manual state: tuyệt đối không auto retry vì có thể đã tính phí.
- `znack.purchases`, `znack.purchaseStatus` và `znack.retryIntroduction` là shop-scoped, bounded và
  dùng purchase UUID opaque. DTO chỉ trả product label, GTIN, quantity, stage/state allowlist, aggregate
  downloaded count, timestamp, safe error kind/retry flags. Retry introduction chỉ tái dùng mã đã tải
  của pipeline `INTRODUCTION_FAILED`, không mua lại mã; command re-resolve verified settings và chặn
  concurrent pipeline. Progress event chỉ advisory, polling persisted status là authoritative.
- `znack.operationLogs` phân trang local journal theo shop và trả action/severity/message đã sanitize,
  HTTP class allowlist cùng timestamp. Local DB id, external order/document id, raw KIZ, signature,
  participant/token, request/response/payload, SQL/stack trace và raw upstream error không qua bridge.
- React phải có loading/empty/error/retry, bounded search/category/page, dirty/stale settings state,
  explicit save, certificate discover/select/test, resumable sync status/cancel, reversible
  hide/restore feedback, purchase preview/confirmation, persisted progress, introduction retry và
  bounded audit journal. Thay shop phải vô hiệu response/session/job cũ. Live native CryptoPro/remote
  sync/purchase/introduction chỉ chạy khi có approval; unavailable/seeded local states có thể smoke
  trên isolated app-data.

### License contract

- Giữ nguyên `LicenseService`, Ed25519 public key, server channel, offline grace 14 ngày và clock
  rollback policy hiện tại; jDesk chỉ thêm adapter/UI, không đổi pricing hay cơ chế cấp phép.
- `license.status` chỉ đọc state trong process. DTO allowlist gồm status, `kizAllowed`, cờ có key
  đã lưu, plan label đã giới hạn, issued/expiry/grace timestamp, số ngày còn lại và safe error kind;
  license key, fingerprint/device name, signed payload/signature, max-device detail, file path,
  server URL và raw exception không qua bridge.
- `license.refresh` gọi oracle hiện tại trên background invocation thread. `license.activate` nhận
  key định dạng `WC-XXXXX-XXXXX-XXXXX-XXXXX`, normalize uppercase nhưng không echo key trong success,
  failure, event hay log. Expected failure chỉ trả error kind allowlist (`invalid_license`,
  `device_limit`, `network`, `unavailable`).
- `license.deactivate` yêu cầu capability `license:manage` và boolean confirmation từ dialog riêng;
  giữ semantics legacy best-effort remote rồi xóa key/license file cục bộ. UI phải nói rõ khi offline,
  slot trên server có thể cần được gỡ sau; response không trả key/fingerprint.
- Settings dialog React phải có loading/error/retry, tám trạng thái license, expiry/offline-grace
  copy, activation form, manual refresh và explicit deactivate confirmation. Runtime validator từ
  chối enum/timestamp/plan/error bất thường trước khi render. Đổi shop không ảnh hưởng license vì
  license thuộc device/app, không thuộc shop.
- Live activation/deactivation chỉ chạy với approval và test artifact riêng. Native evidence mặc
  định dùng app-data cô lập chưa có key và không gọi license server.

### Language and theme contract

- Reuse legacy `app_language` and `app_theme` rows; do not introduce browser-only preference
  storage. Supported languages stay exactly `ru`, `en`, `zh`, `vi`. Missing/corrupt language
  normalizes to Russian, matching `AppLanguage.fromCode`; missing/corrupt theme normalizes to dark,
  matching the JavaFX default.
- Add `system` as a jDesk theme mode beside `dark` and `light`. Persist the mode, not the currently
  resolved OS color scheme. JavaFX rollback safely treats the unknown `system` value as its existing
  dark fallback and does not rewrite it unless the user explicitly changes theme there.
- `preferences.load`, `preferences.setLanguage` and `preferences.setTheme` expose only the two
  allowlisted enum values. Writes require a dedicated capability, reject null/unknown/oversized
  input, serialize concurrent mutations and never return arbitrary `app_config` keys or values.
- Apply `lang` and `data-theme` at the document root. Dark is the pre-bootstrap fallback to avoid a
  light flash and preserve the legacy default; system follows `prefers-color-scheme` without a Java
  callback. Theme tokens must retain focus, text, border, success/warning/danger contrast.
- Language control is not considered parity until every reachable React journey uses translated
  copy. Migration may land incrementally, but parity/evidence must name the translated surfaces and
  the UI must not claim full-language completion while hard-coded Russian remains.
- Feature copy uses the same allowlisted language oracle as the shell, with typed complete
  RU/EN/VI/ZH dictionaries and locale-aware number/date formatting. Shared guarded dialogs accept
  an explicit copy contract so the invoking journey changes language without weakening preview,
  confirmation, receipt validation or capability gates; Russian remains the rollback/default copy.

### User-centred desktop UX completion contract

- Visual language dùng bảng màu tím riêng của WCode, mật độ cao và nhịp phân cấp gần trải nghiệm
  marketplace Wildberries; không sao chép logo, hình ảnh hay trade dress. Shell desktop có sidebar
  gọn, content padding 12–20 px theo viewport, card gap 8–12 px, body text 12–14 px và heading không
  lấn át dữ liệu nghiệp vụ. Light/dark/system đều giữ contrast AA, focus rõ và reduced motion.
- Icon-only button chỉ dùng cho hành động quen thuộc như đóng, tải lại, chỉnh sửa hoặc menu; bắt buộc
  có accessible name, tooltip không thay thế accessible name, vùng bấm tối thiểu 32×32 px và trạng
  thái focus/disabled rõ. Hành động nguy hiểm, trả phí, đưa hàng vào lưu thông hoặc khó đảo ngược vẫn
  dùng nhãn chữ rõ ràng và dialog xác nhận; không đổi logic preview/confirmation/idempotency hiện có.
- Màn hình không hiển thị thuật ngữ triển khai như jDesk, JavaFX, WebView, Java bridge, selector,
  thumbprint, DTO, capability hay giới hạn nội bộ. Nội dung chính chỉ nói người dùng cần biết để hoàn
  tất công việc hoặc tự khắc phục. Thông tin phiên bản kỹ thuật chỉ được nằm trong vùng hỗ trợ chi
  tiết theo hành động chủ động, không xuất hiện ở shell, empty state, toast hay hướng dẫn thường ngày.
- Dùng một bộ primitive chung cho modal, toast, loading, empty và error state. Modal giữ focus,
  đóng bằng Escape khi an toàn và trả focus về trigger; toast dùng live region phù hợp, tự hết hạn
  chỉ với thông báo không cần hành động và không chứa raw exception. Loading không làm nội dung cũ
  biến mất nếu người dùng vẫn có thể đọc/làm việc; mọi async action chống click lặp và giữ state rõ.
- Danh sách dài tải tiếp khi sentinel đi vào viewport bằng `IntersectionObserver`, trong khi Java
  vẫn là authority cho `page`, `pageSize`, `hasMore`/`totalPages` bounded. React chỉ nối các trang
  liên tiếp, deduplicate theo stable ID, chỉ cho một request tải tiếp tại một thời điểm và bỏ response
  lỗi thời sau khi shop/query/filter thay đổi. Cuộn không bao giờ gọi command mutation.
- Mỗi infinite list có nút “Tải thêm” dự phòng dùng được bằng bàn phím/trình đọc màn hình và live
  announcement cho số mục mới. Table vẫn dùng semantic table; card feed chỉ dùng ARIA feed/article
  khi có lợi và đã kiểm chứng trên WebView2. Kết thúc danh sách, lỗi tải trang sau và retry đều được
  thông báo mà không xóa các mục đã tải. Vị trí cuộn và lựa chọn theo SKU/order được giữ khi append.
- Responsive được kiểm chứng ở 320, 768, 1024 và 1440 px: không có horizontal overflow ngoài vùng
  dữ liệu chủ ý, sidebar chuyển sang drawer/compact navigation, action bar wrap có kiểm soát và mọi
  chức năng vẫn truy cập được bằng bàn phím. Không dùng chuỗi nút nhiều chữ làm vỡ layout.
- Ngân sách ban đầu: JS entry gzip không vượt 220 KiB, CSS gzip không vượt 30 KiB, không thêm runtime
  dependency nếu platform/stack hiện có giải quyết được, không có long task >50 ms trong thao tác
  tải thêm mẫu và không render trước dữ liệu chưa được yêu cầu. Nếu danh sách thực tế vượt ngưỡng
  mượt, phải đo trước rồi mới thêm windowing; không làm đổi contract phân trang Java.

## Tech Stack

- Java 25, jDesk `0.1.3`, Gradle wrapper `9.6.1`.
- React `19.2.x`, React DOM `19.2.x`, TypeScript, Vite `7.x`.
- Tailwind CSS `4.x` qua `@tailwindcss/vite`.
- jDesk generated TypeScript bindings + `jdesk-client` `0.1.3`; bindings không thay thế runtime
  validation tại Java boundary.
- Vitest + Testing Library cho frontend; JUnit 5 cho Java command/domain.
- SQLite và các integration Java hiện có của WCode.

Version runtime phải pin/lock. Nâng jDesk chỉ được làm trong task riêng có changelog, test và
native smoke evidence.

## Commands

Trong thời gian migration, cả hai build đều phải chạy:

```bash
# Legacy JavaFX baseline / CI hiện tại
./mvnw -B verify

# jDesk foundation
./gradlew clean test jdeskFrontendBuild
npm --prefix ui run lint
npm --prefix ui run typecheck
npm --prefix ui test -- --run
npm --prefix ui run build

# Development và native verification
./gradlew jdeskDoctor
./gradlew jdeskDev
./gradlew jdeskNativeSmokeTest
./gradlew wcodePackage
```

Live WB test chỉ đọc dữ liệu seller từ WB và ghi cache/local DB. Không tạo supply, deliver,
đổi metadata hoặc mutation seller-state nếu không có một test case được người dùng phê duyệt.

## Project Structure

```text
src/main/java/                  Legacy core + JavaFX trong thời gian migration
src/main/resources/             SQLite/i18n/assets hiện có
src/jdesk/java/                 jDesk composition root và command adapters
src/jdesk/resources/            capabilities/CSP resources
src/jdeskTest/java/             JUnit tests cho bridge/command mới
ui/                             React/TypeScript/Tailwind frontend
ui/src/components/              Component trình bày nhỏ, có test colocated
ui/src/features/                Vertical feature slices
ui/src/generated/               TypeScript bindings do jdesk-codegen sinh
ui/src/lib/                     Bridge/runtime helpers dùng chung
docs/jdesk-migration/           Research, spec, plan, parity/evidence
docs/decisions/                 ADR kiến trúc
```

Không di chuyển hàng loạt Java core ở phase foundation. Khi một slice ổn định, tách JavaFX ra
khỏi service của slice đó để danh sách exclude trong Gradle thu nhỏ dần.

## Code Style

Java command là adapter mỏng, validate ở boundary, không chứa UI state và không trả secret:

```java
@DesktopCommand("dashboard.load")
@RequiresCapability("dashboard:read")
public CompletionStage<DashboardResponse> load(
        DashboardRequest request, InvocationContext context) {
    int shopId = requirePositiveShopId(request.shopId());
    return CompletableFuture.completedFuture(toResponse(loadForShop(shopId)));
}
```

React component dùng named export, semantic elements, explicit async state và Tailwind token:

```tsx
export function ShopPicker({ shops, selectedId, onSelect }: ShopPickerProps) {
  return (
    <label className="grid gap-1.5 text-sm text-ink-muted">
      Cửa hàng
      <select value={selectedId ?? ""} onChange={event => onSelect(Number(event.target.value))}>
        {shops.map(shop => <option key={shop.id} value={shop.id}>{shop.name}</option>)}
      </select>
    </label>
  );
}
```

Quy ước:

- Java: package hiện có, final khi phù hợp, record cho DTO bridge, không raw `Object`.
- TypeScript strict, `.tsx` cho JSX, named exports, không `any` ở boundary.
- Command/event name theo `area.action`; capability theo `area:action`.
- User-facing text phải đi qua catalog i18n; tiếng Nga là locale sản phẩm mặc định, có EN/VI/ZH.
- Không `dangerouslySetInnerHTML`, inline script, eval hoặc log secret/token. Token form chỉ gửi
  một lần React → Java qua capability write-only, rồi xóa input; Java không trả token về frontend.

## Testing Strategy

### Small tests

- Domain/repository hiện có tiếp tục chạy qua Maven JUnit.
- Command adapter test input validation, DTO sanitization và delegation bằng fake/real repository
  trên SQLite temp.
- React component/hook test loading, success, empty, error và keyboard behavior.
- Infinite-scroll test phải cover observer/fallback, append tuần tự, request trùng, response lỗi thời,
  filter reset, lỗi trang sau và end-of-list; test không được khẳng định chi tiết triển khai observer.

### Medium tests

- Contract test compile bindings và TypeScript typecheck.
- Integration test jDesk command với database temp.
- WB live smoke test trên shop có sẵn: token validation, product/supply/order read sync và local
  KPI update; tuyệt đối không log token.

### Large/native tests

- jDesk automation chạy WebView thật: bootstrap, chọn shop, refresh dashboard, navigation,
  console sạch và snapshot.
- Kiểm thử native Windows là release gate cho print/dialog/CryptoPro/update.
- Breakpoint UI: 320, 768, 1024, 1440 px; WCAG 2.1 AA, tab order và visible focus.
- Browser evidence cho UI mới gồm before/after screenshot, console sạch, accessible names, không
  horizontal overflow và trace tải thêm có zero long task >50 ms trên fixture đại diện.

Không snapshot toàn trang để thay thế behavior assertion. Test bug phải fail trước khi fix.

## Boundaries

### Always do

- Giữ JavaFX chạy được cho đến khi slice thay thế có parity evidence.
- Dùng shop/database hiện có cho live test nhưng không in/log/trả API key qua bridge.
- Lấy shared single-instance lock trước khi mở/migrate live database; không chạy JavaFX và jDesk
  đồng thời trên cùng app-data directory.
- Capability deny-by-default và grant tối thiểu theo cửa sổ.
- Chỉ bundled trusted content được chạy trong window có bridge; chặn redirect/navigation khác
  origin và mở external URL bằng system browser không có capability.
- Validate input từ React và response từ WB tại boundary.
- Map mọi bridge exception về allowlisted safe error code/message/correlation id; không serialize
  raw throwable, SQL, upstream body hoặc stack trace.
- Snapshot SQLite/WAL nhất quán, checksum và verify trước mỗi schema-changing migration/canary
  writer version; không chỉ backup một lần ở first launch.
- `PRAGMA user_version` là schema revision authority và chỉ được publish sau khi toàn bộ idempotent
  DDL/data migration hoàn tất. Ready marker phải pin cả writer version, data-migration id, schema
  revision và verified snapshot SHA; marker cũ hoặc database revision thấp hơn luôn tạo rollback
  snapshot mới trước bootstrap. Database revision cao hơn binary phải fail closed mà không chạy DDL,
  tạo snapshot mới hoặc sửa marker; mọi schema change tương lai bắt buộc tăng revision.
- Migration fail phải fail-closed và restore được bằng recovery CLI ngoài normal app bootstrap;
  installed image phải có secondary `WCode-Recovery` launcher. Ready marker có SHA chỉ hợp lệ nếu
  còn ít nhất một snapshot cùng SHA verify được. Retention giữ toàn bộ cửa sổ rollback 30 ngày,
  hai verified fallback mới nhất mỗi reason và snapshot được marker tham chiếu; entry lạ/corrupt
  được giữ làm forensic evidence thay vì tự xóa.
  credential dual-write dùng legacy source-of-truth + version/fingerprint reconciliation cho đến
  khi rollback window kết thúc.
- Chạy legacy verify và các gate jDesk/frontend liên quan sau mỗi increment.
- Mỗi task thay đổi tối đa khoảng 5 file logic; generated lock/wrapper files không tính là logic.

### Ask first

- Mutation seller-state trên Wildberries/Znack, mua KIZ thật hoặc deliver supply.
- Xóa/cột database hay di chuyển API key sau khi migration secret store.
- Thay cơ chế license, update channel, release signing hoặc pricing.
- Xóa JavaFX/release workflow cũ trước khi parity matrix đạt 100%.

### Never do

- Commit/log/expose WB key, license secret, signature material hoặc full stack trace ra frontend.
- Chạy remote content trong app window hoặc nới CSP bằng `unsafe-eval`.
- Gọi WB/Znack trên UI thread.
- Dùng mock-only test để tuyên bố live integration hoàn thành.
- Xóa test thất bại, disable security control hoặc ghi đè database người dùng để làm test pass.

## Success Criteria

- [ ] Tất cả user journey bắt buộc có implementation React/jDesk và parity evidence.
- [ ] jDesk chỉ trở thành entry point mặc định khi parity matrix của mọi journey bắt buộc đạt 100%;
  mức ưu tiên critical/non-critical không được nới gate này.
- [ ] Không còn JavaFX/FXML/MaterialFX/Ikonli dependency hoặc runtime code trong production build.
- [ ] Legacy database mở/migrate không mất dữ liệu; 8 shop hiện có đọc được và API key không qua UI.
- [ ] Live WB smoke trên ít nhất một shop có Content + Marketplace permission pass.
- [ ] FBS/FBO/KIZ/Znack/license/update flows có unit/integration/native tests tương xứng rủi ro.
- [ ] Frontend lint, strict typecheck, test và production build pass.
- [ ] Java test, binding generation, jDesk doctor, native smoke và package pass.
- [ ] Windows EXE/MSI/portable cài, mở, nâng cấp, in và rollback được trên máy thật; first-cutover
  rollback về JavaFX và các lần sau về jDesk N-1 đều giữ nguyên dữ liệu.
- [ ] Fault-injection chứng minh unexpected command exception chỉ trả safe envelope; external
  navigation không bao giờ kế thừa bridge capability.
- [ ] Canary đi qua internal → 10% → 50% → 100%, mỗi cohort tối thiểu 7 ngày/20 cold launches/2
  máy Windows, zero critical/high hoặc data/credential/secret incident; rollback window chỉ đóng
  sau 30 ngày ổn định ở 100% và rehearsal cuối.
- [ ] UI không có console error, dùng bàn phím được, responsive và đạt WCAG 2.1 AA.
- [ ] Mọi danh sách dài dùng bounded infinite scroll + fallback, không còn chuyển trang thay thế
  nội dung; append giữ selection/scroll và không kích hoạt mutation.
- [ ] Shell/màn hình nghiệp vụ dùng compact purple tokens và primitive modal/toast/loading/empty
  thống nhất; shell và copy thường ngày không lộ thuật ngữ triển khai.
- [ ] JS/CSS budget và trace tải thêm đạt ngưỡng trong UX contract ở cả light/dark; không có overflow
  ngoài vùng dữ liệu chủ ý tại 320/768/1024/1440 px.
- [ ] Không critical/high vulnerability reachable; lockfile/SBOM/checksums được tạo.
- [ ] README, migration guide, ADR, parity matrix, operations/runbook và release notes đầy đủ.

## Open Questions

Không có câu hỏi chặn foundation. Các quyết định cần người dùng xác nhận ở phase sau đã được đặt
trong mục “Ask first”; mặc định không thực hiện mutation/destructive action.
