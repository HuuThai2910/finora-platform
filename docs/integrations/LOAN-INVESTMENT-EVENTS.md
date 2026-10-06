# Hợp đồng sự kiện Loan – Investment

> **Trạng thái:** `IMPLEMENTED_LOCAL_VERIFIED` — Loan, Investment, Payment và Blockchain đã có
> producer/consumer idempotent; schema/Flyway, integration test, Fineract V2 và Kafka broker local đã
> được xác minh ngày 2026-10-04. Provider sandbox/triển khai UAT được nghiệm thu riêng.

Tài liệu này là nguồn chung cho luồng từ hồ sơ vay đã được phép đưa lên sàn đến khi giải ngân và phát hành Note.
Nó giải thích cả ý nghĩa nghiệp vụ lẫn contract Kafka để Loan, Investment, Notification,
Payment và Blockchain không suy diễn trạng thái của nhau.

## 1. Cách dùng REST và Kafka

- **REST GET** trả dữ liệu hiện tại để mobile/web hiển thị. Ví dụ nhà đầu tư tải danh sách listing từ
  Investment và tải PDF hợp đồng từ Loan.
- **Kafka event** thông báo một việc đã commit để service khác thực hiện đúng một bước tiếp theo.
  Ví dụ Loan đã cho phép gọi vốn thì Investment tạo listing đúng một lần.
- Kafka không được dùng để gửi PDF, CCCD, hồ sơ KYC, thu nhập, OTP hoặc chữ ký thô. Event chỉ mang
  business ID, exact terms tối thiểu, hash/version và trạng thái cần phối hợp.
- Producer ghi event vào **transactional outbox** trong cùng transaction với state nguồn. Outbox là
  bảng chờ gửi: database đã commit thì event không mất dù Kafka tạm dừng.
- Consumer ghi `processed_events` trong cùng transaction với side effect. Đây là dấu đã xử lý để
  Kafka giao lại cùng `eventId` cũng không tạo listing, hợp đồng hoặc trạng thái lần hai.

## 2. Ranh giới dữ liệu

| Dữ liệu | System of Record | Bản sao được phép |
|---|---|---|
| Application, exact terms, Contract, PDF, trạng thái chữ ký | Loan | Investment chỉ giữ reference/trạng thái hiển thị tối thiểu |
| Listing, order, commitment, allocation, Note | Investment | Loan giữ funding/allocation snapshot bất biến để lập hợp đồng |
| Profile/KYC/định danh pháp lý | User | Loan lấy snapshot tối thiểu qua API nội bộ có bảo vệ; không đưa PII lên Kafka |
| Delivery notification | Notification | Service nguồn chỉ giữ event/reference nếu cần |
| Tiền, hold/capture/ledger | Payment | Service khác chỉ giữ transaction/reference |
| Hash/proof Fabric | Blockchain | Loan giữ proof reference; blockchain không nhận raw PDF |

Investment lưu `market_listings` trong database của Investment. Mobile/web gọi REST Investment để
xem listing; Investment không gọi Loan mỗi lần người dùng mở màn hình. PDF và chữ ký lại thuộc Loan,
nên màn nhà đầu tư gọi REST Loan khi xem hoặc ký hợp đồng.

## 3. Envelope và quy ước chung

```json
{
  "eventId": "8cbcb833-a674-4ed4-94ae-71619d65e0d8",
  "occurredAt": "2026-09-26T10:00:00Z",
  "version": 1,
  "data": {}
}
```

- Tiền truyền bằng chuỗi decimal VND, ví dụ `"50000000.00"`.
- Lãi suất truyền theo **điểm phần trăm/năm**: `"15.0000"` nghĩa là `15%/năm`, không phải `0.15%`.
- Thời gian dùng ISO-8601 UTC.
- Partition key dùng aggregate public ID (`applicationNumber` hoặc `contractNumber`) để giữ thứ tự.
- Event type ở header `finora-event-type`; version ở `finora-event-version`; payload không lặp lại
  `eventId/occurredAt/version` bên trong `data`.

## 4. Bảng event đầy đủ

