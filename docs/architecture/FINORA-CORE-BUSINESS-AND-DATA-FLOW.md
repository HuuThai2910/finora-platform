# FINORA Core — Nghiệp vụ, dữ liệu và luồng code hiện hành

> **Phạm vi:** phần Thái phụ trách — Loan, Payment, Blockchain/Fabric, tích hợp Apache Fineract và phần định tuyến Gateway liên quan.  
> **Mốc đối chiếu:** mã nguồn và migration trên workspace ngày **2026-10-25**.  
> **Mục đích:** một người đọc nghiệp vụ có thể hiểu hệ thống làm gì; developer có thể lần từ API/event tới đúng hàm, transaction, bảng và dependency mà không phải ghép nhiều plan lịch sử.

## 1. Cách đọc và giới hạn tài liệu

Tài liệu này mô tả **AS-IS — code đang tồn tại**, không biến thiết kế tương lai thành chức năng đã chạy. Khi tài liệu này và một plan cũ khác nhau, ưu tiên theo thứ tự:

1. migration và code hiện hành;
2. rule về quyền sở hữu dữ liệu;
3. tài liệu này;
4. plan lịch sử của từng task.

Các nguồn chi tiết vẫn được giữ, nhưng không bắt người đọc phải tự ghép chúng để hiểu luồng chính:

- [Quy tắc trả nợ và công thức](../LOAN-SERVICING-POLICY.md)
- [Kiểm soát pháp lý](../LEGAL-COMPLIANCE.md)
- [Event Loan–Investment](../integrations/LOAN-INVESTMENT-EVENTS.md)
- [Hướng dẫn kiểm thử UI](../testing/UI-MANUAL-TEST-GUIDE.md)
- [Runbook vận hành](../ops/RUNBOOKS.md)

Investment, User, Notification, AI và CIC chỉ được nhắc ở **ranh giới giao tiếp**. Tài liệu không suy diễn cấu trúc nội bộ các service của Hải.

### 1.1 Thuật ngữ bằng lời thường

| Thuật ngữ | Cách hiểu đời thường | Ví dụ trong FINORA |
|---|---|---|
| System of Record (SoR) | Nơi có quyền trả lời cuối cùng dữ liệu nào đúng | Fineract trả lời dư nợ/lịch trả; Payment trả lời tiền đã chuyển chưa |
| Snapshot | Ảnh chụp dữ liệu tại một thời điểm, về sau nguồn đổi thì bằng chứng cũ không đổi | Lịch dự kiến và thông tin Product lúc người vay nộp hồ sơ |
| Idempotency | Gửi lại cùng một lệnh không làm việc đó hai lần | Mạng chập chờn gửi lại lệnh nạp tiền không được cộng ví hai lần |
| Outbox | Hộp thư event lưu chung transaction với nghiệp vụ | Loan duyệt điều khoản và lưu `LoanFundingRequested` cùng lần commit |
| Saga | Hồ sơ theo dõi một quy trình nhiều service, không phải mỗi event là một Saga | Saga giải ngân theo dõi Payment chuyển tiền rồi Loan ghi Fineract |
| Processing lease | Quyền xử lý có thời hạn; worker chết thì worker khác được làm tiếp | Worker giải ngân/ghi proof nhận một lệnh, hết hạn mà chưa xong thì đối soát |
| Reconciliation | Hỏi lại nguồn chuẩn trước khi quyết định gửi lại hoặc sửa trạng thái | Timeout Fineract: tìm giao dịch theo external ID trước khi POST lại |
| Projection | Bản sao tối thiểu để hiển thị/ra quyết định cục bộ, không chiếm quyền nguồn chuẩn | Loan lưu `loan_servicing_projections` lấy từ Fineract |
| Compensation | Hành động bù trừ có lịch sử, không xóa dữ liệu để giả rollback | Hợp đồng thất bại trước giải ngân thì Investment nhả khoản tiền đã giữ |

## 2. Bản đồ trách nhiệm và nguồn dữ liệu chuẩn

| Phạm vi | Nguồn chuẩn | FINORA lưu gì | Không được làm |
|---|---|---|---|
| Hồ sơ, quyết định, hợp đồng, trạng thái khoản vay FINORA | Loan | Application, snapshot chấm điểm/lịch, Contract, Saga, servicing projection | Payment/Investment tự đổi trạng thái khoản vay |
| Sản phẩm core, loan account, lịch trả, dư nợ, phân bổ gốc/lãi/phí/phạt | Apache Fineract | Loan lưu mapping và projection; Payment lưu core transaction/breakdown | Đọc/ghi trực tiếp DB Fineract để sửa dữ liệu |
| Ví, tiền khả dụng/đang giữ, sổ cái, nạp/rút/giải ngân/thu nợ | Payment | Ledger bất biến, command và provider reference | Sửa balance không có bút toán cân bằng |
| Listing, commitment, allocation, Note và chủ Note | Investment | Loan/Payment chỉ giữ logical ID và snapshot cần thiết | Loan suy luận chủ Note hiện tại |
| Hash, trạng thái gửi, biên nhận Fabric | Blockchain | Hash/metadata/txId/block reference; không lưu payload gốc | Lưu PDF, CCCD hoặc dùng blockchain thay state nghiệp vụ |
| PDF hợp đồng gốc và chuỗi chữ ký nhúng | Loan | `loan_contract_documents`, `loan_contract_signatures` | Fabric giữ PDF hoặc quyết định giá trị pháp lý |
| Edge routing/CORS/timeout và xác thực resource | Gateway định tuyến; từng resource service xác thực JWT/quyền | Gateway không có DB | Tin request chỉ vì nó đi qua Gateway |

### 2.1 Liên kết logic xuyên service

Không có foreign key xuyên database. Các mã sau là dây nối để đối chiếu:

| Mã | Sinh ở đâu | Dùng để nối |
|---|---|---|
| `borrowerId` | claim `user_id` | User ↔ Loan ↔ Payment; không phải CCCD |
| `loanApplicationId` | Loan | Loan ↔ Payment ↔ Investment ↔ Blockchain |
| `applicationNumber` | Loan | Mã hồ sơ người dùng/admin nhìn thấy |
| `contractNumber` | Loan | Contract ↔ Saga ↔ Fineract external ID |
| `listingId`, `commitmentId` | Investment | Kết quả gọi vốn và từng phần vốn |
| `paymentHoldReference` | Payment | Một allocation dùng đúng khoản tiền đã giữ |
| `sagaId` | Loan | Một lần giải ngân xuyên Loan–Payment–Fineract |
| `fineractProductId`, `fineractLoanId` | Fineract | Mapping core, không phải ID nghiệp vụ FINORA |
| `repaymentId` | Payment | Thu tiền, ghi core, chia tiền và event trả nợ |
| `eventId` | producer | Consumer chống xử lý event trùng |
| `proofId` | Blockchain, UUID v5 từ mốc nghiệp vụ | PostgreSQL proof ↔ chaincode Fabric |
| Fabric `transactionId` + block reference | Fabric | Biên nhận chứng minh hash đã được ghi lên sổ cái |

## 3. Luồng toàn hệ thống nhìn theo nghiệp vụ

```text
Admin tạo Product
  → Loan đồng bộ Product sang Fineract
  → Người vay xem lịch dự kiến, nộp hồ sơ
  → Loan kiểm eKYC, chụp snapshot, AI chấm điểm, Fineract tính lịch cuối
  → Admin/Policy duyệt và người vay chấp nhận điều khoản nếu cần
  → Loan phát yêu cầu gọi vốn; Investment nhận lệnh và Payment giữ tiền
  → Đủ vốn: Loan lập một PDF chung, nhà đầu tư ký trước, người vay ký cuối
  → Contract EFFECTIVE và Loan mở Saga giải ngân
  → Payment capture hold, cộng ví người vay, phát kết quả
  → Loan tạo/duyệt/giải ngân loan account trong Fineract
  → Loan ACTIVE; Investment phát hành Note; Blockchain neo hash bốn mốc
  → Người vay trả tiền qua Payment
  → Fineract phân bổ gốc/lãi/phí/phạt
  → Payment chia cho chủ Note/FINORA, Loan và Investment cập nhật projection
  → Quá hạn/cơ cấu/tất toán được xử lý theo Fineract + policy hợp đồng
```

Một event thất bại ở Notification hoặc Blockchain **không đảo tiền**. Một bước tiền không rõ kết quả **không được gửi lại mù**; worker phải đối soát theo external ID/reference trước.

---

# Phần A — Loan Service

## 4. Vai trò nghiệp vụ, người dùng và quyền hạn

Loan sở hữu catalog sản phẩm FINORA, hồ sơ vay, quyết định tín dụng, hợp đồng nhiều bên, trạng thái khoản vay FINORA, Saga giải ngân và projection phục vụ vận hành.

