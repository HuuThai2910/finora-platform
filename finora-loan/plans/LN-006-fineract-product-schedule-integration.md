---
task_id: LN-006
title: Apache Fineract 1.15 Product và Schedule integration
owner: Thai
status: REVIEW
roadmap_task: P0-C05_P1-A04
depends_on: [LN-003, FINERACT_1_15_LOCAL_FIXTURE]
created_at: 2026-08-02
updated_at: 2026-10-25
approved_by: Thai
approved_at: 2026-08-03
accepted_by: Thai
accepted_at: 2026-08-08
---

# LN-006 — Tích hợp Fineract Product và Schedule

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Fineract là hệ thống core chịu trách nhiệm áp dụng cấu hình khoản vay và tính lịch trả. LN-006 tạo một cổng tích hợp duy nhất để FINORA đồng bộ Product và xin lịch trả dự kiến; Loan không tự duy trì một công thức khác có thể cho kết quả lệch.

### Ai tham gia và nhìn thấy gì?

- Admin bấm “Đồng bộ core”, theo dõi thành công/thất bại rồi mới kích hoạt Product.
- Borrower nhập số tiền/kỳ hạn và nhìn thấy khoản trả từng kỳ, tổng lãi, tổng phí theo base rate trước khi nộp; sau scoring sẽ thấy lịch cuối theo final rate trước khi ký.
- LN-003 sở hữu Product FINORA; LN-006 chỉ chuyển cấu hình sang Fineract và lưu mapping/kết quả cần thiết.
- LN-004 dùng đúng cổng tính lịch này để tạo bản chụp khi nộp hồ sơ.

### Hai luồng nghiệp vụ

```text
Đồng bộ Product:
DRAFT → tạo command → gọi Fineract → lưu Fineract Product ID → SYNCED → được kích hoạt

Xem lịch ban đầu:
Borrower chọn amount/term/date → Loan kiểm tra Product → Fineract tính theo base rate → UI hiển thị

Tạo lịch cuối:
AI trả grade/decision → Loan tính final rate → Fineract tính lại → lưu snapshot CONTRACT
```

Gửi lại cùng một command không được tạo Product core thứ hai. Nếu request đã tới Fineract nhưng response bị mất, hệ thống phải tìm/đối chiếu theo business key trước khi gửi lại.

### Bốn thành phần dễ nhầm

| Thành phần | Cách hiểu đời thường | Trách nhiệm |
|---|---|---|
| `FineractProductMapping` | Bản nối hai Product | Biết phiên bản Product FINORA nào tương ứng với ID nào trong Fineract |
| `FineractCommand` | Phiếu công việc lưu trong DB | Nhớ cần gọi side effect gì, trạng thái/attempt/lỗi và restart phải tiếp tục từ đâu |
| Retry worker | Tiến trình nền lấy phiếu đến hạn | Gọi lại đúng execution path có lock, lease và reconcile; không phải microservice riêng |
| Fineract REST adapter | Người phiên dịch kỹ thuật | Chuyển contract nội bộ sang HTTP/payload Fineract và phân loại response/error |

Kafka không trực tiếp gọi Fineract. Product sync hiện là việc nội bộ Loan nên command worker gọi REST là
đủ; Outbox/Kafka chỉ cần khi kết quả phải đi qua service boundary. Webhook là chiều Fineract chủ động gọi
callback FINORA, không phải luồng đang triển khai ở LN-006.

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Người dùng hiểu là gì | Nguồn | Hệ thống dùng thế nào | Khi lỗi |
|---|---|---|---|---|
| `fineractProductId` | Product tương ứng trong core | Fineract | Mọi lần tính lịch/tạo khoản vay | Không có thì Product FINORA chưa sẵn sàng |
| `annualInterestRate` | Base rate đã công bố | LN-003 snapshot | Sync Product core và tính lịch ban đầu | Kết quả khác cấu hình thì sync thất bại |
| `finalAnnualInterestRate` | Lãi suất sau định giá rủi ro | Loan pricing policy | Chỉ dùng để tính lịch `CONTRACT` và lập hợp đồng | Ngoài min/max/20% thì từ chối trước khi gọi core |
| `repaymentMethod` | Trả góp đều hoặc gốc đều | LN-003 | Ánh xạ cách Fineract phân bổ từng kỳ | Mapping không hỗ trợ thì không kích hoạt |
| amount/term/date | Lựa chọn cụ thể của borrower | UI/LN-004 | Đầu vào tính lịch | Ngoài chính sách thì từ chối trước khi gọi core |
| `periods[]` | Từng kỳ phải trả dự kiến | Fineract | UI và snapshot hồ sơ/hợp đồng | Không tự dựng periods thay thế |
| totals/first/maximum installment | Tổng nghĩa vụ và mức trả đáng chú ý | Fineract | Giải thích cho borrower và gửi AI | Response thiếu/sai schema thì fail closed |
| `requestHash/configVersion/responseHash` | Dấu vết của cấu hình đã tính | Loan | Chống trùng và đối soát | Không dùng raw credential/payload nhạy cảm |

### Ví dụ thực tế

