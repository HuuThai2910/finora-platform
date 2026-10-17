---
document_type: TASK_PLAN
task_id: LN-009
owner: Thai
reviewers:
  - Hai
status: SUPERSEDED
superseded_by: LN-009-funding-requested-v1.md
depends_on:
  - LN-008
  - Investment event contract
updated_at: 2026-09-21
---

# LN-009 — Đưa điều khoản đã được borrower cho phép lên thị trường gọi vốn

> **LƯU Ý:** Bản thiết kế cũ bên dưới chỉ giữ làm lịch sử quyết định. Kế hoạch đang triển khai là
> [LN-009 funding-requested v1](LN-009-funding-requested-v1.md).

> **Quyết định cập nhật 2026-09-16:** nội dung bên dưới mô tả thiết kế cũ “borrower ký trước rồi mới listing” và không được triển khai nguyên trạng. Target mới phải listing sau `AUTO_AUTHORIZED/ACCEPTED`, xác định lender qua Investment, rồi mới lập Contract song phương cuối. Contract/PDF hiện có chỉ được giữ tạm cho demo; tuyệt đối không mock lender. Event/schema mới cần Hải và Thái cùng review trước khi code.

> **Tiến độ hạ tầng 2026-09-21:** Kafka outbox adapter phía Loan đã được triển khai độc lập với business event: chỉ route exact `eventType + eventVersion` nằm trong allowlist, dùng aggregate làm partition key, chờ broker acknowledgement và truyền trace qua header. Adapter/publisher mặc định tắt, chưa đăng ký topic và không phát các event Contract nội bộ. Phần còn chặn là tên/schema/event trigger listing và consumer idempotency do Thái–Hải cùng duyệt.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Sau khi borrower ký hợp đồng, khoản vay cần được đưa sang thị trường để nhà đầu tư cấp vốn. Loan chỉ gửi “cơ hội gọi vốn” sang Investment; Loan không tự nhận lệnh đầu tư, tính tỷ lệ vốn hoặc giữ tiền nhà đầu tư.

### Ai tham gia và nhìn thấy gì?

- Borrower thấy hợp đồng đã ký đang chờ gọi vốn/đang gọi vốn.
- Investment nhận dữ liệu tối thiểu để tạo listing cho nhà đầu tư.
- Nhà đầu tư xem listing tại Investment, không đọc dữ liệu hồ sơ/KYC/AI nhạy cảm.
- Admin theo dõi trạng thái gửi, lỗi và thao tác retry được kiểm soát.

### Luồng chính

```text
Contract SIGNED → Loan tạo khoản vay PENDING_LISTING + ghi sự kiện chờ gửi
→ publisher gửi LoanListed → Investment tạo listing → xác nhận → Loan chuyển ON_MARKET
```

“Outbox” là bảng giữ sự kiện trong cùng transaction với thay đổi Loan. Nhờ vậy nếu ứng dụng dừng sau khi lưu hợp đồng nhưng trước khi gửi Kafka, sự kiện vẫn còn để gửi lại; gửi lại không tạo listing thứ hai.

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Người đọc hiểu là gì | Nguồn | Ảnh hưởng |
|---|---|---|---|
| `loanNumber/contractNumber` | Khoản vay và hợp đồng đã ký nào đang gọi vốn | Loan | Public business key chống tạo listing trùng, không lộ DB ID |
| principal/term/rate/method | Điều khoản nhà đầu tư cần đánh giá | Contract snapshot | Investment chỉ hiển thị, không được sửa |
| `contractHash` | Bằng chứng listing gắn đúng văn bản đã ký | Contract | Đối chiếu khi tranh chấp/reconcile |
| `investmentListingId` | Mã listing do Investment cấp | Investment | Loan hiển thị trạng thái và nhận event đúng listing |
| listing status/timestamps | Đang chờ gửi, đã lên sàn hay cần xử lý | Loan state machine | Vận hành retry mà không tạo business key mới |

### Ví dụ, lỗi và điều kiện hoàn thành