| Actor | Việc được làm chính |
|---|---|
| Borrower | xem Product/lịch dự kiến; nộp/rút hồ sơ; chấp nhận điều khoản; ký; xem khoản vay/lịch; yêu cầu cơ cấu |
| Investor | đọc hồ sơ ẩn danh của listing; đọc/ký phần hợp đồng thuộc mình |
| Admin | quản lý Product; duyệt hồ sơ; xem chấm điểm; duyệt/từ chối cơ cấu; xử lý Saga/đối soát/thu hồi |
| Worker | chấm điểm, outbox, hết hạn, ký PAdES, booking Fineract, sync servicing, cơ cấu |

Loan vẫn kiểm JWT và quyền resource. Gateway chỉ định tuyến, không thay authorization trong Loan.

## 5. Dữ liệu Loan lưu và ý nghĩa

### 5.1 Nhóm nghiệp vụ chính

| Bảng | Người nghiệp vụ hiểu là gì | Nguồn/quan hệ | Vì sao phải lưu |
|---|---|---|---|
| `loan_products` | Gói vay FINORA admin cấu hình | 1 Product → nhiều Application/mapping lịch sử | Catalog, rate/term/amount và version bất biến khi đã dùng |
| `fineract_product_mappings` | Product FINORA đang ứng với Product nào trong core | Product 1 → 0..N mapping | Không dùng nhầm Product core khi version thay đổi |
| `fineract_commands` | Phiếu việc đồng bộ Product, restart không mất | mapping/aggregate logic | Retry có trạng thái, attempt và lỗi rõ ràng |
| `loan_applications` | Một lần người vay xin vay | Product 1 → 0..N Application | State hồ sơ, snapshot tài chính/KYC, exact terms, funding |
| `schedule_calculation_snapshots` | Lịch dự kiến/lịch cuối Fineract đã tính | Application 1 → nhiều snapshot theo purpose | Chứng minh người dùng đã thấy gì và hợp đồng dùng lịch nào |
| `borrower_eligibility_checks`, `borrower_credit_profiles` | Bằng chứng đủ điều kiện và dữ liệu tín dụng nội bộ | Application | Không tính lại lịch sử bằng dữ liệu mới |
| `credit_scoring_assessments`, `credit_scoring_retry_requests` | Mỗi lần gọi AI và yêu cầu chấm lại | Application 1 → 0..N assessment | Audit model/rule/reason và retry |
| `loan_contracts` | Hợp đồng chung của một hồ sơ đủ vốn | Application tối đa một Contract trong MVP | Điều khoản, hash, policy/version và trạng thái ký |
| `loan_contract_parties` | Người vay và từng nhà đầu tư phải ký | Contract 1 → 2..N party | Mỗi người ký đúng allocation/hash, lender trước borrower |
| `loan_contract_documents` | PDF signable, signed và receipt | Contract 1 → 1..N artifact | Lưu file gốc; Fabric không lưu file |
| `loan_contract_signatures` | Từng revision PAdES và trạng thái provider | Contract 1 → 0..N chữ ký | Resume/reconcile ký nối tiếp, không ký đè file |
| `loan_disbursement_sagas` | Tiến trình giải ngân xuyên service | Application 1 → 0..1 Saga | Restart/repair không chuyển tiền hai lần |
| `finora_loans` | State vòng đời FINORA sau giải ngân | Application 1 → 0..1 loan | ACTIVE/RESTRUCTURING/SETTLED/DEFAULTED |
| `loan_servicing_projections` | Bản chiếu lịch/dư nợ/DPD từ Fineract | FinoraLoan 1 → 1 projection | UI nhanh nhưng không thay nguồn chuẩn Fineract |
| `loan_reschedule_requests` | Yêu cầu điều chỉnh kỳ hạn/gia hạn | Loan 1 → 0..N, tối đa một yêu cầu active | Admin + nhà đầu tư duyệt rồi mới gọi core |
| `loan_collection_cases`, `loan_collection_actions` | Một đợt quá hạn/thu hồi và nhật ký xử lý | Loan 1 → 0..N case; case 1 → 0..N action | Giữ nhóm nợ cao nhất của đợt và audit append-only |
| `loan_reconciliation_incidents` | Sai lệch Loan projection với core | Loan 1 → 0..N incident | Fail-closed, không tự sửa core |
| `loan_repayment_event_quarantine` | Event trả nợ đúng schema nhưng chưa nối được loan | event/application logic | Giữ rồi replay, không bỏ mất event |
| `loan_outbox_events`, `loan_processed_events` | Event đi và event đã nhận | theo aggregate/event ID | Atomic publication và dedup consumer |

Các bảng history (`loan_application_status_histories`, `loan_contract_status_histories`) là timeline append-only, không dùng thay bảng aggregate hiện tại.

### 5.2 Quan hệ dữ liệu chính

```text
LoanProduct 1 ───── 0..N FineractProductMapping ───── 0..N FineractCommand
    │
    └──── 0..N LoanApplication 1 ───── 1..N ScheduleCalculationSnapshot
                      │
                      ├──── 0..N CreditScoringAssessment
                      ├──── 0..1 LoanContract 1 ───── 2..N ContractParty
                      │                         └──── 1..N ContractDocument/Signature
                      ├──── 0..1 DisbursementSaga
                      └──── 0..1 FinoraLoan 1 ───── 1 ServicingProjection
                                                    ├──── 0..N RescheduleRequest
                                                    └──── 0..N CollectionCase
```

Các quan hệ tới Fineract/Investment/Payment/Fabric chỉ là logical ID, không có FK xuyên service.

## 6. Trạng thái Loan dễ hiểu

| Aggregate | Luồng chính | Ý nghĩa quan trọng |
|---|---|---|
| Application | `SUBMITTED → ELIGIBILITY_PENDING → SCORING → APPROVED/PENDING_REVIEW/REJECTED` | `APPROVED` chưa phải đã có tiền; còn gate điều khoản, gọi vốn, ký và giải ngân |
| Funding | `REQUESTED → FULLY_FUNDED` hoặc `EXPIRED/CANCELLED` | FULLY_FUNDED chỉ khóa allocation để lập hợp đồng |
| Contract | `PENDING_LENDER_SIGNATURES → PENDING_BORROWER_SIGNATURE → SIGNED → EFFECTIVE → COMPLETED` | EFFECTIVE = đủ chữ ký; việc chuyển tiền nằm ở Saga riêng |
| Saga | `WAITING_PAYMENT → CORE_BOOKING_PENDING → CORE_BOOKING → COMPLETED` | `REPAIR_REQUIRED` chỉ chạy lại booking/reconcile, không chuyển tiền lần hai |
| FinoraLoan | `ACTIVE ↔ RESTRUCTURING`, rồi `SETTLED`; có thể `DEFAULTED` | Fineract vẫn là nguồn schedule/balance/arrears |
| Reschedule | `PENDING_REVIEW → PENDING_INVESTOR_CONSENT → CREATE/APPROVAL_PENDING → COMPLETED` | Một investor từ chối thì lịch cũ còn hiệu lực |

`WRITTEN_OFF` có trong enum nhưng chưa phải happy path demo hoàn chỉnh; không mô tả nó như quy trình xóa nợ production.

## 7. Luồng Product và đồng bộ Fineract

### 7.1 Nghiệp vụ

Admin tạo Product ở FINORA, yêu cầu đồng bộ, rồi chỉ được kích hoạt khi mapping core đã `SYNCED`. Nếu Fineract lỗi, Product vẫn `DRAFT`; hệ thống không tự tính lịch bằng công thức khác.

### 7.2 Chuỗi code và dữ liệu

```text
POST /api/v1/admin/loan-products/{id}/core-sync
→ AdminLoanProductController#synchronize
→ CoreProductSyncServiceImpl#synchronize
→ CoreProductSyncStateService#prepare                   [TX1]
   ghi/khóa loan_products + fineract_product_mappings + fineract_commands
→ CoreProductSyncServiceImpl#execute
→ CoreProductSyncStateService#startExecution             [TX2 ngắn]
→ CoreProductSyncServiceImpl#createOrReconcile           [ngoài TX]
→ FineractProductHttpClient#findProductByExternalId
→ FineractProductPayloadMapper#toCreateProductPayload
→ FineractProductHttpClient#createProduct POST /loanproducts
→ CoreProductSyncStateService#complete                   [TX3]
```

Timeout không chắc chắn: `createOrReconcile` tìm theo external ID trước/sau POST. Lỗi được `CoreProductSyncStateService#fail` lưu lại; `CoreProductSyncRetryWorker` lấy command đến hạn và gọi lại `execute`.