Với Product base 15%/năm, borrower chọn 50 triệu/12 tháng. Fineract trả lịch ban đầu. Sau AI grade A, Loan có thể ra final rate 14.5% rồi gọi Fineract lần nữa để tạo lịch cuối. Nếu cách trả `ANNUITY`, các khoản dự kiến gần bằng nhau; nếu `EQUAL_PRINCIPAL`, kỳ đầu thường cao nhất rồi giảm dần. UI chỉ hiển thị kết quả Fineract, không tự tính lại bằng JavaScript hoặc Loan.

Khung lãi/thời hạn theo [`LEGAL-RATE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-rate-01--khung-minbasemax-và-trần-lãi-suất) và [`LEGAL-TERM-01`](../../docs/LEGAL-COMPLIANCE.md#legal-term-01--thời-hạn-product-và-hợp-đồng); nghĩa vụ hiển thị lịch theo [`LEGAL-DISCLOSURE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-disclosure-01--công-bố-lãi-và-lịch-trả).

### Trường hợp lỗi và điều kiện hoàn thành

- Fineract không sẵn sàng: preview trả lỗi phụ thuộc rõ, không tạo Application và không dùng công thức dự phòng.
- Lỗi validation/contract không retry; timeout/429/5xx chỉ retry có giới hạn.
- Task hoàn tất khi cả hai repayment method được fixture xác nhận, sync/preview/retry/reconcile pass và secret không xuất hiện trong log/database.

## 1. Mục tiêu nghiệp vụ

Task này tạo biên tích hợp duy nhất để Loan:

- đồng bộ Product FINORA sang core Product Fineract;
- yêu cầu Fineract tính lịch trả nợ dự kiến;
- chuẩn hóa response cho UI, Application snapshot và AI;
- retry/reconcile mà không tạo Product hoặc kết quả logic trùng.

Fineract là engine tính schedule. Loan không giữ công thức annuity/equal-principal dự phòng.

## 2. Hạ tầng và ownership

```text
finora-loan → PostgreSQL 17/Neon Project riêng `finora-loan`, DB `neondb`
      |
      └── HTTPS REST
              ↓
Apache Fineract 1.15.0 → PostgreSQL riêng
                         ├── fineract_tenants
                         └── fineract_default
```

- Host local dự kiến Fineract `18443`, internal `8443`.
- PostgreSQL host local dự kiến `15432`, internal `5432`.
- Loan đã dùng PostgreSQL driver cho database của chính Loan nhưng không dùng connection/role đó để đọc DB Fineract và không quản lý schema core.
- Compose/registry là vùng chung, Hải review trước merge.
- Image/digest, tenant, office, accounting và payment type chỉ khóa sau smoke test 1.15.0.

## 3. Phạm vi

### Trong task

- Local Fineract 1.15.0 + PostgreSQL profile/health/bootstrap fixture.
- `FineractLoanProductGateway` và `FineractScheduleGateway` integration boundaries.
- HTTP client, mapper, configuration, timeout/circuit breaker/retry.
- Product template/create, schedule calculation.
- `FineractCommand` persistence/retry/reconcile cho command có side effect.
- Preview API và `ScheduleCalculationResult` chuẩn hóa.
- Contract test trên fixture/Swagger local.

### Ngoài task

- Không sửa module AI/User/Investment.
- Không submit/approve/disburse core loan; thuộc LN-011.
- Không repayment; thuộc Payment/LN-013.
- Không persist Application; thuộc LN-004.

## 4. Cấu hình runtime

| Property | Nguồn | Quy tắc |
|---|---|---|
| base URL | env | Không hard-code hostname production |
| tenant ID | env/secret config | Local `default`; header bắt buộc |
| username/password hoặc token | secret/env | Không commit/log |
| connect timeout | config | Fail fast, metric riêng |
| read timeout | config | Schedule có timeout phù hợp nhưng hữu hạn |
| retry max/backoff | config | Chỉ timeout/429/5xx và operation idempotent |
| product config version | code/config | Truy vết mapper/tenant assumptions |

Không bật retry cho validation/authorization/business 4xx. Circuit breaker open trả lỗi dependency rõ, không tự tính schedule.

### 4.1. Readiness và cô lập lỗi theo chức năng

`healthy` của container Fineract phải có nghĩa là một API nghiệp vụ có xác thực đã trả HTTP 200. Chỉ kiểm tra
cổng TCP 8443 là sai vì Fineract 1.15 có thể mở cổng trước, sau đó mới khởi tạo Jersey ở request API đầu tiên;
request borrower lúc đó có thể chờ hơn 10 giây rồi timeout.

Loan dùng hai circuit breaker độc lập:

| Circuit | Thao tác | Vì sao phải tách |
|---|---|---|
| `fineract-product` | tìm/tạo Product core | Lỗi cấu hình hoặc đồng bộ của admin không được chặn borrower xem lịch trả |
| `fineract-schedule` | tìm Preview Client và tính schedule | Lỗi preview không được làm hỏng retry/reconcile Product |

