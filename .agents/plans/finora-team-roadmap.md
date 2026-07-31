# Kế hoạch phối hợp triển khai FINORA cho Thái và Hải

> Đây là tài liệu làm việc chung cho con người và AI. Hải hoặc Thái mở file này phải xác định được giai đoạn hiện tại, phần mình sở hữu, dependency cần chốt với người kia và điều kiện để chuyển giai đoạn. Cập nhật cột trạng thái trong cùng PR với công việc liên quan.

## 1. Cách sử dụng

### Trạng thái task

| Trạng thái | Ý nghĩa |
|---|---|
| `BACKLOG` | Đã xác định nhưng chưa sẵn sàng |
| `READY` | Contract/dependency đã đủ, có thể bắt đầu |
| `IN_PROGRESS` | Owner đang thực hiện |
| `BLOCKED` | Có blocker được ghi rõ ở mục nhật ký |
| `REVIEW` | Đã hoàn thành code, đang chờ review/tích hợp |
| `DONE` | Đạt Definition of Done và phase gate liên quan |

### Luật phối hợp

1. Mỗi task có đúng một owner chính; người còn lại review nếu đụng contract hoặc luồng liên service.
2. Không sửa code module của người kia. Hai bên thống nhất contract/fixture trước rồi triển khai độc lập.
3. Không bắt đầu task phụ thuộc khi contract chưa ở trạng thái `READY`.
4. Mỗi tuần/chu kỳ tích hợp ít nhất một lần; không chờ mỗi người hoàn thành toàn bộ service mới ghép.
5. Khi event/API thay đổi, cập nhật producer, consumer fixture, version và roadmap trong cùng change.
6. Luồng kỹ thuật phải tuân theo `../rules/07-service-boundaries.md` và `../rules/08-cross-service-flows.md`.

## 2. Definition of Ready

Một task chỉ chuyển `READY` khi có đủ:

- Mục tiêu và tiêu chí chấp nhận rõ.
- Module/owner và file/vùng dự kiến rõ.
- API/event/database contract hoặc xác nhận task không cần contract.
- Input/output, validation, authorization và state transition rõ.
- Idempotency/failure/compensation rõ nếu có side effect hoặc liên service.
- Dependency ngoài hệ thống được xác định là thật, sandbox, mock adapter hay PoC.
- Dữ liệu test/fixture đã thống nhất, không chứa PII thật.

## 3. Definition of Done chung

Một task chỉ `DONE` khi:

- Code đúng ownership, package-by-feature và không sửa ngoài phạm vi.
- Có migration/constraint/index cần thiết; không dùng `ddl-auto` làm cơ chế triển khai schema.
- Có authentication/authorization và validation phù hợp.
- Không N+1, query không giới hạn hoặc REST call trong vòng lặp.
- Side effect idempotent; tiền có ledger và concurrency protection.
- Event dùng outbox, consumer dùng processed-event nếu task có Kafka.
- Log/trace đủ, comment tiếng Việt giải thích invariant/failure path, không lộ PII.
- Unit/integration/contract test phù hợp đã pass.
- Có hướng dẫn hoặc fixture để người còn lại chạy thử.
- Owner còn lại review contract/vùng chung; demo lại được trên môi trường tích hợp.

## 4. Thứ tự giai đoạn

```text
P0 Nền móng
 → P1 Identity, eKYC, Loan application, Scoring
 → P2 Duyệt, Market, Matching
 → P3 Wallet, Hold, Commitment, Fully funded
 → P4 Disbursement Saga, Notes, Fabric proof
 → P5 Repayment, Distribution, Portfolio
 → P6 Hardening luồng chính
 → P7 Chức năng mở rộng
```

P1–P3 có thể chồng lấn có kiểm soát khi contract liên quan đã `READY`; P4 MUST NOT tích hợp thật trước khi P3 phase gate đạt.

## 5. P0 — Nền móng chung

**Mục tiêu:** mọi service có chuẩn build, security, database, event và observability thống nhất trước khi nghiệp vụ lan rộng.

