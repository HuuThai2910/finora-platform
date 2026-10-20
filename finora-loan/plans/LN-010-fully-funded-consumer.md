---
document_type: TASK_PLAN
task_id: LN-010
owner: Thai
reviewers:
  - Hai
status: SUPERSEDED
superseded_by: LN-010-multi-party-contract-v1.md
depends_on:
  - LN-009
  - Investment funding fixture
updated_at: 2026-09-05
---

# LN-010 — Nhận kết quả gọi vốn đầy đủ

> **LƯU Ý:** Bản thiết kế cũ bên dưới chỉ giữ làm lịch sử quyết định. Kế hoạch đang triển khai là
> [LN-010 multi-party contract v1](LN-010-multi-party-contract-v1.md).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Investment thông báo khi listing đã nhận đủ số vốn hợp lệ. LN-010 giúp Loan xác minh thông báo đó rồi đánh dấu khoản vay `FUNDED` đúng một lần, làm điều kiện bắt đầu quy trình giải ngân. Loan không tự cộng từng lệnh đầu tư và không giữ tiền.

### Ai tham gia và nhìn thấy gì?

- Investment là nguồn chuẩn của order, commitment và tổng vốn đã xác nhận.
- Loan là bên duy nhất đổi trạng thái khoản vay FINORA sang `FUNDED`.
- Borrower thấy “đã huy động đủ vốn, đang chuẩn bị giải ngân”.
- Admin thấy mismatch về listing, hợp đồng, tiền hoặc thứ tự event để xử lý.

### Luồng chính

```text
Investment phát LoanFullyFunded → Loan kiểm tra loan/contract/listing/currency/amount/version
    ├── hợp lệ: lưu bằng chứng + FUNDED + tạo lệnh mở LN-011
    └── không khớp: giữ trạng thái cũ → DLT/manual review
```

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Ý nghĩa nghiệp vụ | Ảnh hưởng |
|---|---|---|
| `fundedAmount/currency` | Số vốn Investment đã khóa hợp lệ | Phải khớp principal/currency hợp đồng mới được `FUNDED` |
| `fundingCompletedAt` | Lúc gọi vốn hoàn tất | Timeline và SLA chuẩn bị giải ngân |
| `fundingVersion` | Thứ tự thay đổi bên Investment | Event cũ không được ghi đè kết quả mới |
| `eventId` | Mã lần thông báo | Nhận lại cùng event không chạy side effect lần hai |
| listing/contract IDs | Bằng chứng vốn thuộc đúng khoản vay | Sai bất kỳ ID nào phải fail closed |

### Ví dụ, lỗi và điều kiện hoàn thành

