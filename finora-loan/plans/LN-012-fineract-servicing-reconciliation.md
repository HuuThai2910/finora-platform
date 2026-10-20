---
document_type: TASK_PLAN
task_id: LN-012
owner: Thai
reviewers:
  - Hai
status: IN_PROGRESS
depends_on:
  - LN-011
  - Fineract event or polling fixture
updated_at: 2026-10-04
---

# LN-012 — Servicing projection và reconciliation với Fineract

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Sau giải ngân, borrower cần xem khoản vay còn bao nhiêu, kỳ nào sắp đến và có quá hạn không. Fineract là nguồn chuẩn của lịch trả, số dư và arrears; Loan chỉ giữ một “bản sao chỉ đọc tối thiểu” để UI phản hồi nhanh và để điều phối các trạng thái FINORA.

“Đối chiếu” là hỏi lại Fineract khi bản sao có thể cũ hoặc hai bên lệch nhau; không sửa thẳng database Fineract/Loan cho khớp.

### Ai tham gia và nhìn thấy gì?

- Borrower xem summary kèm `dataAsOf/lastSyncedAt`, biết dữ liệu cập nhật tới lúc nào.
- Admin xem các sai lệch cần xử lý, không có API sửa số dư tùy ý.
- Fineract phát event hoặc được Loan polling có giới hạn.
- Loan cập nhật projection idempotently và chỉ phát event FINORA khi trạng thái thật sự thay đổi.

### Luồng chính

```text
Fineract event/poll → xác minh mapping + version → cập nhật bản sao Loan
→ UI đọc nhanh

Worker thấy dữ liệu cũ → hỏi lại Fineract → so sánh
    ├── khác biệt an toàn: refresh bản sao
    └── khác biệt tài chính/identity: tạo incident để xử lý
```

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Borrower/admin hiểu là gì | Ảnh hưởng |
|---|---|---|
| status FINORA/core | Trạng thái trải nghiệm và trạng thái core | Chỉ chuyển theo mapping/whitelist đã duyệt |
| principal/disbursed/outstanding/overdue | Số tiền gốc, đã giải ngân, còn nợ, quá hạn | Chỉ đọc từ Fineract projection; Loan không tự tính sửa |
| maturity/overdue dates | Ngày đáo hạn/quá hạn | UI, nhắc nợ và LN-014 |
| source event/version/time | Dữ liệu đến từ cập nhật nào | Bỏ event trùng/cũ và giải thích độ mới |
| reconciliation status | Có sai lệch cần người xử lý không | Ngăn tự động “chữa” sai dữ liệu tài chính |

### Ví dụ, lỗi và điều kiện hoàn thành