Product mới dùng core config V4: advanced allocation, `PROGRESSIVE`, `NEXT_INSTALLMENT`, bật tính lại lãi và tính lãi theo ngày trên dư nợ thực tế. V1–V3 chỉ được đọc tương thích; app chặn tự trả trước/tất toán khoản legacy.

## 8. Luồng nộp hồ sơ, chấm điểm và duyệt

### 8.1 Nộp hồ sơ

```text
POST /api/v1/loan-applications
→ LoanApplicationController#submit
→ LoanApplicationServiceImpl#submit
   → LoanApplicationSubmissionStateService#findExisting       [đọc idempotency]
   → UserServiceBorrowerProfileProvider#getBorrowerProfile     [ngoài TX, kiểm KYC]
   → LoanApplicationSubmissionStateService#prepareContext      [đọc Product/mapping]
   → FineractScheduleHttpClient#calculateSchedule              [ngoài TX]
   → LoanApplicationSubmissionStateService#persist             [TX]
      ghi application + Product/KYC/financial snapshot + schedule snapshot + history
```

Hai request cùng `Idempotency-Key` được unique constraint chặn. Product/mapping được chụp trước khi gọi mạng và khóa/tái kiểm tra khi persist để tránh admin sửa Product giữa chừng.

### 8.2 Chấm điểm bất đồng bộ

```text
CreditScoringWorker#processDueScoring
→ CreditScoringOrchestrator#processApplication
→ CreditScoringStateService#startEligibility                  [TX]
→ BorrowerEligibilityService#evaluate
→ CreditScoringStateService#applyEligibility                  [TX]
→ CreditScoringOrchestrator#executeAssessment
→ CreditScoringStateService#startExecution                    [TX/lease]
→ AiCreditScoringGateway#score                                [ngoài TX]
→ CreditScoringStateService#prepareFinalization               [đọc snapshot]
→ FineractScheduleGateway#calculateSchedule                   [ngoài TX nếu cần lịch final]
→ CreditScoringStateService#complete/#fail                    [TX]
```

AI chỉ trả score/decision/reason/model version; Loan lưu snapshot và áp policy. AI lỗi không tạo điểm mặc định. Admin xem/duyệt qua `AdminLoanDecisionController` → `AdminLoanDecisionServiceImpl`; state mutation nằm ở `AdminLoanDecisionStateService`.

Sau exact terms được chấp thuận, `LoanFundingRequestService` ghi `LoanFundingRequested.v1` vào outbox; không lập hợp đồng sớm.

## 9. Gọi vốn, hợp đồng nhiều bên và chữ ký

### 9.1 Khi Investment báo đủ vốn

```text
Kafka finora.investment.loan-fully-funded
→ LoanFullyFundedConsumer
→ LoanFullyFundedHandler#handle                              [TX]
   dedup loan_processed_events
   khóa loan_applications
   kiểm application/listing/round/amount/allocationHash
   → LoanContractCreationServiceImpl#createFunded            [cùng TX]
      ghi loan_contracts + loan_contract_parties + PDF/document + history
      ghi InvestorSignatureRequested.v1 vào loan_outbox_events
```

Mỗi lender party giữ `commitmentId`, amount, share và `paymentHoldReference`; người vay chỉ ký một hợp đồng chung, không phải một hợp đồng cho mỗi nhà đầu tư.

### 9.2 Ký và kích hoạt

- Investor API đi qua `InvestorLoanContractController` → `InvestorContractServiceImpl`.
- Borrower API đi qua `LoanContractController` → `LoanContractServiceImpl`.
- MOCK/VNPT adapter triển khai `SignatureProvider`; PAdES dùng `PadesSignaturePreparer`, `PadesCmsBuilder`, `PadesSignatureEmbedder`, `PadesSignatureVerifier`.
- `ContractPdfSignatureWorker` reconcile phiên ký bất đồng bộ; mỗi revision nhúng nối tiếp, lender trước, borrower cuối.
- Khi đủ lender, `InvestorContractServiceImpl` ghi `BorrowerSignatureRequested.v1`.
- Khi đủ tất cả bên, `LoanContractStateService#activateMultiParty` trong một transaction:
  1. Contract `SIGNED → EFFECTIVE` và history;
  2. kiểm receipt/PDF;
  3. ghi `LoanContractActivated.v1` vào outbox;
  4. gọi `DisbursementSagaService#start` để ghi Saga + `DisbursementRequested.v2` cùng transaction.

Chứng thư MOCK/UAT chỉ phục vụ demo. Blockchain không thay thế chữ ký số và không tự làm hợp đồng có giá trị pháp lý; xem `LEGAL-CONTRACT-01`.

## 10. Saga giải ngân và booking Fineract

```text
Loan outbox DisbursementRequested.v2
→ Payment xử lý tiền (Phần B)
→ Kafka DisbursementCompleted.v1
→ DisbursementCompletedConsumer
→ DisbursementSagaService#handleCompleted                     [TX, dedup]
   saga → CORE_BOOKING_PENDING
→ DisbursementSagaWorker
→ DisbursementSagaService#claim                               [TX/lease]
→ DisbursementSagaService#execute                             [ngoài TX]
→ FineractCoreLoanBookingGateway#bookAndDisburse
   ensureClient: GET/POST /clients
   ensureLoan: GET/POST /loans theo contractNumber external ID
   POST /loans/{id}?command=approve
   POST /loans/{id}?command=disburse
   GET /loans/{id}?associations=repaymentSchedule
→ DisbursementSagaService#complete                            [TX]
   activate servicing + saga COMPLETED + LoanDisbursed.v1 outbox
```

Mất phản hồi sau khi Fineract đã ghi: lần chạy sau tìm Client/Loan bằng external ID, không tạo lại. Hết retry tự động: `REPAIR_REQUIRED`; admin chỉ requeue bước core qua `AdminDisbursementSagaController`, không yêu cầu Payment chuyển tiền lần nữa.

## 11. Servicing, quá hạn, cơ cấu và thu hồi

### 11.1 Đồng bộ Fineract

`LoanServicingSyncWorker` → `LoanServicingSyncService#sync` → `FineractServicingHttpClient#read` (`GET /loans/{id}?associations=repaymentSchedule`) → transaction `apply` cập nhật `loan_servicing_projections`, `finora_loans`, collection và event DPD nếu đổi.

Sai core identity/external ID/principal/breakdown không được ghi đè projection; `LoanServicingReconciliationService` tạo `loan_reconciliation_incidents`. Admin xem/reconcile qua `AdminLoanServicingReconciliationController`.

### 11.2 Quá hạn và nhóm nợ

- Fineract cung cấp DPD/arrears; `CollectionCaseService#apply` mở/đóng episode và giữ nhóm cao nhất của đợt.
- Từ DPD 91, FINORA chuyển `DEFAULTED` theo policy demo hiện hành.
- Trả bớt sau default không làm giảm ngay nhóm lịch sử của episode; cure đầy đủ mới về `ACTIVE`.
- Đây là benchmark/policy nội bộ, chưa được gọi là phân loại CIC production; xem `LEGAL-OPEN-05`.

### 11.3 Cơ cấu/gia hạn

```text
POST /api/v1/loans/{loanNumber}/reschedule-requests
→ LoanRescheduleController#submit
→ LoanRescheduleService#submit                               [TX]
Admin approve
→ AdminLoanRescheduleController#approve
→ LoanRescheduleService#approve                              [TX]
→ LoanRescheduleConsentRequested.v1 outbox
Investment lấy đồng thuận chủ Note
→ LoanRescheduleConsentCompletedConsumer
→ LoanRescheduleConsentResultHandler                         [TX/dedup]
→ LoanRescheduleWorker
→ LoanRescheduleExecutionService#execute
   claim                                                     [TX/lease]
   FineractRescheduleHttpClient#findByLoanAndMarker           [ngoài TX/reconcile]
   #create POST /rescheduleloans
   #approve POST /rescheduleloans/{id}?command=approve
   FineractServicingHttpClient#read
   complete                                                  [TX]
→ LoanRescheduled.v1 outbox
```

Lịch cũ còn hiệu lực cho tới khi core approve lịch mới. Một chủ Note từ chối thì Loan không gọi Fineract.

## 12. API Loan tra nhanh

