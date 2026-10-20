---
task_id: LN-007
title: AI v17 assessment, risk-based pricing và scoring orchestration
owner: Thai
status: REVIEW
roadmap_task: P1-A05_P1-A06
depends_on: [LN-004, LN-005, LN-006, AI_V17_CONTRACT]
created_at: 2026-08-02
updated_at: 2026-09-16
approved_by: Thai
approved_at: 2026-08-03
accepted_by: Thai
accepted_at: 2026-08-08
---

# LN-007 — AI v17 Assessment và định giá theo rủi ro

> **Bổ sung 2026-09-16:** `APPROVED` không còn đồng nghĩa tạo Contract vô điều kiện. Sau final schedule, Loan dùng outcome-based terms gate của LN-008: không bất lợi hơn → `AUTO_AUTHORIZED`; bất lợi hơn → `PENDING` chờ borrower. Các mô tả “auto-approved tạo Contract ngay” bên dưới phải được đọc theo bổ sung này.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Sau khi hồ sơ được nộp và đủ điều kiện KYC/tuổi, LN-007 lấy dữ liệu đã chốt cùng installment từ Fineract, gửi AI v17 để đánh giá rủi ro, lưu bằng chứng rồi định giá lại từ base rate. `decision` của AI quyết định nhánh tự duyệt, chờ admin hoặc tự từ chối; nhưng Loan mới là service chuyển trạng thái, áp khung lãi và tạo hợp đồng.

### Ai tham gia và nhìn thấy gì?

- Borrower chỉ thấy hồ sơ đang đánh giá, cần bổ sung/chờ xử lý hoặc kết quả phù hợp; không thấy raw model payload.
- AI nhận contract v17 và trả điểm, grade, decision, giải thích cùng model/policy version.
- Admin thấy nguồn dữ liệu, base/final rate, lịch ban đầu/lịch cuối, score/grade, decision và reason.
- Loan giữ snapshot input/output để model thay đổi sau này không làm đổi lý do của quyết định cũ.

### Luồng chính

```text
SUBMITTED → kiểm tra profile/KYC → chụp input → gọi AI v17
    ├── APPROVED: tính final rate/lịch cuối → APPROVED → hợp đồng PENDING_SIGNATURE
    ├── PENDING_REVIEW: tính final rate/lịch cuối → hàng chờ admin
    ├── REJECTED: lưu bằng chứng → REJECTED, không tính final rate/lịch cuối/hợp đồng
    ├── lỗi tạm thời: RETRY_PENDING → thử lại cùng logical assessment
    └── hết retry/contract sai: không tạo điểm giả → admin xử lý theo policy
```

### Nhóm dữ liệu và tác động

| Nhóm dữ liệu | Người đọc hiểu là gì | Nguồn | Ảnh hưởng |
|---|---|---|---|
| Tuổi, nghề nghiệp, thu nhập, nhà ở, DTI | Alternative data về khả năng/ổn định tài chính | LN-004/LN-005 snapshots | Feature AI và evidence admin |
| Tiền vay, kỳ hạn, base rate | Điều khoản borrower đã chọn | Product/Application snapshots | AI đánh giá đúng khoản vay; principal/term không tự đổi |
| `installment` | Khoản trả dự kiến do core tính | LN-006 | AI đánh giá khả năng chi trả; không tự tính lại trong Loan |
| `interest_method` | Cách tính dư nợ giảm dần | Product/Fineract policy | AI hiểu đúng ngữ cảnh installment; không tự tính schedule |
| `so_cccd` | Số định danh nếu User Service cung cấp hợp lệ | User contract tương lai | Hiện gửi `null`, Loan không lưu CCCD thô |
| PD/score/grade/decision/reason | Dự báo, phân hạng và nhánh xử lý | AI v17 | Loan lưu evidence và thực thi policy có version |
| final rate | Base cộng điều chỉnh theo grade, có clamp | Loan pricing policy | Tạo lịch cuối và hợp đồng; không đến từ AI response |

### Vì sao vẫn có `BorrowerCreditProfile` và nhiều Assessment?

`BorrowerCreditProfile` là số liệu lịch sử hiện hành của một borrower, có thể được cập nhật sau mỗi khoản vay. `CreditScoringAssessment` là bằng chứng bất biến của từng lần chấm một Application. Một hồ sơ có thể được chấm lại bằng input/model mới nên có nhiều assessment, nhưng quyết định phải chỉ rõ assessment nào đã được dùng.