| Event / topic | Producer → consumer | Phát khi nào | Tác dụng và REST liên quan | Idempotency |
|---|---|---|---|---|
| `LoanFundingRequested` v1 / `finora.loan.funding-requested` | Loan → Investment | Exact terms đã `AUTO_AUTHORIZED` hoặc borrower `ACCEPTED`; local transaction đã ghi funding request | Investment tạo một `MarketListing` từ snapshot. Nhà đầu tư sau đó GET `/api/v1/market/listings`; event không thay GET | `eventId` trong `processed_events`; unique `loanId + fundingRound` |
| `LoanFullyFunded` v2 / `finora.investment.loan-fully-funded` | Investment → Loan | Tổng commitment hợp lệ bằng đúng target và allocation đã bị khóa | Loan đối chiếu số tiền/hash/hold, lưu allocation snapshot rồi tạo một Contract/PDF chung | `eventId`; transition listing `OPEN → FULLY_FUNDED` chỉ xảy ra một lần; Loan kiểm `allocationVersion` |
| `InvestorSignatureRequested` v1 / `finora.loan.investor-signature-requested` | Loan → Investment; Notification là consumer kế tiếp | PDF signable đã lưu bất biến và các lender parties đã được tạo | Investment cập nhật projection để UI biết Contract; Notification báo từng investor khi module đó được nối. App gọi GET Loan để tải đúng PDF/hash rồi ký | Investment dùng `eventId`; Notification sẽ dùng `sourceEventId + recipientId + channel + templateVersion` |
| `BorrowerSignatureRequested` v1 / `finora.loan.borrower-signature-requested` | Loan → Notification (chưa nối consumer) | Tất cả lender parties đã ký đúng cùng `documentVersion/documentHash` | App hiện đọc lại Contract từ Loan; consumer Notification sẽ báo borrower ở phase nối thông báo | Như event notification ở trên |
| `LoanContractActivated` v1 / `finora.loan.contract-activated` | Loan → Investment; Blockchain/Notification là consumer kế tiếp | Tất cả lender và borrower đã ký; Contract chuyển `EFFECTIVE` | Investment đánh dấu khoản đầu tư có hiệu lực. Trong cùng transaction kích hoạt, Loan tạo durable Saga/outbox `DisbursementRequested`; event này không được hiểu là đã giải ngân | `eventId`; mỗi consumer có `processed_events`; side effect tài chính có idempotency key riêng |
| `DisbursementRequested` v2 / `finora.loan.disbursement-requested` | Loan → Payment | Contract đã `EFFECTIVE`, Loan đã tạo durable Saga | Payment lưu yêu cầu duy nhất theo `sagaId`, capture/đối chiếu từng `paymentHoldReference` và gọi provider ngoài transaction | unique `sagaId`; provider reference/idempotency dùng cùng `sagaId` |
| `DisbursementCompleted` v1 / `finora.payment.disbursement-completed` | Payment → Loan | Provider xác nhận tiền đã chuyển và Payment đã lưu reference | Loan ghi nhận Fineract bằng external ID, không yêu cầu Payment chuyển lại | `eventId` + `sagaId`; `paymentReference` unique |
| `DisbursementFailed` v1 / `finora.payment.disbursement-failed` | Payment → Loan | Lỗi cuối cùng, không còn retry tự động trong Payment | Loan giữ Contract `EFFECTIVE`, saga thành `PAYMENT_FAILED` để vận hành xử lý | `eventId` + `sagaId` |
| `LoanDisbursed` v1 / `finora.loan.disbursed` | Loan → Investment, Notification, Blockchain | Payment success và Fineract đã ghi disbursement | Investment `ACTIVE → FINALIZED` commitments và phát hành Note; các consumer khác chỉ tạo projection/proof/thông báo | `eventId`; Note unique theo commitment/sequence |
| `NoteOwnershipChanged` v1 / `finora.investment.note-ownership-changed` | Investment → Payment | Note vừa phát hành hoặc đổi chủ trên chợ thứ cấp | Payment cập nhật read model chủ Note để kỳ trả tiếp theo chuyển tiền đúng người đang sở hữu | `eventId`; `noteId` unique; event cũ hơn `changedAt` không ghi đè event mới |
| `RepaymentDistributed` v1 / `finora.payment.repayment-distributed` | Payment → Loan, Investment, Blockchain | Ví người vay đã bị trừ, Fineract đã phân bổ và ledger phân phối đã cân bằng | Loan cập nhật projection; Investment cộng gốc/lãi và đóng Note khi hết gốc; Blockchain chỉ neo SHA-256 của `data`, không lưu payload tài chính thô | `eventId`; unique `repaymentId`/`fineractTransactionId`; `processed_events`; proof unique `sourceService + sourceEventId` |
| `LoanDelinquencyChanged` v1 / `finora.loan.delinquency-changed` | Loan → CIC mock, Investment | Snapshot Fineract làm DPD đổi, kể cả repayment khắc phục đưa DPD về 0 | CIC tách nhóm hiện tại/lịch sử; Investment cập nhật risk projection của Note/portfolio | Mỗi consumer dùng `eventId`; Investment bỏ event risk cũ hơn `dataAsOf` |
| `LoanSettled` v1 / `finora.loan.settled` | Loan → Investment lifecycle consumer | Fineract xác nhận `totalOutstanding = 0` và `FinoraLoan` vừa chuyển `SETTLED` | Investment lưu read model mốc đóng khoản vay; không thay `RepaymentDistributed` trong việc chia tiền/đóng Note | Outbox cùng transaction chuyển trạng thái; consumer `processed_events` |
| `LoanRescheduled` v1 / `finora.loan.rescheduled` | Loan → Investment lifecycle consumer | Admin đã duyệt, Fineract đã chấp nhận lịch mới và Loan đã refresh projection | Investment cập nhật maturity/schedule marker cho portfolio; không tự tính lịch hoặc dư nợ | `eventId` trong `processed_events`; `requestId` snapshot, event trùng không ghi lại |
| `InvestorNoteServicingChanged` v1 / `finora.investment.note-servicing-changed` | Investment → Notification | Note projection đổi do trả nợ, quá hạn, khắc phục, cơ cấu hoặc tất toán | Notification fan-out theo chủ Note và tạo in-app delivery; cờ push theo milestone, không push hằng ngày | Outbox Investment; unique `sourceEventId + recipientId + type` ở Notification |