| Nhóm | Endpoint chính | Entry point |
|---|---|---|
| Product | `/api/v1/admin/loan-products/**`, `/api/v1/loan-products/**` | `AdminLoanProductController`, `LoanProductController` |
| Preview/nộp hồ sơ | `/api/v1/loan-products/{id}/repayment-previews`, `/api/v1/loan-applications/**` | `RepaymentPreviewController`, `LoanApplicationController` |
| Admin duyệt/chấm điểm | `/api/v1/admin/loan-applications/**` | `AdminLoanDecisionController`, `AdminCreditScoringController` |
| Hợp đồng borrower | `/api/v1/loan-contracts/**` | `LoanContractController` |
| Hợp đồng investor | `/api/v1/investor/loan-contracts/**` | `InvestorLoanContractController` |
| Khoản vay/lịch | `/api/v1/loans/me`, `/api/v1/loans/{loanNumber}/repayment-schedule` | `LoanServicingController` |
| Cơ cấu | `/api/v1/loans/**/reschedule-requests`, `/api/v1/admin/loan-reschedule-requests/**` | `LoanRescheduleController`, `AdminLoanRescheduleController` |
| Thu hồi | `/api/v1/admin/collection-cases/**` | `AdminCollectionController` |
| Sự cố | `/api/v1/admin/disbursement-sagas/**`, `/loan-servicing-reconciliation/**`, `/repayment-event-quarantine/**` | ba controller admin tương ứng |

---

# Phần B — Payment Service

## 13. Vai trò nghiệp vụ và nguyên tắc tiền

Payment là nơi duy nhất được đổi ví và sổ cái FINORA. Service khác có thể yêu cầu giữ/chuyển/thu tiền nhưng không được sửa balance.

Mỗi biến động dùng **double-entry ledger**: tổng debit phải bằng tổng credit. Nói đơn giản, tiền rời một ngăn phải đi vào một ngăn khác; hệ thống không chỉ cộng/trừ một con số balance. `LedgerPostingService#post` khóa các ví theo thứ tự ổn định, kiểm cân bằng, ghi `payment_ledger_transactions` + `payment_ledger_entries` và cập nhật balance trong cùng transaction.

| Bucket | Nghĩa nghiệp vụ |
|---|---|
| `AVAILABLE` | tiền người dùng được phép sử dụng/rút |
| `HELD` | tiền nhà đầu tư đã dành cho lệnh nhưng chưa giải ngân |
| `CLEARING` | tiền đang đi qua một bước trung gian có đối ứng rõ ràng |
| tài khoản hệ thống theo reference | đối ứng của nạp, giải ngân, repayment, phí hoặc payout |

## 14. Dữ liệu Payment lưu

| Bảng | Nghĩa dễ hiểu | Nguồn/quan hệ | Chốt an toàn |
|---|---|---|---|
| `payment_wallets` | Ví của borrower/investor/platform | một owner + currency → một ví | row lock/version, không âm |
| `payment_ledger_transactions` | Một giao dịch kế toán bất biến | reference/idempotency → một transaction | request hash; replay trả kết quả cũ |
| `payment_ledger_entries` | Các vế debit/credit của giao dịch | transaction 1 → 2..N entry | tổng debit = tổng credit |
| `payment_top_ups` | Lệnh nạp tiền qua mock/ZaloPay sandbox | owner 1 → 0..N top-up | provider order + callback idempotent |
| `payment_holds` | Tiền giữ cho một order | order reference → hold | `HELD → CAPTURE_PENDING/CAPTURED` hoặc `RELEASED` |
| `payment_disbursements` | Lệnh chuyển tiền của một Saga | `sagaId` unique | provider call count, lease, reconcile |
| `payment_loan_accounts` | Projection tối thiểu để Payment thu nợ | LoanDisbursed event | borrower, Fineract loan ID, policy version |
| `payment_note_ownership` | Chủ Note hiện hành dùng chia tiền | NoteOwnershipChanged event | version/event dedup; không phải SoR ownership |
| `payment_repayments` | Một lệnh thu tiền từ ví tới core rồi phân phối | account 1 → 0..N repayment | state bền vững và external reference unique |
| `payment_early_settlement_quotes` | Báo giá tất toán có thời hạn | Fineract quote + policy snapshot | quote stale/expired bị từ chối |
| `payment_partial_prepayment_quotes` | Báo giá trả trước một phần gốc | Fineract snapshot + policy snapshot | amount/fee/schedule version bất biến |
| `payment_withdrawals` | Lệnh rút ra nhà cung cấp | owner 1 → 0..N lệnh | account number mã hóa; HMAC callback |
| `payment_outbox_events`, `payment_processed_events` | Event đi và event đã nhận | aggregate/event ID | outbox + consumer dedup |

```text
PaymentWallet 1 ───── 0..N LedgerEntry N ───── 1 LedgerTransaction
      │
      ├──── 0..N TopUp / Hold / Withdrawal
      └──── borrower wallet tham gia 0..N Repayment

PaymentLoanAccount 1 ───── 0..N Repayment
      ├──── 0..N EarlySettlementQuote
      └──── 0..N PartialPrepaymentQuote
```

## 15. Nạp tiền

### 15.1 Luồng code

```text
POST /api/v1/wallets/me/top-ups
→ WalletController#createTopUp
→ TopUpService#create
   → TopUpService#prepare                                  [TX1]
      validate amount + Idempotency-Key, ghi payment_top_ups
   → TopUpProvider#create                                  [ngoài TX]
      MockTopUpProvider hoặc ZaloPayTopUpProvider
   → khóa top-up, lưu checkoutUrl/QR/provider state         [TX2]

Mock: POST /me/top-ups/{id}/mock-complete
→ TopUpService#completeMock

ZaloPay: POST /api/v1/wallets/callbacks/zalopay
→ WalletController#zaloPayCallback
→ TopUpService#processZaloPayCallback
→ TopUpService#credit                                      [TX]
→ LedgerPostingService#post                                [REQUIRES transaction]
```

Callback lặp chỉ cộng ví một lần. Endpoint mock chỉ hợp lệ ở môi trường cho phép giả lập. ZaloPay sandbox là luồng nạp thử; **giải ngân và payout production chưa dùng ZaloPay**.

## 16. Giữ, nhả và chuyển tiền

```text
POST /api/v1/transactions/holds
→ TransactionController#hold
→ HoldTransferService#hold                                 [TX]
   khóa ví investor, ledger AVAILABLE → HELD, ghi payment_holds

POST /holds/{holdReference}/release
→ HoldTransferService#release                              [TX]
   chỉ phần còn HELD, ledger HELD → AVAILABLE

POST /holds/{holdReference}/settlements
→ HoldTransferService#settleFromHold                       [TX]
   chuyển từ khoản giữ sang người bán/platform cho chợ Note

POST /transactions/transfers
→ HoldTransferService#transfer                             [TX]
   chuyển nội bộ với reference idempotent
```

Investment gọi on-behalf bằng service credential có authority phù hợp. `orderReference`, `holdReference` và request hash ngăn một lệnh giữ/nhả/chuyển khác nội dung tái sử dụng cùng khóa.

## 17. Payment trong Saga giải ngân

```text
Kafka finora.loan.disbursement-requested
→ DisbursementRequestedConsumer
→ PaymentDisbursementService#request                       [TX, dedup]
   ghi payment_disbursements REQUESTED
→ PaymentDisbursementWorker
→ PaymentDisbursementService#claim                         [TX/lease]
→ PaymentDisbursementService#execute                       [ngoài TX gọi PaymentProvider]
→ PaymentDisbursementService#finish                        [TX]
   capture đúng từng hold + ledger
   clearing → borrower AVAILABLE
   status COMPLETED + DisbursementCompleted.v1 outbox
```

Nếu worker chết sau provider success nhưng trước khi lưu, lease hết sẽ đưa lệnh sang `RECONCILIATION_REQUIRED`; `PaymentDisbursementService#reconcile`/`applyReconciliation` chỉ gọi `PaymentProvider#lookup(sagaId)`, không chuyển lại. `AdminDisbursementReconciliationController#recheck` cho vận hành mở thêm lượt lookup.

Provider giải ngân hiện hành mặc định là `mock`; cấu hình khác fail-closed nếu chưa có adapter/quyền hợp lệ. Tiền demo vẫn được phản ánh bằng ledger thật trong FINORA, nhưng không phải chuyển khoản ngân hàng thật.

## 18. Thu nợ, trả trước và phân phối

### 18.1 Quy tắc nghiệp vụ dễ hiểu

| Cơ chế | Người vay trả gì | Sau đó xảy ra gì |
|---|---|---|
| Trả đúng kỳ | đúng nghĩa vụ Fineract của kỳ | Fineract phân bổ, Payment chia cho chủ Note/FINORA |
| Trả kỳ trước ngày đến hạn | Payment giữ tiền ngay; ghi core đúng ngày đến hạn | có thể hủy trước `post_on`; lãi kỳ không bị thay đổi vì trả sớm vài ngày |
| Khắc phục quá hạn | toàn bộ quá hạn hoặc kỳ cũ nhất theo lựa chọn hợp lệ | cập nhật DPD/collection sau khi Fineract ghi nhận |
| Trả trước một phần gốc | nghĩa vụ hiện tại + phần gốc trả thêm + phí công bố | V4 giảm dư nợ ngay, không thu lãi tương lai trên gốc đã trả |
| Tất toán sớm | gốc còn lại + lãi phát sinh tới ngày giao dịch + phí công bố | Fineract đóng nghĩa vụ khi total outstanding về 0 |