| ID | Owner | Công việc | Phối hợp | Trạng thái |
|---|---|---|---|---|
| P0-C01 | Thái + Hải | Chốt REST error/pagination, money/time format, Kafka envelope | Review chung | `BACKLOG` |
| P0-C02 | Thái + Hải | Chốt JWT claims, role `borrower/investor/admin`, resource authorization | Gateway/User/Loan | `BACKLOG` |
| P0-A01 | Thái | Chuẩn Flyway/Testcontainers cho Loan và Payment | Hải review pattern | `BACKLOG` |
| P0-B01 | Hải | Chuẩn Mongo migration/index test cho Investment; Python Ruff/Pytest | Thái review contract | `BACKLOG` |
| P0-A02 | Thái | Outbox + idempotent consumer reference implementation phía Java do Thái sở hữu | Hải dùng contract, không sửa module | `BACKLOG` |
| P0-B02 | Hải | Notification consumer reference, retry/DLT/idempotency | Thái cung cấp event fixture | `BACKLOG` |
| P0-C03 | Thái + Hải | Trace ID HTTP/Kafka, JSON logging, Actuator/readiness | Chia theo module owner | `BACKLOG` |
| P0-C04 | Thái + Hải | Docker Compose profile tối thiểu và smoke test hạ tầng | Review chung | `BACKLOG` |

**Contract phải chốt:** error envelope, event envelope, correlation headers, idempotency header, role/claim, timestamp và money serialization.

**Phase gate P0:** một request qua Gateway ghi DB + outbox, Kafka consumer xử lý idempotently; retry cùng event không tạo bản ghi thứ hai; trace đi xuyên producer/consumer; migration và test chạy lại từ môi trường sạch.

## 6. P1 — Identity, eKYC, hồ sơ vay và scoring

**Luồng tham chiếu:** F01, F02 trong `08-cross-service-flows.md`.

### Hải

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P1-B01 | User | Đồng bộ Keycloak identity và user profile | User API + authorization + migration | `BACKLOG` |
| P1-B02 | User | KYC application/state machine, document metadata | Không lưu/log ảnh nhạy cảm sai chỗ | `BACKLOG` |
| P1-B03 | AI | eKYC technical API: OCR/face/liveness/forgery contract | Model/provider version + reason | `BACKLOG` |
| P1-B04 | User | Orchestrate eKYC và manual review | Event KYC version 1 | `BACKLOG` |
| P1-B05 | AI | Credit preprocessing/model registry/predictor | Model package chống train/serve skew | `BACKLOG` |
| P1-B06 | AI | Credit scoring + reason codes/XAI cơ bản | Contract fixture cho Loan | `BACKLOG` |
| P1-B07 | Notification | Template/trạng thái gửi cho KYC và hồ sơ | Idempotency theo source event | `BACKLOG` |

### Thái

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P1-A01 | Loan | Loan product và điều kiện gói vay | Migration + API admin/read | `BACKLOG` |
| P1-A02 | Loan | Loan application draft/submit | State transition + authorization | `BACKLOG` |
| P1-A03 | Loan | KYC eligibility adapter theo contract | Không truy cập DB User | `BACKLOG` |
| P1-A04 | Loan | AI credit client với timeout/circuit breaker | Fixture chạy khi AI chưa hoàn tất | `BACKLOG` |
| P1-A05 | Loan | Lưu immutable scoring snapshot | Model/rule version + reason codes | `BACKLOG` |
| P1-A06 | Loan | State `SUBMITTED → SCORING → PENDING_REVIEW/REJECTED` | Retry/manual failure state | `BACKLOG` |

### Điểm bắt tay P1

- Hải cung cấp OpenAPI/example JSON cho KYC status và credit result trước khi Thái implement adapter.
- Thái cung cấp loan feature request/schema và validation cần thiết trước khi Hải khóa model input contract.
- Hai bên dùng cùng fixture version; không import DTO Java/Python của nhau qua `finora-common`.

**Phase gate P1:** người dùng xác thực → KYC → tạo hồ sơ → AI scoring → Loan lưu snapshot → hồ sơ chờ duyệt/từ chối; AI timeout không tạo điểm giả; request/event trùng không tạo hồ sơ hoặc scoring artifact trùng.