### Ví dụ, lỗi và điều kiện hoàn thành

Hồ sơ 50 triệu/12 tháng, base 15%, installment lấy từ Fineract. AI trả grade A và `APPROVED`; Loan áp `-0.5` điểm phần trăm, ra 14.5%, gọi Fineract tạo lịch cuối, tự chuyển `APPROVED` rồi tạo hợp đồng chờ borrower ký. LN hoàn tất khi contract v17 có test, retry không tạo assessment trùng, không gọi remote trong transaction và admin tái hiện được input/output/model/policy/base/final rate.

## 1. Mục tiêu nghiệp vụ

Sau khi Application được nộp, Loan kiểm tra eligibility, tạo input bất biến, gọi AI v17, định giá theo grade, lấy lịch cuối từ Fineract và chuyển trạng thái theo `decision` đã version hóa.

```text
Application SUBMITTED
    → eligibility check
    → lấy ScheduleCalculationSnapshot SUBMISSION_SCORING
    → tạo CreditScoreRequest snapshot
    → gọi AI v17 /api/v1/ai/credit/explain
    → lưu CreditScoringAssessment
    → Loan áp grade adjustment và clamp min/max/20%
    → nếu không REJECTED: Fineract tính Schedule CONTRACT theo final rate
    → APPROVED / PENDING_REVIEW / REJECTED
```