`LoanContractActivated` chỉ xác nhận hợp đồng có hiệu lực. `DisbursementCompleted` chỉ xác nhận phía
Payment. `LoanDisbursed` mới xác nhận toàn bộ happy path bắt buộc (Payment + Fineract) đã hoàn tất.

## 5. Payload v1 đã chốt cho lát tích hợp đầu tiên

### 5.1 `LoanFundingRequested` v1

```json
{
  "loanApplicationId": 123,
  "applicationNumber": "LA-2026-000123",
  "listingVersion": 1,
  "fundingRound": 1,
  "productCode": "PERSONAL-01",
  "purpose": "Chi phí giáo dục",
  "borrowerRegion": null,
  "creditGrade": "B",
  "creditScore": 720,
  "targetAmount": "50000000.00",
  "annualInterestRate": "15.0000",
  "termMonths": 12,
  "repaymentMethod": "ANNUITY",
  "termsVersion": "LOAN_TERMS_V1",
  "termsHash": "sha256-hex"
}
```

- `loanApplicationId` hiện là khóa tương quan nội bộ giữa hai service trong môi trường demo;
  `applicationNumber` là business/public ID dùng hiển thị, log và partition key.
- `borrowerRegion` là optional. Investment hiển thị “Không công bố” nếu Loan chưa có public region;
  không tự lấy địa chỉ KYC để điền.
- `creditScore` là optional; `creditGrade` và final rate mới là snapshot chính cần cho listing.
- Investment không tự thay `targetAmount`, `annualInterestRate`, `termMonths` hoặc `repaymentMethod`.

### 5.2 `LoanFullyFunded` v2

```json
{
  "loanApplicationId": 123,
  "applicationNumber": "LA-2026-000123",
  "listingId": 456,
  "fundingRound": 1,
  "fundedAmount": "50000000.00",
  "currency": "VND",
  "allocationVersion": 1,
  "allocationHash": "sha256-hex",
  "fundingCompletedAt": "2026-09-26T10:30:00Z",
  "allocations": [
    {
      "commitmentId": 1001,
      "investorId": "keycloak-user-id",
      "amount": "30000000.00",
      "sharePercent": "60.000000",
      "paymentHoldReference": "HOLD-uuid-1"
    },
    {
      "commitmentId": 1002,
      "investorId": "keycloak-user-id-2",
      "amount": "20000000.00",
      "sharePercent": "40.000000",
      "paymentHoldReference": "HOLD-uuid-2"
    }
  ]
}
```