HTTP 4xx không được tính là sự cố tạm thời để mở circuit. Timeout của TimeLimiter và
`SocketTimeoutException` đều được chuẩn hóa thành `FINERACT_TIMEOUT`, có `retryable=true`; lỗi kết nối thật mới là
`FINERACT_UNAVAILABLE`. Mỗi circuit tự chuyển `OPEN → HALF_OPEN` sau 30 giây và chỉ cho một số request thăm dò
có giới hạn, vì vậy người dùng không phải bấm liên tục để “đánh thức” dependency.

## 5. Mapping Product

| FINORA | Fineract | Quy tắc |
|---|---|---|
| Product `annualInterestRate` (base) | `interestRatePerPeriod` | `15.0000` nghĩa là 15%/năm; dùng khi sync và preview ban đầu |
| `ANNUITY` | `amortizationType=1` | Equal installments |
| `EQUAL_PRINCIPAL` | `amortizationType=0` | Equal principal |
| calculation policy | `interestType=0` | Declining balance; không có nghĩa lãi suất Product là cố định |
| cùng chu kỳ trả nợ | `interestCalculationPeriodType=1` | Lãi được tính theo kỳ trả hàng tháng; khớp payload đã kiểm thử |
| annual rate | `interestRateFrequencyType=3` | Per year |
| monthly repayment | `repaymentEvery=1`, frequency `2` | Months |
| amount range | principal/min/max principal | Decimal string |
| term range | number/min/max repayments | Monthly V1 |

### Ràng buộc schedule engine theo cách trả nợ

Fineract 1.15 không chỉ dựa vào `amortizationType`. Với lịch `PROGRESSIVE`, engine vẫn tạo EMI có
tổng tiền trả gần đều và phần gốc tăng dần, kể cả khi request gửi `amortizationType=0`. Vì vậy FINORA
khóa đồng thời ba thuộc tính sau khi tạo, tính thử và booking khoản vay:

| Cách trả | Schedule engine | Transaction strategy | Kết quả nghiệp vụ |
|---|---|---|---|
| `ANNUITY` | `PROGRESSIVE` đối với V2–V4 | `advanced-payment-allocation-strategy` | Tổng tiền mỗi kỳ gần đều, gốc tăng dần và lãi giảm dần |
| `EQUAL_PRINCIPAL` | `CUMULATIVE` | `mifos-standard-strategy` | Gốc mỗi kỳ bằng nhau (kỳ cuối xử lý phần làm tròn), tổng tiền kỳ sau giảm dần |

V4 vẫn bật tính lại lãi theo ngày cho cả hai cách trả. Với `EQUAL_PRINCIPAL`, Fineract dùng
`rescheduleStrategyMethod=2` (giảm số kỳ); phương án điều chỉnh kỳ cuối của lịch `PROGRESSIVE` không
được Fineract chấp nhận trên lịch `CUMULATIVE`.

Product đã đồng bộ bằng mapping cũ không được sửa trực tiếp hoặc tái sử dụng: admin phải tạo Product
FINORA mới. Cơ chế reconcile đọc lại cả amortization, schedule type, strategy và cờ tính lại lãi để
fail closed nếu Fineract trả về Product cùng external ID nhưng sai bất biến.

`daysInYearType`, `daysInMonthType`, `transactionProcessingStrategyCode`, accounting rule và account IDs lấy từ tenant bootstrap/template; không copy ID từ tài liệu mẫu.

## 6. Create Product request/response

Endpoint core: `POST /fineract-provider/api/v1/loanproducts`.

Payload draft:

```json
{
  "name": "FINORA Personal 12M v1",
  "shortName": "FP01",
  "currencyCode": "VND",
  "digitsAfterDecimal": 0,
  "inMultiplesOf": 1000,
  "principal": "50000000",
  "minPrincipal": "10000000",
  "maxPrincipal": "100000000",
  "numberOfRepayments": 12,
  "minNumberOfRepayments": 6,
  "maxNumberOfRepayments": 24,
  "repaymentEvery": 1,
  "repaymentFrequencyType": 2,
  "interestRatePerPeriod": "15.0000",
  "interestRateFrequencyType": 3,
  "amortizationType": 1,
  "interestType": 0,
  "interestCalculationPeriodType": 1,
  "transactionProcessingStrategyCode": "${FROM_TEMPLATE}",
  "accountingRule": "${APPROVED_RULE}",
  "isInterestRecalculationEnabled": false,
  "daysInYearType": "${FROM_TEMPLATE}",
  "daysInMonthType": "${FROM_TEMPLATE}"
}
```

Allowlist response: `resourceId`, `changes` cần thiết. Loan lưu `resourceId` thành `fineractProductId`, request hash/config version và sanitized response metadata.

### Idempotency Product

- Local unique: `loanProductId + productVersion + CREATE_PRODUCT`.
- External correlation: `FINORA-LP-{id}-V{version}` nếu API 1.15 hỗ trợ external ID; nếu không, mapper dùng shortName/name version và reconcile search có kiểm soát.
- Timeout sau khi gửi nhưng chưa nhận response: reconcile trước, không POST mù lần hai.