AI sở hữu model và decision threshold; Loan sở hữu Application, pricing, schedule snapshot và Contract. Principal/term không tự đổi. Căn cứ thiết kế: [`LEGAL-AI-01`](../../docs/LEGAL-COMPLIANCE.md#legal-ai-01--chấm-điểm-tự-động-duyệt-và-khả-năng-giải-trình), [`LEGAL-RATE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-rate-01--khung-minbasemax-và-trần-lãi-suất) và [`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md#legal-data-01--dữ-liệu-hồ-sơ-và-cccd).

## 2. Phạm vi

- Tạo `BorrowerCreditProfile` current aggregate.
- Tạo `CreditScoringAssessment` immutable theo logical scoring.
- AI client/mapper v17, timeout/circuit breaker/retry.
- Pricing service theo grade và final Fineract schedule.
- Tự động tạo Contract `PENDING_SIGNATURE` khi AI `APPROVED`.
- Orchestrate Application scoring states và history.
- Lưu input source/model/result/failure snapshot.
- API/query admin xem assessment và retry có kiểm soát.
- Contract/unit/integration/N+1 test.

Không cập nhật internal credit history từ repayment/DPD trong task này; LN-014 thực hiện từ Fineract event. LN-007 chỉ khởi tạo/đọc projection.

## 3. `BorrowerCreditProfile`

| Field | PostgreSQL | Ai ghi | Tác dụng |
|---|---|---|---|
| `id` | BIGINT | DB | Khóa nội bộ |
| `borrowerId` | VARCHAR(100) unique | Loan projection | Một aggregate mỗi borrower |
| `hasInternalCreditHistory` | BOOLEAN | LN-014 | Phân biệt người mới và người có lịch sử 0 lỗi |
| `internalDelinquenciesLast2Years` | INT | LN-014 | Số event >=30 DPD trong 24 tháng |
| `internalDefaultedLoanCount` | INT | LN-014 | Số loan nội bộ default, mỗi loan một lần |
| `completedLoanCount` | INT | LN-014 | Evidence bổ trợ |
| `onTimePaymentRate` | DECIMAL(7,4) nullable | LN-014 | Projection hành vi |
| `source` | VARCHAR(30) | Loan | `NO_HISTORY/FINERACT_INTERNAL` |
| `calculationPolicyVersion` | VARCHAR(30) | LN-014 | Truy vết cách tính |
| `sourceEventId/sourceEventAt` | varchar/datetime nullable | LN-014 | Idempotency/freshness |
| audit/version | chuẩn chung | Loan | Concurrency/audit |

Nếu borrower chưa có row, LN-007 tạo profile `hasInternalCreditHistory=false`, counts 0, source `NO_HISTORY`; không coi đây là dữ liệu CIC.

AI v17 hiện không nhận `delinq_2yrs/pub_rec`, vì vậy profile chỉ là bằng chứng nội bộ cho admin và nền cho LN-014. Nếu model tương lai cần lại, phải version contract thay vì âm thầm ánh xạ.

## 4. AI v17 request và nguồn field

| AI field | Kiểu JSON | Nguồn Loan | Quy tắc |
|---|---|---|---|
| `person_age` | integer | LN-005 profile snapshot | Local 30; production derive DOB |
| `emp_length` | string nullable | employment months | Mapper có contract test |
| `annual_inc` | number | financial snapshot | VND/năm |
| `loan_amnt` | number | requested amount | VND |
| `home_ownership` | enum | financial snapshot | Allowlist AI |
| `purpose` | enum | purpose mapper | Allowlist AI |
| `int_rate` | number | Product snapshot | `15.0` = 15%/năm |
| `term_months` | integer | requested term | Tháng |
| `verification_status` | enum | LN-005 | `Not Verified` khi tự khai |
| `dti` | number | DTI snapshot | Phần trăm |
| `installment` | number | lịch `SUBMISSION_SCORING` | ANNUITY dùng khoản kỳ đại diện; EQUAL_PRINCIPAL dùng maximum installment |
| `interest_method` | string | Fineract/Product policy | Hiện gửi `DECLINING_BALANCE` |
| `so_cccd` | string/null | User Service | Hiện `null`; không bịa dữ liệu và không lưu CCCD thô trong Loan |

Không gửi `fico_score` hoặc training target `loan_status`.

### Payload mẫu

```json
{
  "person_age": 30,
  "emp_length": "5 years",
  "annual_inc": 300000000,
  "loan_amnt": 50000000,
  "home_ownership": "RENT",
  "purpose": "education",
  "int_rate": 15.0,
  "term_months": 12,
  "verification_status": "Not Verified",
  "dti": 15.5,
  "installment": 4513000,
  "interest_method": "DECLINING_BALANCE",
  "so_cccd": null
}
```

`int_rate` là base rate, không phải final rate. AI dùng input này để hiểu hồ sơ; Loan chỉ tính final rate sau khi nhận grade. Request không có `suggested_rate`, `effective_apr`, `delinq_2yrs` hoặc `pub_rec`.

## 5. AI v17 response

Contract hiện tại:

```json
{
  "pd_probability": 0.18,
  "risk_score": 82,
  "evaluation_score": 86.5,
  "credit_grade": "A",
  "decision": "APPROVED",
  "dien_giai": {},
  "giai_thich_mo_hinh": {},
  "rule_trace": {},
  "rejection_reasons": [],
  "model_version": "17.0.0",
  "decision_policy_version": "credit-policy-v17"
}
```

Loan allowlist và lưu các field trên cùng response hash. `decision` được thực thi như sau:

| AI decision | Loan xử lý |
|---|---|
| `APPROVED` | Tính final rate theo grade, lấy lịch `CONTRACT`, chuyển `APPROVED`, tạo Contract `PENDING_SIGNATURE`; chưa giải ngân |
| `PENDING_REVIEW` | Tính final rate/lịch cuối rồi đưa admin xem và quyết định |
| `REJECTED` | Lưu evidence, chuyển `REJECTED`; không tính final rate, không tạo lịch cuối/hợp đồng |

`credit_grade` quyết định mức điều chỉnh giá; `decision` quyết định luồng. Ví dụ grade B vẫn có thể `PENDING_REVIEW` nếu evaluation score chưa đạt ngưỡng auto approve.

## 6. `CreditScoringAssessment`

| Field | PostgreSQL | Tác dụng |
|---|---|---|
| `id`, `applicationId` | BIGINT/FK | Một Application có nhiều assessment |
| `requestId` | VARCHAR(100) | Correlation |
| `logicalScoringKey` | VARCHAR(150) unique | app + input hash + model |
| `attemptNo` | INT | Retry kỹ thuật |
| `status` | VARCHAR(30) | `PENDING/PROCESSING/SUCCEEDED/RETRY_PENDING/FAILED` |
| model requested/actual | VARCHAR(30) | Compatibility/audit |
| `inputSnapshotJson` | JSON | Contract v17 đã gửi, bao gồm installment/base rate |
| `inputSourceSnapshotJson` | JSON | Nguồn MOCK/SELF_DECLARED/FINERACT/INTERNAL_PROXY |
| `inputHash` | VARCHAR(64) | Bất biến/dedupe |
| PD/risk/evaluation/grade | decimal/int/varchar | Prediction |
| `suggestedLimit` | DECIMAL(18,2) nullable | Cột tương thích dữ liệu cũ; v17 không trả và luôn để null |
| `aiRecommendation` | VARCHAR(30) | `APPROVED/PENDING_REVIEW/REJECTED` được Loan thực thi có audit |
| `rejectionReason` | VARCHAR(1000) nullable | Tóm tắt reason; danh sách đầy đủ nằm trong response snapshot |
| `rawResponseJson/responseHash` | JSON/VARCHAR | Allowlist evidence gồm explanation/rule trace/policy version |
| failure code/detail | varchar nullable | Retry/diagnostic đã lọc |
| started/completed/scoredAt | datetime UTC | Timeline |
| audit/version | chuẩn chung | Truy vết |

Không update result assessment đã SUCCEEDED. Re-score tạo assessment mới và Application trỏ `latestAssessmentId`.

### 6.1 Hai loại dữ liệu khác nhau và quan hệ hiện tại

- `BorrowerCreditProfile` là **số liệu cộng dồn hiện tại của một người vay** trong FINORA. LN-014 có thể cập nhật nó khi phát sinh trả chậm/default.
- `CreditScoringAssessment` là **biên bản bất biến của một lần chấm điểm**. Nó phải giữ input/output cũ kể cả khi profile của borrower thay đổi sau này.

```text
User/Borrower (service khác)
        │ logical borrowerId, không FK
        └────────── (1) BorrowerCreditProfile [0..1 row hiện tại]

LoanApplication (1) ──────── (N) BorrowerEligibilityCheck
      │                              │
      │                              └──────── (N) CreditScoringAssessment
      │                                              │
      │                                              └── (N) CreditScoringRetryRequest
      │
      └── latestCreditAssessmentId ──► 0..1 CreditScoringAssessment
```

`latestCreditAssessmentId` là con trỏ giúp màn review đọc nhanh bản mới nhất; nó không thay thế timeline `1-N`. Service phải kiểm tra assessment được trỏ tới thuộc chính Application đó. Retry kỹ thuật tiếp tục cùng assessment; re-score nghiệp vụ với input/model/policy mới mới tạo assessment khác.

| Nhóm field Assessment | Nếu không lưu thì hệ thống mất gì? |
|---|---|
| `logicalScoringKey`, `inputHash` | Không chống được hai worker ghi hai kết quả cho cùng bộ input |
| model requested/actual | Không biết quyết định cũ do model nào tạo hoặc phát hiện model lệch contract |
| input + input-source snapshot | Không giải thích được từng field v17 đến từ tự khai, Fineract, mock hay User contract |
| PD/risk/grade/recommendation | Admin không có kết quả đã dùng để review |
| raw response allowlist + response hash | Không đối chiếu được response đã parse với bằng chứng gốc đã lọc |
| failure/attempt/next retry | Worker không biết lỗi nào được thử lại và lúc nào |

## 7. Scoring orchestration

1. Application create trả `201 SUBMITTED`; không chờ AI trong HTTP request.
2. Sau commit, scheduled `CreditScoringWorker` quét một batch Application/Assessment đến lượt; **hiện chưa dùng outbox hoặc Kafka**.
3. Transaction chuyển `SUBMITTED → ELIGIBILITY_PENDING` và ghi history.
4. Gọi LN-005 ngoài transaction; eligible mới chuyển `SCORING`.
5. Tạo Assessment PENDING/input snapshot trong transaction.
6. Gọi AI ngoài transaction.
7. Transaction đọc response, tính `RiskBasedPricingResult` nhưng chưa gọi Fineract.
8. Nếu decision không phải `REJECTED`, gọi Fineract ngoài transaction để tính lịch `CONTRACT` theo final rate.
9. Transaction cuối lưu response/final schedule/state; nhánh `APPROVED` tạo Contract `PENDING_SIGNATURE` bằng cùng service với admin approve.
10. Timeout/5xx của AI hoặc Fineract: `SCORING_RETRY_PENDING`; retry cùng logical assessment.
11. Hết retry: `PENDING_REVIEW` với failure/manual reason; không tạo điểm hay lịch giả.

## 8. Decision và pricing policy tại scoring

Hai policy độc lập để tránh vòng lặp:

1. AI dùng `auto_approve/auto_reject` tạo `decision`. Loan không tự chấm DSR lần hai.
2. Nếu decision không phải `REJECTED`, Loan dùng `credit_grade` tạo final rate: mặc định A `-0.5`, B `0`, C `+0.5`, D/E `+1.0` điểm phần trăm; sau đó clamp theo min/max Product và 20%.

Decision quyết định state, grade quyết định giá. Requested amount và term giữ nguyên. E thường bị AI reject nên mức điều chỉnh chỉ là cấu hình phòng thủ và không sinh Contract. Mọi kết quả lưu `modelVersion`, `decisionPolicyVersion` và `pricingPolicyVersion`.

## 9. API/query

- `GET /api/v1/admin/loan-applications/{applicationNumber}/assessments` — paginated timeline.
- `GET /api/v1/admin/loan-applications/{applicationNumber}/assessments/{assessmentId}` — evidence detail.
- `POST /api/v1/admin/loan-applications/{applicationNumber}/scoring-retry` — chỉ assessment `FAILED`,
  có idempotency/version; trả `202 Accepted` và biên nhận ngắn gọn để UI poll API chi tiết.
- Borrower response chỉ hiển thị trạng thái phù hợp, không lộ raw model/internal proxy chi tiết gây hiểu nhầm.

## 10. Transaction/idempotency/failure

- Network call không trong transaction. Transaction 1 claim/chuyển state; gọi AI; transaction 2 ghi success hoặc failure và Application history.
- Unique logical scoring key chống duplicate result; lock/version bảo vệ trạng thái khi hai worker chạm cùng assessment.
- Retry kỹ thuật không tạo assessment mới; re-score nghiệp vụ có request/policy mới.
- Response model version không tương thích → FAILED `AI_MODEL_VERSION_MISMATCH`.
- Schema/unknown enum → `AI_CONTRACT_MISMATCH`, không đánh success.
- AI timeout không rollback Application đã submit.
- Log requestId/applicationId/model/latency/result class, không log input/raw response.

| Tình huống cạnh tranh/lỗi | Cách xử lý và lý do |
|---|---|
| Hai worker cùng thấy `PENDING` | Chỉ một worker claim `PROCESSING`; bên còn lại bỏ qua sau lock/version check |
| Instance chết sau khi claim | processing lease dựa trên `updatedAt` cho phép worker sau thu hồi bản bị treo |
| AI timeout/429/5xx | ghi `RETRY_PENDING` + `nextRetryAt`; không giữ DB lock trong lúc chờ |
| AI trả 4xx/schema/model sai | fail không retry mù vì gửi lại cùng payload không tự sửa contract |
| AI đã nhận request nhưng Loan mất response | retry cùng `requestId/logicalScoringKey`; không tạo assessment nghiệp vụ mới |
| Borrower withdraw trước khi response về | transaction hoàn tất phải kiểm tra lại state; không đẩy hồ sơ đã rút sang review |

Worker là bộ xử lý nền **trong chính Loan Service**, không phải webhook. Kafka chỉ cần khi phát sự kiện cho service khác; dùng Kafka để Loan tự gọi lại chính nó không làm bước chấm AI chính xác hơn.

## 11. Index/N+1

Index chỉ được tạo cho đường đọc thật; JSON input/output bất biến không được index vì hiện không lọc theo từng thuộc tính JSON và index đó chỉ làm chậm ghi.

| Index hiện tại | Query |
|---|---|
| unique `borrower_credit_profiles.borrower_id` | lấy đúng profile hiện tại của borrower |
| unique `logical_scoring_key` và `request_id` | idempotency/correlation |
| `idx_eligibility_checks_application_created(application_id, created_at DESC, id DESC)` | timeline eligibility |
| `idx_credit_assessments_application_created(application_id, created_at DESC, id DESC)` | timeline assessment |
| partial `idx_credit_assessments_pending(updated_at,id)` | worker lấy PENDING cũ trước |
| partial `idx_credit_assessments_retry(next_retry_at,id)` | chỉ quét RETRY_PENDING đã đến hạn |
| partial `idx_credit_assessments_processing_lease(updated_at,id)` | thu hồi PROCESSING bị treo |
| `idx_scoring_retry_requests_assessment(assessment_id,created_at DESC,id DESC)` | audit các lần admin yêu cầu retry |

N+1 sẽ xảy ra nếu lấy N Application rồi lazy-load assessment/history cho từng dòng. Admin queue dùng projection/join con trỏ latest assessment trong một query phân trang; không fetch toàn bộ collections hoặc gọi AI/User theo từng row. Detail dùng các query timeline riêng có giới hạn.

## 12. Migration/file/test

- Migration sau LN-004 tạo credit profile/assessment, latest assessment/scoring columns và constraints.
- Integration client package `integration/ai`, DTO riêng, không import Python/shared domain.
- Unit test mapper v17 fields/source/units và response explain.
- Contract test success/422/5xx/model mismatch.
- Unit/integration test ba decision branch, state/history/idempotent retry/concurrency.
- Test ANNUITY vs EQUAL_PRINCIPAL chứng minh AI nhận đúng installment Fineract.
- Test grade adjustment, clamp Product/legal cap và final schedule purpose.
- Query-count test admin review/timeline.

## 13. Acceptance criteria

- Không có credit history từ Application body.
- Input/result/source/model snapshot tái hiện được quyết định lịch sử.
- Contract v17 không chứa `suggested_rate/suggested_limit`; cột legacy không tham gia quyết định.
- AI không ghi Application trực tiếp; Loan thực thi decision và giữ audit source `AI_POLICY/ADMIN`.
- Grade tạo final rate trong khung min/base/max; principal/term không tự đổi.
- `APPROVED` tự động chỉ tạo Contract `PENDING_SIGNATURE`, không giải ngân.
- Timeout/retry không tạo score giả hoặc assessment trùng.
- Test/fixture v17 được Hải review.

## 14. Nghiệm thu

```text
Plan decision: REVIEW_AFTER_V17_UPDATE
Reviewer: Thai
AI contract reviewer: Hai
Updated at: 2026-09-05
Notes: Loan đã chuyển contract v17, risk-based pricing và decision automation; cần chạy lại integration trên PostgreSQL/Fineract/AI sạch trước khi xác nhận DONE.
```

## 15. Hiện thực đã hoàn thành và đường đi của một request

### Từ hồ sơ hợp lệ tới bản ghi assessment

| Thứ tự | Hàm | Việc xảy ra trong hệ thống |
|---:|---|---|
| 1 | `CreditScoringStateService.applyEligibility()` | Tạo/đọc `BorrowerCreditProfile`, lấy schedule `SUBMISSION_SCORING` và tạo assessment. |
| 2 | `AiCreditScoringMapper.map()` | Tạo JSON v17, source snapshot và SHA-256 input hash; installment/base rate lấy từ snapshot. |
| 3 | `CreditScoringAssessment.pending()` | Lưu assessment `PENDING` theo logical key duy nhất trước khi gọi mạng. |
| 4 | `CreditScoringStateService.startExecution()` | Khóa assessment, tăng attempt và chuyển sang `PROCESSING`; transaction kết thúc trước HTTP. |
| 5 | `AiCreditScoringHttpClient.score()` | POST JSON tới `/api/v1/ai/credit/explain`, có timeout/circuit breaker và kiểm tra model `17.0.0`. |
| 6 | `CreditScoringStateService.prepareFinalization()` | Validate response, tính `RiskBasedPricingResult` và dựng request lịch cuối; chưa gọi mạng trong transaction. |
| 7 | `FineractScheduleGateway.calculateSchedule()` | Nếu không reject, tính lịch `CONTRACT` theo final rate ngoài transaction. |
| 8 | `CreditScoringStateService.complete()` | Lưu response/hash/final schedule và chuyển state theo AI decision trong một transaction local. |
| 9 | `LoanContractCreationService.create()` | Chỉ nhánh auto-approved tạo Contract `PENDING_SIGNATURE`; borrower vẫn phải đọc và ký. |

Nếu AI timeout/5xx, `fail()` đặt assessment vào `RETRY_PENDING` và Application thành
`SCORING_RETRY_PENDING`; worker thử lại cùng assessment. Contract/model sai là lỗi không retry,
không tạo điểm mặc định và không tự duyệt hồ sơ.

### Worker, processing lease và retry tự động

`CreditScoringWorker.processDueScoring()` chạy hai batch hữu hạn: batch Application cần eligibility và
batch Assessment cần gọi/retry AI. API danh sách không kích hoạt AI, nên không tạo remote call theo từng
dòng và không làm chậm màn hình admin.

```text
findAssessmentCandidates()
  ├─ PENDING
  ├─ RETRY_PENDING với nextRetryAt <= now
  └─ PROCESSING với updatedAt <= now - processingLease
        ↓
CreditScoringOrchestrator.executeAssessment(id)
  → CreditScoringStateService.startExecution(id) [DB lock ngắn]
  → AiCreditScoringHttpClient.score(...)         [ngoài transaction]
  → prepareFinalization(...)                     [read transaction ngắn]
  → FineractScheduleGateway.calculateSchedule()  [ngoài transaction, nếu cần]
  → complete(...) hoặc fail(...)                 [transaction mới]
```

Processing lease mặc định 2 phút. Assessment `PROCESSING` còn lease bị bỏ qua để hai worker không gọi AI
cùng lúc; assessment mất lease được tiếp quản vì instance cũ có thể đã dừng giữa HTTP call. Retry luôn
dùng lại `requestId`, input snapshot và assessment cũ, không tạo một điểm tín dụng logic thứ hai.

`fail()` chỉ xếp retry tự động cho timeout/kết nối, HTTP 429/5xx hoặc circuit open khi vẫn còn attempt.
`nextRetryAt` tăng theo `retryBackoff × attemptCount`; implementation hiện chưa có jitter. Khi lỗi
contract/model, lỗi 4xx nghiệp vụ hoặc hết attempt, Assessment thành `FAILED`, Application về
`PENDING_REVIEW` để con người xử lý và tuyệt đối không sinh score/lịch mặc định.

### Hash, logical key và evidence quyết định

| Dữ liệu | Hàm tạo/lưu | Vì sao cần |
|---|---|---|
| `inputSnapshotJson` | `AiCreditScoringMapper.map()` | Giữ đúng contract v17 thực tế đã gửi AI |
| `inputSourcesJson` | `CreditScoringSourceSnapshot` | Ghi nguồn KYC, financial snapshot, Fineract schedule và credit profile/policy version |
| `inputHash` | `HashingService.sha256(request)` | Dấu vân tay canonical của request, không phụ thuộc thứ tự serialize |
| `logicalScoringKey` | `applicationId + inputHash + modelVersion` | Cùng hồ sơ, input và model chỉ có một assessment logic nhờ unique constraint |
| `responseSnapshotJson` | `StoredAiCreditResponse.from(response)` | Lưu allowlist v17 gồm explanation, rule trace, rejection reasons và policy version |
| `responseHash` | `HashingService.sha256(storedResponse)` | Đối chiếu output đã dùng cho quyết định có bị thay đổi hay không |
| `decisionPolicyVersion` | `complete()` | Ghi đúng policy AI đã tạo decision |
| `pricingPolicyVersion` | `RiskBasedPricingService.calculate()` | Giải thích grade được đổi thành bao nhiêu điểm phần trăm |

Hash không phải mã hóa và không chứng minh AI “đúng”; nó chứng minh assessment lịch sử đang tham chiếu
đúng input/output snapshot. `modelVersion`, source version và policy version mới giải thích dữ liệu được
xử lý theo phiên bản nào.

### Ranh giới với AI Service

| Thành phần | Chịu trách nhiệm | Không chịu trách nhiệm |
|---|---|---|
| `AiCreditScoringMapper` | Ánh xạ contract v17 và source snapshot | Không gọi HTTP, không quyết định giá |
| `AiCreditScoringHttpClient` | Timeout/circuit breaker, correlation ID, validate required field/range/model version và phân loại lỗi | Không đổi Application state, không log payload tài chính |
| `CreditScoringOrchestrator` | Đặt external call giữa các transaction local | Không tự persist entity |
| `CreditScoringStateService` | Lock, state transition, automatic retry, pricing/final-schedule preparation và lưu evidence | Không gọi HTTP hoặc sửa model AI |
| `RiskBasedPricingService` | Chuyển grade thành adjustment, clamp Product/legal cap | Không tự chấm điểm hay tính schedule |
| `LoanContractCreationService` | Tạo cùng một dạng Contract cho auto/admin approval | Không giải ngân và không gọi AI |
| `CreditScoringAdminStateService` | Idempotency và transaction cho yêu cầu retry thủ công của admin | Không gọi AI trực tiếp |
| AI Service | Tính prediction/grade/decision theo model v17 và trả explanation/policy version | Không ghi Loan DB, không tính final rate hoặc lịch trả nợ |

### API admin và hàm thực thi

| API | Controller → service | Tác dụng |
|---|---|---|
| `GET .../{applicationNumber}/assessments` | `AdminCreditScoringController.list()` → `CreditScoringAssessmentServiceImpl.list()` | Xem timeline phân trang, không phát sinh N+1. |
| `GET .../{applicationNumber}/assessments/{assessmentId}` | `detail()` → `service.detail()` | Xem input, nguồn, model, kết quả và lỗi của đúng assessment. |
| `POST .../{applicationNumber}/scoring-retry` | `retry()` → `service.retry()` → `CreditScoringAdminStateService.retry()` | Admin yêu cầu retry có version và `Idempotency-Key`; trả `202` cùng `resultPath`, không trả lỗi cũ như kết quả lần retry mới. |

Retry thủ công khác retry tự động: admin tạo `CreditScoringRetryRequest` có idempotency key + request
hash, mở lại đúng Assessment cũ và chuyển Application sang `SCORING_RETRY_PENDING`. Bấm lại cùng key/body
trả kết quả cũ; cùng key nhưng body khác bị từ chối. Worker mới là thành phần gọi AI ở chu kỳ tiếp theo.

### Docker AI và bằng chứng kiểm thử cần chạy lại

Compose service `ai` publish `127.0.0.1:8000`, chạy non-root, có healthcheck và
`restart: unless-stopped`. Image phải lấy model v17/runtime hiện hành của Hải; không copy CSV huấn luyện.

Integration test sau thay đổi phải chứng minh đủ ba fixture:

```text
APPROVED      → final schedule → Application APPROVED → Contract PENDING_SIGNATURE
PENDING_REVIEW → final schedule → Application PENDING_REVIEW → admin có base/final evidence
REJECTED      → Application REJECTED → không final schedule/Contract
```

Test cũng phải chứng minh `installment` AI nhận đúng giá trị Fineract cho cả `ANNUITY` và
`EQUAL_PRINCIPAL`. Đây là contract liên owner; Hải review fixture AI, Thái review state/pricing/contract Loan.

### File code chính

- `integration/ai/*`: request/response contract, HTTP gateway và lỗi dependency.
- `service/CreditScoringOrchestrator`: điều phối, không chứa transaction dài.
- `service/CreditScoringStateService`: state transition/lock/persistence cho worker và automatic retry.
- `service/CreditScoringAdminStateService`: transaction/idempotency của manual admin retry.
- `mapper/scoring/CreditScoringMapping` và `CreditScoringSourceSnapshot`: input v17 cùng bằng chứng nguồn.
- `service/pricing/*`: policy grade adjustment và clamp min/base/max/legal cap.
- `service/contract/LoanContractCreationService`: dùng chung cho auto/admin approval.
- `service/CreditScoringWorker`: batch scheduler cho hồ sơ mới và retry.
- `domain/CreditScoringAssessment`: bằng chứng một lần chấm; kết quả thành công không bị ghi đè.
- `domain/BorrowerCreditProfile`: projection lịch sử nội bộ, không giả danh CIC.
- `controller/AdminCreditScoringController`: API đọc/retry dành cho admin.
- `db/migration/V3__create_borrower_eligibility_and_credit_assessment.sql`: schema/index/constraint.

### Trạng thái nghiệm thu hiện tại

- Code/migration/unit test đã cập nhật theo v17 và risk-based pricing.
- Còn phải chạy Flyway/integration test với PostgreSQL + Fineract + AI v17 sạch; task giữ `REVIEW` cho tới khi Thái kiểm tra và Hải review fixture liên service.
