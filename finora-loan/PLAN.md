# Kế hoạch triển khai FINORA Loan Service

## 1. Thông tin điều phối

- **Owner:** Thái
- **Module:** `finora-loan`
- **Tài liệu thiết kế tổng thể duy nhất:** [`plans/LOAN-SERVICE-DESIGN.md`](plans/LOAN-SERVICE-DESIGN.md)
- **Roadmap nhóm:** [`../.agents/plans/finora-team-roadmap.md`](../.agents/plans/finora-team-roadmap.md)
- **Ranh giới service:** [`../.agents/rules/07-service-boundaries.md`](../.agents/rules/07-service-boundaries.md)
- **Luồng liên service:** [`../.agents/rules/08-cross-service-flows.md`](../.agents/rules/08-cross-service-flows.md)
- **Câu hỏi contract gửi Hải:** [`plans/AI-CREDIT-CONTRACT-QUESTIONS-FOR-HAI.md`](plans/AI-CREDIT-CONTRACT-QUESTIONS-FOR-HAI.md)

File này **chỉ dùng để điều phối**: task, dependency, trạng thái, thứ tự thực hiện và cổng nghiệm thu. Nghiệp vụ xuyên suốt nằm trong Design; field/API/migration/index/test chi tiết nằm trong file LN tương ứng.

Mọi LN hiện tại và LN tạo mới phải theo [`Cấu trúc chuẩn tài liệu LN`](plans/LN-DOCUMENT-STANDARD.md): lớp nghiệp vụ dễ đọc đặt trước lớp thiết kế kỹ thuật.

## 2. Quyền chuyển trạng thái

```text
BACKLOG -> DRAFT --(Thái duyệt)--> APPROVED --(bắt đầu code)--> IN_PROGRESS
IN_PROGRESS --(code + test xong)--> READY_FOR_REVIEW
READY_FOR_REVIEW --(Thái nghiệm thu)--> ACCEPTED
READY_FOR_REVIEW --(Thái yêu cầu sửa)--> CHANGES_REQUESTED
ACCEPTED --(quyết định mới thay thế)--> SUPERSEDED
```

- Agent được tạo `DRAFT`, bắt đầu task đã `APPROVED` và bàn giao ở `READY_FOR_REVIEW`.
- Chỉ Thái chuyển task Loan sang `APPROVED` hoặc `ACCEPTED`.
- `SUPERSEDED` chỉ dành cho task từng được nghiệm thu nhưng đã có task thay thế được ghi rõ; không đồng nghĩa xóa lịch sử.
- `BLOCKED` chỉ dùng khi task đang làm gặp trở ngại thực tế; dependency chưa sẵn sàng giữ `BACKLOG`.
- Mỗi thời điểm chỉ một task Loan `IN_PROGRESS`, trừ khi Thái cho phép rõ ràng.
- Task liên service chỉ được duyệt khi contract đã chốt hoặc LN ghi rõ adapter mock và điểm thay thế.

## 3. Danh sách task