Contract 50 triệu/12 tháng/15% được ký. Loan ghi một `LoanListed.v1`; Investment trả `INV-LIST-001`, Loan chuyển `ON_MARKET`. Nếu Kafka gửi event hai lần, Investment vẫn chỉ có một listing. LN hoàn tất khi Contract chưa ký không thể listing, transaction rollback không để sự kiện mồ côi và event/log không chứa thu nhập, CCCD hay raw AI payload.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-CONTRACT-01`, `LEGAL-DISCLOSURE-01` và `LEGAL-DATA-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md). Chỉ listing hợp đồng borrower đã ký/xác nhận đúng version; tham chiếu [Luật Giao dịch điện tử 20/2023/QH15](https://vanban.chinhphu.vn/?classid=1&docid=208421&pageid=27160&typegroupid=3) và [Luật Bảo vệ quyền lợi người tiêu dùng 19/2023/QH15](https://vanban.chinhphu.vn/?docid=208363&pageid=27160).
- Event chỉ chứa điều khoản cần cho quyết định đầu tư và business/hash reference; không phát tán thu nhập, CCCD, raw AI explanation hoặc hồ sơ KYC qua Kafka.
- Listing không được làm thay đổi principal, term, final rate hoặc văn bản đã ký. Mọi đề nghị đổi điều khoản phải tạo offer/contract version mới và lấy lại sự đồng ý.

## 1. Mục tiêu nghiệp vụ

Sau khi borrower ký `LoanContract`, Loan phải yêu cầu Investment mở một cơ hội gọi vốn đúng một lần. Loan không tự quản lý order/commitment của nhà đầu tư và không đọc database Investment.

Đầu ra của task:

- Contract `SIGNED` mới đủ điều kiện listing.
- Ghi outbox `LoanListed` cùng transaction với state Loan.
- Lưu trạng thái gửi/đối soát tối thiểu, không sao chép sổ đầu tư.
- Consumer phía Investment có contract đủ rõ để Hải triển khai/review.

Ngoài phạm vi: nhận kết quả fully funded, giữ tiền, giải ngân và tạo Fineract loan.

## 2. Luồng nghiệp vụ

1. Borrower ký Contract thành công trong LN-008.
2. Cùng local transaction, Loan tạo `FinoraLoan` ở `PENDING_LISTING` nếu chưa tồn tại và ghi outbox `LoanListed`.
3. Publisher gửi event sau commit.
4. Investment tạo listing idempotently theo `loanNumber/contractNumber` và trả acknowledgement/event theo contract đã chốt.
5. Loan lưu `investmentListingId` và chuyển `ON_MARKET` khi nhận xác nhận hợp lệ.
6. Timeout chỉ làm trạng thái `LISTING_PENDING/RETRY`; không tạo listing mới với business key khác.

Invariant:

- Một Contract chỉ sinh tối đa một `FinoraLoan` và một logical listing.
- Contract chưa `SIGNED`, `DECLINED` hoặc `EXPIRED` không được listing.
- Event trùng không tạo side effect lần hai.

## 3. Dữ liệu mục tiêu

> Toàn bộ entity/sơ đồ trong LN-009 là **thiết kế dự kiến**, chưa tồn tại trong migration V1–V5. Chỉ tạo migration sau khi event contract với Investment được Hải xác nhận.

### `FinoraLoan` phần được khởi tạo trong LN-009

| Field | Kiểu đề xuất | Tác dụng |
|---|---|---|
| `id` | `BIGINT` identity | Khóa nội bộ đồng nhất các entity Loan hiện tại |
| `loanNumber` | `VARCHAR(50)`, unique | Business/public ID ổn định gửi liên service, không lộ DB ID |
| `contractId` | `BIGINT`, unique/FK | Bảo đảm một Contract chỉ có một khoản vay |
| `applicationId` | `BIGINT`, FK | Truy vết hồ sơ gốc |
| `borrowerId` | kiểu logical ID đã chốt | Ownership |
| `status` | `VARCHAR(40)` | `PENDING_LISTING`, `ON_MARKET`, ... |
| `investmentListingId` | `VARCHAR(100)`, nullable, unique | ID do Investment cấp |
| `listingRequestedAt` | `TIMESTAMPTZ` | Thời điểm phát yêu cầu |
| `listedAt` | `TIMESTAMPTZ`, nullable | Thời điểm Investment xác nhận |
| `version` | `BIGINT` | Optimistic locking |
| audit fields | timestamp/actor | Truy vết |

### Outbox event

Payload tối thiểu dự kiến:

```json
{
  "eventId": "uuid",
  "eventType": "LoanListed.v1",
  "occurredAt": "2026-08-02T10:00:00Z",
  "loanNumber": "FL-2026-000001",
  "contractNumber": "LC-2026-000001",
  "borrowerId": "BORROWER-001",
  "principal": 50000000,
  "termMonths": 12,
  "annualInterestRate": 15.0000,
  "repaymentMethod": "ANNUITY",
  "contractHash": "sha256..."
}
```

Không đưa full profile, CCCD, thu nhập hoặc AI payload vào event. Exact schema/version/partition key phải được Hải review trước merge.

Outbox cần lưu tối thiểu `eventId`, aggregate type/ID, event type/version, business key, payload allowlist, status, attempt count, `nextRetryAt`, `publishedAt`, lỗi đã lọc và audit time. Payload là dữ liệu cần phát; các cột status/retry là dữ liệu vận hành để publisher biết bản nào còn chờ.

### 3.1 Quan hệ dự kiến và lý do tách bảng

```text
LoanApplication (1) ── (0..1) LoanContract SIGNED
                              │
                              └── (0..1) FinoraLoan
                                         │
                                         └── (1..N) OutboxEvent
                                                        │ Kafka
                                                        ▼
                                              Investment Listing
```

- `Contract 1-0..1 FinoraLoan`: chỉ Contract đã ký mới khởi tạo khoản vay gọi vốn; unique `contract_id` ngăn ký/retry tạo hai loan.
- `FinoraLoan 1-N OutboxEvent`: một loan về sau có nhiều loại sự kiện/version; riêng logical `LoanListed.v1` phải unique theo business key.
- Không gộp Outbox vào `FinoraLoan`: event cần vòng đời publish/retry/failed riêng, còn loan giữ trạng thái nghiệp vụ.
- Investment Listing thuộc DB của Investment. Loan chỉ giữ `investmentListingId`, tuyệt đối không FK hoặc đọc DB Investment.
- Xóa Contract/Loan theo hard-delete không được phép sau khi có outbox; nếu nghiệp vụ hủy thì phát event/state mới.

## 4. Persistence và index

Index là “đường tắt” bám theo câu query thật, không phải thứ thêm cho mọi cột:

- Unique `finora_loans.contract_id`: chống tạo khoản vay/listing logic trùng.
- Unique có điều kiện nghiệp vụ cho `investment_listing_id` khi đã có giá trị.
- Partial index `(listing_requested_at, id) WHERE status='PENDING_LISTING'`: worker/reconcile chỉ quét listing pending theo thời gian.
- Outbox dùng unique `(aggregate_type, aggregate_id, event_type, logical_version)` hoặc business key tương đương.
- Partial index `(next_retry_at, id) WHERE status IN ('PENDING','RETRY_PENDING')`: publisher lấy event đến hạn mà không quét event đã publish.

Index trạng thái chỉ được thêm khi worker thật sự truy vấn theo prefix trên; kiểm tra bằng integration test và execution plan khi dữ liệu mẫu đủ lớn.

## 5. Transaction, idempotency và lỗi

- Contract sign, tạo `FinoraLoan` và outbox thuộc cùng **local transaction**: hoặc cả ba commit, hoặc không có trạng thái “đã ký nhưng mất sự kiện”.
- Publisher không gọi Investment trong transaction ký.
- Consumer acknowledgement dùng `eventId` và `loanNumber` để loại trùng.
- Retry timeout/5xx với backoff; validation/contract 4xx chuyển `LISTING_REVIEW_REQUIRED`.
- Event đã publish nhưng chưa nhận xác nhận phải reconcile theo business key, không phát event version mới tùy tiện.

Hai publisher/node có thể cùng thấy một event. Worker phải claim bằng lock/lease/version; broker vẫn có thể giao trùng nên Investment bắt buộc idempotent theo event/business key. Outbox bảo đảm **không làm mất ý định gửi**, không hứa “chỉ gửi đúng một lần”.

N+1 cần tránh ở màn admin: query projection lấy loan + trạng thái outbox mới nhất theo batch, không đọc từng loan rồi chạy thêm một query/event hoặc gọi Investment cho từng dòng.

## 6. API và quyền

- Borrower chỉ đọc trạng thái listing qua API Loan detail.
- Admin có thể xem lỗi/retry; thao tác retry cần role admin, audit và idempotency key.
- Không cung cấp endpoint sửa trực tiếp `investmentListingId` nếu không có quy trình repair được kiểm soát.

## 7. File dự kiến

- Domain/entity/repository cho `FinoraLoan` và listing state.
- Outbox producer/publisher mapping.
- Consumer acknowledgement/reconcile adapter.
- Flyway migration kế tiếp, tests và fixture event v1.

## 8. Kiểm thử và nghiệm thu

- Contract chưa ký bị từ chối listing.
- Hai request/event trùng chỉ tạo một loan/listing/outbox logic.
- Rollback transaction không để outbox mồ côi.
- Publisher retry sau restart không đổi business key.
- Không có PII ngoài allowlist trong event/log.
- Hải xác nhận event contract; Thái nghiệm thu state và persistence Loan.
