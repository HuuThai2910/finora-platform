---
task_id: LN-004
title: Direct-submit Loan Application và immutable snapshots
owner: Thai
status: REVIEW
roadmap_task: P1-A02
depends_on: [LN-003, LN-006]
created_at: 2026-08-02
updated_at: 2026-09-05
approved_by: Thai
approved_at: 2026-08-03
accepted_by: Thai
accepted_at: 2026-08-08
---

# LN-004 — Tạo Loan Application trực tiếp ở SUBMITTED

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Loan Application là hồ sơ chính thức ghi nhận borrower muốn vay bao nhiêu, trong bao lâu, cho mục đích gì và khả năng tài chính tự khai tại thời điểm nộp. Form chưa bấm “Nộp hồ sơ” chỉ nằm trên thiết bị; backend không tạo Draft. Khi nộp thành công, dữ liệu được chụp lại và không tự đổi theo Product hoặc thông tin mới về sau.

### Ai tham gia và nhìn thấy gì?

- Borrower chọn Product, số tiền, kỳ hạn, nhập thông tin tài chính, xem lịch ban đầu theo base rate rồi nộp.
- Loan kiểm tra Product, điều kiện nhập, quyền sở hữu và chống gửi trùng.
- Fineract tính lịch trả dự kiến; LN-004 chỉ lưu bản chụp kết quả.
- Admin/AI dùng đúng hồ sơ đã nộp ở các LN sau; không sửa âm thầm dữ liệu borrower.

### Luồng chính trên UI

```text
Chọn Product → nhập form → xem preview → xác nhận đã đọc thông tin giá
→ bấm Nộp → Loan tính lại/kiểm tra → lưu SUBMITTED + các bản chụp → trả mã hồ sơ
```

Nếu borrower thoát trước khi nộp, không có hồ sơ backend. Nếu bấm hai lần cùng yêu cầu, idempotency bảo đảm chỉ có một hồ sơ. Nếu Fineract không tính được lịch, không tạo hồ sơ thiếu dữ liệu.

### Dữ liệu borrower nhập và ảnh hưởng

| Dữ liệu | Người dùng hiểu là gì | Hệ thống dùng lúc nào | Nếu thiếu hoặc sai | Vì sao phải lưu |
|---|---|---|---|---|
| `loanProductId` | Gói vay đã chọn | Lấy lãi suất, cách trả và giới hạn | Product không còn bán thì từ chối | Biết hồ sơ dựa trên chính sách nào |
| `requestedAmount` | Số tiền mong muốn nhận | Preview, AI, duyệt và hợp đồng | Ngoài khoảng Product thì từ chối | Đây là nghĩa vụ gốc borrower yêu cầu |
| `requestedTermMonths` | Số tháng muốn trả | Preview, AI và hợp đồng | Ngoài khoảng Product thì từ chối | Quyết định số kỳ và dòng tiền mỗi tháng |
| `purposeCode` | Nhóm mục đích chuẩn | Báo cáo, AI và hàng chờ admin | Không thuộc danh mục thì từ chối | Các hồ sơ cùng mục đích được hiểu thống nhất |
| `purposeDetail` | Giải thích cụ thể | Admin đọc khi cần, bắt buộc với `OTHER` | Quá dài/thiếu với OTHER thì từ chối | Không ép mọi nhu cầu vào tên nhóm chung |
| `declaredMonthlyIncome` | Thu nhập tháng tự khai | Tính thu nhập năm và DTI cho AI/admin | Không dương thì từ chối | Đóng băng thông tin đã dùng để đánh giá |
| `employmentLengthMonths` | Thời gian làm việc | Kiểm tra hợp lý và AI | Giá trị vô lý thì từ chối/review | Phản ánh độ ổn định nghề nghiệp tự khai |
| `educationLevel` | Trình độ học vấn | Admin/future policy; AI v17 hiện không dùng | Có thể để trống theo policy | Không cần hỏi lại khi policy mở rộng |
| `homeOwnership` | Tình trạng chỗ ở | AI/admin đánh giá | Ngoài danh mục thì từ chối | Chuẩn hóa alternative data |
| `monthlyDebtObligations` | Tổng nghĩa vụ nợ mỗi tháng | Tính DTI | Âm thì từ chối | Đánh giá phần thu nhập đã phải dùng trả nợ |
| `expectedDisbursementDate` | Ngày dự kiến nhận tiền | Fineract tính các mốc trả | Ngày quá khứ thì từ chối | Lịch dự kiến phụ thuộc mốc giải ngân |
| disclosure version/accepted | Xác nhận đã xem cách tính và điều khoản dự kiến | Trước khi nộp | Chưa đồng ý thì không nộp | Lưu bằng chứng consent cho nội dung đã hiển thị |