Phí trả trước/tất toán lấy từ version đã in trong hợp đồng, không lấy từ env. Code hiện hành `AGRIBANK_REFERENCE_FINORA_2026_V2` áp dụng:

- khoản ≤ 12 tháng: 1% phần gốc trả trước trong 50% đầu thời hạn; 0,5% từ sau 50% đến 70%; sau 70% là 0%;
- khoản > 12 tháng: 2% trong năm đầu, 1,5% sau năm đầu;
- phí khác 0 có mức tối thiểu 100.000 đồng;
- 80% tổng phí cho chủ Note, 20% cho FINORA.

Đây là **policy hợp đồng của bản demo**, không phải tuyên bố pháp luật bắt buộc mọi P2P dùng đúng tỷ lệ này. Hợp đồng `LOAN_TERMS_V1` không công bố phí thì dùng `NO_FEE_DISCLOSED` = 0. Công thức, ví dụ thuần nghiệp vụ và kiểm soát `LEGAL-SERVICING-01` nằm trong [LOAN-SERVICING-POLICY.md](../LOAN-SERVICING-POLICY.md).

### 18.2 Chuỗi code trả kỳ

```text
GET /api/v1/repayments/scheduled-due
→ RepaymentController#scheduledDue
→ PaymentRepaymentService#scheduledDue
→ FineractRepaymentGateway#scheduledRepaymentQuote
   GET /loans/{id}/transactions/template?command=repayment
   GET /loans/{id}?associations=repaymentSchedule

POST /api/v1/repayments
→ RepaymentController#create
→ PaymentRepaymentService#create
→ #prepareCollection / #collect                         [TX qua TransactionTemplate]
   khóa payment_loan_accounts, chống một khoản có hai lệnh in-flight
   LedgerPostingService#post borrower AVAILABLE → CLEARING
   ghi payment_repayments COLLECTED
→ PaymentRepaymentWorker
→ PaymentRepaymentService#claim                           [TX]
→ #postToCore                                             [ngoài TX]
→ FineractRepaymentGateway#postRepayment
   POST /loans/{id}/transactions?command=repayment
   GET transaction + schedule để lấy breakdown
→ #saveCoreResult                                         [TX]
→ #distribute                                             [TX]
   RepaymentAllocator chia theo owner projection/policy
   LedgerPostingService#post CLEARING → investor/platform
   COMPLETED + RepaymentDistributed.v1 outbox
```

### 18.3 Báo giá/tất toán/trả trước một phần

```text
Early settlement quote
RepaymentController#createEarlySettlementQuote
→ EarlySettlementQuoteService#create
→ FineractRepaymentGateway#earlySettlementQuote
   GET transactions/template?command=prepayLoan
→ EarlySettlementFeeCalculator#calculate
→ lưu payment_early_settlement_quotes

Partial prepayment quote
RepaymentController#createPartialPrepaymentQuote
→ PartialPrepaymentQuoteService#create
→ FineractRepaymentGateway#partialPrepaymentSnapshot
→ EarlySettlementFeeCalculator#calculate trên prepaidPrincipal
→ lưu payment_partial_prepayment_quotes

Execute
→ PaymentRepaymentService#createEarlySettlement/#createPartialPrepayment
→ cùng pipeline collect → core → distribute ở trên
```

Khoản V1–V3 trả `409 PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN`. Quote hết hạn, core state đã đổi hoặc có lệnh in-flight trả `*_QUOTE_STALE`/conflict, không thu theo báo giá cũ.

### 18.4 Không rõ kết quả khi ghi Fineract

- 4xx chắc chắn: `PaymentRepaymentService#recordCoreFailure` hoàn tiền và `FAILED` nếu tra cứu lại không thấy giao dịch.
- timeout/5xx/mất phản hồi: `RECONCILIATION_REQUIRED`, **không hoàn tiền ngay** vì Fineract có thể đã nhận.
- worker gọi `#reconcileCorePosting` → `FineractRepaymentGateway#findPostedRepayment` theo external ID `FINORA-REPAY-<repaymentId>`.
- admin chỉ `repost` sau khoảng an toàn; `#repostCorePosting` tra cứu trước rồi mới POST lại cùng external ID.

## 19. Rút tiền

```text
POST /api/v1/wallets/me/withdrawals
→ WithdrawalController#create
→ WithdrawalService#request
   TX: khóa ví, kiểm min/daily limit/available, mã hóa số TK,
       ledger AVAILABLE → PAYOUT clearing, ghi REQUESTED
→ WithdrawalWorker
→ WithdrawalProcessingService#execute
   claim TX ngắn → PayoutProvider#submit ngoài TX → apply kết quả TX
→ callback HMAC / lookup khi mất callback
→ COMPLETED hoặc refund + FAILED
→ WithdrawalCompleted/WithdrawalFailed outbox
```

Payout hiện chỉ có mock. Timeout hoặc worker chết không được gửi lệnh chi mới; chuyển `RECONCILIATION_REQUIRED`, lookup theo `withdrawalId`, hết lượt thì admin dùng `/api/v1/admin/withdrawals/{id}/recheck`.

## 20. API Payment tra nhanh

| Nhóm | Endpoint | Entry point |
|---|---|---|
| Ví/lịch sử/nạp | `/api/v1/wallets/me`, `/me/transactions`, `/me/top-ups/**` | `WalletController` |
| Hold/transfer | `/api/v1/transactions/**` | `TransactionController` → `HoldTransferService` |
| Trả nợ/quote | `/api/v1/repayments/**` | `RepaymentController` |
| Rút tiền | `/api/v1/wallets/me/withdrawals/**` | `WithdrawalController` |
| Đối soát repayment | `/api/v1/admin/repayment-reconciliation/**` | `AdminRepaymentReconciliationController` |
| Đối soát giải ngân | `/api/v1/admin/disbursement-reconciliation/**` | `AdminDisbursementReconciliationController` |
| Đối soát rút | `/api/v1/admin/withdrawals/**` | `AdminWithdrawalController` |

---

# Phần C — Apache Fineract 1.15

## 21. Fineract làm gì và không làm gì

Fineract là **core lending**, không phải toàn bộ FINORA:

- làm: Product core, Client, loan account, lịch chính thức, dư nợ, arrears, phân bổ repayment và reschedule;
- không làm: duyệt hồ sơ FINORA, gọi vốn, hợp đồng pháp lý, ví, chủ Note, AI hay blockchain.

Loan và Payment chỉ gọi REST. Không service nào được sửa bảng `m_*` để “chữa nhanh” sai lệch.

## 22. Hai database và quyền sở hữu schema

| Database | Vai trò | Ai quản lý schema |
|---|---|---|
| `fineract_tenants` | registry tenant và connection tới tenant DB | Liquibase/Fineract |
| `fineract_default` | dữ liệu tenant `default`: Product, Client, Loan, schedule, transaction | Liquibase/Fineract |

Flyway của FINORA **không chạy** lên hai DB này. Việc chuyển từ PostgreSQL Docker sang Neon chỉ đổi datasource của Fineract; không đổi contract REST và không cho FINORA quyền đọc DB trực tiếp.

## 23. Ánh xạ API, đối tượng nghiệp vụ và bảng vật lý

> Bảng vật lý dưới đây dành cho DBA/đối soát đọc-only. Code FINORA không phụ thuộc tên bảng và không join chéo.

| Nghiệp vụ | REST Fineract FINORA gọi | Bảng Fineract điển hình | Mapping FINORA |
|---|---|---|---|
| Product core | `GET/POST /loanproducts` | `m_product_loan` và các bảng cấu hình liên quan | `fineract_product_mappings.fineract_product_id` |
| Client borrower | `GET/POST /clients` | `m_client` | external ID `FINORA-BORROWER-<borrowerId>` |
| Loan account | `GET/POST /loans` | `m_loan` | external ID = `contractNumber`; `finora_loans.fineract_loan_id` |
| Lịch trả | `GET /loans/{id}?associations=repaymentSchedule` | `m_loan_repayment_schedule` | snapshot/projection ở Loan; không copy làm SoR |
| Giải ngân/trả nợ | `POST /loans/{id}?command=disburse`; `POST /loans/{id}/transactions?command=repayment` | `m_loan_transaction` | Payment lưu `fineract_transaction_id` + breakdown |
| Phí/phạt core | đọc trong transaction/schedule/template | `m_loan_charge` và liên kết transaction | Payment dùng breakdown REST |
| Cơ cấu | `/rescheduleloans` + command approve | `m_loan_reschedule_request` và lịch liên quan | `loan_reschedule_requests.fineract_reschedule_id` |