## 7. Calculate schedule request/response

Endpoint: `POST /fineract-provider/api/v1/loans?command=calculateLoanSchedule`.

Request bắt buộc:

- `productId`, principal;
- loan term, number/frequency repayments;
- rate, amortization, interest type/calculation period;
- expected disbursement date;
- transaction processing strategy;
- `dateFormat=yyyy-MM-dd`, locale.

Response allowlist:

- currency, term days;
- total principal/interest/fees/penalties/repayment;
- periods: period, from/due date, days, principal, interest, fees, penalties, total due, balance.

Loan map sang DTO không phụ thuộc class Fineract:

```text
ScheduleCalculationResult
  amount, termMonths, annualInterestRate, repaymentMethod
  estimatedDisbursementDate
  firstInstallment, maximumInstallment
  totalPrincipal, totalInterest, totalFees, totalRepayment
  periods[]
  calculationPolicyVersion, responseHash
```

`ANNUITY`: installment gửi AI là khoản kỳ đại diện do Fineract trả. `EQUAL_PRINCIPAL`: gửi `maximumInstallment`, thường là kỳ đầu. AI v17 phải dùng giá trị Loan gửi, không tự xây một lịch trả khác.

### 7.1. Vì sao Fineract được gọi hai lần?

- Lần 1, `SUBMISSION_SCORING`: dùng base rate để borrower xem trước và tạo đúng `installment` cho AI.
- Lần 2, `CONTRACT`: chỉ thực hiện sau khi AI thành công và Loan đã tính final rate; principal, term và repayment method giữ nguyên.
- Đây không phải chấm điểm lại. Loan chỉ thay rate vào cùng request schedule để Fineract trả dòng tiền cuối.
- Mỗi purpose có tối đa một snapshot nhờ unique `(application_id, purpose)`. Khi AI từ chối thì không gọi lần 2.
- Hai lần gọi remote đều nằm ngoài transaction database; transaction chỉ lưu kết quả đã hoàn chỉnh.

## 8. Preview API FINORA

`POST /api/v1/loan-products/{productId}/repayment-previews`

```json
{
  "amount": "50000000",
  "termMonths": 12,
  "expectedDisbursementDate": "2026-08-15"
}
```

Response:

```json
{
  "productId": 1,
  "amount": "50000000",
  "termMonths": 12,
  "annualInterestRate": "15.0000",
  "interestRateUnit": "PERCENT_PER_YEAR",
  "repaymentMethod": "ANNUITY",
  "estimatedDisbursementDate": "2026-08-15",
  "firstInstallment": "4513000",
  "maximumInstallment": "4513000",
  "totalInterest": "4156000",
  "totalFees": "0",
  "totalRepayment": "54156000",
  "periods": []
}
```

Preview không tạo Application và mặc định không persist. LN-004 gọi cùng gateway để tạo immutable submission snapshot.

## 9. Entity `FineractCommand`

### 9.1. Vì sao cần lưu command thay vì gọi HTTP rồi quên?

`FineractCommand` là phiếu công việc bền vững. Nếu Loan gọi HTTP trực tiếp mà chưa lưu phiếu, service
có thể restart sau khi Fineract đã tạo Product nhưng trước khi Loan nhận/lưu response. Lúc đó hệ thống
không biết phải tiếp tục hay đối chiếu từ đâu và dễ POST tạo trùng.

```text
API tạo phiếu PENDING trong DB
    ↓
request hiện tại hoặc worker nhận quyền xử lý → PROCESSING
    ↓ gọi REST ngoài transaction
    ├── thành công → SUCCEEDED
    ├── lỗi tạm thời còn lượt → RETRY_PENDING + nextRetryAt
    └── lỗi vĩnh viễn/hết lượt → FAILED
```

Command không phải domain event và không thay outbox. Nó điều khiển một side effect cụ thể từ Loan sang
Fineract; event/outbox mô tả việc đã xảy ra cho service khác.

### 9.2. Field và tác động trong vận hành

