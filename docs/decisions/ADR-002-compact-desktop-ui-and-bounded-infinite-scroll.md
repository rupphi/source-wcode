# ADR-002: Compact desktop UI và bounded infinite scroll cho jDesk

## Status

Accepted

## Date

2026-07-19

## Context

Các journey React đã dùng lại WCode Java core, nhưng presentation còn thưa, nhiều nút dài làm vỡ
layout và các list vẫn thay toàn bộ nội dung khi chuyển trang. Một số copy còn lộ chi tiết triển khai
như jDesk, JavaFX, WebView hoặc Java bridge. Người dùng yêu cầu một ứng dụng desktop gọn, tím, mượt,
responsive và đặt công việc của seller lên trước chi tiết kỹ thuật.

List catalog có thể lớn nên không được trả toàn bộ dữ liệu qua bridge. Đồng thời infinite scroll
thuần túy có thể gọi trùng request, mất focus, tăng DOM vô hạn hoặc làm người dùng bàn phím không tới
được nội dung tiếp theo. Quyết định phải giữ Java business logic và command contracts đã kiểm chứng,
không đưa mutation vào hành vi cuộn.

## Decision

1. Giữ Java pagination bounded làm authority. Frontend tích lũy các page liên tiếp và tự tải page
   kế khi sentinel xuất hiện, nhưng chỉ có một request cùng list key đang chạy; response của shop,
   query, filter hoặc generation cũ bị bỏ. Stable ID được dùng để deduplicate khi dữ liệu thay đổi
   giữa hai lần đọc.
2. Mọi list có nút “Tải thêm” dự phòng và live announcement. Table giữ semantic table; chỉ card list
   phù hợp mới cân nhắc ARIA feed/article. Observer là progressive enhancement, không là cách duy
   nhất để truy cập dữ liệu.
3. Không tự window/virtualize trước khi đo. CSS `content-visibility` hoặc windowing chỉ được thêm khi
   trace fixture/live chứng minh bottleneck, kèm test focus/scroll; backend contract không đổi.
4. Dùng semantic compact tokens màu tím riêng của WCode cho light/dark/system. Icon-only action chỉ
   dành cho hành động quen thuộc và luôn có accessible name/tooltip; hành động nguy hiểm, trả phí
   hoặc khó đảo ngược giữ nhãn chữ + confirmation.
5. Dùng primitive presentation chung cho modal, toast, loading, empty/error. Nội dung thường ngày
   diễn đạt kết quả và bước tiếp theo của người dùng; chi tiết runtime chỉ nằm trong hỗ trợ nâng cao.
6. Không thêm runtime dependency cho observer, icon, toast hoặc modal: React 19, Lucide và Web APIs
   hiện có đủ cho lát này. Async transition chỉ dùng sau khi profiling cho thấy nó cải thiện phản hồi;
   không optimistic-update thao tác thanh toán, xóa, deliver hoặc consume KIZ.

## Source basis

- React mô tả transition là cách đánh dấu cập nhật không khẩn cấp và giữ UI phản hồi, nhưng không
  thay thế contract đồng bộ dữ liệu: https://react.dev/reference/react/useTransition
- `IntersectionObserver` cung cấp quan sát giao cắt bất đồng bộ, phù hợp cho sentinel:
  https://developer.mozilla.org/en-US/docs/Web/API/IntersectionObserver
- `aria-live` dùng để thông báo cập nhật động mà không chuyển focus:
  https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-live
- WAI-ARIA feed pattern yêu cầu feed/article/aria-busy nhưng ví dụ cũng cảnh báo khoảng trống hỗ trợ;
  vì vậy chỉ dùng cho card feed phù hợp và luôn giữ fallback:
  https://www.w3.org/WAI/ARIA/apg/patterns/feed/
  https://www.w3.org/WAI/ARIA/apg/patterns/feed/examples/feed/
- Tailwind responsive/state variants là cơ sở cho breakpoint và focus/disabled styles:
  https://tailwindcss.com/docs/responsive-design
  https://tailwindcss.com/docs/hover-focus-and-other-states

## Alternatives Considered

### Trả toàn bộ list từ Java

Rejected: phá giới hạn bridge, tốn bộ nhớ và tăng thời gian render với catalog hàng chục nghìn SKU.

### Thay Java API bằng cursor mới ngay lập tức

Deferred: cursor có lợi khi dữ liệu đổi nhanh, nhưng là thay contract đã có mà không cần thiết cho
UX hiện tại. Stable page + dedupe + stale-response guard giữ rủi ro thấp hơn trong migration.

### Infinite scroll không có nút dự phòng

Rejected: bàn phím/trình đọc màn hình và WebView không hỗ trợ observer vẫn phải truy cập được toàn bộ
dữ liệu; lỗi trang sau cũng cần một retry chủ động.

### Sao chép giao diện/asset Wildberries

Rejected: WCode chỉ học nhịp tím, mật độ và phân cấp marketplace; sao chép thương hiệu không cần
thiết và làm mất bản sắc sản phẩm.

## Consequences

- Người dùng thấy list nối tiếp và ít nút chữ hơn, nhưng backend vẫn giới hạn tải và giữ logic WCode.
- Frontend cần một hook/component có test cho race, retry, filter reset, focus và end-of-list.
- Những list dữ liệu cực lớn có thể cần windowing sau profiling; đó là task hiệu năng riêng.
- JavaFX vẫn được giữ trong rollback window theo ADR-001; hoàn thiện UI không tự động đóng canary gate.