Tên bảng có thể thay đổi theo migration Fineract; API mới là contract runtime. Không đánh dấu luồng thành công chỉ vì nhìn thấy một hàng DB: phải kiểm trạng thái REST, FINORA aggregate, ledger và event.

## 24. Các adapter Fineract hiện hành

| Adapter | Nhóm chức năng | Điểm an toàn |
|---|---|---|
| `FineractProductHttpClient` | tìm/tạo Product | external ID + contract validation |
| `FineractScheduleHttpClient` | preview/lịch final | preview client riêng, không tạo loan account |
| `FineractCoreLoanBookingGateway` | Client + Loan + approve + disburse | borrower/contract external ID, reconcile trước create |
| `FineractServicingHttpClient` | schedule/balance/DPD/history | đọc snapshot, Loan fail-closed khi identity lệch |
| `FineractRescheduleHttpClient` | create/read/approve reschedule | marker request UUID để reconcile |
| `FineractRepaymentGateway` | due/prepay quote/post/find repayment | external ID `FINORA-REPAY-*`, phân loại outcome unknown |
| `FineractRequestExecutor` | auth/timeout/circuit breaker Loan | circuit riêng theo nhóm call; 4xx nghiệp vụ không mở circuit |

### 24.1 Chiến lược Product V4

`FineractAllocationPolicy` là nơi ánh xạ version bất biến:

| Version | Strategy | Future allocation | Interest recalculation | Tự trả trước trên app |
|---|---|---|---|---|
| V1 | `mifos-standard-strategy` | core mặc định | tắt, theo kỳ | legacy: chặn |
| V2 | `advanced-payment-allocation-strategy` | `REAMORTIZATION` | tắt, theo kỳ | legacy: chặn |
| V3 | advanced | `NEXT_INSTALLMENT` | tắt, theo kỳ | legacy: chặn |
| V4 | advanced + `PROGRESSIVE` | `NEXT_INSTALLMENT` | bật, theo ngày/dư nợ thực | hỗ trợ |

V4 là cấu hình dùng cho Product mới. `FineractProductPayloadMapper` và `FineractSchedulePayloadMapper` phải gửi cùng strategy/interest mode; `FineractCoreLoanBookingGateway` cũng gửi cùng mode khi tạo loan account.

## 25. Cách kiểm tra Fineract đúng luồng

1. Product FINORA chỉ `ACTIVE` sau mapping `SYNCED` và `GET /loanproducts/{id}` khớp config.
2. Sau giải ngân: `GET /loans?externalId=<contractNumber>` chỉ có một loan; trạng thái active và principal đúng.
3. `GET /loans/{id}?associations=repaymentSchedule` là lịch UI/Loan phải phản ánh.
4. Sau trả nợ: tìm transaction theo external ID `FINORA-REPAY-<repaymentId>`, rồi đối chiếu breakdown với Payment.
5. Không update bảng `m_*`; sai khác tạo incident và đi đường reconcile/repost/requeue có audit.

---

# Phần D — Blockchain Service và Hyperledger Fabric

## 26. Hai lớp khác nhau

| Lớp | Giữ gì | Tác dụng |
|---|---|---|
| Blockchain Service/PostgreSQL | proof command, hash, trạng thái retry, provider, txId, block reference, lần verify | hàng đợi bền vững và màn vận hành |
| Hyperledger Fabric | proof ID, loại mốc, aggregate reference, SHA-256, payload version, source, txId | sổ cái write-once để bên kiểm toán đối chiếu |

PDF, CCCD, payload tài chính đầy đủ và hồ sơ vay **không nằm trên Fabric**. File PDF vẫn ở Loan. Fabric transaction ID là biên nhận do mạng Fabric trả về, không phải Fineract reference.

## 27. Bốn mốc đang được neo

| Event nguồn | Proof type | Aggregate dễ hiểu |
|---|---|---|
| `LoanContractActivated.v1` | `CONTRACT_DOCUMENT` | hợp đồng đã đủ chữ ký |
| `LoanDisbursed.v1` | `DISBURSEMENT` | khoản vay đã giải ngân và core booking xong |
| `RepaymentDistributed.v1` | `REPAYMENT` | một giao dịch trả nợ đã thu, ghi core và phân phối |
| `NoteOwnershipChanged.v1` | `NOTE_OWNERSHIP` | phát hành/chuyển chủ Note |

Servicing proof khác như từng quyết định cơ cấu/default chưa được tự động neo nếu không nằm trong bốn event trên; không được mô tả là đã có.

## 28. Chuỗi code từ Kafka tới Fabric

```text
Kafka event
→ MilestoneProofConsumer#contractActivated/#loanDisbursed/
                         #repaymentDistributed/#noteOwnershipChanged
→ MilestoneProofRegistrar#register
   parse envelope, lấy eventId/occurredAt/data
   canonicalize data JSON v2, tính SHA-256
   tạo proofId UUID v5 theo proofType + aggregateType + aggregateId
→ ProofSubmissionService#register                         [TX]
   dedup sourceService+eventId và milestone unique
   ghi blockchain_proof_submissions PENDING
→ ProofSubmissionWorker
→ ProofSubmissionStateService#claim                      [TX/lease]
→ ProofSubmissionProcessor#process                       [ngoài TX gọi provider]
→ MockProofLedger hoặc FabricProofLedger#submit
   Fabric: GetProof trước → RegisterProof nếu chưa có
→ GatewayFabricLedgerClient#submit
   endorse → submit orderer → chờ commit/block
→ ProofSubmissionStateService#markConfirmed/#markFailed  [TX]
```

### 28.1 Vì sao phải đọc trước khi ghi

Nếu Fabric đã commit nhưng JVM chết trước khi lưu receipt, DB vẫn `PROCESSING`. Sau lease, worker khác claim lại; `FabricProofLedger#read` gọi chaincode `GetProof`. Nếu hash/metadata khớp, service dùng lại transaction gốc. Nếu cùng proof ID nhưng khác nội dung, trạng thái đi lỗi vĩnh viễn để điều tra, không ghi đè.

## 29. Chaincode và mạng Fabric

Chaincode Go `finora-ledger` chỉ có ba hàm:

- `RegisterProof`: ghi lần đầu; cùng ID/cùng nội dung trả bản ghi gốc; khác nội dung bị từ chối;
- `GetProof`: đọc theo proof ID;
- `GetProofsByAggregate`: đọc các proof của một aggregate.

Không có hàm sửa/xóa. Mạng local dùng channel `finora-channel`, chaincode `finora-ledger`, hai tổ chức `Org1MSP` và `Org2MSP`, endorsement `MAJORITY`, một orderer. Đây là mạng demo local, chưa phải hạ tầng production có Fabric CA/HSM/nhiều orderer/tổ chức kiểm toán độc lập.

Provider mặc định là `MOCK`. Chỉ khi chạy profile Fabric và `BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC`, receipt mới là transaction/block thật trên Fabric. UI phải phân biệt rõ “MOCK, không có trên Fabric” với “Trên Fabric”.

## 30. Kiểm tra toàn vẹn và đối soát

```text
POST /api/v1/blockchain/proofs/{proofId}/verify
→ ProofController#verify
→ ProofVerificationService#verify
   nếu admin cung cấp object data gốc: canonicalize + hash và so DB
   nếu provider Fabric: FabricProofLedger#read và so DB ↔ chain

ProofChainReconciliationWorker
→ ProofVerificationService#reconcile
→ ProofSubmissionStateService#recordChainCheck
```

Object gốc để verify phải lấy từ **event/source service tương ứng**. Blockchain không thể tự dựng lại payload vì nó cố ý không lưu payload. Bỏ trống vẫn kiểm DB ↔ Fabric; nhập object cho phép kiểm thêm source data ↔ DB hash.

| Trạng thái | Ý nghĩa vận hành |
|---|---|
| `PENDING` | chờ gửi/retry |
| `PROCESSING` | một worker đang có lease |
| `CONFIRMED` | provider đã trả receipt hợp lệ |
| `DEAD_LETTER` | hết retry hoặc conflict không thể tự chữa; admin phải điều tra |

API admin: `GET /api/v1/blockchain/proofs`, `GET /{proofId}`, `POST /{proofId}/verify`, `POST /{proofId}/retry` qua `ProofController` → `ProofQueryService`/`ProofVerificationService`/`ProofSubmissionService#requeue`.

## 31. Bảng Blockchain

`blockchain_proof_submissions` chứa:

