# W4A — Rút hồ sơ/phần vốn trước chữ ký

**Owner:** Thái (Loan/Mobile), Hải review phần Investment

**Trạng thái:** READY_FOR_REVIEW
**Phạm vi:** Loan, Investment ở ranh giới hủy lệnh/event, Mobile; không đổi AI scoring, Payment ledger hay chính sách sau chữ ký.

## Quy tắc nghiệp vụ đã chốt

- Người vay được rút hồ sơ khi hồ sơ chưa có quyết định; nếu hồ sơ đã duyệt và đang gọi vốn thì được hủy đợt gọi vốn trước khi hợp đồng có hiệu lực.
- Nhà đầu tư được hủy riêng lệnh góp vốn khi listing còn `OPEN`.
- Khi allocation đã khóa để lập hợp đồng, không sửa riêng allocation. Nhà đầu tư chưa ký có thể từ chối ký; toàn bộ hợp đồng/đợt gọi vốn bị hủy và các khoản giữ được nhả theo worker hiện có. **SUPERSEDED 2026-11-08 bởi FUNDING-REOPEN-001:** nhà đầu tư chưa ký từ chối chỉ nhả phần của họ, khoản vay quay lại sàn gọi vốn bổ sung, bên đã ký giữ nguyên.
- Bên đã có chữ ký `SIGNED` không được rút đơn phương bằng các API trên.
- Hủy/từ chối phải idempotent, có history/outbox và không gọi Payment trong transaction Investment.

## Thay đổi

- [x] Loan: đồng bộ các trạng thái rút hồ sơ trước quyết định và ghi đúng `fromStatus`.
- [x] Loan/Investment: hủy đợt gọi vốn `REQUESTED` bằng `LoanFundingCancelled.v1` mở rộng tương thích ngược.
- [x] Loan: API nhà đầu tư từ chối hợp đồng trước chữ ký, đóng Contract và phát bù trừ hiện có.
- [x] Investment: trả thông tin lệnh/listing trong commitment để Mobile hủy lệnh đang mở.
- [x] Mobile: hiển thị rút hồ sơ, rút lệnh góp vốn và từ chối ký theo đúng trạng thái; không dùng UI cũ.
- [x] Test Loan/Investment/Mobile và cập nhật event, luật liên service, hướng dẫn UI.
- [x] B44: chỉ cập nhật tiến độ 8 test payload/order cũ; 2 ca chính sách CIC vẫn chờ owner Hải, không tự đổi scoring.

## Chốt an toàn

- Không sửa migration đã áp dụng; Loan dùng migration mới V33.
- Không phát lệnh hoàn tiền trực tiếp từ Loan. Investment hủy commitment/order, worker nhả hold idempotently.
- Event producer/consumer và UI phải được triển khai cùng đợt; consumer chấp nhận cả payload cancellation đã khóa allocation và payload borrower rút khi listing còn mở.
- Task chỉ chuyển `READY_FOR_REVIEW` sau khi test liên quan chạy đạt; phần Investment cần Hải review trước merge/push chung.

## Xác minh 31/10/2026

- Loan: build Docker đạt; 32 unit test miền rút/từ chối ký đạt.
- Investment: build Docker đạt; `FundingFlowIT` đạt trên PostgreSQL Testcontainers, gồm nhánh listing `OPEN` và race sang `FULLY_FUNDED`.
- Notification: build Docker đạt; 19 test `NotificationFlowIT` đạt trên PostgreSQL Testcontainers.
- Mobile: `npx tsc --noEmit` đạt; không sửa `finora-web` vì không có màn hình người vay/nhà đầu tư thuộc thay đổi này.
- `git diff --check` đạt ở cả Platform, Mobile và Web.