- `allocations` được sắp theo `commitmentId` trước khi hash để producer và consumer đối chiếu cùng
  một canonical snapshot.
- `investorId` là logical identity reference, không phải họ tên/CCCD. Bản hiện tại hiển thị mã tham
  chiếu này và không bịa định danh pháp lý. Batch API User để snapshot họ tên/định danh vào PDF là
  dependency trước production; không gọi User theo từng investor.
- `paymentHoldReference` là mã Payment trả khi giữ tiền. Loan lưu cùng lender party và chuyển nguyên
  mã này vào Saga; Payment chỉ capture khi owner, amount và reference khớp allocation đã ký.
- Sau khi event này được commit, allocation không được sửa âm thầm. Investor từ chối/hết hạn ký
  phải đi qua flow thay thế allocation và tạo `documentVersion` mới (phase sau).

### 5.3 `DisbursementRequested` v2 và event kết quả v1

```json
{
  "sagaId": "0e9b73a6-984a-4fe5-bd5f-0b409c9f0d38",
  "loanApplicationId": 123,
  "applicationNumber": "LA-2026-000123",
  "contractNumber": "LC-2026-000123",
  "listingId": 456,
  "borrowerId": "keycloak-user-id",
  "amount": "50000000.00",
  "currency": "VND",
  "allocations": [{"commitmentId": 1001, "investorId": "investor-id", "amount": "50000000.00", "paymentHoldReference": "HOLD-uuid-1"}]
}
```

- `DisbursementRequested` dùng payload trên; danh sách allocation phải đúng snapshot đã ký.
- `DisbursementCompleted` trả `sagaId`, các ID đối chiếu, amount/currency, `paymentReference`, `completedAt`.
- Payment chỉ tạo `DisbursementCompleted` sau khi đã ghi đủ hai bút toán cân bằng: capture phần
  tiền đang giữ của nhà đầu tư vào clearing và chuyển clearing sang số dư khả dụng của người vay.
  Dữ liệu `COMPLETED` cũ thiếu bút toán thứ hai được worker bù đúng một lần theo
  `BORROWER_CREDIT:<sagaId>`; không sửa hoặc xóa sổ cái cũ.
- `DisbursementFailed` trả `sagaId`, application/contract ID và mã lỗi đã lọc; không chứa raw provider body.
- `LoanDisbursed` trả thêm `fineractLoanId` và `disbursedAt`; đây là trigger duy nhất để tạo Note.

### 5.4 Quyền sở hữu Note và repayment v1

`NoteOwnershipChanged.v1` mang `loanApplicationId`, `listingId`, `currency`, `reason`, `reference`,
`changedAt` và mảng `notes(noteId, noteNumber, investorId, outstandingPrincipal)`. `reason` hiện là
`ISSUED` hoặc `TRANSFERRED`. Event không mang PII. Payment giữ projection để chọn ví nhận tiền;
Investment vẫn là nguồn chuẩn quyền sở hữu Note.

`RepaymentDistributed.v1` mang đầy đủ:

| Field | Ý nghĩa |
|---|---|
| `repaymentId`, `repaymentReference` | Identity/idempotency xuyên Payment ledger và Fineract |
| `repaymentType`, `quoteId` | Loại trả nợ; `quoteId` chỉ có với báo giá tất toán trước hạn |
| `platformFee` | Phí FINORA đã công bố/snapshot riêng, không gửi vào dư nợ Fineract |
| `loanApplicationId`, `fineractLoanId`, `fineractTransactionId` | Liên kết ba hệ thống |
| `amount`, `principalAmount`, `interestAmount`, `feeAmount`, `penaltyAmount` | Tổng thu và breakdown; `feeAmount = core fee + platformFee`, các phần còn lại từ Fineract |
| `outstandingPrincipal`, `outstandingInterest`, `outstandingFee`, `outstandingPenalty` | Từng thành phần dư nợ chính thức sau khi trả |
| `totalOutstanding`, `overdueAmount`, `nextDueDate`, `nextDueAmount` | Tổng dư nợ, quá hạn và kỳ tiếp theo từ Fineract |
| `currency`, `transactionDate`, `completedAt` | Tiền tệ và thời điểm nghiệp vụ |
| `allocations[]` | `noteId`, `noteNumber`, `investorId`, gốc/lãi đã credit cho từng Note |