Fineract báo outstanding 45 triệu lúc 10:00; Loan hiển thị con số đó cùng `dataAsOf`. Khi Fineract tạm lỗi, UI có thể hiển thị bản cũ với nhãn stale thay vì số giả. LN hoàn tất khi duplicate/out-of-order event an toàn, worker batch có giới hạn, danh sách không gọi remote theo từng dòng và mismatch tài chính tạo incident thay vì update mù.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-DISCLOSURE-01`, `LEGAL-PAYMENT-01` và `LEGAL-DATA-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md). UI phải hiển thị số dư/kỳ trả đúng nguồn và thời điểm, tránh thông tin gây nhầm lẫn theo [Luật Bảo vệ quyền lợi người tiêu dùng 19/2023/QH15](https://vanban.chinhphu.vn/?docid=208363&pageid=27160).
- Projection stale phải có `dataAsOf/stale`; không hiển thị dữ liệu cũ như số liệu thời gian thực. Sai lệch tiền tạo incident để đối soát, không tự sửa sổ core hoặc xóa dấu vết.
- Servicing event/profile chỉ giữ dữ liệu cần thiết, phân quyền theo borrower/admin và tuân thủ [Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15](https://vanban.chinhphu.vn/?docid=214590&pageid=27160&typegroupid=3).

## 1. Mục tiêu nghiệp vụ

Loan cần một bản chiếu nhẹ để hiển thị trạng thái khoản vay và điều phối nghiệp vụ, nhưng Fineract vẫn là nguồn chuẩn của schedule, balance và arrears. Task này xây event ingestion/polling, projection và công cụ phát hiện sai lệch.

Không sao chép toàn bộ transaction/schedule thành entity có thể chỉnh sửa và không cho admin sửa số dư bằng API Loan.

## 2. `LoanServicingProjection`

> Entity này là thiết kế dự kiến sau LN-011, chưa có trong migration V1–V5. Projection là bản đọc local có thể dựng lại; nó không trở thành sổ cái hay nguồn chuẩn tài chính thứ hai.

| Field | Kiểu đề xuất | Tác dụng |
|---|---|---|
| `finoraLoanId` | `BIGINT`, unique/FK | Liên kết entity khoản vay FINORA |
| `loanNumber` | `VARCHAR(50)`, unique | Public/business ID dùng cho API/event |
| `contractId/applicationId` | `BIGINT` | Truy vết entity Loan hiện tại |
| `borrowerId` | `VARCHAR(100)` logical ID | Ownership, không FK User |
| `fineractClientId/productId/fineractLoanId` | `BIGINT`, unique phù hợp | Mapping core |
| `externalId` | `VARCHAR(100)`, unique | Contract number/business key |
| `finoraLoanStatus` | `VARCHAR(40)` | Trạng thái trải nghiệm FINORA |
| `fineractStatusCode` | `VARCHAR(40)` | Trạng thái core cuối đã nhận |
| `principal/disbursed/outstanding/overdue` | `DECIMAL(19,0)` | Summary chỉ đọc |
| `expected/actualDisbursementDate` | `DATE` | Theo dõi giải ngân |
| `maturityDate/overdueSince` | `DATE`, nullable | Hiển thị/cảnh báo |
| `lastSourceEventId` | `VARCHAR(100)` | Idempotency |
| `lastSourceEventAt` | `TIMESTAMPTZ` | Ordering |
| `lastSyncedAt` | `TIMESTAMPTZ` | Độ mới dữ liệu |
| `projectionVersion` | `BIGINT` | Version nguồn |
| `version` | `BIGINT` | Optimistic locking local |

### 2.1 Quan hệ dự kiến

```text
FinoraLoan (1) ── (0..1) FineractLoanMapping
      │                         │
      └── (0..1) LoanServicingProjection ◄── event/poll ── Fineract
                              │
                              └── (0..N) ReconciliationIncident
```

- Một `FinoraLoan` có tối đa một projection hiện hành; snapshot cũ chỉ lưu thêm nếu audit thực sự cần, không mặc định nhân bản toàn bộ schedule.
- Mapping giữ identity giữa hai hệ thống; projection giữ summary thay đổi theo servicing. Tách hai trách nhiệm để refresh balance không làm mất external/business mapping.
- Một projection có thể phát sinh nhiều incident theo thời gian; incident cần status/owner/resolution riêng nên không nhét thành một text lỗi trên projection.
- Contract/Application liên kết bằng scalar ID nội bộ; User và Fineract là ranh giới service nên không có FK xuyên database.

## 3. Nguồn cập nhật

Ưu tiên event business đáng tin cậy từ Fineract adapter. Nếu event chưa đủ, polling/reconciliation gọi:

`GET /loans/{id}?associations=repaymentSchedule,transactions`

Response được allowlist: status, summary, schedule/transactions cần đối soát. Không deserialize toàn bộ payload core vào domain.

Event/response phải mang hoặc suy ra `fineractLoanId`, source event/version/time. Event cũ hơn projection hiện tại bị bỏ qua có metric.

## 4. Luồng event

1. Nhận event và validate schema/version.
2. Insert `processed_events(event_id)`.
3. Tìm mapping theo Fineract loan ID; không gọi User/Fineract trong vòng lặp danh sách.
4. Áp dụng transition/summary theo whitelist.
5. Update projection và processed event cùng transaction.
6. Phát domain event sau commit nếu FINORA state thật sự thay đổi.

Unknown loan/event type đưa vào quarantine/DLT, không tự tạo projection thiếu Contract.

Processed marker, cập nhật projection và outbox khi state FINORA đổi phải nằm trong cùng local transaction. Network polling Fineract luôn ở ngoài transaction. Nếu hai event cùng loan đến đồng thời, lock/version + source version quyết định bản mới hơn; timestamp nhận tại Loan không được dùng để ghi đè event có source version cao hơn.

## 5. Reconciliation

- Worker quét projection stale theo `(last_synced_at, id)` với batch giới hạn.
- Mỗi batch gọi Fineract theo chiến lược tránh N+1 không kiểm soát: API bulk nếu có; nếu chỉ API từng loan thì concurrency/rate limit/batch size rõ và không nằm trong request người dùng.
- So sánh status, balance, disbursed amount, overdue và schedule version/hash.
- Sai lệch an toàn được refresh projection; sai lệch tài chính/identity tạo incident `RECONCILIATION_REQUIRED`.
- Không update trực tiếp Fineract DB hoặc “sửa cho khớp” bằng SQL.

## 6. API mục tiêu

- `GET /api/v1/loans/{id}`: summary + `dataAsOf/lastSyncedAt`.
- `GET /api/v1/loans/{id}/repayment-schedule`: đọc core/validated snapshot, có staleness metadata.
- `GET /api/v1/admin/loan-servicing-reconciliation`: phân trang projection stale; không remote-call
  theo từng dòng.
- `POST /api/v1/admin/loan-servicing-reconciliation/{loanNumber}/reconcile`: GET lại Fineract ngoài
  transaction rồi cập nhật projection trong transaction ngắn; lặp lại an toàn và không tạo giao dịch.
- `GET /api/v1/admin/loan-servicing-reconciliation/incidents`: queue incident phân trang, mặc định
  `OPEN`, lọc được `status/type`; không gọi Fineract và không cho sửa balance bằng tay.

## 7. Index và hiệu năng

- Unique Fineract loan ID, public loan number và external ID chặn hai projection/mapping cho cùng khoản vay.
- `(borrower_id, finora_loan_status, id)` cho danh sách borrower: lọc owner trước, trạng thái sau, `id` phân trang ổn định.
- `(last_synced_at, id)` hoặc partial index theo trạng thái active cho stale scan: lấy dữ liệu cũ nhất trước.
- `(status, updated_at, id)` cho admin incident queue; partial unique `(finora_loan_id, type)` khi
  `OPEN` để lỗi lặp chỉ tăng `occurrenceCount`, không tạo vô hạn incident trùng.
- Endpoint list dùng projection DTO, không fetch Contract/Application graph.
- Query-count test với nhiều loan; không remote call theo từng row.

N+1 ở đây nguy hiểm theo hai kiểu: N query database để lấy Contract/Application cho N projection, hoặc N HTTP call Fineract trong request danh sách. List phải đọc projection bằng một query; reconcile chạy nền theo batch/rate limit. Index làm ghi chậm hơn nên migration phải kèm query repository cụ thể và test/explain plan tương ứng.

## 8. Kiểm thử và nghiệm thu

- Duplicate/out-of-order event.
- Unknown mapping, schema version và DLT.
- Event update atomic với processed marker.
- Reconcile phát hiện mismatch và không tự sửa dữ liệu tài chính nguy hiểm.
- Stale marker hiển thị đúng khi Fineract unavailable.
- List/query count và batch giới hạn.
- Restart worker không mất/nhân đôi side effect.

## 9. Hiện trạng triển khai ngày 2026-10-04

- Migration V19 đã tạo `finora_loans` và `loan_servicing_projections` với mapping Application,
  Contract, borrower, Fineract loan, toàn bộ summary balance, overdue/DPD, kỳ kế tiếp, maturity,
  source/staleness/version và audit time.
- `LoanDisbursed` tạo aggregate và projection trong cùng local transaction trước outbox.
- API borrower: `GET /api/v1/loans/me`, `GET /api/v1/loans/{loanNumber}` và
  `GET /api/v1/loans/{loanNumber}/repayment-schedule`.
- `RepaymentDistributed.v1` cập nhật projection idempotently từ kết quả Fineract.
- Polling worker đọc allowlist `GET /loans/{id}?associations=repaymentSchedule`, gọi mạng ngoài
  transaction, cập nhật projection/collection/outbox trong transaction ngắn và đánh dấu stale khi lỗi.
- Admin có queue projection stale và trigger reconcile read-only. Danh sách dùng một query projection
  và một batch query Loan; migration V22 thêm partial index đúng theo queue này.
- Migration V23 có incident bền vững cho sai `coreLoanId`, `externalId`, principal giải ngân và tổng
  breakdown dư nợ. Snapshot vi phạm bị fail-closed, projection giữ stale; cùng lỗi tăng số lần xuất hiện.
  Sau khi nguồn được khắc phục và một snapshot hợp lệ được reconcile, incident được đóng tự động bằng
  `CORE_SNAPSHOT_VALIDATED`; không có API ghi tay số dư/DPD.
- Response borrower trả cả `dataAsOf`, `lastSyncedAt` và `stale` để UI không mô tả dữ liệu cũ là số
  liệu thời gian thực.

Chưa xong: quarantine event không map được và E2E restart/timeout với Fineract thật; vì vậy LN-012
vẫn `IN_PROGRESS`, chưa đạt nghiệm thu toàn bộ.