Listing cần 50 triệu và Investment báo đủ 50 triệu VND, version 3: Loan chuyển `ON_MARKET → FUNDED`. Nếu báo 49 triệu, sai currency hoặc sai listing, Loan không giải ngân. Task hoàn tất khi duplicate/out-of-order event an toàn và processed-event, trạng thái cùng lệnh tiếp theo được commit nguyên tử.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-LIMIT-01` và `LEGAL-PAYMENT-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md). Luồng P2P phải đối chiếu phạm vi sandbox, quản lý dư nợ và kênh thanh toán tại [Điều 11 Nghị định 94/2025/NĐ-CP](https://vanban.chinhphu.vn/?classid=1&docid=213519&pageid=27160).
- `FUNDED` chỉ xác nhận cam kết vốn đủ và hợp lệ; không đồng nghĩa tiền đã chuyển cho borrower, không được tự ghi `DISBURSED/ACTIVE`.
- Trước production phải có kiểm soát tổng dư nợ từ nguồn được duyệt. Giới hạn Product demo 100 triệu không thay thế phép kiểm tra dư nợ theo borrower/CIC.

## 1. Mục tiêu nghiệp vụ

Loan nhận `LoanFullyFunded` từ Investment, xác minh tính hợp lệ và chuyển khoản vay FINORA từ `ON_MARKET` sang `FUNDED` đúng một lần. Investment vẫn là nguồn chuẩn về order, commitment và tỷ lệ funded.

Ngoài phạm vi: Loan không tự cộng từng khoản đầu tư, không giữ tiền nhà đầu tư và chưa giải ngân trong task này.

## 2. Contract event dự kiến

```json
{
  "eventId": "uuid",
  "eventType": "LoanFullyFunded.v1",
  "occurredAt": "2026-08-03T10:00:00Z",
  "loanNumber": "FL-2026-000001",
  "contractNumber": "LC-2026-000001",
  "investmentListingId": "INV-LIST-001",
  "fundedAmount": 50000000,
  "currency": "VND",
  "fundingCompletedAt": "2026-08-03T09:59:00Z",
  "fundingVersion": 3
}
```

Hải phải xác nhận exact name/type, quy tắc amount, version và trường hợp listing bị hủy/giảm vốn trước merge.

## 3. Luồng xử lý

1. Consumer nhận event và kiểm tra schema/version.
2. Tìm `FinoraLoan` theo `loanNumber`, đối chiếu `contractNumber` và listing ID.
3. Kiểm tra trạng thái hiện tại là `ON_MARKET` và Contract vẫn `SIGNED`.
4. Xác nhận `fundedAmount` khớp principal snapshot; không dùng số tiền client gửi.
5. Trong một transaction: ghi processed event, funding snapshot và chuyển `FUNDED`.
6. Ghi outbox/command khởi tạo LN-011 sau commit.

Event đến trước acknowledgement listing được giữ retryable hoặc reconcile theo listing ID; không tự tạo loan mới.

## 4. Field bổ sung vào funding projection

> Đây là thiết kế dự kiến sau LN-009; các cột/bảng chưa có trong migration V1–V5.

| Field | Kiểu đề xuất | Tác dụng |
|---|---|---|
| `fundedAmount` | `DECIMAL(19,0)` | Tổng vốn do Investment xác nhận |
| `fundingCompletedAt` | `TIMESTAMP(6)` | Mốc hoàn tất gọi vốn |
| `fundingVersion` | `BIGINT` | Ordering event từ Investment |
| `lastInvestmentEventId` | `VARCHAR(100)` | Chống trùng/truy vết; không ép mọi broker/event ID phải là UUID |
| `fundingSnapshotJson` | JSON allowlist hoặc columns | Bằng chứng tối thiểu, không chứa danh sách investor |

Không lưu danh sách commitment/investor trong Loan. Khi UI cần chi tiết, gọi API tổng hợp có ranh giới rõ hoặc chuyển sang Investment.

### 4.1 Quan hệ dự kiến

```text
Investment Listing ── LoanFullyFunded.v1 ──► ProcessedEvent
                                                    │
                                                    ▼
LoanContract (1) ── (0..1) FinoraLoan/FundingProjection
                                  │
                                  └── (1..N) OutboxEvent/Command mở LN-011
```

- `ProcessedEvent` không phải bản sao Kafka. Nó chỉ lưu `eventId`, type/version, source, aggregate ID, received/processed time và kết quả để Loan biết event nào đã áp dụng.
- Funding projection nằm trên `FinoraLoan` vì đây là trạng thái Loan cần đọc thường xuyên; không sao chép investor/order thuộc Investment.
- Một loan có thể nhận nhiều event/version, nhưng `fundingVersion` chỉ được tăng; event cũ không được kéo state lùi.
- Outbox mở Saga được ghi cùng transaction với `FUNDED`, tránh funded thành công nhưng không bao giờ bắt đầu giải ngân.

## 5. Idempotency, ordering và index

- `processed_events(event_id)` unique: broker giao lại cùng event thì consumer ack kết quả cũ, không chạy side effect lần hai.
- Event cùng loan nhưng `fundingVersion` nhỏ hơn/equal version đã áp dụng bị ignore có audit.
- Partial index `(funding_completed_at, id) WHERE status='FUNDED'` chỉ phục vụ hàng chờ tạo Saga/reconcile; không quét loan còn gọi vốn hoặc đã active.
- Index `(source, aggregate_id, source_version)`/unique tương đương phục vụ phát hiện version trùng nếu contract Investment yêu cầu.
- Optimistic locking chặn admin/event cùng sửa state.
- Chuyển state và ghi processed event/outbox trong cùng transaction.

`Ordering` nghĩa là event có thể đến không đúng thứ tự. Loan so `fundingVersion`, không dùng thời gian nhận ở Kafka để đoán bản nào mới hơn. Transaction khóa/check version của `FinoraLoan`; nếu hai consumer xử lý cùng loan, chỉ bản hợp lệ mới commit.

List API dùng projection từ PostgreSQL. Không gọi Investment cho từng row và không lazy-load toàn bộ processed event/outbox, nếu không sẽ tạo N+1 query/network call.

## 6. Failure path

- Sai listing/contract/principal: đưa DLT/manual review, không đánh `FUNDED`.
- Event trùng: ack nhưng không lặp side effect.
- Database rollback: event được broker giao lại.
- Event schema mới chưa hỗ trợ: fail closed và metric rõ.
- Funding bị đảo ngược sau fully funded là flow riêng cần policy; không tự giảm trạng thái bằng event không được chốt.

## 7. Kiểm thử và nghiệm thu

- Happy path `ON_MARKET -> FUNDED`.
- Duplicate/out-of-order event.
- Amount, currency, contract và listing mismatch.
- Atomic processed-event + state + outbox.
- Query danh sách không gọi Investment theo từng row.
- Hải review fixture; Thái nghiệm thu transition và audit.