Invariant bắt buộc:

- `principal + interest + fee + penalty = amount`; `platformFee` là phần được cộng rõ trong `fee`.
- Tổng phân bổ theo Note bằng đúng tổng gốc/lãi.
- Payment debit clearing đúng bằng tổng credit ví nhà đầu tư và ví platform.
- Fineract là nguồn breakdown; Loan/Investment không tự chạy waterfall khác.
- POST Fineract có kết quả không chắc chắn thì chuyển `RECONCILIATION_REQUIRED`, không POST lại mù.

### 5.5 Quá hạn và đóng khoản vay

`LoanDelinquencyChanged.v1` mang `loanApplicationId`, `loanNumber`, `borrowerId`, `fineractLoanId`,
DPD/nhóm nợ trước và sau, `overdueAmount`, `overdueSince`, `totalOutstanding`, `dataAsOf`.
Không mang CCCD. User đăng ký mapping `borrowerId ↔ CCCD` vào CIC qua HTTP nội bộ sau eKYC.
Repayment khắc phục đủ phần quá hạn phải tạo event có `daysPastDue = 0`; nếu chỉ sửa projection thì
CIC sẽ giữ sai nhóm cũ.

`LoanSettled.v1` mang `loanApplicationId`, `loanNumber`, `contractNumber`, `borrowerId`,
`fineractLoanId`, `settledAt`. Event được phát từ cả kết quả repayment và worker sync core, nhưng khóa
trạng thái/processed event bảo đảm chỉ transition thực sự mới ghi outbox.

### 5.6 Cơ cấu khoản vay

`LoanRescheduled.v1` chỉ được phát sau khi admin đã duyệt, Fineract đã approve yêu cầu reschedule và Loan
đã đọc lại servicing snapshot. Request còn `PENDING_REVIEW`, lỗi Fineract hoặc chỉ đổi local state không
được phát event này.

| Field | Ý nghĩa |
|---|---|
| `loanApplicationId`, `loanNumber`, `fineractLoanId` | Định danh khoản vay xuyên Loan/Fineract |
| `borrowerId` | Logical user ID; không phải CCCD |
| `requestId` | Idempotency/correlation của lần cơ cấu |
| `requestType` | `INSTALLMENT_ADJUSTMENT` hoặc `TERM_EXTENSION` |
| `previousMaturityDate`, `newMaturityDate` | Ngày đáo hạn trước/sau theo snapshot core |
| `rescheduleFromDate`, `adjustedDueDate`, `extraTerms` | Tham số lịch đã được Fineract chấp nhận |
| `occurredAt` | Thời điểm Loan commit lịch mới theo UTC |

Payload không mang `reasonComment`, admin note hoặc PII tự do. Investment/Notification là consumer dự kiến;
Hải cần review contract trước khi bật consumer. Retry/restart phía Loan dùng marker `[FINORA:<requestId>]`
để dò yêu cầu đã tạo trên Fineract trước khi POST lại.

## 6. Luồng và trạng thái mục tiêu

```text
Loan APPROVED + exact terms được phép
  → FUNDING_REQUESTED
  → Investment OPEN
  → Investment FULLY_FUNDED + allocation frozen
  → Loan tạo Contract PENDING_LENDER_SIGNATURES
  → PENDING_BORROWER_SIGNATURE
  → FULLY_SIGNED/EFFECTIVE
  → WAITING_PAYMENT → CORE_BOOKING → COMPLETED
  → Investment FINALIZED commitments + ACTIVE Notes
```

Luồng Investment là bắt buộc. Loan không tạo Contract ngay sau terms consent;
Loan luôn ghi `LoanFundingRequested` và chỉ tạo Contract sau `LoanFullyFunded` hợp lệ.

## 7. Failure, retry và DLT

- Broker lỗi sau local commit: outbox retry có backoff; không tạo event mới với business key khác.
- Kafka giao trùng: consumer trả thành công sau khi thấy `processed_events`, không chạy lại side effect.
- Event `LoanFullyFunded.v1` hoặc `DisbursementRequested.v1` còn tồn trong topic/DLT không được diễn
  giải theo schema mới; phải bỏ hoặc replay lại từ business state thành event v2 sau khi đối soát.
