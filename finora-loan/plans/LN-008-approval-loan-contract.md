---
task_id: LN-008
title: Admin decision, LoanContract và borrower signature
owner: Thai
status: IN_PROGRESS
roadmap_task: P2-A01
depends_on: [LN-007]
created_at: 2026-08-02
updated_at: 2026-09-17
approved_by: Thai
approved_at: 2026-08-08
accepted_by:
accepted_at:
---

# LN-008 — Admin duyệt và LoanContract

> **Bổ sung 2026-09-16 — thay thế các đoạn “approve tạo Contract ngay” bên dưới:** Application giữ `termsConfirmationStatus/version/hash/expiry`. Điều khoản không bất lợi hơn được `AUTO_AUTHORIZED` theo disclosure V2; điều khoản bất lợi hơn phải `ACCEPTED` rõ ràng trước khi tạo Contract. `DECLINED/EXPIRED` không có Contract. Không tạo bảng LoanOffer và không sao chép final terms. Contract/PDF hiện tại vẫn được tạo sau gate này như artifact demo để tiếp tục kiểm thử, không giả lập lender và không được dùng để giải ngân production. Target Contract song phương sau Investment được theo dõi tại LN-009.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

LN-008 biến một hồ sơ đã được đánh giá thành quyết định rõ ràng. AI v17 có thể tự duyệt/từ chối theo policy; admin chỉ xử lý nhánh `PENDING_REVIEW`. Mọi hồ sơ được duyệt đều dùng final rate và lịch cuối, nhưng chỉ tạo `LoanContract` sau khi gate xác nhận điều khoản đã qua; không có LoanOffer trung gian.

### Ai tham gia và nhìn thấy gì?

- Admin thấy một màn hình review tổng hợp: hồ sơ, nguồn dữ liệu, lịch trả dự kiến và đánh giá AI.
- Admin không được sửa amount/term/final rate/repayment method trong lúc approve; muốn đổi điều khoản phải mở luồng offer mới ở tương lai.
- Borrower nhận thông báo, mở một hợp đồng có đầy đủ số tiền, kỳ hạn, lãi suất, lịch dự kiến và chọn ký hoặc từ chối.
- Ký hợp đồng chưa có nghĩa tiền đã được giải ngân; khoản vay chỉ hiệu lực sau Saga LN-011.

### Luồng chính

```text
AI APPROVED ────────────────────────→ APPROVED + LoanContract PENDING_SIGNATURE
AI PENDING_REVIEW → admin từ chối ─→ REJECTED + lý do
                  └ admin phê duyệt → APPROVED + LoanContract PENDING_SIGNATURE
AI REJECTED ────────────────────────→ REJECTED

LoanContract PENDING_SIGNATURE → borrower SIGN / DECLINE / timeout EXPIRE
```

### Dữ liệu hợp đồng và ảnh hưởng

| Dữ liệu | Borrower hiểu là gì | Nguồn | Ảnh hưởng/nguyên tắc |
|---|---|---|---|
| principal/term/base/final rate/method | Điều khoản ban đầu và cuối | Application pricing snapshots | Principal/term không tự đổi; final rate bị chặn trong min/max/20% |
| initial/final schedule totals/periods | So sánh trước và sau thẩm định | LN-006 calculation snapshots | Contract chỉ dùng lịch cuối; UI vẫn giữ lịch ban đầu để giải thích |
| decision reason/policy/assessment | Vì sao admin quyết định | Admin + Loan policy | Audit, giải quyết khiếu nại, không hiển thị nội dung nhạy cảm sai chỗ |
| `termsVersion/documentVersion` | Bộ điều khoản và mẫu văn bản đã dùng | Loan policy/renderer | Xác định chính xác nội dung pháp lý |
| `documentHash` | Dấu vân tay của văn bản | Loan | Server đối chiếu để borrower ký đúng bản vừa xem |
| `status/expiresAt` | Hợp đồng còn chờ ký, đã ký hay hết hạn | State machine | Chặn ký lại/sửa im lặng |
| signed actor/time/method | Bằng chứng borrower đồng ý | Identity/signature boundary | Không tuyên bố click-wrap là chữ ký số SmartCA |

Trong tài liệu này, “snapshot” là bản chụp dữ liệu tại lúc borrower nộp hồ sơ; Product hoặc Fineract thay đổi về sau không tự sửa bản chụp đó.

### Vì sao Application và Contract không gộp?

Application là yêu cầu xin vay và chứa dữ liệu phục vụ đánh giá; Contract là văn bản điều khoản được phê duyệt để ký. Tách hai entity giúp hồ sơ bị từ chối không có “hợp đồng”, đồng thời khóa nội dung đã ký mà không biến toàn bộ dữ liệu đánh giá thành văn bản pháp lý.

### Ví dụ, lỗi và điều kiện hoàn thành

Admin duyệt hồ sơ 50 triệu/12 tháng, base 15%, final 15.5% dựa trên assessment số 20. Hệ thống tạo một Contract đúng
các snapshot, render nội dung text legacy và PDF `SIGNABLE` xác định, rồi để borrower ký đúng hash PDF đã xem. Hai admin bấm
duyệt đồng thời chỉ tạo một Contract. Nếu render/lưu văn bản lỗi, không được để Application `APPROVED`
mà thiếu Contract. LN hoàn tất khi approve/reject/sign/decline/expire có state, audit, idempotency và
concurrency test đầy đủ.

## 0. Quyết định tương thích đã rà soát ngày 2026-09-05

| Điểm chốt | Quyết định hiện hành | Vì sao |
|---|---|---|
| Public identifier | Application dùng `applicationNumber`, Contract dùng `contractNumber` | Khớp API LN-004/LN-007 và không làm UI phụ thuộc ID database |
| Schedule khi approve | Dùng snapshot `CONTRACT` theo final rate | LN-007 đã gọi Fineract ngoài transaction; admin không tính lại và Contract không dùng lịch base |
| Fineract trong approve/sign | Không gọi Fineract | Final schedule đã có trước khi vào state review/approved; lịch official sau giải ngân vẫn thuộc LN-011 |
| Document MVP | Giữ text UTF-8 legacy và sinh PDF `SIGNABLE`/`SIGNED_RECEIPT` bất biến trong PostgreSQL | Borrower mở/tải/ký cùng một artifact; không phụ thuộc renderer trên mobile |
| Chữ ký | Server chọn provider; local là `MOCK + CLICK_WRAP_MVP` | Lưu evidence nhưng không tuyên bố mock là chữ ký số; VNPT fail-closed tới khi có contract chính thức |
| Application sau consent | Giữ quyết định `APPROVED`; Contract giữ `SIGNED/DECLINED/EXPIRED` | Không sao chép Contract state vào Application rồi tạo hai nguồn trạng thái |
| Identity hiện tại | `MockCurrentUserProvider` cho local | Khớp LN-002 đang backlog; tuyệt đối không dùng mock khi deploy thật |
| AI decision | `APPROVED/REJECTED` tự động; `PENDING_REVIEW` mới cần admin | Loan thực thi state/pricing/Contract, AI chỉ trả contract có version |

