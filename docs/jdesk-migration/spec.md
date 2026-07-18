# Spec: WCode trên jDesk

Trạng thái: Accepted from user objective; living specification
Ngày: 2026-07-18

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
./gradlew jdeskPackage
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
- Migration fail phải fail-closed và restore được bằng recovery CLI ngoài normal app bootstrap;
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
- [ ] Không critical/high vulnerability reachable; lockfile/SBOM/checksums được tạo.
- [ ] README, migration guide, ADR, parity matrix, operations/runbook và release notes đầy đủ.

## Open Questions

Không có câu hỏi chặn foundation. Các quyết định cần người dùng xác nhận ở phase sau đã được đặt
trong mục “Ask first”; mặc định không thực hiện mutation/destructive action.