Borrower không nhập lãi suất, installment, DTI, lịch sử quá hạn hoặc `borrowerId`; hệ thống lấy/tính từ nguồn sở hữu để tránh tự khai sai hoặc giả mạo.

### Vì sao có ba nhóm bản chụp?

- Product snapshot giữ amount/term, min/base/max rate và cách trả đúng lúc nộp, dù admin sửa Product sau đó.
- Financial snapshot giữ dữ liệu tự khai và các số dẫn xuất đã dùng để đánh giá.
- Schedule calculation snapshot `SUBMISSION_SCORING` giữ lịch ban đầu theo base rate để UI, AI và admin dùng cùng một kết quả. Sau scoring, LN-007 tạo thêm snapshot `CONTRACT` theo final rate; đây không phải hồ sơ mới.

Đây không phải ba hồ sơ hay ba hợp đồng; tất cả là bằng chứng bên trong của một `LoanApplication`.

### Ví dụ và điều kiện hoàn thành

Borrower chọn Product base 15%/năm trong khung 12–18%, vay 50 triệu trong 12 tháng, thu nhập 25 triệu, nợ tháng 3 triệu. Hệ thống tính DTI 12%, nhận lịch ban đầu từ Fineract rồi tạo một hồ sơ `SUBMITTED`. Task đạt yêu cầu khi retry không sinh hồ sơ thứ hai, Product thay đổi không làm đổi snapshot, lỗi Fineract không để bản ghi dở dang và borrower chỉ xem/withdraw được hồ sơ của mình.