| Dữ liệu | Người vận hành/hệ thống hiểu gì | Dùng ở bước nào | Nếu thiếu hoặc sai | Kỹ thuật lưu trữ |
|---|---|---|---|---|
| `commandId` | Mã của một phiếu việc để log/API/worker cùng nói về một attempt chain | Response sync, log và `execute()` | Không lần được một lỗi xuyên request/restart | UUID `VARCHAR(36)` unique |
| `commandType` | Hành động phải làm | Chọn handler/policy; hiện chỉ `CREATE_PRODUCT` | Worker có thể chạy sai side effect | `VARCHAR(50)` + check allowlist |
| `aggregateType/aggregateId` | Product FINORA nào yêu cầu hành động | Lock/đọc cấu hình nguồn và màn admin | Command mồ côi hoặc gắn nhầm Product | FK `aggregate_id → loan_products.id` |
| `mappingId` | Mapping nào phải hoàn tất | Cập nhật đúng bằng chứng sync sau response/reconcile | Product có thể `SYNCED` nhưng mapping khác vẫn PENDING | FK `fineract_product_mappings.id` |
| `idempotencyKey` | Mã duy nhất của một ý định tạo Product core | `prepare()` tìm/dùng lại command khi request gửi trùng | Hai request có thể tạo hai side effect logic | `VARCHAR(150)` unique |
| `requestHash` | Dấu vân tay cấu hình canonical | So sánh cùng key có cùng payload không | Cùng key bị tái sử dụng cho terms khác | SHA-256 `VARCHAR(64)` |
| `requestSnapshotJson` | Payload an toàn đã định gửi | Audit/retry cùng nội dung | Retry có thể tự dựng payload mới khác lịch sử | `JSONB`, không chứa Authorization/Tenant secret |
| `responseSnapshotJson` | Phần response allowlist đã được chấp nhận | Audit resource ID/changes | Khó giải thích vì sao mapping được đánh success | `JSONB` nullable trước success |
| `status` | Phiếu đang chờ, đang làm, chờ thử lại, xong hay dừng | Worker query và state transition | Worker chạy lại command terminal hoặc bỏ quên job | enum string có check constraint |
| `attemptCount` | Đã cấp bao nhiêu lần thực thi | Dừng retry đúng giới hạn | Retry vô hạn hoặc dừng quá sớm | `INT >= 0`, tăng khi claim attempt |
| `lastErrorCode/detail` | Lỗi cuối đã phân loại/lọc | Quyết định retry và hỗ trợ admin | Mọi lỗi bị gom thành “không thành công” | nullable, không PII/credential |
| `nextRetryAt` | Thời điểm sớm nhất worker được thử lại | Query batch `RETRY_PENDING` | Gọi dồn dập làm dependency quá tải | `TIMESTAMPTZ`, nullable |
| `completedAt` | Thời điểm command đi đến success/terminal | Audit/metric | Không đo được thời gian hoàn tất | `TIMESTAMPTZ` nullable |
| `updatedAt` | Lần cuối command còn được xử lý | Processing lease phát hiện instance treo | Command `PROCESSING` có thể mắc kẹt mãi | `TIMESTAMPTZ`, dùng cùng status |
| `version` | Bản command worker đang cập nhật | Chống hai execution ghi đè | Attempt sau bị attempt cũ ghi đè | optimistic version |

Unique idempotency key bảo vệ một logical command trong Loan DB. `externalId` của Mapping mới giúp
reconcile side effect bên Fineract; hai giá trị này không thay thế nhau.

## 10. Transaction và failure path

Transaction là nhóm thay đổi cùng thành công hoặc cùng hoàn tác. LN-006 cố ý không giữ transaction khi
chờ HTTP, vì timeout 10 giây không được giữ connection/row lock và chặn admin khác.

```text
Transaction 1 — prepare:
Mapping PENDING + Product PENDING + Command PENDING → cùng commit/rollback

Transaction 2 — claim ngắn:
lock command, kiểm tra status/lease/nextRetryAt, tăng attempt, chuyển PROCESSING → commit

Ngoài transaction:
GET reconcile → nếu chưa có mới POST Fineract → có thể timeout

Transaction 3 — complete/fail:
Command + Mapping + Product cùng ghi success/retry/failure → commit
```

| Failure | Cách hiểu | Có retry? | State/kết quả |
|---|---|---|---|
| Validation/auth/business 4xx | Request sai hoặc không được phép; gửi lại y nguyên không tự hết lỗi | Không | `FAILED`, Product chưa activate |
| Timeout/429/5xx | Dependency chậm/quá tải/lỗi tạm thời | Có giới hạn | `RETRY_PENDING`, tính `nextRetryAt` theo backoff |
| Timeout sau POST | Loan không biết Fineract đã tạo chưa | Reconcile trước, không POST mù | Tìm thấy thì complete mapping cũ; không thấy mới xét retry |
| Response thiếu/sai schema | Contract hai bên không còn khớp | Không tự đoán field | `FINERACT_CONTRACT_MISMATCH`, không đánh `SYNCED` |
| Preview unavailable | Borrower chưa nhận được lịch đáng tin | Người dùng thử lại request | `503 CORE_LENDING_UNAVAILABLE`, không dùng công thức local |

Implementation hiện chưa thêm jitter vào backoff; đây là giới hạn hardening, không mô tả như đã có.

### 10.1. Concurrency: request và worker chạy cùng lúc

| Tình huống | Nguy cơ | Cơ chế | Kết quả |
|---|---|---|---|
| HTTP sync và worker cùng thấy command | Hai POST cùng lúc | `startExecution()` row lock ngắn + processing lease | Chỉ một attempt được cấp; bên kia bỏ qua |
| Instance chết khi `PROCESSING` | Command mắc kẹt | worker quét `updatedAt <= now - lease` | Instance khác tiếp quản rồi reconcile |
| Attempt cũ trả về sau attempt mới | Kết quả cũ ghi đè | status/version và state allowlist | Transaction cũ không được hoàn tất sai state |
| Hai request tạo logical sync | Hai command/mapping | unique idempotency + Product/version | Trả/dùng lại command hiện hữu |

## 11. Package/file dự kiến