## 7. P2 — Duyệt, đưa lên sàn và matching

**Luồng tham chiếu:** F03 và phần market của F04.

### Thái

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P2-A01 | Loan | Admin review/approve/reject | Optimistic lock + audit | `BACKLOG` |
| P2-A02 | Loan | Loan listing intent và `ON_MARKET` | Outbox `LoanListed` v1 | `BACKLOG` |
| P2-A03 | Loan | Funding summary và consume fully-funded | Loan tự đổi `FUNDED` | `BACKLOG` |

### Hải

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P2-B01 | Investment | Consume `LoanListed`, tạo market projection | Unique `loanId + listingVersion` | `BACKLOG` |
| P2-B02 | Investment | Market query/filter/pagination/WebSocket nếu cần | Index + không N+1/unbounded | `BACKLOG` |
| P2-B03 | Investment | Investment order state machine | Validation và concurrency rule | `BACKLOG` |
| P2-B04 | Investment | Matching engine partial/full | Deterministic, test concurrent order | `BACKLOG` |
| P2-B05 | Investment | Funding aggregation | Chống overfund, funded-once | `BACKLOG` |

**Contract phải chốt:** `LoanListed` v1, listing expiry/cancel, amount/interest/term/grade, `LoanFullyFunded` v1, partition key `loanId`.

**Phase gate P2:** admin duyệt → market projection xuất hiện → nhiều lệnh được match chính xác → tổng vốn không vượt target → `LoanFullyFunded` phát đúng một lần → Loan tự chuyển `FUNDED`.

## 8. P3 — Wallet, hold tiền và commitment

**Luồng tham chiếu:** F04.

### Thái

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P3-A01 | Payment | Wallet + immutable ledger schema | Constraint/index/audit fields | `BACKLOG` |
| P3-A02 | Payment | Deposit sandbox/mock adapter | Webhook signature/idempotency | `BACKLOG` |
| P3-A03 | Payment | Hold/release/capture API | `@Version`/lock + unique key | `BACKLOG` |
| P3-A04 | Payment | Balance invariant/concurrency tests | Không âm ví/double hold | `BACKLOG` |
| P3-A05 | Payment | Financial event outbox | Reference transaction ID | `BACKLOG` |

### Hải

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P3-B01 | Investment | Payment client/adapter cho hold/release | Timeout và duplicate response | `BACKLOG` |
| P3-B02 | Investment | Commitment sau hold thành công | Unique order/commitment | `BACKLOG` |
| P3-B03 | Investment | Compensation release khi commitment lỗi | Retry idempotent | `BACKLOG` |
| P3-B04 | Investment | Reconcile order/hold reference | Repair flow, không sửa Payment DB | `BACKLOG` |

**Contract phải chốt:** hold/release/capture request-response, error code, idempotency key, `paymentTransactionId`, timeout ownership và reconciliation endpoint/event.

**Phase gate P3:** hai request đồng thời không tiêu cùng số dư; request trùng trả kết quả cũ; commitment lỗi sau hold được release; tổng available + held và ledger cân bằng; fully-funded chỉ tính commitment có hold hợp lệ.

## 9. P4 — Saga giải ngân, Notes và Fabric proof

**Luồng tham chiếu:** F05, F08, F09.

### Thái

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P4-A01 | Loan | Durable Disbursement Saga state machine | `sagaId`, step, attempt, timeout | `BACKLOG` |
| P4-A02 | Payment | Capture commitments và disbursement ledger | Financial idempotency | `BACKLOG` |
| P4-A03 | Loan | Compensation/retry/restart recovery | Không lặp side effect | `BACKLOG` |
| P4-A04 | Blockchain | Fabric adapter submit/query | Hash only, no PII | `BACKLOG` |
| P4-A05 | Blockchain | Retry/DLT/submission status | Không rollback nghiệp vụ đã commit | `BACKLOG` |