- Payload sai schema/rate/amount: fail closed và chuyển DLT/manual review; không tạo listing “gần đúng”.
- Target không chia hết mệnh giá Note hiện hành: Investment từ chối listing có mã lỗi rõ, tuyệt đối
  không làm tròn giảm tiền vay. Phase sau có thể hỗ trợ partial Note bằng contract mới.
- Loan nhận funded amount/hash sai: giữ trạng thái cũ và đưa event vào retry/DLT; không tạo Contract.
- Notification lỗi không rollback trạng thái Contract; Notification tự retry theo delivery history.

## 8. Bản đồ tham chiếu

- Luồng chuẩn toàn hệ thống: [08-cross-service-flows.md](../../.agents/rules/08-cross-service-flows.md),
  F03 và F04.
- Ownership/SoR: [07-service-boundaries.md](../../.agents/rules/07-service-boundaries.md).
- Loan producer: [LN-009](../../finora-loan/plans/LN-009-funding-requested-v1.md).
- Loan consumer và Contract nhiều bên: [LN-010](../../finora-loan/plans/LN-010-multi-party-contract-v1.md).
- Loan restructure producer: [LN-015](../../finora-loan/plans/LN-015-loan-restructuring.md).
- Pháp lý/hợp đồng/dữ liệu: [`LEGAL-CONTRACT-01`, `LEGAL-DATA-01`, `LEGAL-PAYMENT-01`](../LEGAL-COMPLIANCE.md).

## 9. Chạy local để test end-to-end

Khởi động broker bằng Compose (profile `demo` đã bao gồm Kafka):

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d kafka
```

Loan và Investment chạy trực tiếp từ IntelliJ cùng dùng `localhost:9092` và cần các biến sau:

```dotenv
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
FINORA_SIGNATURE_PROVIDER=VNPT_SMART_CA
```

`docker/.env` chỉ được Docker Compose đọc. Khi Loan/Investment chạy trực tiếp từ IntelliJ, mỗi service
đọc file `.env` trong **working directory của chính service**. Chỉ cần khai báo địa chỉ broker nếu
khác `localhost:9092`; consumer và outbox của cả Loan lẫn Investment luôn hoạt động,
không cò cờ bật/tắt. Loan fail-fast nếu cấu hình messaging bị ghi đè thành nửa vời.

Các hồ sơ cũ đã tạo Contract theo code demo không tự chuyển ngược thành listing vì Contract
và history đã là bằng chứng bất biến. Muốn kiểm tra luồng mới phải tạo hồ sơ mới sau
khi restart. Mốc đúng là:

1. Sau terms consent: Loan có `funding_status=REQUESTED`, chưa có `loan_contracts`.
2. Outbox Loan có `LoanFundingRequested`, `publishable=true`, sau đó chuyển `PUBLISHED`.
3. Investment có `market_listings` tương ứng; chỉ sau `LoanFullyFunded` Loan mới tạo Contract
   `PENDING_LENDER_SIGNATURES`.

`LoanApplicationResponse.funding` là projection cho mobile/web: `REQUESTED` hiển thị “Đang gọi vốn”,
`FULLY_FUNDED` cho phép client bắt đầu tra Contract. Client không được suy đoán “APPROVED = đã có Contract”.

`MOCK` chỉ dành cho local/dev. Khi dùng `VNPT_SMART_CA`, Loan lưu một giao dịch async riêng cho mỗi
investor, mobile poll endpoint refresh có giới hạn và chỉ mở lượt borrower sau khi mọi lender đã ký.
Môi trường UAT hiện dùng signer test cố định từ secret cấu hình; production phải ánh xạ signer theo
danh tính người dùng thay vì dùng chung signer cố định. Mobile gọi mọi REST qua Gateway; route
`/api/v1/investor/loan-contracts/**` thuộc Loan, còn Market/Portfolio thuộc Investment.

Tài liệu này là đặc tả kỹ thuật cho khóa luận, không thay thế phê duyệt pháp lý về mẫu hợp đồng hoặc
hình thức chữ ký của từng bên trước production.