```text
integration/fineract/
  client/
    FineractLoanProductGateway
    FineractScheduleGateway
    FineractProductHttpClient
    FineractScheduleHttpClient
    FineractRequestExecutor
  mapper/
    FineractProductPayloadMapper
    FineractSchedulePayloadMapper
    FineractRepaymentPolicy
  contract/
    request + response nội bộ adapter
config/FineractProperties
service/CoreProductSyncService
service/RepaymentPreviewService
domain/FineractCommand
repository/FineractCommandRepository
```

Integration boundary có interface vì cần fake adapter deterministic cho test và có external implementation thật.

## 12. Query/N+1/observability

N+1 là lấy một danh sách rồi gọi thêm DB/HTTP cho từng dòng. Worker được phép xử lý từng command sau
khi đã lấy **batch ID có giới hạn**, nhưng API danh sách Product/Application không được gọi Fineract cho
từng item chỉ để làm giàu response.

| Query/use case | Index/giới hạn hiện hành | Vì sao |
|---|---|---|
| Command mới `PENDING` | partial `idx_fineract_commands_pending(updated_at,id)` | Index chỉ chứa job đang chờ; ID giúp batch ổn định |
| Retry đến hạn | partial `idx_fineract_commands_retry(next_retry_at,id)` | Worker tìm range thời gian trong đúng status, không quét command thành công |
| `PROCESSING` mất lease | partial `idx_fineract_commands_processing_lease(updated_at,id)` | Tìm job cũ hơn ngưỡng lease để phục hồi sau restart |
| Lịch sử command theo Product | `idx_fineract_commands_aggregate(aggregate_type,aggregate_id,created_at DESC)` | Màn vận hành xem đúng Product, không tải tất cả command |

- Worker query chỉ trả ID theo `batchSize`; mỗi ID dùng chung `execute()` và không giữ entity/transaction
  trong vòng lặp gọi mạng.
- Không gọi template/Fineract trong vòng lặp Product catalog.
- Tenant template/config chỉ cache khi ghi rõ key/TTL/invalidation; hiện không thêm cache để che lỗi core.
- Log dependency, operation, latency, command/product ID và result class; không log body/credential.
- Metric: latency/error/circuit state, retry queue, command mất lease và contract mismatch.

## 13. Test bắt buộc

- Contract fixture create Product cho cả two repayment methods.
- Product response resource ID được lưu đúng và activate được.
- Duplicate command không POST logic hai lần.
- Timeout sau POST thực hiện reconcile.
- 400 không retry; 429/5xx retry có giới hạn.
- Schedule mapper tính đúng first/max/total và không dùng float/double trong Loan.
- Preview reject Product không ACTIVE/SYNCED và amount/term ngoài range.
- Fineract unavailable trả 503, không fallback công thức.
- Container smoke: health, tenant auth, Product create, schedule calculate.
- Secret/PII không xuất hiện trong log/persistence snapshot.

## 14. Acceptance criteria

- Fineract 1.15.0 local chạy với PostgreSQL riêng, health và fixture lặp lại được.
- Product sync/idempotency/retry/reconcile đúng.
- Preview trả schedule chuẩn hóa cho cả ANNUITY/EQUAL_PRINCIPAL.
- Loan không đọc DB Fineract và không có local repayment calculator.
- Contract test dùng Swagger/fixture đúng version, không phụ thuộc Internet.
- Vùng Compose/registry được Hải review trước merge.

## 15. Nghiệm thu

```text
Plan decision: ACCEPTED
Reviewer: Thai
Cross-service reviewer: Hai
Reviewed at: 2026-08-08
Notes:
```

## 16. Luồng gọi API và thứ tự hàm thực tế

### 16.1 Admin đồng bộ Product sang Fineract

```text
POST /api/v1/admin/loan-products/{id}/core-sync
→ AdminLoanProductController.synchronize(id, version)
→ CoreProductSyncServiceImpl.synchronize(id, version)
  1. CoreProductSyncStateService.prepare(...)                       [transaction 1]
     → ProductRepository.findByIdForSubmit                          [write lock]
     → tạo externalId/idempotencyKey/requestHash
     → tìm command cũ hoặc tạo Mapping PENDING + Product PENDING + Command PENDING
     → commit
  2. CoreProductSyncServiceImpl.execute(commandId)                  [không có DB transaction bao ngoài]
     → CoreProductSyncStateService.startExecution(...)              [transaction 2, lock command]
     → createOrReconcile(execution)
       → FineractProductHttpClient.findProductByExternalId(...)     [GET Fineract]
       → nếu chưa có: FineractProductHttpClient.createProduct(...)  [POST Fineract]
       → nếu POST lỗi/timeout: GET đối chiếu lại externalId
     → success: CoreProductSyncStateService.complete(...)           [transaction 3]
       → Mapping SYNCED + Product SYNCED + Command SUCCEEDED
     → failure: CoreProductSyncStateService.fail(...)               [transaction 3]
       → RETRY_PENDING hoặc FAILED theo loại lỗi/số attempt
  3. response(productId, commandId)
     → đọc Product + Command đã commit → CoreProductSyncResponse
```