### Hải

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P4-B01 | Investment | Finalize/lock commitments command | Idempotent + version check | `BACKLOG` |
| P4-B02 | Investment | Activate Notes sau disbursement | Ownership snapshot/version | `BACKLOG` |
| P4-B03 | Investment | Repair Note activation | Không đảo ledger Payment | `BACKLOG` |
| P4-B04 | Notification | Saga success/failure notification | Không rollback Saga | `BACKLOG` |

**Contract phải chốt:** finalize/unfinalize commitment, capture/disbursement, `DisbursementCompleted/Failed`, Note activation, proof request/reference; mọi message có `sagaId`, `loanId`, `step`, `attempt`, `eventId`.

**Phase gate P4:** funded loan giải ngân và thành `ACTIVE`; restart giữa Saga tiếp tục đúng bước; Kafka duplicate không giải ngân hai lần; Payment failure bù đúng; Fabric outage đi retry/DLT nhưng không làm sai tiền; Notes chỉ active sau kết quả tài chính hợp lệ.

## 10. P5 — Trả nợ, waterfall và portfolio

**Luồng tham chiếu:** F06, F09.

### Thái

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P5-A01 | Loan | Repayment schedule/installment state | Version và amount due | `BACKLOG` |
| P5-A02 | Payment | Collect/manual payment/auto-debit cơ bản | Provider/idempotency reference | `BACKLOG` |
| P5-A03 | Payment | Waterfall fee/penalty/interest/principal | Rule version + rounding remainder | `BACKLOG` |
| P5-A04 | Payment | Phân bổ investor wallets | Balanced ledger | `BACKLOG` |
| P5-A05 | Loan | Consume repayment, overdue/closed transition | Không chạy lại collection | `BACKLOG` |
| P5-A06 | Blockchain | Repayment proof | Reconciliation reference | `BACKLOG` |

### Hải

| ID | Module | Công việc | Đầu ra bắt buộc | Trạng thái |
|---|---|---|---|---|
| P5-B01 | Investment | Ownership snapshot contract | Version hiệu lực tại thời điểm trả | `BACKLOG` |
| P5-B02 | Investment | Update Note principal/interest | Consumer idempotent | `BACKLOG` |
| P5-B03 | Investment | Portfolio và cashflow projection | Rebuild được từ event | `BACKLOG` |
| P5-B04 | Notification | Due/paid/overdue/investor-credit templates | Preference + idempotency | `BACKLOG` |

**Invariant bắt buộc:** tiền thu = phí + phạt/lãi + gốc + rounding remainder; không mất tiền do làm tròn; projection lỗi không thu tiền lần hai.

**Phase gate P5:** một kỳ trả nợ chạy xuyên Loan–Payment–Investment–Blockchain–Notification; ledger cân; schedule và Notes đúng; duplicate/restart không nhân đôi thu hoặc phân bổ.

## 11. P6 — Hardening và demo luồng chính

| ID | Owner | Công việc | Kết quả | Trạng thái |
|---|---|---|---|---|
| P6-C01 | Thái + Hải | E2E vertical slice từ đăng ký đến một kỳ trả nợ | Script/demo lặp lại được | `BACKLOG` |
| P6-C02 | Thái | Test concurrency/financial reconciliation | Báo cáo invariant | `BACKLOG` |
| P6-C03 | Hải | Test model/XAI, matching và projection rebuild | Báo cáo metric | `BACKLOG` |
| P6-C04 | Thái + Hải | Chaos cases: Kafka/AI/Fabric/DB timeout, restart Saga | Evidence failure recovery | `BACKLOG` |
| P6-C05 | Thái + Hải | Security/PII/log review | Không secret/PII leak | `BACKLOG` |
| P6-C06 | Thái + Hải | Performance test và N+1/query review | Baseline latency/throughput | `BACKLOG` |
| P6-C07 | Thái + Hải | Đồng bộ OpenAPI/event catalog/diagram/báo cáo | Tài liệu khớp code thật | `BACKLOG` |

**Phase gate P6:** vertical slice chính đạt Definition of Done, có demo sạch và demo failure, kết quả test/metric lưu được, không còn blocker mức nghiêm trọng trước khi mở rộng diện rộng.

## 12. P7 — Chức năng mở rộng sau luồng chính