Phần hiển thị và xác nhận phải tuân theo [`LEGAL-DISCLOSURE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-disclosure-01--công-bố-lãi-và-lịch-trả) và dữ liệu hồ sơ theo [`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md#legal-data-01--dữ-liệu-hồ-sơ-và-cccd).

## 1. Nghiệp vụ cần đạt

Borrower điền form ở UI. Nếu thoát trước khi gửi thì backend không có hồ sơ. Khi nhấn nộp, Loan validate dữ liệu, dùng Fineract tính schedule và tạo Application `SUBMITTED` cùng các snapshot bất biến.

```text
UI giữ form tạm
    → borrower xem preview Fineract
    → POST Loan Application
    → Loan validate Product/profile input
    → Loan gọi Fineract calculate schedule
    → transaction tạo Application SUBMITTED
       + Product snapshot
       + Financial snapshot
       + ScheduleCalculationSnapshot
       + StatusHistory null → SUBMITTED
```

Không còn backend Draft, update Draft, cancel Draft hoặc submit Draft.

## 2. Phạm vi

### Trong task

- Sửa LoanApplication/schema/API từ Draft sang direct submit.
- Bổ sung education, idempotency key, min/base/max-rate, repayment và core mapping snapshots.
- Loại dữ liệu delinquency/public record tự khai.
- Tạo `ScheduleCalculationSnapshot` bất biến.
- Thay cancel Draft bằng withdraw trước quyết định.
- Viết lại V2 thành schema Application cuối vì migration cũ chưa merge/chưa chạy trên Neon dùng chung.
- Test transaction, ownership, idempotency, N+1 và Postman.

### Ngoài task

- Chưa gọi AI; thuộc LN-007.
- Chưa quyết định approve/reject; thuộc LN-008.
- Chưa JWT thật; vẫn dùng provider mock tập trung.
- Không lưu official active-loan schedule; Fineract là nguồn chuẩn.

## 3. Request tạo Application

`POST /api/v1/loan-applications` với header `Idempotency-Key`.

```json
{
  "loanProductId": 1,
  "requestedAmount": "50000000",
  "requestedTermMonths": 12,
  "purposeCode": "EDUCATION",
  "purposeDetail": "Học phí chương trình chuyên môn",
  "declaredMonthlyIncome": "25000000",
  "employmentLengthMonths": 60,
  "educationLevel": "UNIVERSITY",
  "homeOwnership": "RENT",
  "monthlyDebtObligations": "3000000",
  "expectedDisbursementDate": "2026-08-15",
  "pricingDisclosureVersion": "PRICING-V1",
  "pricingDisclosureAccepted": true
}
```

Client không được gửi borrower ID, status, rate, installment, DTI, annual income, credit history, createdBy hoặc updatedBy.

## 4. Tác dụng từng field request

| Field | Kiểu Java/API | Nguồn | Dùng ở đâu | Validation |
|---|---|---|---|---|
| `loanProductId` | `Long` | UI catalog | Lấy Product/current mapping | Product ACTIVE + SYNCED |
| `requestedAmount` | decimal string/`BigDecimal` | Borrower | Fineract, AI, Contract | Trong Product range, scale 2 |
| `requestedTermMonths` | `Integer` | Borrower | Fineract, AI, Contract | Trong Product range |
| `purposeCode` | enum | Loan catalog | AI mapper/report/admin | Allowlist |
| `purposeDetail` | string nullable | Borrower | Admin/Contract nếu cần | Bắt buộc khi OTHER; max 500 |
| `declaredMonthlyIncome` | decimal string | Borrower | Derive annual income/DTI/AI | > 0 |
| `employmentLengthMonths` | integer nullable | Borrower | AI `emp_length`, review | >= 0, hợp lý với age ở LN-005 |
| `educationLevel` | enum nullable | Borrower | Review/future model | AI v17 hiện không nhận |
| `homeOwnership` | enum | Borrower | AI/review | `RENT/OWN/MORTGAGE/OTHER` |
| `monthlyDebtObligations` | decimal string | Borrower | Derive DTI | >= 0 |
| `expectedDisbursementDate` | `LocalDate` | UI/Loan policy | Schedule dự kiến | Không trước business date |
| disclosure fields | string/boolean | UI/Borrower | Bằng chứng đã xem terms dự kiến | Version bắt buộc, accepted=true |

Không còn `declaredDelinquenciesLast2Years` và `declaredPublicRecordCount`. LN-007 lấy từ `BorrowerCreditProfile`.

## 5. `LoanApplication` fields mục tiêu

| Nhóm | Field/PostgreSQL | Tác dụng |
|---|---|---|
| ID | `id BIGINT`, `application_number VARCHAR(30)` unique | Khóa và số hiển thị |
| Ownership | `borrower_id VARCHAR(100)` | Lấy từ CurrentUserProvider, không body/FK User |
| Idempotency | `idempotency_key VARCHAR(150)` | Unique theo borrower/request |
| Requested | Product ID, amount DECIMAL(18,2), term SMALLINT, purpose code/detail | Điều borrower xin vay |
| Financial | embedded snapshot mục 6 | Alternative data tự khai |
| Product | embedded snapshot mục 7 | Terms Product tại submit |
| Calculation | `submission_calculation_snapshot_id BIGINT` | Phép tính Fineract đã dùng |
| Scoring | `latest_assessment_id BIGINT` nullable, `scoring_status VARCHAR(30)` | LN-007 cập nhật |
| Decision | reason/actor/time/policy nullable | LN-008 cập nhật, không tạo cột “để dành” nếu migration LN-008 tách riêng |
| Lifecycle | `status VARCHAR(30)`, `submitted_at TIMESTAMP(6) WITH TIME ZONE`, `version BIGINT` | State/concurrency |
| Audit | created/updated by + timestamps | Truy vết |

`latestAssessmentId` chỉ thêm trong migration LN-007 nếu task đó chưa chạy; LN-004 không cần tạo nullable future column sớm.

## 6. `ApplicantFinancialSnapshot`

| Field Java | PostgreSQL | Cách tạo | Consumer |
|---|---|---|---|
| `declaredMonthlyIncome` | `DECIMAL(18,2)` | Request | Admin/derive |
| `annualIncomeSnapshot` | `DECIMAL(18,2)` | monthly × 12, HALF_UP | AI |
| `employmentLengthMonths` | `SMALLINT` nullable | Request | LN-005 rule/AI mapper |
| `educationLevel` | `VARCHAR(30)` nullable | Request | Admin/future model |
| `homeOwnership` | `VARCHAR(20)` | Request | AI |
| `monthlyDebtObligations` | `DECIMAL(18,2)` | Request | DTI |
| `dtiSnapshot` | `DECIMAL(9,4)` | debt/monthly income × 100 | AI/admin |
| `informationSource` | `VARCHAR(30)` | `SELF_DECLARED` | Không gọi là verified/CIC |
| `capturedAt` | `TIMESTAMP(6) WITH TIME ZONE` | Clock | Audit UTC |

Embedded cùng Application vì đúng 1:1, không có lifecycle/repository riêng. Sau submit bất biến.

## 7. Product snapshot

| Field | Tác dụng |
|---|---|
| code/name/version | Hiển thị và truy vết Product lịch sử |
| min/max amount, min/max term | Chứng minh validation tại submit |
| `minAnnualInterestRate` | Biên lãi thấp nhất Product tại thời điểm nộp |
| `annualInterestRate` | Base rate đưa Fineract tính lịch ban đầu và gửi AI dưới tên `int_rate` |
| `maxAnnualInterestRate` | Biên lãi cao nhất Product tại thời điểm nộp |
| `repaymentMethod` | Cách schedule được tính |
| `fineractProductId` | Core product đã dùng |
| `coreMappingId/configVersion` | Truy vết mapper/tenant policy |

Nếu admin đổi Product sau submit, Application vẫn dùng snapshot cũ.

## 8. `ScheduleCalculationSnapshot`

| Field | PostgreSQL | Tác dụng |
|---|---|---|
| `id`, `applicationId` | BIGINT/FK | Liên kết hồ sơ |
| `purpose` | VARCHAR(30) | `SUBMISSION_SCORING` cho lịch ban đầu; LN-007 thêm `CONTRACT` cho lịch cuối |
| `requestId` | VARCHAR(100) unique | Correlation |
| `fineractProductId` | BIGINT | Core config |
| `expectedDisbursementDate` | DATE | Cơ sở schedule |
| `requestSnapshotJson` | JSON | Payload đã loại secret |
| `periodsSnapshotJson` | JSON | Schedule dự kiến bất biến |
| totals | DECIMAL(18,2) | principal/interest/fees/repayment |
| `firstInstallment`, `maximumInstallment` | DECIMAL(18,2) | UI/AI |
| `responseHash`, `calculationPolicyVersion` | VARCHAR | Audit/version |

`LoanApplicationResponse.calculationSnapshot.periods` trả danh sách có kiểu rõ ràng gồm kỳ, ngày,
số ngày, gốc, lãi, phí, phạt, tổng đến hạn và dư nợ. Mapper đọc từ `periodsSnapshotJson` đã lưu;
không gọi lại Fineract khi borrower hoặc admin xem hồ sơ vì kết quả mới có thể khác bằng chứng lúc nộp.
| `calculatedAt`, audit fields | datetime | Truy vết |

Đây là evidence snapshot, không phải bảng schedule đang hoạt động và không được update khi Fineract thay đổi sau này.

### 8.1 Quan hệ dữ liệu hiện tại sau LN-004

```text
LoanProduct (1) ──────────── (N) LoanApplication
      │                              │
      │                              ├── (1) ApplicantFinancialSnapshot [embedded]
      │                              ├── (1) ProductSnapshot [embedded]
      │                              ├── (1..2) ScheduleCalculationSnapshot
      │                              └── (N) LoanApplicationStatusHistory
      │
      └── (N) FineractProductMapping
                    ▲
                    └── Application giữ coreMappingId/fineractProductId dạng snapshot
```

- `LoanProduct 1-N LoanApplication`: một sản phẩm có thể được nhiều người chọn; mỗi hồ sơ chỉ nộp theo đúng một Product/version tại thời điểm submit.
- `LoanApplication 1-N ScheduleCalculationSnapshot`: mỗi hồ sơ có đúng một lịch `SUBMISSION_SCORING` và có thêm tối đa một lịch `CONTRACT` nếu AI không từ chối. Unique key là `(application_id, purpose)`, vì vậy không thể tạo hai bản cùng mục đích.
- Financial/Product snapshot là các cột embedded cùng hàng Application, không phải bảng và không có repository/lifecycle riêng.
- History là `1-N` và append-only: một hồ sơ có nhiều lần đổi trạng thái, nhưng lịch sử cũ không bị sửa hoặc cascade-delete.
- `borrowerId` chỉ là logical ID sang User Service, không tạo foreign key xuyên database microservice.
- Eligibility, AI Assessment và Contract là quan hệ được bổ sung lần lượt ở LN-005/LN-007/LN-008; chúng không phải bảng do LN-004 tự tạo trước.

## 9. State machine

```text
null → SUBMITTED
SUBMITTED → WITHDRAWN
SUBMITTED → ELIGIBILITY_PENDING       (LN-005/LN-007)
ELIGIBILITY_PENDING → SCORING         (LN-007)
SCORING → SCORING_RETRY_PENDING       (LN-007)
SCORING → APPROVED/PENDING_REVIEW/REJECTED (LN-007, theo AI decision policy)
PENDING_REVIEW → APPROVED/REJECTED    (LN-008)
```

LN-004 chỉ triển khai `null → SUBMITTED` và `SUBMITTED → WITHDRAWN`. History append-only, state + history cùng transaction.

## 10. API thay đổi

### Giữ

- `POST /api/v1/loan-applications` nhưng semantics đổi thành direct submit.
- `GET /api/v1/loan-applications/{applicationNumber}`.
- `GET /api/v1/loan-applications/me` có pagination.
- `GET /api/v1/loan-applications/{applicationNumber}/history` có pagination/size limit.

### Xóa/thay thế

- Xóa update Draft.
- Xóa submit Draft endpoint.
- Xóa cancel Draft.
- Thêm `POST /api/v1/loan-applications/{applicationNumber}/withdraw` nhận `version` và reason.

## 11. Transaction và idempotency

`Transaction` ở đây là một nhóm thay đổi database phải cùng thành công hoặc cùng rollback. Không giữ transaction trong lúc gọi Fineract vì chờ mạng lâu sẽ giữ connection/lock và làm request khác bị nghẽn.

1. Validate identity/body/Product read model.
2. Gọi LN-006 calculate schedule **ngoài transaction**.
3. Mở transaction ngắn, đọc/lock Product version cần thiết và tái xác nhận ACTIVE/SYNCED/range.
4. Insert Application, ba snapshot và history `null → SUBMITTED` trong cùng transaction.
5. Unique borrower/idempotency key bảo đảm retry trả Application đã tạo.

Hai request cùng key nhưng body khác trả `409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD` dựa trên request hash.

Các tình huống cạnh tranh phải được xử lý rõ:

| Tình huống | Cách hệ thống bảo vệ | Kết quả borrower nhìn thấy |
|---|---|---|
| UI gửi hai lần cùng key/cùng body | unique key + request hash | Trả lại cùng một Application, không nhân đôi hồ sơ |
| Cùng key nhưng body khác | so request hash | 409 để UI sinh key mới hoặc giữ payload cũ |
| Admin sửa/deactivate Product khi Fineract đang tính lịch | khóa và kiểm tra lại status/version trước insert | Không tạo hồ sơ bằng Product đã hết hiệu lực |
| Withdraw và worker scoring chạy cùng lúc | `@Version`/lock + kiểm tra transition | Chỉ một transition hợp lệ commit, bên còn lại nhận conflict/invalid state |

## 12. Migration dự kiến

Sau khi LN-004 và dependency LN-003/LN-006 được duyệt, viết mới `V2__create_loan_applications.sql` trực tiếp theo thiết kế cuối:

- Tạo Application ở trạng thái đầu tiên `SUBMITTED`; không tạo cột/rule/API cho backend Draft.
- Tạo idempotency key/hash, education, min/base/max-rate, repayment/core mapping snapshots.
- Tạo `schedule_calculation_snapshots` và status history append-only.
- Không tạo các cột min/base/max rate snapshot hoặc declared delinquency/public record cũ.
- Dùng PostgreSQL identity và `TIMESTAMP(6) WITH TIME ZONE`; thêm FK, unique, check constraint và index phục vụ đúng query mục 13.
- Không tạo V4/backfill vì V2 cũ chưa merge, Neon chưa chạy migration và chưa có dữ liệu nghiệp vụ cần giữ.

Migration phải được test từ database đã chạy V1 cuối của LN-003, sau đó Hibernate `validate` phải pass với toàn bộ entity LN-003/LN-004.

## 13. Index và N+1

`Index` giống mục lục của cuốn sách: PostgreSQL tìm đúng nhóm hàng nhanh hơn thay vì đọc cả bảng. Đổi lại mỗi lần insert/update phải cập nhật index và tốn dung lượng, vì vậy chỉ tạo index bám đúng truy vấn thật.

| Index/constraint hiện tại | Query được phục vụ | Vì sao thứ tự cột như vậy |
|---|---|
| unique `(borrower_id, idempotency_key)` | create retry | Khoanh đúng borrower trước rồi chống trùng key của người đó |
| `idx_loan_applications_borrower_created(borrower_id, created_at DESC, id DESC)` | danh sách hồ sơ của borrower | lọc owner rồi phân trang mới nhất; `id` phá hòa khi cùng thời gian |
| `idx_loan_applications_worker(status, updated_at, id)` | worker lấy hồ sơ đến lượt xử lý | lọc state trước, lấy bản chờ lâu trước; đây là bản V4 thay index status/created cũ |
| `idx_loan_applications_product(loan_product_id, created_at DESC, id DESC)` | tra các hồ sơ tham chiếu Product | phục vụ audit/impact của một Product |
| `idx_application_histories_timeline(loan_application_id, created_at DESC, id DESC)` | timeline một hồ sơ | không quét history của hồ sơ khác |
| unique `schedule_calculation_snapshots.application_id` và `request_id` | lấy đúng evidence của hồ sơ/correlation | direct-submit hiện chỉ có một snapshot, nên chưa cần index timeline giả định |

`N+1` là lỗi một query lấy N hồ sơ rồi ORM chạy thêm một query cho từng history/snapshot, khiến 20 hồ sơ có thể thành 21+ query. List Application dùng DTO projection, không fetch history/calculation collections. Detail tải từng phần bằng query rõ; test ít nhất 3 Application để chứng minh query count gần cố định khi số dòng tăng.

## 14. Failure và error code

- Product không ACTIVE/SYNCED: `LOAN_PRODUCT_NOT_AVAILABLE`.
- Amount/term sai: `LOAN_TERMS_OUT_OF_RANGE`.
- Fineract lỗi: `CORE_LENDING_UNAVAILABLE`, không tạo Application.
- Duplicate same payload: trả resource cũ.
- Duplicate different payload: 409.
- Sai owner: 404/403 theo security policy thống nhất, không lộ resource.
- Withdraw sai state/version: `INVALID_APPLICATION_TRANSITION`/`VERSION_CONFLICT`.

## 15. Test/Postman/acceptance

- Direct submit happy path cho ANNUITY và EQUAL_PRINCIPAL.
- Thoát UI không request → không có DB record.
- Snapshot Product/financial/calculation đúng và bất biến.
- Không request/response chứa credit history tự khai.
- Duplicate/concurrent submit không tạo hai Application.
- Fineract timeout không tạo partial Application/history.
- Withdraw owner đúng; sai owner/state/version bị từ chối.
- Migration V1 + V2 tạo đúng schema cuối từ PostgreSQL 17 database sạch.
- Query count/N+1 và pagination.
- Postman có body/URL riêng từng case.

## 16. Nghiệm thu

```text
Plan decision: ACCEPTED
Reviewer: Thai
Reviewed at: 2026-08-08
Notes:
```

## 17. Luồng gọi API và thứ tự hàm thực tế

### 17.1 Borrower nộp hồ sơ

```text
POST /api/v1/loan-applications
→ LoanApplicationController.submit(idempotencyKey, request)
→ LoanApplicationServiceImpl.submit(request, idempotencyKey)       [không mở transaction dài]
  1. validateDisclosure(request)
  2. HashingService.sha256(request)
  3. LoanApplicationSubmissionStateService.findExisting(...)       [read-only transaction]
     └─ đã có cùng key/body → existingResponse(...) → trả hồ sơ cũ
  4. LoanApplicationSubmissionStateService.prepareContext(...)      [read-only transaction]
     → ProductRepository.findById
     → LoanProduct.requireAvailable/requireRequestedTerms
     → FineractProductMappingRepository.findById
  5. FineractScheduleGateway.calculateSchedule(...)                 [HTTP ngoài DB transaction]
  6. LoanApplicationSubmissionStateService.persist(...)             [write transaction]
     → ProductRepository.findByIdForSubmit                           [write lock]
     → kiểm tra lại Product version + mapping
     → ApplicantFinancialSnapshot.capture(...)
     → LoanApplication.submit(...)
     → ApplicationRepository.saveAndFlush
     → ScheduleCalculationSnapshot.submission(...)
     → CalculationRepository.saveAndFlush
     → Application.attachSubmissionCalculationSnapshot(...)
     → HistoryRepository.save(APPLICATION_SUBMITTED)
  7. LoanApplicationMapper.toResponse(application, calculation)
→ HTTP 201
```

Hai lần đọc Product là có chủ đích: lần đầu lấy cấu hình để gọi Fineract; lần sau khóa và xác nhận
Product chưa đổi trong lúc chờ mạng. Nếu đã đổi, toàn bộ ghi hồ sơ bị từ chối bằng
`LOAN_PRODUCT_CHANGED`, tránh lưu schedule của cấu hình cũ.

### 17.2 Idempotency và request cạnh tranh

```text
Cùng Idempotency-Key + cùng request hash
→ findExisting/existingResponse
→ trả đúng Application + calculation snapshot cũ

Cùng Idempotency-Key + body khác
→ existingResponse phát IDEMPOTENCY_KEY_REUSED (409)

Hai request đồng thời cùng vượt kiểm tra đầu
→ unique constraint chặn request thứ hai
→ catch DataIntegrityViolationException
→ đọc và trả resource mà request thắng đã tạo
```

### 17.3 Các API đọc và rút hồ sơ

| API | Thứ tự hàm | Điểm quan trọng |
|---|---|---|
| `POST /{number}/withdraw` | `controller.withdraw → service.withdraw → getApplication → LoanApplication.withdraw → save → history.save → getCalculation → mapper` | Application và history cùng transaction; domain check owner/state/version |
| `GET /{number}` | `getMine → getApplication → requireOwner → getCalculation → mapper` | Detail tải đúng một calculation snapshot |
| `GET /me` | `listMine → repository.findByBorrowerId(Pageable) → mapper(application, null)` | Không gọi calculation theo từng dòng, tránh N+1 |
| `GET /{number}/history` | `history → getApplication → requireOwner → historyRepository.findByLoanApplicationId(Pageable) → mapper` | History phân trang |
| `GET /api/v1/loan-purposes` | `LoanPurposeController.purposes → LoanApplicationServiceImpl.purposes → LoanPurpose.values` | Danh mục enum, chưa query database |

### 17.4 Transaction boundary cần ghi nhớ

- `LoanApplicationServiceImpl.submit()` không có `@Transactional`: đây là chủ ý để không giữ DB connection khi gọi Fineract.
- `prepareContext()` và `findExisting()` là read-only transaction ngắn.
- `persist()` là write transaction duy nhất ghi Application, ba nhóm snapshot và history.
- `withdraw()` ghi trạng thái và history trong cùng transaction.
- Mapper chỉ chạy sau khi state cần thiết đã được load; không dựa vào Open Session In View.

### 17.5 Vì sao cần hash và snapshot trong luồng submit?

| Dữ liệu/cơ chế | Hàm tạo hoặc sử dụng | Lý do cần | Không được hiểu là |
|---|---|---|---|
| `requestHash` | `HashingService.sha256(request)` và `existingResponse(...)` | Chứng minh cùng `Idempotency-Key` có đúng cùng nội dung form; body khác bị trả `IDEMPOTENCY_KEY_REUSED` thay vì dùng nhầm hồ sơ cũ | Mã hóa hoặc che giấu dữ liệu tài chính |
| JSON canonical | `HashingService.sha256(Object)` | Sắp xếp ổn định tên field/key trước SHA-256, để hai object có cùng nội dung không đổi hash chỉ vì thứ tự serialize/Map khác nhau | JSON dùng để hiển thị hoặc payload đã mã hóa |
| Product snapshot | `LoanApplication.submit(...)` | Giữ amount/rate/method/version người vay đã thấy; Product thay đổi sau này không sửa hồ sơ lịch sử | Bản tham chiếu luôn chạy theo Product hiện tại |
| `responseHash` lịch trả | `FineractSchedulePayloadMapper.toScheduleResult(...)` | Hash allowlist tổng tiền và các kỳ Fineract đã trả để lần sau đối chiếu đúng calculation được dùng cho hồ sơ/AI | Chữ ký số của Fineract hoặc bằng chứng pháp lý |
| `calculationPolicyVersion` | `ScheduleCalculationSnapshot.submission(...)` | Ghi phiên bản quy tắc mapper Fineract, giúp giải thích cùng response đã được chuẩn hóa theo contract nào | Phiên bản Product hoặc model AI |

Hash chỉ là “dấu vân tay” kiểm tra dữ liệu có giống nhau hay không. Loan vẫn phải bảo vệ snapshot JSON,
không được log toàn bộ request tài chính và không dùng SHA-256 này để lưu password.

### 17.6 Vai trò các hàm quan trọng trong submit

| Hàm | Vì sao cần tồn tại riêng | State/dữ liệu thay đổi |
|---|---|---|
| `validateDisclosure()` | Buộc borrower xác nhận đúng phiên bản nội dung lãi suất đã được công bố trước khi tạo nghĩa vụ | Không ghi DB; sai version dừng request |
| `findExisting()` | Chặn request lặp trước khi gọi Fineract, tránh tính lại lịch không cần thiết | Chỉ đọc Application theo borrower + key |
| `prepareContext()` | Chụp scalar Product/mapping rồi đóng transaction trước external call | Không tạo Application |
| `FineractScheduleGateway.calculateSchedule()` | Để Fineract — nguồn tính lịch — trả schedule; Loan không có công thức fallback | Chỉ gọi mạng, chưa ghi Application |
| `persist()` | Khóa và kiểm tra lại Product, sau đó ghi Application + snapshots + history nguyên tử | Tạo `SUBMITTED` và toàn bộ bằng chứng liên quan |
| `getSubmissionCalculation()` / `getFinalCalculation()` | Detail đọc riêng lịch ban đầu và lịch cuối theo purpose; list cố ý không tải periods để tránh N+1 | Chỉ đọc tối đa hai snapshot theo Application |

Phần adapter HTTP, kiểm tra response Fineract và failure mapping được mô tả tại LN-006; LN-004 chỉ
chịu trách nhiệm quyết định khi nào calculation đủ hợp lệ để trở thành snapshot của hồ sơ.

## 18. Kết quả triển khai hiện tại

- API đã tạo thẳng `SUBMITTED`; đã xóa Draft/update/submit/cancel backend cũ.
- Request dùng alternative data tự khai, không cò delinquency/public record tự khai.
- `Idempotency-Key` + request hash chống tạo trùng và từ chối key dùng cho body khác.
- Application, Product/financial snapshots, Fineract calculation snapshot và history được ghi nguyên tử.
- List không tải calculation/history theo từng dòng, tránh N+1; detail tải snapshot riêng.
- Postman đã viết lại theo luồng direct-submit/withdraw.
- Integration test PostgreSQL đã pass, bao gồm direct-submit, snapshot, idempotency,
  withdraw/history và query-count chống N+1.