Không transaction nào bao trùm cuộc gọi HTTP. Command bền vững là điểm tiếp tục sau timeout/restart.

### 16.2 Retry worker

```text
CoreProductSyncRetryWorker.retryDueCommands()                        [mỗi 10 giây]
→ CoreProductSyncStateService.findDueCommandIds(batchSize)          [query phân trang/giới hạn]
→ với từng commandId: CoreProductSyncServiceImpl.execute(commandId)
→ dùng lại đúng execution path của HTTP endpoint
```

Worker chỉ quét ID, không giữ JPA entity hoặc transaction trong vòng lặp gọi mạng.

### 16.3 Borrower xem lịch trả dự kiến

```text
POST /api/v1/loan-products/{productId}/repayment-previews
→ RepaymentPreviewController.preview(productId, request)
→ RepaymentPreviewServiceImpl.preview(...)
  1. ProductRepository.findById
  2. LoanProduct.requireAvailable + requireRequestedTerms
  3. FineractProductMappingRepository.findById + check SYNCED
  4. FineractScheduleHttpClient.calculateSchedule(scheduleRequest)
     → resolvePreviewClientId()
       → lần đầu: GET /clients?externalId=FINORA-PREVIEW-CLIENT
       → FineractSchedulePayloadMapper.findClientIdByExternalId
       → cache clientId trong process
     → FineractSchedulePayloadMapper.toSchedulePayload(request, clientId)
     → POST /loans?command=calculateLoanSchedule
     → FineractSchedulePayloadMapper.toScheduleResult(...)
  5. RepaymentPreviewMapper.toResponse(...)
→ HTTP 200
```

`submittedOnDate` là ngày hiện tại; `expectedDisbursementDate` là ngày borrower chọn. Client preview
chỉ thỏa contract calculate của Fineract 1.15 và không được dùng khi booking khoản vay thật.

### 16.4 Vai trò các hàm integration

| Hàm | Vai trò |
|---|---|
| `FineractRequestExecutor.execute` | Bọc circuit breaker, đo latency và thống nhất exception mapping |
| `FineractRequestExecutor.mapException` | Phân loại 4xx/5xx/timeout/circuit-open thành mã lỗi FINORA |
| `findProductByExternalId` | Reconcile exact Product, chống POST trùng sau timeout |
| `createProduct` | Side effect tạo core Product, bắt buộc nhận `resourceId` |
| `resolvePreviewClientId` | Resolve/cache Client kỹ thuật theo external ID, không hardcode numeric ID |
| `calculateSchedule` | Gửi payload schedule và trả domain-neutral `ScheduleCalculationResult` |
| `FineractProductPayloadMapper` | Allowlist request/response riêng của Product |
| `FineractSchedulePayloadMapper` | Kiểm tra và chuẩn hóa schedule; thiếu field bắt buộc không tự đổi thành `0` |
| `FineractRepaymentPolicy` | Một nguồn ánh xạ amortization/interest policy cho cả Product và Schedule |

### 16.5 Processing lease và cách phục hồi command bị treo

Processing lease là khoảng thời gian một request/worker được xem là đang sở hữu quyền xử lý command.
Nó không phải database lock kéo dài: DB lock chỉ tồn tại trong `startExecution()`, còn lease được nhận
biết bằng `status=PROCESSING` và `updatedAt`.

```text
findDueCommandIds()
  ├─ PENDING                                      → có thể chạy
  ├─ RETRY_PENDING và nextRetryAt <= now          → đã đến lượt retry
  └─ PROCESSING và updatedAt <= now - lease       → instance cũ có thể đã dừng, cho phép tiếp quản

startExecution(commandId) [write lock ngắn]
  ├─ SUCCEEDED                                    → bỏ qua
  ├─ PROCESSING còn lease                         → bỏ qua, tránh hai HTTP call đồng thời
  ├─ RETRY_PENDING chưa đến nextRetryAt            → bỏ qua
  └─ còn lại                                      → tăng attempt, chuyển PROCESSING, đóng transaction
```

`processingLease` hiện mặc định 2 phút, dài hơn nhiều so với timeout của một lần gọi Fineract. Khoảng
đệm này giúp tránh worker mới tiếp quản khi request cũ thực tế vẫn đang chạy. Nếu instance dừng sau khi
Fineract đã tạo Product nhưng trước khi Loan lưu success, command sẽ hết lease và được lấy lại. Lần chạy
mới bắt buộc `findProductByExternalId()` trước POST, nên hoàn tất mapping cũ thay vì tạo Product logic thứ hai.

### 16.6 Năm giá trị chống trùng không thay thế nhau

| Giá trị | Hàm tạo/sử dụng | Mục đích |
|---|---|---|
| `configurationVersion` | Product tăng khi cấu hình thay đổi | Phân biệt hai phiên bản nghiệp vụ của cùng Product |
| `externalId` | `prepare()` tạo `FINORA-LP-{id}-V{version}` | Business key ổn định để tìm/reconcile Product bên Fineract |
| `idempotencyKey` | `prepare()` tạo `CREATE_PRODUCT:{externalId}` | Unique logical command trong database Loan |
| `requestHash` | `HashingService.sha256(configuration)` | Chặn cùng business key bị dùng với payload cấu hình khác |
| schedule `responseHash` | `FineractSchedulePayloadMapper.toScheduleResult()` | Dấu vân tay canonical của allowlist totals/periods đã dùng cho UI, Application và AI |