Các task có thể chạy song song theo ownership, nhưng mỗi task vẫn phải qua Definition of Ready/Done.

### Thái

| ID | Module | Chức năng | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| P7-A01 | Loan | Trả nợ sớm | P5 | `BACKLOG` |
| P7-A02 | Loan | Tái cơ cấu | P5 + consent contract | `BACKLOG` |
| P7-A03 | Loan | NPL policy/dashboard | P5 + reporting | `BACKLOG` |
| P7-A04 | Loan | SmartCA adapter | Contract/sandbox ký số | `BACKLOG` |
| P7-A05 | Payment | Payment gateway nạp/rút thật hoặc sandbox | P3 | `BACKLOG` |
| P7-A06 | Payment | Auto-debit nâng cao | P5 | `BACKLOG` |
| P7-A07 | Blockchain | Explorer | P4 | `BACKLOG` |
| P7-A08 | Blockchain | Integrity reconciliation | P4/P5 | `BACKLOG` |

### Hải

| ID | Module | Chức năng | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| P7-B01 | Investment | Auto-invest | P3 | `BACKLOG` |
| P7-B02 | Investment | Secondary market/Note transfer | P5 + payment settlement | `BACKLOG` |
| P7-B03 | AI | SHAP/XAI nâng cao | P1 | `BACKLOG` |
| P7-B04 | AI | Champion/challenger + backtest | Model registry/dataset | `BACKLOG` |
| P7-B05 | AI | Fraud detection | Payment behavior contract | `BACKLOG` |
| P7-B06 | AI | Document forgery | P1 eKYC | `BACKLOG` |
| P7-B07 | AI/Loan contract | Early warning | P5 behavior snapshot | `BACKLOG` |
| P7-B08 | User/AI | NFC CCCD PoC | Thiết bị/pháp lý rõ | `BACKLOG` |
| P7-B09 | Notification | Đa kênh email/SMS/push | Provider adapter | `BACKLOG` |
| P7-B10 | User | Admin user/RBAC nâng cao | P0 security | `BACKLOG` |

## 13. Nhịp phối hợp đề xuất

### Trước khi bắt đầu một task liên service

1. Owner mở issue/task ID từ roadmap.
2. Hai bên chốt contract bằng OpenAPI/event JSON example và error cases.
3. Producer owner cung cấp fixture; consumer owner viết contract test trên fixture.
4. Chuyển task `READY`; mỗi người chỉ sửa module của mình.

### Khi đang triển khai

- Cập nhật thay đổi contract ngay, không chờ đến lúc merge.
- Ghi blocker bằng mẫu: `Task`, `Owner`, `Blocked by`, `Cần ai quyết định`, `Ngày phát hiện`, `Phương án tạm`.
- Không mock tùy ý khác contract; mock phải dùng cùng fixture/version.

### Khi tích hợp

1. Chạy test riêng từng module.
2. Chạy contract test producer/consumer.
3. Chạy happy path xuyên service.
4. Chạy duplicate, timeout và restart case tối thiểu.
5. Hai owner xác nhận state và dữ liệu ở từng SoR, không chỉ nhìn response UI.

## 14. Nhật ký quyết định và blocker

Thêm dòng mới, không sửa mất lịch sử đã dùng để triển khai.

| Ngày | Task/Flow | Quyết định hoặc blocker | Owner quyết định | Ảnh hưởng contract | Trạng thái |
|---|---|---|---|---|---|
| YYYY-MM-DD | P?-???/F?? | Mô tả | Thái/Hải/Cả hai | Có/Không + version | Open/Resolved |

## 15. Quy tắc cập nhật roadmap

- Owner cập nhật task khi chuyển `IN_PROGRESS`, `BLOCKED`, `REVIEW`, `DONE`.
- Chỉ đánh `DONE` sau khi có bằng chứng test và điều kiện phase gate liên quan.
- Thêm/bỏ/đổi owner chức năng phải được cả hai xác nhận và cập nhật ownership/rule nếu boundary đổi.
- Roadmap mô tả kế hoạch thực thi; nếu xung đột với rule kiến trúc/an toàn thì rule ưu tiên và roadmap phải được sửa.

