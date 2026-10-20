---
document_type: TASK_PLAN
task_id: LN-016
owner: Thai
reviewers: []
status: DONE
depends_on: [LN-012, LN-014]
updated_at: 2026-10-04
---

# LN-016 — Thu hồi, default và nhật ký xử lý

## Bản đọc nhanh

Thu hồi bắt đầu ngay khi có nghĩa vụ quá hạn, không đợi đến ngày 90. Loan tự mở một hồ sơ theo từng
episode quá hạn, tăng cấp theo DPD và đóng episode khi người vay khắc phục. Từ 91 ngày, khoản vay chuyển
`DEFAULTED`; lịch sử CIC mock vẫn đi qua `LoanDelinquencyChanged.v1`. Admin có hàng đợi và nhật ký hành
động, nhưng không được sửa số dư Fineract.

```text
Fineract snapshot DPD > 0 -> mở/cập nhật CollectionCase
1-9 EARLY_REMINDER -> 10-90 ATTENTION -> 91-180 NPL -> 181-360 INTENSIVE -> >360 LOSS
DPD >= 91 -> FinoraLoan DEFAULTED
DPD về 0 -> case CURED + loan ACTIVE (hoặc SETTLED nếu dư nợ bằng 0)
```

Các nhóm là benchmark nội bộ trong `LOAN-SERVICING-POLICY.md`; `LEGAL-OPEN-05` vẫn cần legal review
trước production. Không gọi các nhóm này là kết luận CIC thật.

## Phạm vi và dữ liệu

- `LoanCollectionCase`: một episode quá hạn; chỉ tối đa một case `OPEN` cho mỗi loan.
- `LoanCollectionAction`: audit append-only do admin ghi; không làm thay đổi balance.
- Một `FinoraLoan (1) -> (0..N) LoanCollectionCase (1) -> (0..N) LoanCollectionAction`.
- Partial unique index chặn hai case mở; idempotency unique chặn ghi action trùng.
- API admin phân trang case, xem action và ghi action. Không có API sửa DPD/amount.

## Transaction và failure

Projection, trạng thái loan và collection case được cập nhật cùng local transaction khi polling/event
repayment đến. Không gọi mạng trong `CollectionCaseService`. Event cũ/trùng đã được servicing pipeline
chặn trước đó. Action admin dùng request hash; cùng key/cùng payload trả bản cũ, khác payload trả conflict.

## Kiểm thử

- Biên DPD 1/9/10/90/91/180/181/360/361.
- Một episode chỉ có một case; cure đóng case, tái quá hạn mở episode mới.
- Default/cure/settle transition và repayment/polling cùng áp dụng một policy.
- Migration upgrade, schema validation và full Loan verify.

## Bản đồ code thực tế

- Migration: `V21__create_loan_collection_cases.sql` tạo `loan_collection_cases` và
  `loan_collection_actions`, partial unique index bảo đảm mỗi loan chỉ có một episode `OPEN`.
- Domain: `domain/collection/*` giữ stage/status/action và invariant; `FinoraLoan` giữ transition
  `ACTIVE ↔ DEFAULTED`, vẫn cho settle hoặc cơ cấu khoản đang default.
- Đồng bộ: `CollectionCaseService` chạy trong transaction bắt buộc; được gọi bởi
  `LoanServicingSyncService`, `RepaymentDistributedHandler` và completion của reschedule.
- API admin: `GET /api/v1/admin/collection-cases`, `GET/POST .../{caseId}/actions`; action append-only,
  có `Idempotency-Key` + request hash, không có API sửa balance/DPD.
- Test: boundary DPD, episode/cure/default, event repayment, migration V4→V21, Hibernate schema
  validation và toàn bộ Loan verify.

## Kết quả kiểm chứng

- Compile reactor `finora-common + finora-loan`: pass.
- Unit test: `124` Loan test và `14` Common test, không failure/error.
- Full integration verify được ghi nhận sau bước Testcontainers cuối của LN-016.