Hình thức hiển thị/ký phải theo [`LEGAL-DISCLOSURE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-disclosure-01--công-bố-lãi-và-lịch-trả) và [`LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md#legal-contract-01--hợp-đồng-và-xác-nhận-điện-tử).

## 1. Nghiệp vụ cần đạt

Với hồ sơ `PENDING_REVIEW`, admin xem toàn bộ evidence rồi approve hoặc reject. Với AI `APPROVED`, Loan dùng cùng `LoanContractCreationService` để tạo hợp đồng tự động, không chờ thao tác admin. Không nhánh nào tạo LoanOffer hoặc sửa principal/term borrower đã yêu cầu.

```text
Application PENDING_REVIEW
    ├── Admin REJECT → Application REJECTED
    └── Admin APPROVE
          → Application APPROVED
          → LoanContract PENDING_SIGNATURE
                ├── borrower SIGN → SIGNED
                ├── borrower DECLINE → DECLINED
                └── timeout → EXPIRED
```

```text
Application SCORING + AI APPROVED
    → final rate + final schedule đã sẵn sàng
    → Application APPROVED (decisionSource=AI_POLICY)
    → LoanContract PENDING_SIGNATURE
```

Application không phải hợp đồng. Màn hình admin không phải hợp đồng. Chỉ LoanContract là văn bản borrower ký.

## 2. Phạm vi

- Admin review projection/API.
- Approve/reject command, audit, optimistic lock.
- `LoanContract` entity, state machine và bảng artifact PDF bất biến.
- Tạo nội dung text legacy, PDF/hash/version và click-wrap receipt MVP.
- Borrower read/sign/decline.
- Contract expiry job idempotent.
- Migration/index/test/Postman.

Không publish market listing; LN-009 mở sau Contract SIGNED. Không SmartCA thật; thuộc task mở rộng. Không core loan booking; LN-011. Không Kafka/Notification integration trong LN-008 local; borrower đọc Contract qua API `/me`, event thông báo chỉ bổ sung khi contract dùng chung được duyệt.

### 2.1 Policy MVP được khóa trong task

| Policy | Giá trị | Tác dụng |
|---|---|---|
| decision policy | `ADMIN_MANUAL_DECISION_V1` | Ghi phiên bản quy tắc admin decision, tách với policy/model AI |
| terms version | `LOAN_TERMS_V1` | Xác định bộ điều khoản borrower đã đọc |
| document version | `CLICK_WRAP_TEXT_V2` | Renderer tạo văn bản tiếng Việt có từng mục và nhãn dễ đọc; V1 là chuỗi canonical `key=value` đã ngừng tạo mới |
| signature method | `CLICK_WRAP_MVP` | Chỉ cho phép consent click-wrap trong LN-008 |
| signature window mặc định | 7 ngày | Hạn ký nếu request không chọn thời điểm ngắn hơn |
| signature window tối đa | 30 ngày | Chặn admin tạo Contract chờ ký vô hạn |
| expiry worker delay | 60 giây | Đủ nhanh cho MVP nhưng không quét DB liên tục |
| expiry batch size | 100 | Giới hạn số Contract mỗi lượt để transaction/job không phình to |

Các giá trị trên đi qua `LoanContractProperties`, có default không nhạy cảm trong `application.yml`.
Nếu thay đổi ý nghĩa điều khoản hoặc renderer phải tăng version, không sửa nội dung Contract đã tạo.

Reason-code allowlist MVP:

- approve: `POLICY_APPROVED`;
- admin reject: `INSUFFICIENT_REPAYMENT_CAPACITY`, `IDENTITY_OR_KYC_NOT_ELIGIBLE`, `INCONSISTENT_DECLARED_INFORMATION`, `POLICY_NOT_SATISFIED`, `OTHER_MANUAL_REVIEW`;
- borrower decline: `TERMS_NOT_ACCEPTED`, `BORROWER_CHANGED_MIND`, `OTHER`;
- system expiry: `SIGNATURE_WINDOW_EXPIRED`.

Code phục vụ rule/report/audit; detail chỉ giải thích thêm và không được dùng thay code chuẩn hóa.

## 3. Admin review response

Một endpoint trả read model đủ duyệt, không bắt UI gọi nhiều endpoint theo từng row. “Projection/read model” ở đây là bản chỉ đọc tối thiểu để hiển thị; nguồn chuẩn vẫn là các bảng Loan sở hữu:

Các API đọc dành cho admin:

- `GET /api/v1/admin/loan-applications?page=0&size=20` — danh sách tất cả hồ sơ, luôn phân trang;
- `GET /api/v1/admin/loan-applications?status=PENDING_REVIEW&page=0&size=20` — lọc theo một trạng thái cụ thể; cùng contract hỗ trợ `APPROVED`, `REJECTED` và các trạng thái xử lý khác;
- `GET /api/v1/admin/loan-applications/{applicationNumber}/review` — detail cố định số query.

Gồm:

- Application/requested terms/purpose.
- `id` số của hồ sơ (explorer bằng chứng blockchain tra `LOAN_APPLICATION` theo ID này) và `contractNumber`
  (`null` khi chưa lập Contract) — B73.
- Financial snapshot và source tự khai.
- Product min/base/max rate, repayment/core mapping snapshot.
- Initial và final schedule summary/periods; trường `schedule` tương thích trả final nếu đã có.
- Eligibility/KYC/profile source.
- Latest AI assessment allowlist: model version, score/grade/limit/recommendation/reason; không trả raw JSON.
- BorrowerCreditProfile/source/policy version.
- Application status/version/history summary.

Assessment có thể `null` khi profile/KYC không đủ chắc chắn và hồ sơ đi thẳng từ eligibility sang manual
review. Queue dùng một projection query join latest assessment; detail dùng số query cố định cho
Application, schedule, eligibility mới nhất, credit profile, assessment, Contract và history giới hạn.
Không gọi User/AI/Fineract khi admin mở queue/detail và không trả PII hoặc raw snapshot không cần duyệt.

Detail response tối thiểu để UI duyệt trong một lần gọi:

- schedule summary và toàn bộ `periods` có kiểu rõ ràng lấy từ snapshot bất biến của hồ sơ;
- Contract detail trả cùng cấu trúc từng kỳ thay vì JSON kỹ thuật để borrower đọc trước khi ký;

```json
{
  "applicationNumber": "LA-0123456789ABCDEF0123",
  "status": "PENDING_REVIEW",
  "version": 5,
  "borrower": { "borrowerId": "BORROWER-001", "profileSource": "LOCAL_MOCK", "kycStatus": "VERIFIED" },
  "requestedAmount": 50000000,
  "requestedTermMonths": 12,
  "annualInterestRate": 15.0000,
  "finalAnnualInterestRate": 15.5000,
  "pricingCreditGrade": "C",
  "pricingAdjustmentPercentagePoints": 0.5000,
  "pricingPolicyVersion": "RISK_PRICING_V1",
  "repaymentMethod": "ANNUITY",
  "initialSchedule": { "totalInterest": 4154790, "maximumInstallment": 4512899 },
  "finalSchedule": { "totalInterest": 4300000, "maximumInstallment": 4525000 },
  "assessment": { "assessmentId": 20, "status": "SUCCEEDED", "actualModelVersion": "17.0.0", "evaluationScore": 70.0, "creditGrade": "C", "aiRecommendation": "PENDING_REVIEW", "decisionPolicyVersion": "credit-policy-v17" },
  "decision": null
}
```

Danh sách trả `PageResponse<AdminLoanReviewSummaryResponse>` theo chuẩn hiện có; không truyền `status` nghĩa là xem tất cả,
không phải tải toàn bộ database trong một response. Mỗi page không nhúng document, raw periods hoặc history list vào từng row.

## 4. Admin approve/reject contract

### Approve

`POST /api/v1/admin/loan-applications/{applicationNumber}/approve`

Header bắt buộc: `Idempotency-Key`.

```json
{
  "applicationVersion": 5,
  "assessmentId": 20,
  "decisionReasonCode": "POLICY_APPROVED",
  "decisionReasonDetail": "Đủ điều kiện theo policy v1",
  "contractExpiresAt": "2026-08-20T17:00:00+07:00"
}
```

Body không có amount/term/rate/installment. Service lấy exact terms từ immutable Application/Product/calculation snapshots. Assessment phải SUCCEEDED và là latest/được phép theo policy.

`assessmentId` là bắt buộc khi approve và phải trỏ đúng assessment `SUCCEEDED` mới nhất của Application. Admin không được chọn một assessment cũ thuận lợi hơn.
`contractExpiresAt` có thể bỏ trống để dùng hạn 7 ngày; nếu truyền thì phải ở tương lai và không vượt 30 ngày kể từ thời điểm approve.

Approve trả HTTP 200 cho cả lần đầu và retry cùng key/body:

```json
{
  "applicationNumber": "LA-0123456789ABCDEF0123",
  "applicationStatus": "APPROVED",
  "applicationVersion": 6,
  "contractNumber": "LC-ABCDEF0123456789ABCD",
  "contractStatus": "PENDING_SIGNATURE",
  "contractVersion": 0,
  "documentHash": "sha256...",
  "expiresAt": "2026-08-15T10:00:00Z"
}
```

### Reject

`POST /api/v1/admin/loan-applications/{applicationNumber}/reject`

Header bắt buộc: `Idempotency-Key`.

```json
{
  "applicationVersion": 5,
  "assessmentId": null,
  "reasonCode": "INSUFFICIENT_REPAYMENT_CAPACITY",
  "reasonDetail": "Không đáp ứng ngưỡng policy v1"
}
```

`assessmentId` được phép `null` khi hồ sơ đi thẳng tới manual review do thiếu điều kiện profile/KYC và chưa có AI assessment thành công. Nếu hồ sơ đã có assessment thì lưu đúng assessment mới nhất được admin sử dụng. Reason code phải thuộc allowlist, dài tối đa 50 ký tự; detail tối đa 1000 ký tự và không chứa PII nhạy cảm hoặc nội dung phân biệt đối xử.

Reject trả HTTP 200 với `applicationNumber`, status `REJECTED`, version mới, reason code, policy version, `adminDecidedBy` và `adminDecidedAt`; không trả raw AI evidence.

## 5. Application decision fields

Được thêm bằng migration LN-008, không tạo sớm ở LN-004:

| Field | PostgreSQL | Tác dụng |
|---|---|---|
| `adminDecisionReasonCode` | VARCHAR(50) nullable | Lý do admin chuẩn hóa, cùng giới hạn với status history |
| `adminDecisionReasonDetail` | VARCHAR(1000) nullable | Giải thích audit của admin |
| `adminDecisionPolicyVersion` | VARCHAR(30) nullable | Policy admin đã dùng; chỉ có sau quyết định admin |
| `adminDecisionAssessmentId` | BIGINT nullable | Assessment evidence; chỉ nullable cho admin reject manual trước scoring |
| `adminDecisionIdempotencyKey` | VARCHAR(150) nullable | Chống lặp command approve/reject của admin |
| `adminDecisionRequestHash` | VARCHAR(64) nullable | Phát hiện cùng admin key nhưng body khác |
| `adminDecidedBy` | VARCHAR(100) nullable | Admin actor từ provider |
| `adminDecidedAt` | TIMESTAMPTZ nullable | Thời điểm admin quyết định theo UTC instant |

Migration V5 đồng thời bổ sung `APPROVED` vào constraint của `loan_applications` và status history. `adminDecisionPolicyVersion` là policy quyết định của Loan, không thay thế `modelVersion` hay `decisionPolicyVersion` nằm trong AI assessment.

Không thêm cột `decision`: `LoanApplication.status` đã là nguồn duy nhất cho kết quả `APPROVED/REJECTED`. Các field `adminDecision*` chỉ giữ evidence khi admin thực sự quyết định, tránh hai cột trạng thái có thể mâu thuẫn. Hồ sơ bị `REJECTED` từ cổng eligibility của LN-005 giữ evidence ở eligibility check + status history và để toàn bộ `adminDecision*` là `null`.

Migration V8 bổ sung `decisionSource=AI_POLICY|ADMIN`, `automatedDecisionPolicyVersion` và
`automatedDecidedAt`. Nhánh AI không giả lập admin evidence; nhánh admin phải có đủ bộ `adminDecision*`.

Approve/reject + admin decision fields + Application status history + Contract (nếu approve) phải commit trong cùng local transaction. Không tồn tại trạng thái Application `APPROVED` nhưng thiếu Contract. Application không giữ thêm `loanContractId`; Contract đã có unique `applicationId`, tránh quan hệ hai chiều và hai nguồn liên kết.

## 6. `LoanContract` fields

| Field | PostgreSQL | Ai tạo | Tác dụng |
|---|---|---|---|
| `id` | BIGINT | DB | Khóa |
| `contractNumber` | VARCHAR(50) unique | Generator | Public ID dạng `LC-` + 20 ký tự hex hoa, không lộ database ID |
| `applicationId` | BIGINT unique | Loan | Một Contract/Application MVP |
| `borrowerId` | VARCHAR(100) | Application | Ownership |
| `principalAmount` | DECIMAL(18,2) | Snapshot | Exact requested/approved amount |
| `termMonths` | SMALLINT | Snapshot | Exact term |
| `annualInterestRate` | DECIMAL(7,4) | Application `finalAnnualInterestRate` | Lãi suất cuối theo năm; base rate chỉ dùng để giải thích so sánh |
| `repaymentMethod` | VARCHAR(30) | Product snapshot | ANNUITY/EQUAL_PRINCIPAL |
| `calculationSnapshotId` | BIGINT | Loan | Schedule evidence |
| `totalInterest` | DECIMAL(18,2) | Calculation snapshot | Tổng lãi dự kiến borrower được công bố trước khi ký |
| `totalFees` | DECIMAL(18,2) | Calculation snapshot | Tổng phí dự kiến; MVP giữ 0 nếu Product không có phí |
| `totalPenalties` | DECIMAL(18,2) | Calculation snapshot | Phạt trong lịch dự kiến; MVP giữ 0, không dùng để che policy phạt tương lai |
| `totalRepayment` | DECIMAL(18,2) | Calculation snapshot | Tổng tiền borrower dự kiến phải trả |
| `firstInstallment` | DECIMAL(18,2) | Calculation snapshot | Khoản đầu tiên để borrower dễ đối chiếu với preview |
| `maximumInstallment` | DECIMAL(18,2) | Calculation snapshot | Nghĩa vụ tháng lớn nhất đã dùng trong disclosure/chấm điểm |
| `scheduleResponseHash` | VARCHAR(64) | Calculation snapshot | Chứng minh Contract dùng đúng lịch `CONTRACT` theo final rate |
| `expectedDisbursementDate` | DATE | Calculation | Mốc lịch dự kiến |
| `termsVersion` | VARCHAR(30) | Policy | Version điều khoản |
| `documentVersion` | VARCHAR(30) | Renderer | Template document |
| `documentContent` | TEXT | Renderer | Toàn bộ nội dung text borrower đọc và ký trong MVP |
| `documentContentType` | VARCHAR(50) | Renderer | Cố định `text/plain; charset=UTF-8` |
| `documentHash` | VARCHAR(64) | Loan | Bằng chứng nội dung bất biến |
| `status` | VARCHAR(30) | State machine | Lifecycle |
| `consentIdempotencyKey` | VARCHAR(150) nullable | Loan | Chống lặp sign/decline |
| `consentRequestHash` | VARCHAR(64) nullable | Loan | Phát hiện cùng key nhưng payload khác |
| `consentAction` | VARCHAR(20) nullable | Loan | `SIGN/DECLINE` đã xử lý |
| `signedBy` | VARCHAR(100) nullable | Current user | Borrower đã thực hiện consent ký |
| `signedAt` | TIMESTAMPTZ nullable | Clock | Thời điểm consent ký |
| `signatureMethod` | VARCHAR(30) nullable | Policy/request | Chỉ nhận `CLICK_WRAP_MVP` trong LN-008 |
| `declinedBy` | VARCHAR(100) nullable | Current user | Borrower từ chối Contract |
| `declinedAt` | TIMESTAMPTZ nullable | Clock | Thời điểm từ chối |
| `declineReasonCode` | VARCHAR(50) nullable | Allowlist | Lý do chuẩn hóa, dùng báo cáo mà không đọc text tự do |
| `declineReasonDetail` | VARCHAR(1000) nullable | Borrower | Giải thích tùy chọn, phải lọc giới hạn và không log INFO |
| `expiresAt` | TIMESTAMPTZ | Policy/admin request | Hạn cuối borrower được consent |
| `effectiveAt` | TIMESTAMPTZ nullable | LN-011 | Chỉ có sau giải ngân thành công, LN-008 chưa gán |
| `version` | BIGINT | JPA | Chống hai thao tác consent/expiry cùng thắng |
| `createdBy/updatedBy` | VARCHAR(100) | Current user/system worker | Biết ai tạo và ai thực hiện thay đổi cuối |
| `createdAt/updatedAt` | TIMESTAMPTZ | Clock | Audit thời gian tạo/cập nhật |

Không lưu `documentUri`: PDF đang nằm trong PostgreSQL để demo end-to-end và endpoint chỉ trả bytes đã lưu. Khi chuyển sang object storage, URI phải trỏ tới object bất biến; hash PDF đã consent vẫn là nguồn đối chiếu, không được render lại rồi thay bytes cũ.

Tạo thêm `LoanContractDocument`:

| Field | PostgreSQL | Tác dụng |
|---|---|---|
| `contractId` | BIGINT FK | Contract sở hữu artifact |
| `artifactType` | VARCHAR(30) | `SIGNABLE` hoặc `SIGNED_RECEIPT`; unique theo Contract/type |
| `documentVersion` | VARCHAR(30) | Phiên bản template PDF |
| `contentType/contentLength` | VARCHAR/INTEGER | Metadata tải file và kiểm tra bytes |
| `contentHash` | VARCHAR(64) | SHA-256 của đúng bytes PDF |
| `content` | BYTEA | File PDF bất biến, không tải trong API list |
| `createdAt/updatedAt` | TIMESTAMPTZ | Dấu vết tạo artifact |

Tạo thêm `LoanContractStatusHistory`:

| Field | PostgreSQL | Tác dụng |
|---|---|---|
| `id` | BIGINT | Khóa kỹ thuật của history row |
| `contractId` | BIGINT | Contract bị thay đổi trạng thái |
| `fromStatus` | VARCHAR(30) nullable | `null` cho lúc tạo, còn lại là trạng thái trước đó |
| `toStatus` | VARCHAR(30) | Trạng thái mới đã commit |
| `reasonCode` | VARCHAR(50) nullable | Lý do chuẩn hóa của create/sign/decline/expire |
| `actorType` | VARCHAR(20) | `ADMIN`, `BORROWER` hoặc `SYSTEM` |
| `actorId` | VARCHAR(100) | Admin, borrower hoặc `SYSTEM` thực hiện |
| `occurredAt` | TIMESTAMPTZ | Thời điểm transition thật sự commit |
| `traceId` | VARCHAR(100) nullable | Lần ngược request/job tạo transition |

Khi tạo Contract phải có row đầu tiên `null → PENDING_SIGNATURE`; mỗi transition sign/decline/expire/effective phải có một history row. LN-008 triển khai creation và ba transition sign/decline/expire.

### 6.1 Constraint bắt buộc

- `loan_contracts.application_id` unique và foreign key tới `loan_applications(id)`; `calculation_snapshot_id` foreign key tới `schedule_calculation_snapshots(id)`.
- `loan_applications.admin_decision_assessment_id` foreign key tới `credit_scoring_assessments(id)`; service còn phải xác minh assessment đó thuộc chính Application, là bản mới nhất và `SUCCEEDED` khi approve.
- Không tạo foreign key `borrower_id` sang User DB vì mỗi microservice sở hữu database riêng.
- `loan_contract_status_histories.contract_id` foreign key tới Contract; không cascade xóa lịch sử nghiệp vụ.
- Check allowlist cho Contract status và bổ sung `APPROVED` vào Application/status-history check hiện hành.
- Contract `SIGNED` bắt buộc có `signed_by`, `signed_at`, `signature_method`; `DECLINED` bắt buộc có `declined_by`, `declined_at`, `decline_reason_code`.
- `expires_at > created_at`; hash đúng 64 ký tự; amount/totals/installment không âm; term/rate dương.
- Application `APPROVED` bắt buộc có latest assessment, final rate và final calculation snapshot. Nếu `decisionSource=ADMIN` thì phải đủ admin evidence; nếu `AI_POLICY` thì phải có automated policy/time. `REJECTED` từ eligibility được phép không có cả hai bộ evidence.
- Các cột admin decision bắt buộc phải cùng null hoặc đủ reason/policy/idempotency hash/actor/time theo nhánh trên; detail luôn tùy chọn và assessment chỉ được null ở admin reject manual eligibility.
- Không hard-delete Application/Contract/history đã tham gia quyết định; sửa sai bằng forward transition/task policy sau này.

### 6.2 Quan hệ dữ liệu sau LN-008

```text
LoanApplication (1) ───────────── (0..1) LoanContract
      │                                      │
      │                                      ├──── (N) LoanContractStatusHistory
      │                                      └──── (1..2) LoanContractDocument
      │
      ├──── (1) ScheduleCalculationSnapshot SUBMISSION_SCORING
      ├──── (0..1) ScheduleCalculationSnapshot CONTRACT ─► Contract.calculationSnapshotId
      │
      └──── (N) CreditScoringAssessment
                    ▲
                    ├── latestCreditAssessmentId: bản mới nhất để hiển thị
                    └── adminDecisionAssessmentId: đúng bản admin đã dùng để quyết định
```

- `Application 1-0..1 Contract`: hồ sơ bị reject thì không có Contract; approve thành công phải tạo đúng một Contract. Unique `application_id` bảo vệ quy tắc này ở database.
- Contract không phải “hồ sơ vay thứ ba”. Application là yêu cầu và hành trình xét duyệt; Contract là điều khoản bất biến borrower sẽ đọc/ký và tiếp tục sang giải ngân.
- Lịch `SUBMISSION_SCORING` là evidence ban đầu/base rate; Contract chỉ tham chiếu lịch `CONTRACT` theo final rate và sao chép totals để nội dung đã ký không đổi về sau.
- Một Contract có nhiều history vì sign/decline/expire là các sự kiện khác nhau. History append-only, không cascade-delete để giữ dấu vết consent.
- Một Contract mới có đúng một `SIGNABLE`; sau khi ký có thêm một `SIGNED_RECEIPT`. Unique `(contract_id, artifact_type)` ngăn ghi đè hoặc tạo trùng.
- Hai con trỏ Assessment có ý nghĩa khác nhau: `latest...` phục vụ màn hình hiện tại; `adminDecision...` khóa đúng bằng chứng admin đã xem, dù sau đó có re-score.
- `borrowerId` vẫn là logical ID sang User Service, không có foreign key xuyên microservice.

## 7. Schedule trong Contract

Amount, term và repayment method không thay đổi; rate trong Contract là final rate sau scoring. Do actual disbursement có thể khác ngày dự kiến, Contract phải nói rõ:

- schedule tại lúc ký là dự kiến dựa trên `expectedDisbursementDate`;
- Fineract sinh official schedule theo actual disbursement;
- thay đổi chỉ ở mốc ngày/interest phát sinh theo policy đã công bố, không tự đổi rate/method/principal/term;
- nếu chênh lệch vượt tolerance/policy pháp lý, phải xin consent/version hợp đồng mới, không cập nhật im lặng.

LN-007 đã tạo snapshot `purpose=CONTRACT` theo final rate trước khi hồ sơ vào `PENDING_REVIEW` hoặc `APPROVED`. LN-008 không gọi lại Fineract khi admin approve/sign; nó bắt buộc dùng đúng final snapshot. LN-011 mới tạo official schedule sau ngày giải ngân thật; nếu lịch chính thức vượt tolerance/policy đã công bố thì phải tạo consent/version mới, không sửa Contract đã ký.

## 8. Contract state machine

```text
PENDING_SIGNATURE → SIGNED
PENDING_SIGNATURE → DECLINED
PENDING_SIGNATURE → EXPIRED
SIGNED → EFFECTIVE       (LN-011 sau disbursement)
EFFECTIVE → COMPLETED    (task servicing)
```

- Không sign contract expired/declined/already signed.
- SIGNED không đồng nghĩa đã giải ngân.
- EFFECTIVE chỉ sau Payment + Fineract disbursement Saga thành công.
- Terms/document hash bất biến từ PENDING_SIGNATURE.
- Application giữ `APPROVED` khi Contract `DECLINED` hoặc `EXPIRED`; trạng thái consent chỉ thuộc Contract.
- Expiry worker đọc một batch ID giới hạn, sau đó xử lý từng Contract trong transaction ngắn với lock/version check và ghi history. Không cần lease vì job không gọi mạng.

## 9. Borrower API

- `GET /api/v1/loan-contracts/me?page=0&size=20` — danh sách Contract của borrower hiện tại.
- `GET /api/v1/loan-contracts/{contractNumber}` — chỉ owner/admin, trả exact terms/document/hash/status/version.
- `GET /api/v1/loan-contracts/{contractNumber}/document` — chỉ owner, trả PDF hiện hành đã lưu với `ETag` là SHA-256; sau ký ưu tiên `SIGNED_RECEIPT`.
- `POST /api/v1/loan-contracts/{contractNumber}/sign` — yêu cầu header `Idempotency-Key`:

```json
{
  "version": 0,
  "documentHash": "sha256...",
  "pdfDocumentHash": "sha256-cua-signable-pdf...",
  "signatureMethod": "CLICK_WRAP_MVP"
}
```

- `POST /api/v1/loan-contracts/{contractNumber}/decline` — yêu cầu header `Idempotency-Key`, nhận `version`, `reasonCode`, `reasonDetail`.

Server đối chiếu cả hash text legacy và hash PDF `SIGNABLE` để chứng minh borrower ký đúng artifact vừa xem. Contract legacy không có PDF vẫn được backend tương thích, nhưng UI mới không cho ký nếu chưa có PDF. Không nhận borrower ID/actor từ body. Local hiện lấy owner từ `MockCurrentUserProvider`; JWT/resource authorization thật thuộc LN-002 và bắt buộc trước deploy/demo dùng chung.

Sign/decline trả HTTP 200 với `contractNumber`, status/version mới, actor/time tương ứng và document hash. GET detail trả thêm exact terms, schedule dự kiến và `documentContent`; GET list chỉ trả summary, không kéo document lớn cho từng row.

## 10. Document/signature MVP

- Renderer text legacy vẫn deterministic. Renderer PDF A4 dùng font DejaVu đóng gói trong service, dữ liệu từ immutable Application/Contract schedule snapshot và `CONTRACT_PDF_V1`.
- Nội dung tối thiểu gồm application/contract number, principal, term, annual rate, repayment method, tổng nghĩa vụ, installment lớn nhất, expected dates, toàn bộ các kỳ dự kiến (ngày đến hạn, gốc, lãi, tổng trả, dư nợ còn lại), schedule hash/reference, policy versions, hạn ký và cảnh báo lịch đang là dự kiến.
- `CLICK_WRAP_TEXT_V2` diễn đạt các nội dung trên bằng tiếng Việt có tiêu đề, mục và nhãn; không đưa chuỗi `key=value`/dòng phân cách bằng `|` thành giao diện chính cho borrower. Contract V1 đã tạo vẫn bất biến, UI chỉ diễn giải bằng các field có cấu trúc và cho mở bản gốc để đối chiếu.
- Hash bằng `HashingService.sha256Text(documentContent)` trên đúng bytes UTF-8 đã lưu; không hash object JSON không canonical.
- Render và lưu text legacy cùng PDF `SIGNABLE` trong transaction tạo Contract; nếu render/lưu PDF lỗi thì Application không được commit `APPROVED` thiếu tài liệu.
- Khi sign, Loan kiểm tra hash và bytes PDF `SIGNABLE`, cập nhật Contract rồi sinh `SIGNED_RECEIPT` trong cùng transaction. Endpoint tải không render lại nên ETag/hash ổn định.
- `CLICK_WRAP_MVP` lưu actor, timestamp, idempotency/trace reference. Không tự tin tưởng IP/device do client gửi và chưa thu thập metadata chưa có privacy policy.
- Không tuyên bố đây là chữ ký số pháp lý SmartCA; adapter thật là task riêng.
- Object storage, signed URL, SmartCA và chữ ký thật của bên cho vay nằm ngoài phần mở rộng này; PostgreSQL BYTEA chỉ là lựa chọn demo/local trước khi chốt hạ tầng artifact production.

## 11. Transaction/idempotency/concurrency

“Idempotency” nghĩa là admin/borrower bấm gửi lại cùng một yêu cầu thì hệ thống trả lại kết quả cũ, không tạo Contract hoặc transition lần hai.

- Approve/reject khóa Application, kiểm tra `applicationVersion`, lưu `adminDecisionIdempotencyKey` và SHA-256 canonical request trước khi chuyển trạng thái.
- Cùng key + cùng request trả lại kết quả hiện có; cùng key + request khác trả 409 `IDEMPOTENCY_KEY_REUSED`.
- Unique `application_id` ở Contract cộng với lock/version Application ngăn hai admin tạo hai Contract.
- `ContractNumberGenerator` dùng cùng pattern UUID rút gọn như `ApplicationNumberGenerator`; unique constraint là lớp bảo vệ cuối. Collision cực hiếm làm rollback toàn bộ approve, không để Application duyệt dở; client retry command sẽ sinh số mới.
- Reject không tạo Contract; approve tạo Contract, content/hash và history cùng transaction.
- Sign/decline khóa Contract, dùng `@Version`, lưu consent key/hash/action; cạnh tranh chỉ một terminal transition thắng.
- Sign/decline luôn so `expiresAt` với server `Clock`, không dựa vào việc scheduled worker đã kịp chạy. Nếu đã quá hạn, state-service commit `EXPIRED` + history trước, sau đó lớp orchestration trả `CONTRACT_EXPIRED`; không ném exception bên trong transaction làm rollback transition hết hạn.
- Expiry worker chỉ update Contract còn `PENDING_SIGNATURE AND expires_at <= now()` và ghi history trong cùng transaction mỗi Contract.
- Scheduled worker chỉ lấy tối đa `batchSize` ID rồi gọi một bean state-service riêng `expireOne(contractId)` cho từng ID; không đặt một transaction bao cả batch và không tự gọi method `@Transactional` trong cùng class làm mất Spring proxy.
- Không gọi AI/Fineract/User/document storage trong transaction và không cần distributed transaction ở LN-008.

## 12. Index/N+1

Index giống mục lục: cột lọc phải đứng trước cột sắp xếp. Partial index chỉ chứa đúng các hàng còn chờ review/chờ hết hạn nên worker không phải quét toàn bộ Contract đã kết thúc; đổi lại index vẫn tốn dung lượng và chi phí cập nhật, vì vậy không index mọi field hợp đồng.

| Index | Query |
|---|---|
| unique contract_number | tra cứu/external ID |
| unique application_id | một Contract/Application |
| unique partial admin_decision_idempotency_key | một admin decision key không điều khiển hai hồ sơ |
| unique partial consent_idempotency_key | một consent key không điều khiển hai Contract |
| borrower_id, created_at, id | danh sách Contract borrower |
| partial `(expires_at, id)` WHERE status=`PENDING_SIGNATURE` | expiry worker chỉ quét Contract cần thiết |
| partial Application `(created_at, id)` WHERE status=`PENDING_REVIEW` | hàng chờ cần admin xử lý ngay |
| Application `(created_at, id)` | tab “Tất cả”, sắp xếp hồ sơ mới nhất mà không quét và sort toàn bảng |
| Application `(status, created_at, id)` | các tab lọc trạng thái như chờ duyệt/đã duyệt/đã từ chối |
| contract history `(contract_id, occurred_at, id)` | timeline Contract ổn định |

Danh sách admin dùng một page query và một batch query lấy latest assessment/summary, không fetch history/assessment/contracts
collections theo từng row. Detail dùng số query chuyên biệt cố định và query-count test; tuyệt đối không gọi remote service khi đọc.

Entity giữ scalar foreign key (`applicationId`, `calculationSnapshotId`) thay vì collection/bidirectional JPA graph; read model dùng projection/query chuyên biệt để trách nhiệm và số query rõ ràng.

### 12.1 Cache, log, audit và dữ liệu nhạy cảm

- Không dùng Redis/cache trong LN-008: queue, status, version và hạn ký thay đổi thường xuyên; đọc trực tiếp PostgreSQL với index/projection giúp kết quả nhất quán. Chỉ xem xét cache sau khi có số đo tải thật.
- INFO log chỉ ghi `applicationNumber`, `contractNumber`, from/to status, actor type, trace ID và kết quả; không log `documentContent`, financial snapshot, AI raw payload hay reason detail.
- WARN log có error code/trace ID cho version conflict, idempotency conflict và expiry race; không log stack trace lặp cho lỗi nghiệp vụ dự kiến.
- Application/Contract history là audit nghiệp vụ; `createdBy/updatedBy` là audit bản ghi. Hai loại không thay thế nhau.
- Hàm implementation quan trọng phải có comment/JavaDoc tiếng Việt giải thích invariant, transaction, idempotency, document hash và lý do không gọi mạng; không comment lặp lại cú pháp.

## 13. Failure/error

- Application không PENDING_REVIEW: `INVALID_APPLICATION_TRANSITION`.
- Assessment stale/failed: `ASSESSMENT_NOT_ELIGIBLE_FOR_DECISION`.
- Version conflict: 409.
- `contractExpiresAt` trong quá khứ hoặc quá 30 ngày: validation error.
- Duplicate approve same idempotency/body: trả Contract hiện tại.
- Duplicate approve khác body: 409.
- Document render/persist fail: rollback toàn bộ, không để Application APPROVED không có Contract.
- Sign sai hash: `CONTRACT_CONTENT_MISMATCH`.
- Contract expired: `CONTRACT_EXPIRED`.
- Sai owner: 403; không tiết lộ nội dung Contract của borrower khác.

## 14. File/package dự kiến thay đổi

| Khu vực | File/lớp dự kiến | Trách nhiệm duy nhất |
|---|---|---|
| migration | `V5__create_admin_decision_and_loan_contract.sql` | Thêm decision evidence, `APPROVED`, Contract/history/constraint/index |
| migration | `V7__add_admin_loan_application_list_indexes.sql` | Hỗ trợ tab tất cả và các tab lọc trạng thái theo thứ tự mới nhất |
| `domain/application` | `LoanApplication`, `LoanApplicationStatus` | Chuyển quyết định Application và giữ evidence; không chứa logic ký Contract |
| `domain/contract` | `LoanContract`, `LoanContractStatus`, `LoanContractStatusHistory`, `SignatureMethod` | Invariant và state machine riêng của Contract/consent |
| `dto/decision` | request/response admin review, approve, reject | Contract HTTP cho admin, không dùng entity làm response |
| `dto/contract` | response/detail/sign/decline request | Contract HTTP cho borrower |
| `repository/application` | projection/repository review queue | Queue/detail cố định số query, lock Application khi quyết định |
| `repository/contract` | Contract/history repository + projection | Ownership lookup, lock consent, expiry batch và timeline |
| `service/decision` | interface + implementation admin decision | Orchestrate approve/reject và local transaction |
| `service/contract` | interface + implementation borrower contract | Read/sign/decline, expiry transition và document rendering |
| `mapper/decision`, `mapper/contract` | mapper read model/response | Không đặt business transition trong mapper |
| `controller` | `AdminLoanDecisionController`, `LoanContractController` | Validate HTTP/header/path rồi gọi application service |
| `config` | `LoanContractProperties` | Policy/version/hạn ký không nhạy cảm |
| test | package mirror domain/service/controller/repository | State, idempotency, concurrency, query count, migration và renderer |
| Postman | collection/environment/README hiện có | Request độc lập có sẵn URL/body; README chỉ rõ giá trị number/version/hash cần copy vào environment |

Không bổ sung DTO/enum/request/response vào package gốc `domain`, `service` hoặc `integration`. LN-008 không sửa client AI/Fineract vì quyết định và consent là nghiệp vụ local của Loan.

## 15. Migration/test/Postman

- Migration V5 tạo decision fields, contracts, contract histories, constraints/index và bổ sung `APPROVED`; V7 bổ sung index cho danh sách admin tất cả/lọc trạng thái. Kiểm tra migrate từ schema sạch lẫn V4 hiện tại.
- Test approve/reject happy path và mọi invalid transition.
- Regression: hồ sơ đã `REJECTED` từ eligibility vẫn hợp lệ với `adminDecision* = null`; admin reject mới phải có đủ admin evidence.
- Hai admin approve cạnh tranh chỉ tạo một Contract.
- Approve chỉ dùng latest `SUCCEEDED` assessment; reject manual được phép không có assessment theo đúng nhánh eligibility.
- Contract exact terms bằng final pricing/schedule snapshots; không lấy rate trực tiếp từ AI và không gọi lại Fineract khi approve/sign.
- Sign/decline/expiry/idempotency/version/hash.
- Mọi transition tạo đúng history; expiry batch không bỏ history và không xử lý lại terminal Contract.
- Sai owner bị từ chối.
- Renderer/hash deterministic.
- Query count danh sách tất cả, danh sách lọc trạng thái và detail.
- Cập nhật Postman với `applicationNumber`, `contractNumber`, `contractVersion`, `documentHash`, `assessmentId` và `Idempotency-Key`; từng request có sẵn body/URL.
- Khi triển khai migration, cập nhật expected table count/truncate helper trong `FinoraLoanApplicationIT`.

## 16. Acceptance criteria

- Không còn LoanOffer trong domain/API/DB/flow mới.
- Admin không sửa amount/term/rate/method khi approve.
- Borrower ký đúng một Contract với exact terms/hash/version.
- Contract lưu được chính nội dung UTF-8 borrower đã đọc, không chỉ lưu hash hoặc URI.
- Approve/reject/sign/decline retry an toàn và phát hiện cùng key khác payload.
- Danh sách admin có tab tất cả và lọc trạng thái, luôn phân trang, không N+1 và không gọi remote service.
- Contract SIGNED chưa bị hiểu là disbursed/EFFECTIVE.
- Application giữ quyết định APPROVED; Contract giữ trạng thái consent riêng.
- Mọi state change có actor/history/idempotency/concurrency test.
- Mock identity được ghi rõ là giới hạn local và không được dùng production.

## 17. Nghiệm thu

```text
Plan decision: APPROVED
Implementation status: IN_PROGRESS_PDF_DOCUMENT
Reviewer: Thai
Reviewed at: 2026-08-08
Notes: Thái đã duyệt bản rà soát và yêu cầu bắt đầu triển khai ngày 2026-08-08.
```

## 18. Thay đổi kế hoạch ngày 2026-08-08

- Đổi public path từ database `{id}` sang `{applicationNumber}`/`{contractNumber}`.
- Chuẩn hóa toàn bộ timestamp mới theo PostgreSQL `TIMESTAMPTZ`.
- Contract dùng final schedule `purpose=CONTRACT`; external recalculate đã được chuyển sang LN-007 trước transaction quyết định.
- Khóa policy/version/consent MVP, lưu deterministic document text + hash ngay trong Loan DB.
- Bổ sung idempotency cho cả admin decision và borrower consent, Contract history và expiry batch transaction ngắn.
- Tách Application decision state khỏi Contract consent state; không phát sinh thêm LoanOffer.
- Khóa projection/query-count cho admin review để tránh N+1 và tránh gọi remote khi đọc.

Thái đã xác nhận bản thay đổi này ngày 2026-08-08; task chuyển qua `APPROVED` rồi `IN_PROGRESS` để bắt đầu migration/code.

## 19. Bản đồ API → hàm sau triển khai

### 19.1 Admin đọc hồ sơ

| API | Thứ tự hàm thực tế | Cách hoạt động |
|---|---|---|
| `GET /api/v1/admin/loan-applications` | `AdminLoanDecisionController.listApplications` → `AdminLoanDecisionServiceImpl.listApplications` → `LoanApplicationRepository.findAll(Pageable)` hoặc `findByStatus` + `CreditScoringAssessmentRepository.findAllById` → `AdminLoanDecisionMapper.toSummary` | Không có `status` thì lấy một page tất cả; có `status` thì lọc một trạng thái. Assessment luôn batch-load nên số query không tăng theo số dòng, không tải document/history và không gọi mạng. |
| `GET /api/v1/admin/loan-applications/{applicationNumber}/review` | `AdminLoanDecisionController.reviewDetail` → `AdminLoanDecisionServiceImpl.reviewDetail` → các repository snapshot local → `AdminLoanDecisionMapper.toDetail` | Đọc Application, initial/final schedule, eligibility, credit profile, latest assessment, số hợp đồng (`LoanContractRepository.findContractNumberByApplicationId`: một query lấy riêng cột theo khóa duy nhất `application_id`, không tải văn bản hợp đồng) và tối đa 20 history bằng số query cố định. Đây là màn hình bằng chứng để admin quyết định, không chấm lại AI. |

### 19.2 Admin approve/reject

`approve` đi theo thứ tự:

1. `AdminLoanDecisionController.approve` kiểm tra path/header/body rồi gọi interface `AdminLoanDecisionService`.
2. `AdminLoanDecisionServiceImpl.approve` chuẩn hóa `Idempotency-Key`, tạo canonical request hash bằng `HashingService.sha256` và gọi state service. Lớp này không mở transaction ghi để nhánh khôi phục sau unique race có thể đọc kết quả đã commit.
3. `AdminLoanDecisionStateService.approve` mở một local transaction, kiểm tra duplicate, khóa Application, xác nhận latest assessment `SUCCEEDED`, final rate và final schedule `CONTRACT`.
4. `LoanApplication.approveByAdmin` kiểm tra version/state/latest assessment và ghi evidence `decisionSource=ADMIN`.
5. `LoanContractCreationService.create` dùng final terms, gọi renderer/hash và tạo đúng một Contract `PENDING_SIGNATURE`; cùng service này đã được nhánh AI auto-approved dùng.
6. Application, Application history, Contract và Contract history được `saveAndFlush` trong cùng transaction. `AdminLoanDecisionMapper.toDecision` chỉ chạy sau khi state service trả kết quả.

`reject` đi theo `AdminLoanDecisionController.reject` → `AdminLoanDecisionServiceImpl.reject` → `AdminLoanDecisionStateService.reject` → `LoanApplication.rejectByAdmin`. Nó dùng cùng idempotency/version/lock/history nhưng không render và không tạo Contract. Hồ sơ manual review từ eligibility được phép reject với `assessmentId = null`; nếu đã có assessment thì phải tham chiếu bản mới nhất.

`executeWithDuplicateRecovery` ở facade chỉ bắt `DataIntegrityViolationException` do race, sau đó gọi lại `findCommittedByKey`. Cùng key + cùng hash trả kết quả cũ; cùng key + khác Application/action/body đi lại qua `duplicateDecision` và trả `409 IDEMPOTENCY_KEY_REUSED`.

### 19.3 Borrower đọc/ký/từ chối Contract

| API | Thứ tự hàm thực tế | Điểm quan trọng |
|---|---|---|
| `GET /api/v1/loan-contracts/me` | `LoanContractController.listMine` → `LoanContractServiceImpl.listMine` → một page Contract + batch Application → `LoanContractMapper.toSummary` | Không đưa `documentContent` lớn vào từng dòng và không N+1 Application. |
| `GET /api/v1/loan-contracts/{contractNumber}` | `LoanContractController.detail` → `LoanContractServiceImpl.detail` → `LoanContract.requireOwner` → Application/Schedule repository → `LoanContractMapper.toDetail` | Chỉ owner giả lập hiện tại được đọc exact document/hash/schedule. |
| `GET .../{contractNumber}/document` | controller → `LoanContractServiceImpl.document` → owner check → `LoanContractDocumentRepository` | Trả đúng bytes PDF bất biến đã lưu; trước ký dùng `SIGNABLE`, sau ký ưu tiên `SIGNED_RECEIPT`; `ETag` là SHA-256 của artifact. |
| `POST .../{contractNumber}/sign` | controller → `LoanContractServiceImpl.sign` → duplicate check/prepare → `SignatureProvider.sign` ngoài transaction → `LoanContractStateService.sign` → lock và kiểm tra lại owner/version/expiry/hash → tạo `SIGNED_RECEIPT` → Contract/history/outbox cùng commit → mapper | Local dùng `MOCK + CLICK_WRAP_MVP`; response lưu provider transaction/evidence. VNPT adapter chưa gọi mạng và trả fail-closed tới khi có contract chính thức. |
| `POST .../{contractNumber}/decline` | controller → `LoanContractServiceImpl.decline` → request hash → `LoanContractStateService.decline` → `LoanContract.decline` → Contract/history save → mapper | Là terminal state riêng; không đổi ngược Application `APPROVED`. |
| `GET .../{contractNumber}/history` | controller → `LoanContractServiceImpl.history` → owner check → history page → mapper | Timeline phân trang, sắp xếp `occurredAt DESC, id DESC`. |

Nếu sign/decline đến đúng lúc đã hết hạn, `LoanContractStateService` commit `EXPIRED` và history trước. Sau khi transaction kết thúc, `LoanContractServiceImpl.rejectExpired` mới trả `409 CONTRACT_EXPIRED`, nên lỗi HTTP không rollback dấu vết hết hạn.

### 19.4 Expiry worker

`LoanContractExpiryWorker.expireDueContracts` không có transaction bao cả batch. Nó gọi `LoanContractStateService.dueIds` để lấy tối đa `expiryBatchSize` ID theo partial index, rồi gọi `expireOne` qua Spring proxy cho từng ID. `expireOne` khóa một Contract, gọi `LoanContract.expireIfDue`, lưu Contract + history trong transaction ngắn; chạy lại trên terminal Contract trả `false` và không tạo history trùng.

## 20. Kết quả triển khai và bằng chứng bàn giao

### 20.1 Thành phần đã thêm/cập nhật

- Flyway `V5__create_admin_decision_and_loan_contract.sql`: decision evidence, trạng thái `APPROVED`, hai bảng Contract/history, foreign key/check/unique/partial index.
- Domain: `domain/decision`, `domain/contract` và admin transition trong `LoanApplication`.
- HTTP contract: DTO tách theo `dto/decision` và `dto/contract`; hai controller mới dùng public number.
- Application/domain service: `AdminLoanDecisionServiceImpl`, `AdminLoanDecisionStateService`, `LoanContractServiceImpl`, `LoanContractStateService`, deterministic renderer và expiry worker.
- Persistence/read model: repository Application/assessment bổ sung batch/latest query; repository Contract/history mới.
- Cấu hình: `LoanContractProperties` đã được đăng ký qua `LoanTimeConfig`; không có secret trong YAML.
- Postman: collection có 26 request độc lập và environment có đủ application/assessment/contract/version/hash/idempotency variables.
- Flyway `V8__add_risk_based_pricing_and_ai_v17.sql`: bổ sung min/base/max rate, final pricing/schedule, nguồn quyết định AI/admin và cho phép mỗi hồ sơ giữ riêng lịch ban đầu với lịch hợp đồng.
- Flyway `V9__create_immutable_contract_pdf_documents.sql`: lưu PDF `SIGNABLE` và `SIGNED_RECEIPT` bất biến, kèm version/content type/hash/length; unique theo Contract và loại artifact.
- Luồng tạo Contract được gom vào `LoanContractCreationService`: cả AI auto-approve và admin approve dùng cùng một quy tắc, tránh tạo hai cách lập hợp đồng khác nhau.
- Luồng tạo Contract sinh PDF A4 phía server trong cùng transaction; endpoint tải không render lại. Khi ký, server xác minh SHA-256 của đúng bytes `SIGNABLE` borrower đã mở rồi sinh receipt có dấu vết click-wrap.
- Flyway `V11__add_signature_provider_evidence.sql`: thêm provider/transaction/evidence, backfill bản ký legacy thành `MOCK` và ràng buộc cặp provider-method.
- Signature boundary: `SignatureProvider`, deterministic `MockSignatureProvider`, cấu hình secret qua environment và `VnptSmartCaSignatureProvider` fail-closed; không lưu OTP/token/private key.
- Flyway `V12__create_transactional_outbox.sql`: bảng event local, partial index lấy bản đến hạn, processing lease và claim token. Tạo/ký/từ chối/hết hạn Contract ghi event cùng local transaction; relay gọi transport ngoài transaction, retry có backoff/giới hạn và dead-letter. Kafka publisher mặc định tắt nên chưa thay đổi contract liên service.

### 20.2 Lệnh và kết quả kiểm tra ngày 2026-08-08

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
& 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd' -pl finora-loan -am verify
```

Kết quả: `BUILD SUCCESS`, exit code `0`.

- Common unit test: 6 pass.
- Loan unit/architecture test: 34 pass.
- `FinoraLoanApplicationIT`: 8 pass, 1 skip có chủ đích (`finora.live.ai` chỉ bật khi test AI Docker thật).
- `LoanMigrationUpgradeIT`: V1–V4 tạo schema cũ, sau đó V5 migrate thành công trên PostgreSQL 17.5; không còn pending migration.
- Integration test đã kiểm tra schema sạch 12 bảng nghiệp vụ, Hibernate context, submit/scoring, admin review query count, approve tạo đúng một Contract, retry idempotent, đọc exact document/hash, sign idempotent và history.
- Kết quả lịch sử ngày 2026-08-08: hai file Postman khi đó parse JSON thành công với 24 request và 15 environment variables.

### 20.2.1 Rà soát thay đổi AI v17/risk pricing ngày 2026-09-05

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven\lib\maven3\bin\mvn.cmd' -q -pl finora-loan -am clean test
```

- Unit/slice/architecture test sạch: **54 test, 0 failure, 0 error, 0 skipped**.
- Hai file Postman hiện tại parse JSON thành công: **26 request, 16 environment variables**.
- `LoanMigrationUpgradeIT` đã được nâng từ kỳ vọng V7 lên V8 và kiểm tra các cột pricing/decision cùng unique index của hai loại schedule.
- Chưa chạy lại `mvn verify` cho V8 trong phiên này vì Docker daemon đang tắt. Do đó migration V8 giữ trạng thái `REVIEW`, chưa được ghi là đã pass Testcontainers/PostgreSQL thật.
- Cần chạy lại `mvn -pl finora-loan -am verify` khi Docker Desktop sẵn sàng; không dùng Neon production làm database kiểm thử phá dữ liệu.

### 20.2.2 Rà soát PDF hợp đồng bất biến ngày 2026-09-07

- Common: **6 test pass**; Loan unit/architecture: **49 test pass**, không có failure/error.
- `ContractPdfRendererTest` kiểm tra header PDF, SHA-256, text tiếng Việt và tài liệu có nhiều trang; bản render kiểm tra gồm 4 trang A4.
- `FinoraLoanApplicationIT` đã bổ sung luồng đọc metadata/tải `SIGNABLE`, ký bằng `pdfDocumentHash` và nhận `SIGNED_RECEIPT`; `LoanMigrationUpgradeIT` đã nâng kỳ vọng lên V9.
- `mvn verify` đã được gọi nhưng Testcontainers không thể khởi tạo vì Docker Desktop đang tắt. Đây là chặn môi trường; chưa được ghi nhận V9/API integration pass PostgreSQL thật.
- Cần bật Docker Desktop và chạy lại `mvn -pl finora-loan -am verify` trước khi chuyển task sang `READY_FOR_REVIEW`.

### 20.2.3 Rà soát signature provider và transactional outbox ngày 2026-09-17

```powershell
& 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' -pl finora-loan -am verify
```

- Kết quả: `BUILD SUCCESS`, exit code `0`.
- Common: **6 unit test pass**; Loan: **72 unit/architecture test pass**.
- Integration/migration: **13 test**, **12 pass + 1 live-AI test skip có chủ đích**; clean migration và V4→V12 đều pass PostgreSQL 17.5.
- Integration chứng minh ký mock trả provider/transaction/evidence, retry cùng idempotency key không tạo event trùng, `LoanContractCreated` và `LoanContractSigned` cùng ở outbox `PENDING` khi Kafka chưa bật.
- Known warning không làm fail build: Flyway 9.22.3 cảnh báo PostgreSQL 17 mới hơn dải hỗ trợ chính thức; scheduler Fineract cũ có thể log lỗi lúc Testcontainers đóng DB trước Spring context shutdown.

### 20.3 Rollback/forward-fix

Migration Flyway đã áp dụng không được sửa hoặc xóa. PostgreSQL chạy DDL migration theo transaction; nếu V5 lỗi giữa chừng thì toàn migration rollback. Khi V5 đã có dữ liệu `APPROVED`/Contract, không rollback binary cũ vì code cũ không hiểu trạng thái mới; phải backup trước deploy và sửa tiếp bằng migration V6 forward-fix.

### 20.4 Known limitation còn lại

- Identity vẫn là `MockCurrentUserProvider`; JWT/RBAC thật của LN-002 bắt buộc trước demo dùng chung/production.
- `MOCK + CLICK_WRAP_MVP` chỉ là consent trong hệ thống, chưa phải chữ ký số SmartCA. LN-018 chỉ được mở adapter thật khi VNPT cấp certificate/serial và tài liệu API/callback chính thức.
- Contract `SIGNED` chưa giải ngân và chưa tạo official Fineract loan/schedule; thuộc LN-011.
- Contract event đã được ghi bền vững vào local transactional outbox nhưng chưa phát Kafka; topic/schema/consumer liên service phải được Thái và Hải review ở bước messaging.
- Flyway do Spring Boot 3.2.5 quản lý vẫn cảnh báo PostgreSQL 17 mới hơn dải đã kiểm thử chính thức của Flyway đi kèm, dù cả clean migration và V4→V5 hiện đều pass. Việc nâng Flyway nên xử lý trong task nền PostgreSQL thay vì sửa riêng LN-008.

Task đang ở `IN_PROGRESS`. Backend đã pass Testcontainers đến V12; chỉ chuyển sang `READY_FOR_REVIEW` sau khi kiểm tra mở/lưu/ký PDF trên thiết bị. Chỉ Thái chuyển sang `ACCEPTED` sau khi đọc code và nghiệm thu lại luồng UI/API.