- identity: `proof_id`, `proof_type`, aggregate type/ID, loan application ID nếu có;
- source: service, event ID/type/version, occurred-at;
- evidence: payload hash, hash scheme, payload version;
- delivery: provider, status, attempt, next attempt, claim token/lease;
- receipt: provider transaction ID, block reference, confirmed-at;
- verification: chain check status/time và lỗi giới hạn.

Unique theo source event và theo milestone bảo vệ cả event giao trùng lẫn producer phát lại cùng mốc bằng event ID khác.

---

# Phần E — Gateway và hợp đồng xuyên service

## 32. Gateway hiện làm gì

Gateway không có database và không chứa business logic. Code hiện hành chịu trách nhiệm:

- định tuyến path tới service;
- CORS theo cấu hình;
- timeout/connect pool tới upstream qua `UpstreamHttpClientConfig`;
- health liveness/readiness riêng của Gateway.

JWT/resource authorization vẫn được Loan, Payment và Blockchain kiểm lại. Không được xem request là đáng tin chỉ vì đã đi qua Gateway.

| Route | Đích local | Ghi chú thứ tự |
|---|---:|---|
| `/api/v1/loans/**`, `/loan-products/**`, `/loan-applications/**`, `/loan-contracts/**`, `/investor/loan-*` | Loan 8081 | public/resource routes |
| `/api/v1/wallets/**`, `/transactions/**`, `/repayments/**` và ba nhóm admin Payment | Payment 8082 | phải đứng trước catch-all admin Loan |
| `/api/v1/blockchain/**` | Blockchain 8083 | service tự yêu cầu ADMIN cho proof APIs |
| `/api/v1/admin/users/**` | User 8085 | đứng trước catch-all admin Loan |
| `/api/v1/admin/**` còn lại | Loan 8081 | catch-all admin hiện hành |

Profile Docker chỉ đổi URI thành `http://loan:8081`, `http://payment:8082`, `http://blockchain:8083`; contract path không đổi.

## 33. Event catalog liên quan phần Thái

| Topic/event | Producer | Consumer liên quan | Side effect và dedup |
|---|---|---|---|
| `finora.loan.funding-requested` | Loan | Investment | tạo listing; Loan outbox theo Application/version |
| `finora.investment.loan-fully-funded` | Investment | Loan | `LoanFullyFundedHandler`; `loan_processed_events`; tạo Contract |
| `finora.investment.loan-funding-expired` | Investment | Loan | funding `EXPIRED`, không Contract |
| `finora.loan.funding-cancelled` | Loan | Investment | hủy commitment/nhả hold khi chưa capture |
| `finora.loan.investor-signature-requested` | Loan | Investment/Notification | gắn PDF chung và mời từng lender ký |
| `finora.loan.borrower-signature-requested` | Loan | Notification | chỉ sau khi đủ lender |
| `finora.loan.contract-activated` | Loan | Investment/Blockchain/Notification | Contract effective; blockchain proof hợp đồng |
| `finora.loan.disbursement-requested` | Loan | Payment (`payment-group`) | Payment command unique `sagaId` |
| `finora.payment.disbursement-completed/failed` | Payment | Loan | `loan_processed_events`; Saga tự chuyển state |
| `finora.loan.disbursed` | Loan | Investment/Payment/Blockchain/Notification | phát hành Note, mở servicing account, proof giải ngân |
| `finora.investment.note-ownership-changed` | Investment | Payment/Blockchain | cập nhật owner projection; proof phát hành/chuyển Note |
| `finora.payment.repayment-collected/cancelled` | Payment | Notification khi bật | báo khoản trả kỳ đang giữ/hủy; không phải phân phối |
| `finora.payment.repayment-distributed` | Payment | Loan/Investment/Blockchain/Notification | projection servicing/portfolio + proof repayment |
| `finora.loan.delinquency-changed` | Loan | Investment; CIC chỉ khi deployment hỗ trợ | DPD/nhóm nợ projection; không mang CCCD |
| `finora.loan.reschedule-consent-requested` | Loan | Investment/Notification | mở biểu quyết chủ Note |
| `finora.investment.reschedule-consent-completed` | Investment | Loan | chỉ unanimous approval mới chạy core |
| `finora.loan.rescheduled`, `finora.loan.settled` | Loan | Investment/Notification | cập nhật lifecycle projection |
| `finora.payment.withdrawal-completed/failed` | Payment | Notification | thông báo payout, không đổi Loan |

Envelope chung có `eventId`, `occurredAt`, `version`, `data`; key Kafka theo aggregate để giữ thứ tự khi cần. Event chỉ mô tả việc đã xảy ra, không cho consumer quyền sửa aggregate ngoài ownership.

### 33.1 Outbox Loan

```text
Business service ghi state + OutboxService#recordForPublication   [cùng TX]
→ OutboxRelayWorker#publishDueEvents
→ OutboxRelayStateService#dueIds
→ OutboxRelayService#publish
   → OutboxRelayStateService#claim                                 [TX/lease]
   → KafkaOutboxPublisher#publish + broker ack                     [ngoài TX]
   → OutboxRelayStateService#markPublished/#markFailed             [TX]
```

### 33.2 Outbox Payment

Business service ghi state/ledger + `PaymentOutboxEvent.pending` cùng transaction. `PaymentOutboxPublisher#publish` lấy batch đến hạn, publish Kafka và cập nhật attempt/status. Consumer Payment dùng `payment_processed_events`; record contract/hạ tầng lỗi vào DLT cấu hình riêng của Payment.

## 34. Ma trận transaction, đồng thời, idempotency và lỗi

| Luồng | Cùng commit/rollback | External call | Chống trùng/đồng thời | Không rõ kết quả |
|---|---|---|---|---|
| Product sync | Product + mapping + command; sau đó success/fail riêng | Fineract ngoài TX | Product version, command unique, row lock/lease | tìm Product theo external ID |
| Nộp hồ sơ | Application + snapshots + history | User/Fineract ngoài TX | borrower + Idempotency-Key + request hash; tái kiểm Product khi persist | chưa persist thì client gửi lại cùng key |
| Chấm điểm | assessment state từng bước | AI/Fineract ngoài TX | assessment/attempt/lease | retry bounded; không tạo score mặc định |
| Full funding → Contract | Application funding + Contract/party/docs + outbox | không gọi mạng trong TX | processed event + allocation hash/version | Kafka retry/DLT |
| Ký | party/signature/doc revision/history + outbox | SmartCA ngoài TX giữa các state bền vững | signer/sequence/in-flight unique; PDF hash | poll/reconcile provider theo transaction ID |
| Giải ngân | Payment ledger + command/outbox; Loan Saga + outbox cục bộ | provider/Fineract ngoài TX | `sagaId`, hold refs, external contract ID, lease token | lookup provider/Fineract; không transfer lại |
| Repayment | collect ledger + command; core result; distribution ledger + outbox | Fineract ngoài TX | Idempotency-Key, one in-flight/loan, `FINORA-REPAY-*` | giữ clearing, reconcile; không hoàn mù |
| Rút tiền | reserve ledger + withdrawal; result/refund + outbox | payout ngoài TX | owner+key+request hash, lease | lookup, manual recheck; không submit lại mù |
| Proof | proof command; receipt/failure riêng | Fabric ngoài TX | source event unique + milestone unique + chaincode write-once | GetProof trước RegisterProof |

### 34.1 Ba ví dụ race condition

1. **Hai request nộp hồ sơ cùng key:** cả hai có thể qua lần đọc đầu, unique constraint chỉ cho một persist; request thua đọc lại record và trả cùng kết quả.
2. **Hai worker giải ngân:** row lock + processing lease chỉ cho một claim; claim token cũ không được ghi đè kết quả của lượt mới.
3. **Hai lần ghi cùng proof:** DB unique chặn trước; nếu vẫn đua trên Fabric, chaincode trả bản ghi gốc khi nội dung khớp và từ chối khi hash khác.

## 35. Security, PII và kiểm soát pháp lý

| Phạm vi | Kiểm soát |
|---|---|
| JWT/RBAC | resource service kiểm role và ownership; service-to-service dùng client credential/authority riêng |
| PII | event không mang CCCD; proof không giữ payload; log không chứa token/PIN/private key/ảnh giấy tờ |
| PIN tài chính | token PIN gắn loại hành động; không dùng token rút tiền cho trả nợ |
| Callback | ZaloPay/payout mock kiểm MAC/HMAC; callback không tin body chưa xác thực |
| Bank account | Payment mã hóa số tài khoản, UI/API chỉ hiển thị che |
| Contract | PDF hash phải khớp bản người ký đã xem; từng party có evidence riêng |
| Money | `BigDecimal`, VND, ledger cân bằng, idempotency và audit |