| Task | Nội dung | Phụ thuộc chính | Trạng thái | Đặc tả |
|---|---|---|---|---|
| LN-001 | Baseline MySQL lịch sử | Không | `SUPERSEDED` | [LN-001](plans/LN-001-loan-foundation.md) |
| LN-001A | Chuyển Loan sang PostgreSQL 17/Neon | LN-001; quyết định 2026-08-03 | `IN_PROGRESS` | [LN-001A](plans/LN-001A-postgresql-neon-migration.md) |
| LN-002 | JWT/phân quyền thật; error/log/observability baseline | Identity contract do Hải phụ trách | `BACKLOG` | [LN-002](plans/LN-002-security-error-observability.md) |
| LN-003 | Product fixed rate, repayment method, Fineract mapping | LN-001A được `ACCEPTED`; Thái duyệt LN | `IN_PROGRESS` | [LN-003](plans/LN-003-loan-product.md) |
| LN-004 | Direct-submit Application và snapshots | LN-003, LN-006 | `IN_PROGRESS` | [LN-004](plans/LN-004-loan-application.md) |
| LN-005 | Borrower profile/KYC provider | LN-004; User contract hoặc mock provider | `REVIEW` | [LN-005](plans/LN-005-borrower-profile-kyc.md) |
| LN-006 | Fineract Product/Schedule adapter | LN-003; Fineract fixture | `IN_PROGRESS` | [LN-006](plans/LN-006-fineract-product-schedule-integration.md) |
| LN-007 | Internal credit profile và AI v10 assessment | LN-004, LN-005, LN-006; AI fixture | `REVIEW` | [LN-007](plans/LN-007-credit-profile-ai-assessment.md) |
| LN-008 | Admin decision, LoanContract, borrower signature | LN-007; consent policy | `BACKLOG` | [LN-008](plans/LN-008-approval-loan-contract.md) |
| LN-009 | Market listing và funding outbox | Contract `SIGNED`; Investment contract | `BACKLOG` | [LN-009](plans/LN-009-market-listing-outbox.md) |
| LN-010 | Funding completion consumer | LN-009; Investment contract | `BACKLOG` | [LN-010](plans/LN-010-fully-funded-consumer.md) |
| LN-011 | Disbursement saga và Fineract loan booking | LN-010; Payment/Fineract contract | `BACKLOG` | [LN-011](plans/LN-011-disbursement-fineract-booking-saga.md) |
| LN-012 | Fineract servicing projection và reconciliation | LN-011; event/reconcile policy | `BACKLOG` | [LN-012](plans/LN-012-fineract-servicing-reconciliation.md) |
| LN-013 | Repayment/read schedule API | LN-011, LN-012; Payment contract | `BACKLOG` | [LN-013](plans/LN-013-repayment-schedule-boundary.md) |
| LN-014 | Delinquency/default và credit profile update | LN-012, LN-013; DPD policy | `BACKLOG` | [LN-014](plans/LN-014-delinquency-credit-profile.md) |
| LN-015 | Early settlement | LN-013, LN-014 | `BACKLOG` | Chưa tạo |
| LN-016 | Restructuring | LN-013, LN-014; consent policy | `BACKLOG` | Chưa tạo |
| LN-017 | NPL policy/dashboard | LN-013, LN-014 | `BACKLOG` | Chưa tạo |
| LN-018 | SmartCA adapter | LN-008; sandbox/contract | `BACKLOG` | Chưa tạo |

Mỗi task chỉ có một file hiện hành. Implementation Product/Application thử nghiệm trước đây không còn là task riêng; LN-003 và LN-004 đã chứa toàn bộ thiết kế mới nhất.

## 4. Thứ tự triển khai hiện hành

```text
LN-003 Product domain/mapping
    -> LN-006 Fineract product + schedule adapter
    -> LN-004 direct-submit Application
    -> LN-005 borrower profile/KYC
    -> LN-007 credit profile + AI assessment
    -> LN-008 admin decision + Contract/signature
    -> LN-009...LN-018 funding, disbursement, servicing và hardening
```

Có thể dùng provider mock để LN-005 không chờ User Service. Tuy nhiên phần contract thật phải được Hải rà soát trước tích hợp môi trường dùng chung.

## 5. Cổng bắt đầu một LN

Một LN chỉ được Thái duyệt khi có:

- lớp đọc nghiệp vụ theo `LN-DOCUMENT-STANDARD.md`: actor, màn hình/kết quả nhìn thấy, luồng chính, ví dụ, lỗi và ý nghĩa dữ liệu bằng ngôn ngữ đời thường;
- mục tiêu, phạm vi và phần không làm;
- luồng nghiệp vụ và điều kiện chuyển trạng thái;
- field/type/source/use đối với dữ liệu mới;
- API/event/request/response nếu có;
- migration, constraint, index và query mà index phục vụ;
- transaction boundary, idempotency, timeout, retry/reconcile;
- cách chống N+1, log/audit và bảo vệ dữ liệu nhạy cảm;
- file dự kiến thay đổi, test plan và acceptance criteria;
- dependency ngoài service được xác nhận hoặc đánh dấu mock/chờ contract;
- bảng field quan trọng tách ý nghĩa nghiệp vụ khỏi kiểu Java/PostgreSQL; không dùng mô tả kỹ thuật như “khóa/truy vết” để thay cho tác dụng trong hệ thống;
- `status: APPROVED`, `approved_by: Thai` và `approved_at`.