Canonical hash sắp xếp field/key trước SHA-256 để cùng nội dung sinh cùng digest. Hash không che payload,
không thay unique constraint và cũng không thay việc reconcile qua `externalId`.

### 16.7 Hàm nào chuyển từng trạng thái command?

| Từ → đến | Hàm | Lý do |
|---|---|---|
| chưa có → `PENDING` | `CoreProductSyncStateService.prepare()` | Ghi command/mapping bền vững trước external call |
| `PENDING/RETRY_PENDING/PROCESSING` hết lease → `PROCESSING` | `startExecution()` | Cấp đúng một attempt dưới DB lock ngắn |
| `PROCESSING → SUCCEEDED` | `complete()` | Ghi command, mapping và Product synced trong cùng transaction |
| `PROCESSING → RETRY_PENDING` | `fail()` | Chỉ lỗi tạm thời và còn attempt; tính `nextRetryAt` theo backoff |
| `PROCESSING → FAILED` | `fail()` | Lỗi vĩnh viễn hoặc hết attempt; Product chưa được activate |
| `FAILED → PENDING` | `prepare()` khi admin sync lại | Cho phép sửa/phục hồi có kiểm soát nhưng vẫn dùng logical business key cũ |

HTTP endpoint và retry worker đều gọi `CoreProductSyncService.execute()`. Một execution path duy nhất
giúp chúng có cùng lock, reconcile, error mapping và state transition thay vì hai cách xử lý dễ lệch nhau.

## 17. Kết quả triển khai hiện tại

- Đã có RestClient adapter, Basic Auth + tenant header, timeout và hai Resilience4j circuit breaker độc lập cho
  Product/Schedule; HTTP 4xx không làm mở circuit.
- Product sync ghi mapping/command trước, gọi HTTP ngoài transaction, sau đó ghi success/retry/failure.
- Retry worker có batch giới hạn; quét cả `PENDING`, retry đến hạn và `PROCESSING` mất lease;
  request lặp không tạo command logic thứ hai.
- Test state service đã chứng minh command `PROCESSING` còn lease bị bỏ qua và command hết lease được
  tiếp quản bằng attempt mới; domain cho phép lại `PROCESSING` chỉ sau kiểm tra lease dưới DB lock.
- Đồng bộ Product đối chiếu exact `externalId` trước POST và đối chiếu lại khi POST lỗi/timeout;
  nếu Fineract đã tạo resource thì Loan hoàn tất mapping cũ thay vì POST tạo bản thứ hai.
- Resilience4j TimeLimiter là `12s`, cao hơn RestClient read-timeout `10s`; cả TimeLimiter timeout và socket
  read-timeout đều được phân loại là `FINERACT_TIMEOUT` tạm thời thay vì nhầm thành lỗi kết nối hoặc lỗi vĩnh viễn.
- Preview và submission dùng chung schedule gateway; không có công thức fallback trong Loan.
- Payload schedule có `clientId`, `loanType=individual`, `submittedOnDate` hiện tại và
  `expectedDisbursementDate` riêng. Adapter resolve/cache Client kỹ thuật theo exact external ID;
  Client này chỉ dùng preview và không được booking khoản vay thật.
- Mapper/service test cho `ANNUITY`, `EQUAL_PRINCIPAL`, schedule allowlist, exact external ID và
  timeout-after-POST đã pass.
- Fixture Fineract 1.15.0/PostgreSQL 18.3 đã healthy; smoke bootstrap idempotent allowlist currency
  `VND`, tenant authentication và Preview Client. Volume mới chỉ có `USD` trước bootstrap, nên chỉ
  kiểm tra container healthy chưa đủ chứng minh Product FINORA có thể được tạo.
- Docker functional readiness gọi API `/offices` bằng tenant credential và chỉ báo `healthy` sau HTTP 200. Lần
  khởi tạo Jersey đầu tiên vì thế xảy ra trong healthcheck, không bị đẩy sang request preview của borrower.
- Live test đã xác nhận Fineract tạo Product `FINORA-LP-1-V1`, Client preview được seed
  idempotent và schedule thật 50 triệu/12 tháng được core tính thành công. Vùng Docker dùng chung vẫn cần Hải
  review trước khi merge theo ownership đã thống nhất.
- Hardening ngày 2026-08-10 đã được kiểm tra bằng một lần recreate Fineract thật: container giữ `starting` khi
  cổng chưa mở, probe API đầu tiên hoàn tất sau khoảng 2,3 giây rồi mới chuyển `healthy`; smoke xác nhận lại VND,
  Preview Client và endpoint Loan trả đủ 6 kỳ cho preview 50 triệu/6 tháng. Maven verify JDK 21 và rule validation pass.