Các control cần dẫn chiếu khi thay đổi chức năng:

- Product/rate/term: `LEGAL-RATE-01`, `LEGAL-TERM-01`, `LEGAL-LIMIT-01`;
- disclosure/AI/data: `LEGAL-DISCLOSURE-01/02`, `LEGAL-AI-01`, `LEGAL-DATA-01`;
- hợp đồng/chữ ký/proof: `LEGAL-CONTRACT-01`;
- tiền, repayment, phí và cơ cấu: `LEGAL-PAYMENT-01`, `LEGAL-SERVICING-01`.

Mọi mục `LEGAL-OPEN-*` vẫn cần người có thẩm quyền xác nhận. Không biến mức phí demo, DPD 91 hoặc Fabric local thành kết luận pháp luật production.

## 36. Ví dụ xuyên suốt dễ hiểu

Giả sử hồ sơ vay 10.000.000 đồng được hai nhà đầu tư cấp vốn, mỗi người 5.000.000 đồng:

1. Loan giữ hồ sơ, lịch preview/final và quyết định; Fineract chưa có loan account thật.
2. Payment giữ 5.000.000 đồng trong ví mỗi investor; tiền chưa sang borrower.
3. Investment báo allocation; Loan tạo một hợp đồng chung gồm borrower + hai lender.
4. Hai lender ký trước, borrower ký cuối. Loan chuyển Contract `EFFECTIVE` và mở một Saga.
5. Payment capture hai hold, ghi ledger tổng 10.000.000 đồng và cộng 10.000.000 đồng vào ví borrower; chỉ một `sagaId` được xử lý.
6. Loan dùng `contractNumber` tạo một loan account Fineract 10.000.000 đồng, approve và disburse; nhận lịch chính thức.
7. Loan thành `ACTIVE`; Investment tạo hai Note; Blockchain ghi hash hợp đồng, giải ngân và Note.
8. Borrower trả một kỳ: Payment trừ ví, Fineract tách gốc/lãi/phí/phạt, Payment chia theo chủ Note hiện tại và phát event; Loan cập nhật lịch/DPD, Blockchain ghi hash repayment.

Nhìn DB riêng lẻ sẽ không đủ để kết luận bước đã xong. Ví dụ `payment_disbursements=COMPLETED` chứng minh tiền FINORA đã chuyển, nhưng phải có Saga Loan `COMPLETED`, Fineract loan active và `LoanDisbursed` mới kết luận toàn bộ giải ngân hoàn tất.

## 37. Kiểm tra bằng UI, API và database

### 37.1 Product

- UI: Product hiển thị “đã đồng bộ”, sau đó admin mới kích hoạt.
- API: Loan Product có `coreSyncStatus=SYNCED`; Fineract `GET /loanproducts/{id}` khớp V4.
- DB Loan: `loan_products`, `fineract_product_mappings`, `fineract_commands` cùng đúng version.

### 37.2 Hợp đồng và giải ngân

- UI: từng lender đã ký, borrower đã ký, Contract effective; borrower có tiền; khoản vay active.
- API: Loan Contract signed document/receipt; Payment admin query Saga; Loan servicing detail.
- DB: Loan `loan_contracts/parties/signatures/disbursement_sagas/finora_loans`; Payment `payment_disbursements` + balanced ledger; Fineract đọc qua REST; không sửa DB core.
- Event: một `LoanDisbursed.v1`; Investment/Blockchain lỗi không được tạo chuyển tiền lần hai.

### 37.3 Repayment

- UI: due lấy từ `/scheduled-due`, không tự cộng ở frontend; sau hoàn tất lịch/dư nợ cập nhật.
- API: Payment repayment `COMPLETED`, core transaction ID có giá trị; Loan servicing projection không stale.
- DB: `payment_repayments` breakdown bằng tổng amount; ledger distribution cân bằng; outbox có `RepaymentDistributed`.
- Fineract: transaction external ID duy nhất và schedule/outstanding giảm đúng.

### 37.4 Blockchain

- UI: badge phải ghi rõ MOCK hay Fabric; search bằng application/contract/repayment/Note reference.
- API: proof `CONFIRMED`; verify trả source-hash/chain check tương ứng.
- DB Blockchain: hash/receipt/provider; Fabric: `GetProof(proofId)` cùng hash/metadata khi provider thật.

Các câu SQL chỉ dùng đọc database của đúng service. Không join Loan–Payment–Fineract bằng một query và không update để làm kết quả “đẹp”.

## 38. Kiểm thử và bằng chứng code

### 38.1 Bộ test quan trọng

| Phạm vi | Test tiêu biểu |
|---|---|
| Loan Product/Fineract | `CoreProductSyncServiceImplTest`, `CoreProductSyncStateServiceTest`, `FineractPayloadMapperTest`, `FineractCoreLoanBookingGatewayTest` |
| Application/scoring | `FinoraLoanApplicationIT`, `BorrowerEligibilityServiceTest`, `AiCreditScoringHttpClientTest` |
| Contract/PAdES | `LoanContractTest`, `PadesSignatureChainTest`, `ContractPdfRendererTest`, `VnptSmartCaSignatureProviderTest` |
| Saga/servicing | `DisbursementSagaServiceTest`, `DisbursementSagaRepairTest`, `LoanServicingReconciliationServiceTest`, `CollectionCaseServiceTest` |
| Payment ledger/hold | `FinoraPaymentApplicationIT`, `LedgerPostingCommandTest`, `HoldSettlementTest` |
| Repayment | `PaymentRepaymentTest`, `FineractRepaymentGatewayTest`, `FineractRepaymentConnectionLossTest`, `PaymentKafkaDeadLetterIT` |
| Withdrawal | `WithdrawalFlowIT`, `PaymentWithdrawalTest`, `AccountNumberCipherTest` |
| Blockchain/Fabric | `FinoraBlockchainApplicationIT`, `FabricProofFlowLiveIT`, `FabricOutageDrillLiveIT`, `ProofSubmissionProcessorTest` |
| Gateway | `GatewayHealthProbesTest`, `UpstreamHttpClientConfigTest` |

### 38.2 Lệnh verify phù hợp

```powershell
mvn -pl finora-loan -am verify
mvn -pl finora-payment -am verify
mvn -pl finora-blockchain -am verify
mvn -pl finora-gateway -am verify
```

Test Fabric live cần network/profile Fabric; không được báo pass nếu chỉ chạy test mock. Kiểm thử full business UI dùng [UI-MANUAL-TEST-GUIDE.md](../testing/UI-MANUAL-TEST-GUIDE.md); fault/restart và lệnh phục hồi dùng [RUNBOOKS.md](../ops/RUNBOOKS.md).

## 39. Hạn chế và việc chưa phải production

- disbursement provider và payout vẫn mock; ZaloPay hiện dùng cho top-up sandbox;
- SmartCA UAT/MOCK và chứng thư demo chưa phải chữ ký production có giá trị pháp lý đã thẩm định;
- Fabric local chưa có CA/HSM/multi-orderer/tổ chức kiểm toán độc lập;
- proof tự động hiện chỉ có bốn milestone;
- nhóm nợ/CIC production và phạm vi pháp lý P2P còn legal gate;
- Gateway hiện thiên về route/CORS/timeout; resource authorization nằm ở service;
- Prometheus/dashboard, backup/restore và một số manual-repair/outbox operations còn thuộc backlog vận hành.

## 40. Nguồn code chính để tra cứu

- Loan controllers/services: [`finora-loan/src/main/java/com/finora/loan`](../../finora-loan/src/main/java/com/finora/loan)
- Loan migrations: [`finora-loan/src/main/resources/db/migration`](../../finora-loan/src/main/resources/db/migration)
- Payment controllers/services: [`finora-payment/src/main/java/com/finora/payment`](../../finora-payment/src/main/java/com/finora/payment)
- Payment migrations: [`finora-payment/src/main/resources/db/migration`](../../finora-payment/src/main/resources/db/migration)
- Blockchain/Fabric adapter: [`finora-blockchain/src/main/java/com/finora/blockchain`](../../finora-blockchain/src/main/java/com/finora/blockchain)
- Blockchain migrations: [`finora-blockchain/src/main/resources/db/migration`](../../finora-blockchain/src/main/resources/db/migration)
- Fabric chaincode/network: [`docker/fabric-network`](../../docker/fabric-network)
- Gateway routes: [`finora-gateway/src/main/resources/application.yml`](../../finora-gateway/src/main/resources/application.yml)
- Cross-service rules: [`.agents/rules/08-cross-service-flows.md`](../../.agents/rules/08-cross-service-flows.md)
- Service boundaries: [`.agents/rules/07-service-boundaries.md`](../../.agents/rules/07-service-boundaries.md)