Nếu thay đổi vượt đáng kể scope đã duyệt, phải thêm Plan Amendment và chờ Thái duyệt.

## 6. Cổng bàn giao và đánh dấu hoàn thành

Chỉ chuyển `READY_FOR_REVIEW` khi LN đã ghi:

- file thực tế thêm/sửa/xóa;
- migration đã chạy và cách rollback/forward-fix;
- lệnh kiểm tra, exit code và kết quả test;
- bằng chứng từng acceptance criterion;
- kiểm tra N+1/hiệu năng tương ứng rủi ro;
- known limitation và nợ kỹ thuật;
- hướng dẫn Thái chạy lại bằng IntelliJ/Docker/Postman;
- contract/roadmap/rule đã đồng bộ nếu có thay đổi liên service.

Chỉ Thái đánh `ACCEPTED` sau khi đọc code, chạy thử và nghiệm thu. Khi đó cập nhật đồng thời:

1. Trạng thái task trong file này.
2. Phần thực thi/kết quả trong LN tương ứng.
3. Design nếu có quyết định xuyên suốt thay đổi.
4. Roadmap/rule chung nếu ảnh hưởng Hải hoặc service khác.

## 7. Điểm chặn hiện tại

- LN-003/LN-006/LN-004 đang triển khai theo phê duyệt của Thái; compile, unit test và PostgreSQL Testcontainers `verify` đã pass.
- Fineract 1.15.0/PostgreSQL 18.3 local đã healthy; live test xác nhận Product được tạo nhưng response
  đầu tiên bị timeout. Adapter đã có reconciliation exact external ID và test timeout-after-POST;
  Client preview đã seed idempotent và Fineract đã tính schedule thật thành công; còn restart Loan
  để nghiệm thu Product recovery + schedule qua endpoint FINORA.
- Migration schema từ PostgreSQL database sạch đã pass; Neon chỉ migrate khi chạy Loan bằng credential của Thái, không ghi secret vào repository.
- LN-007 cần Hải xác nhận AI Rule Engine dùng `installment` nhận từ Loan và contract response v10.
- LN-005 dùng local provider trong giai đoạn hiện tại; User contract thật chưa chặn việc phát triển domain.
- LN-002 chưa chặn core Loan, nhưng bắt buộc trước demo tích hợp hoặc deploy có người dùng thật.

## 8. Nhật ký quyết định gần nhất

| Ngày | Phạm vi | Quyết định | Trạng thái |
|---|---|---|---|
| 2026-08-01 | LN-001 | Loan từng dùng MySQL 8.4, Flyway và database riêng | `SUPERSEDED` bởi LN-001A |
| 2026-08-02 | LN-002 | Tạm dùng actor giả lập tập trung; JWT/Keycloak đưa về backlog | Đã chốt |
| 2026-08-02 | LN-003–LN-008 | Bỏ LoanOffer; Contract là bước consent duy nhất; AI v10 không định giá | Chờ duyệt từng LN |
| 2026-08-03 | LN-003/LN-004 | Hợp nhất bản A vào task gốc; chỉ giữ một file mới nhất mỗi LN; V1/V2 cũ không được chạy trên Neon | Đã áp dụng cho tài liệu |
| 2026-08-02 | Tài liệu Loan | Design là nguồn tổng thể duy nhất; PLAN chỉ điều phối; LN giữ chi tiết triển khai | Đã áp dụng |
| 2026-08-03 | LN-001A | Loan/Payment/Blockchain chuyển PostgreSQL 17; mỗi service một Neon Project; Docker là offline/test fallback | `IN_PROGRESS` |
| 2026-08-03 | Toàn bộ LN hiện có | Mỗi LN bắt buộc có lớp đọc nghiệp vụ trước thiết kế kỹ thuật; dùng `LN-DOCUMENT-STANDARD.md` làm cổng duyệt chung | Đã chuẩn hóa LN-001 đến LN-014 |
