---
task_id: R2-W2
title: Ngày nghiệp vụ Việt Nam, booking theo ngày giải ngân thực tế và phục hồi saga/Payment
owner: Thai
status: READY_FOR_REVIEW
depends_on: [R1A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# R2-W2 — Ngày nghiệp vụ và phục hồi tài chính (Wave 2, phần R2)

Thái duyệt phạm vi qua quyết định D7 (ngày nghiệp vụ `Asia/Ho_Chi_Minh`) và ủy quyền chọn phương án khuyến nghị
ngày 2026-10-17. Plan xử lý defect B06–B13 trong
[`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 3b.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Ngày nghiệp vụ (B06):** người vay ở Việt Nam ký và nhận tiền ngày 17/10 nhưng Fineract ghi ngày 16/10 nếu
   giao dịch diễn ra từ 00:00 đến 07:00, vì Loan/Payment lấy ngày theo UTC. Kỳ đầu lệch một ngày so với hợp đồng;
   trả đúng ngày app báo có thể bị tính quá hạn.
2. **Ngày booking (B07):** Loan gửi Fineract ngày giải ngân dự kiến lúc nộp hồ sơ. Gọi vốn có thể mất 14 ngày và
   ký thêm 7 ngày, nên ngày booking thường muộn hơn; Fineract từ chối và saga rơi vào `REPAIR_REQUIRED` sau khi
   Payment đã chuyển tiền. Hợp đồng hiện hành đã quy định “thời hạn vay tính từ ngày giải ngân thực tế; lịch chính
   thức xác định theo ngày giải ngân thực tế”, nên Fineract phải được ghi theo ngày giải ngân thực tế.
3. **Phục hồi (B08, B09, B12):** dừng service giữa bước ghi Fineract hoặc giữa lúc gọi provider làm saga/khoản giải
   ngân kẹt vĩnh viễn; event `DisbursementFailed` mâu thuẫn có thể ghi đè trạng thái saga.
4. **Phân loại lỗi (B10, B13) và outbox (B11):** Fineract đã ghi repayment nhưng đọc lại lỗi thì Payment hoàn tiền
   và báo thất bại; lỗi Fineract khi tính nghĩa vụ trả về `500`; publish Kafka không có timeout.

### Ai thấy gì?

- Người vay: ngày giải ngân/trả nợ và kỳ đến hạn theo ngày Việt Nam; lịch chính thức sau giải ngân đến từ Fineract.
- Nhà đầu tư: không bị giữ tiền vô thời hạn khi provider/Fineract không phản hồi; không bị capture hai lần.
- Vận hành: bản ghi chỉ dừng ở `REPAIR_REQUIRED`/`RECONCILIATION_REQUIRED` khi thật sự cần người quyết định.

### Điều kiện hoàn thành nghiệp vụ

- Từ 00:00–07:00 giờ Việt Nam, ngày giải ngân, ngày trả nợ và kỳ đầu trong Fineract khớp ngày Việt Nam.
- Booking sau ngày dự kiến vẫn thành công; lịch chính thức lấy từ Fineract theo ngày giải ngân thực tế.
- Service chết giữa chừng thì worker nhận lại sau lease, tra cứu nguồn chuẩn trước, không chuyển tiền/ghi core lần hai.

## 1. Current state

| Defect | Code hiện hành | Hậu quả |
|---|---|---|
| B06 | `LoanTimeConfig`/`PaymentTimeConfiguration` trả `Clock.systemUTC()`; 16 chỗ `LocalDate.now(clock)`; `CreditScoringStateService` đổi `submittedAt` theo UTC; mã đơn top-up dùng ngày UTC | Ngày nghiệp vụ lùi một ngày trước 07:00 |
| B07 | `FineractCoreLoanBookingGateway` gửi `expectedDisbursementDate` = ngày dự kiến của hợp đồng, `submittedOnDate` = hôm nay | Fineract `error.msg.loan.submittal.cannot.be.after.expected.disbursement.date` |
| B08 | `DisbursementSaga.startCoreBooking` xóa `nextRetryAt`; `dueIds` chỉ chọn `CORE_BOOKING_PENDING`/`RETRY_PENDING` | Saga kẹt `CORE_BOOKING` sau crash |
| B09 | `PaymentDisbursement.start` xóa `nextAttemptAt`; `RECONCILIATION_REQUIRED` không có worker | Hold kẹt `CAPTURE_PENDING` |
| B10 | `postRepayment` gom lỗi GET sau POST thành công vào `FINERACT_REPAYMENT_REJECTED` | Hoàn tiền + `FAILED` dù core đã ghi |
| B11 | `PaymentOutboxPublisher` gọi `send().get()` không giới hạn, nuốt lỗi không log | Worker treo khi broker treo |
| B12 | `DisbursementSaga.paymentFailed` ghi đè mọi trạng thái trừ `COMPLETED` | Event mâu thuẫn làm sai saga |
| B13 | `scheduledRepaymentQuote` ném lỗi thô khi Fineract lỗi | API trả `500 INTERNAL_ERROR` |

## 2. Thiết kế

### 2.1 Ngày nghiệp vụ

`finora.business-time-zone: ${FINORA_BUSINESS_TIME_ZONE:Asia/Ho_Chi_Minh}` cho Loan và Payment. Bean `Clock` trả
`Clock.system(zone)`: `Instant`/`clock.instant()` không đổi (thời điểm tuyệt đối), chỉ `LocalDate.now(clock)` trả ngày
Việt Nam. Zone sai làm service không khởi động (fail fast). Fineract tenant đã dùng `Asia/Ho_Chi_Minh` nên hai bên
cùng một lịch ngày. Mã đơn top-up (`yyMMdd`) dùng cùng zone vì ZaloPay yêu cầu ngày theo GMT+7.

### 2.2 Booking theo ngày giải ngân thực tế

Mỗi lần gọi `bookAndDisburse` lấy một `bookingDate = LocalDate.now(clock)` và dùng cho `activationDate`,
`submittedOnDate`, `expectedDisbursementDate`, `approvedOnDate`, `actualDisbursementDate`. Ngày dự kiến trong
Contract giữ nguyên làm bằng chứng đã ký; số tiền mỗi kỳ không đổi vì sản phẩm tính lãi theo kỳ
(`interestCalculationPeriodType = 1`), chỉ ngày đến hạn dịch theo ngày giải ngân như điều khoản hợp đồng. Lịch chính thức
sau booking được worker servicing đọc từ Fineract; thông báo lịch chính thức cho người vay thuộc R7.

### 2.3 Lease và claim token cho saga `CORE_BOOKING`

- `startCoreBooking(now, leaseUntil)` lưu `nextRetryAt = leaseUntil` (mặc định 5 phút, lớn hơn tổng timeout các lệnh
  Fineract của một lần booking).
- `dueIds` chọn thêm `CORE_BOOKING` có `nextRetryAt <= now` (lease hết hạn). `claim` nhận lại saga đó như một lần thử
  mới; quá `max-attempts` thì chuyển `REPAIR_REQUIRED` với `CORE_BOOKING_STALLED` thay vì lặp vô hạn.
- `CoreBookingWork` mang `attempt`; `complete`/`failCore` bỏ qua kết quả của lần thử cũ (attempt khác hoặc trạng thái đã
  đổi) và chỉ log, không ném lỗi.
- An toàn khi chạy lại: gateway tìm client/loan theo `externalId`, đọc trạng thái trước khi approve/disburse, nên lần thử
  sau không tạo khoản vay hay giải ngân core thứ hai.

### 2.4 `DisbursementFailed` chỉ hợp lệ khi saga chờ Payment

`paymentFailed` chỉ chuyển `WAITING_PAYMENT → PAYMENT_FAILED`; đã `PAYMENT_FAILED`/`COMPLETED` thì no-op; trạng thái khác
là dữ liệu mâu thuẫn → ném lỗi để event vào DLT cho người đối soát (fail closed), không tự hủy vốn.

### 2.5 Lease và đối soát khoản giải ngân Payment

- `start(now, leaseUntil)` lưu `nextAttemptAt = leaseUntil`. Lease hết hạn khi còn `PROCESSING` nghĩa là không biết provider
  đã chuyển tiền hay chưa → `RECONCILIATION_REQUIRED (PAYMENT_PROCESSING_INTERRUPTED)`, không gọi `disburse` lại.
- `PaymentProvider.lookup(sagaId)` tra kết quả theo idempotency key. Mock trả kết quả tất định; provider chưa hỗ trợ trả
  rỗng. Worker đối soát định kỳ (`reconciliation-interval`, tối đa `reconciliation-max-attempts`): có kết quả thành công
  → đi tiếp như thành công (capture, credit borrower, `DisbursementCompleted`); thất bại chắc chắn → trả hold về `HELD`
  + `DisbursementFailed`; chưa biết → giữ nguyên, hết lượt thì chờ người xử lý.
- `finish` chỉ chạy khi trạng thái là `PROCESSING` hoặc `RECONCILIATION_REQUIRED`; kết quả đến muộn của lần thử cũ bị bỏ
  qua, nên không capture hay phát event hai lần.

### 2.6 Repayment: tách lỗi POST và lỗi đọc lại

- Lỗi HTTP 4xx của POST: Fineract từ chối chắc chắn → hoàn tiền như hiện tại.
- Lỗi 5xx/mất kết nối của POST: chưa biết → `RECONCILIATION_REQUIRED`, đối soát theo `externalId`.
- POST đã trả `resourceId` nhưng GET breakdown lỗi: core đã ghi → `RECONCILIATION_REQUIRED (FINERACT_REPAYMENT_POSTED_READ_FAILED)`;
  không hoàn tiền.
- Lỗi khi đọc nghĩa vụ (`transactions/template`): `503 FINERACT_REPAYMENT_QUOTE_UNAVAILABLE`.

### 2.7 Outbox Payment

`send().get(publish-timeout)` (mặc định 10 giây), log `eventId`/`eventType` khi lỗi, giữ cờ interrupt; consumer đã chống
trùng theo `eventId` nên giao lại sau timeout là an toàn.

## 3. Transaction và concurrency

```text
Transaction A: khóa saga/disbursement (SELECT … FOR UPDATE) + đổi trạng thái + ghi lease
Ngoài transaction: gọi Fineract/provider
Transaction B: khóa lại, kiểm attempt/trạng thái, ghi kết quả + outbox
```

Hai worker cùng thấy lease hết hạn: row lock trong `claim` cho đúng một worker nhận; worker kia thấy trạng thái/lease mới
và bỏ qua. Lần thử cũ trả kết quả muộn: claim token (attempt) không khớp nên bị bỏ qua.

## 4. Test và acceptance

- Unit: clock zone mặc định; booking dùng ngày Việt Nam lúc 2026-10-16T20:00Z; saga lease/claim token/stalled;
  `paymentFailed` guard; Payment lease → reconciliation, lookup thành công/thất bại/rỗng, `finish` guard; repayment
  POST 4xx/5xx/GET lỗi; quote lỗi 503.
- Regression: toàn bộ test Maven Loan/Payment/Investment.
- E2E R1A chạy lại trong khung 00:00–07:00 giờ Việt Nam không còn `R1A-BIZDATE`.

## 5. Bản đồ code thực tế

### 5.1 Ngày nghiệp vụ (B06)

- `LoanTimeConfig.loanClock` và `PaymentTimeConfiguration` → `businessClock(zone)` = `Clock.system(ZoneId.of(zone))`;
  zone sai ném `DateTimeException` lúc tạo bean nên service không khởi động.
- `CreditScoringStateService`: ngày nộp hồ sơ gửi AI = `submittedAt.atZone(clock.getZone()).toLocalDate()`.
- `TopUpService`: tiền tố `app_trans_id` (`yyMMdd`) dùng `DateTimeFormatter.withZone(clock.getZone())`.
- Mọi `LocalDate.now(clock)` có sẵn của Loan/Payment tự trả ngày Việt Nam, không phải sửa từng chỗ.

### 5.2 Booking theo ngày giải ngân thực tế (B07)

`FineractCoreLoanBookingGateway.bookAndDisburse` lấy `bookingDate = LocalDate.now(clock)` một lần và truyền xuống các
helper tạo client (`activationDate`), tạo loan (`submittedOnDate`, `expectedDisbursementDate`), approve
(`approvedOnDate`, `expectedDisbursementDate`) và disburse (`actualDisbursementDate`). Command vẫn mang ngày dự kiến
của Contract nhưng gateway không dùng ngày đó cho Fineract.

### 5.3 Saga `CORE_BOOKING` (B08, B12)

```text
DisbursementSagaWorker (scheduler)
 → DisbursementSagaService.dueIds()            CORE_BOOKING_PENDING | RETRY_PENDING | CORE_BOOKING hết lease
 → execute(id)
     Tx A  claim(id): findByIdForUpdate → lease hết hạn và attempt ≥ max → markCoreBookingStalled (REPAIR_REQUIRED)
                      ngược lại startCoreBooking(now, now + core-booking-lease) → CoreBookingWork(sagaId, attempt, command)
     ngoài Tx  CoreLoanBookingGateway.bookAndDisburse(command)          tìm client/loan theo externalId, không tạo lần hai
     Tx B  complete(sagaId, attempt, fineractLoanId) | fail(sagaId, attempt, code, detail, retryable)
           DisbursementSaga.complete/failCore trả false khi attempt hoặc trạng thái đã đổi → chỉ log, không kích hoạt
           khoản vay hay phát LoanDisbursed lần hai
```

- `DisbursementSaga.paymentFailed` trả `true` chỉ khi `WAITING_PAYMENT → PAYMENT_FAILED`; trùng → `false`; trạng thái
  khác ném `IllegalStateException` (event vào DLT). `handleFailed` chỉ gọi `LoanFundingCancellationService.cancel`
  khi kết quả là `true`.
- Cấu hình `DisbursementSagaProperties` (`finora.loan.disbursement.max-attempts=3`, `retry-backoff=30s`,
  `core-booking-lease=5m`).
- Migration Loan V28: index một phần `(next_retry_at, id)` cho ba trạng thái worker quét. Saga `REPAIR_REQUIRED`,
  `COMPLETED`, `PAYMENT_FAILED` có `next_retry_at = NULL`, nằm ngoài index nên không chiếm lô 20 bản ghi.

### 5.4 Khoản giải ngân Payment (B09)

```text
PaymentDisbursementWorker
 → PaymentDisbursementService.dueIds()         REQUESTED | RETRY_PENDING | PROCESSING hết lease | RECONCILIATION_REQUIRED đến lượt
 → execute(id)
     Tx A  claim(id): PROCESSING hết lease → requireReconciliation(PAYMENT_PROCESSING_INTERRUPTED) → Work.reconcile
                      REQUESTED/RETRY_PENDING → giữ chỗ capture các hold, start(now, now + processing-lease)
     ngoài Tx  provider.disburse(sagaId, …)  hoặc  provider.lookup(sagaId)
     Tx B  finish(id, result)               chỉ khi acceptsProviderResult() (PROCESSING/RECONCILIATION_REQUIRED)
           applyReconciliation(id, found)   có kết quả chắc chắn → finish; chưa có → recordReconciliationCheck
```

- Kết quả `PAYMENT_PROVIDER_UNCERTAIN` (provider ném lỗi) → `RECONCILIATION_REQUIRED`, hold giữ `CAPTURE_PENDING`,
  không retry chuyển tiền.
- `MockPaymentProvider.lookup` trả kết quả tất định giống `disburse`; provider chưa hỗ trợ tra cứu trả rỗng nên lệnh
  chờ người đối soát sau `reconciliation-max-attempts` (mặc định 30 lần × 1 phút).
- Migration V12: `reconciliation_attempt_count`, xếp lịch cho bản ghi `RECONCILIATION_REQUIRED` cũ. Migration V13:
  index một phần `(next_attempt_at, id)` cho bốn trạng thái worker quét, thay index V2 chỉ phủ hai trạng thái.
- Cấu hình `PaymentDisbursementProperties` (`finora.payment.disbursement.*`).

### 5.5 Repayment, quote và outbox (B10, B11, B13)

- `FineractRepaymentGateway.postRepayment`: HTTP 4xx của POST → `FINERACT_REPAYMENT_REJECTED` (không retry, hoàn tiền);
  5xx/mất kết nối → `FINERACT_REPAYMENT_OUTCOME_UNKNOWN` (outcome unknown); POST đã có `resourceId` nhưng GET lỗi →
  `FINERACT_REPAYMENT_POSTED_READ_FAILED` (outcome unknown). Hai mã sau đi nhánh đối soát theo externalId sẵn có.
- `FineractQuoteErrors.read` bọc mọi lần đọc nghĩa vụ trong `PaymentRepaymentService`, `EarlySettlementQuoteService`,
  `PartialPrepaymentQuoteService`: không có kỳ đến hạn → 422; core không phản hồi → 503
  `FINERACT_REPAYMENT_QUOTE_UNAVAILABLE`; core từ chối → 502 `FINERACT_REPAYMENT_QUOTE_REJECTED`.
- `PaymentOutboxPublisher.publishOne`: `send().get(outbox-publish-timeout)`; lỗi/timeout → log `eventId/type/lỗi` rồi
  `failed(..., now + 10s)`; `InterruptedException` đặt lại cờ interrupt.

### 5.6 Bổ sung cùng wave

- B20 (phần Payment): `GET /api/v1/repayments/scheduled-due?loanApplicationId=` → `RepaymentController.scheduledDue` →
  `PaymentRepaymentService.scheduledDue` (kiểm chủ khoản vay qua tài khoản thu nợ đang hoạt động, đọc
  `transactions/template` của Fineract) → `ScheduledDueResponse(amount, overdueAmount, currency, repaymentType,
  businessDate)`. Mobile `LoanPaymentScreen` dùng số này thay vì tự cộng kỳ.
- B21: `LoanServicingSyncWorker` log lỗi Fineract/nghiệp vụ một dòng `warn` (không stack trace) cho khoản vay không
  đồng bộ được; lỗi khác vẫn log `error` kèm stack.

## 6. Sai khác so với thiết kế

- Không cần sửa 16 chỗ `LocalDate.now(clock)`: đổi zone của bean `Clock` là đủ, `Instant` giữ nguyên.
- Thêm migration index Loan V28 và Payment V13 sau khi rà truy vấn worker: index cũ chỉ phủ trạng thái trước Wave 2.
- Event `DisbursementFailed` mâu thuẫn không chỉ bị chặn mà còn được log và đẩy DLT (fail closed), đúng §2.4.
- Ngày dự kiến trong Contract và PDF không đổi; người vay chưa được thông báo lịch chính thức sau booking (R7).

## 7. Bằng chứng kiểm thử

| Lớp | Test | Kết quả |
|---|---|---|
| Unit Loan | `LoanTimeConfigTest` (2), `FineractCoreLoanBookingGatewayTest.booksEveryCoreDateOnTheVietnamBusinessDateOfActualDisbursement`, `DisbursementSagaTest` (5: lease, claim token, stalled, `paymentFailed` guard), `DisbursementSagaServiceTest` (8: lease còn hạn không claim lại, lease hết hạn claim với token mới, kết quả muộn bị bỏ qua, event mâu thuẫn không nhả tiền, backoff) | Toàn module 225 PASS |
| IT Loan | `FinoraLoanApplicationIT` trên PostgreSQL Testcontainers | 16 PASS (1 skip có sẵn) |
| Unit Payment | `PaymentTimeConfigurationTest` (2), `PaymentDisbursementTest` (4), `FineractRepaymentGatewayTest` (3 test mới cho 4xx/5xx/GET lỗi), `FineractQuoteErrorsTest` (4), `PaymentOutboxPublisherTest` (3: broker treo, ack, interrupt) | Toàn module PASS |
| IT Payment | `interruptedDisbursementIsReconciledByLookupAndCompletedExactlyOnce`, `uncertainProviderResultWaitsForLookupInsteadOfRetryingTransfer`, `scheduledDueReturnsCoreObligationOnlyToLoanOwner` cùng các IT cũ | 14 PASS |
| E2E R1A | `r1a-20261017-044507`, `r1a-20261017-044938`, `r1a-20261017-052157`, chạy lúc 04:45, 04:49 và 05:21 giờ Việt Nam (21:45Z, 21:49Z, 22:21Z ngày 16/10); lần cuối với toàn bộ jar Wave 2, Flyway áp Loan V28 và Payment V13 | 42/42 mỗi lần, `defects = []` |

E2E trên stack thật (Fineract 1.15, AI v17, CIC Node, ký `MOCK`, provider `mock`): hợp đồng ghi giải ngân dự kiến
2026-10-17, kỳ đầu 2026-11-17; Fineract loan 13, 14 và 15 ghi `actualDisbursementDate = 2026-10-17`, kỳ đầu 2026-11-17 —
cùng ngày Việt Nam dù đồng hồ UTC vẫn là 16/10. Trả kỳ 1: 1.725.000 (gốc 1.625.000, lãi 100.000), `transactionDate =
2026-10-17`, Fineract transaction 34, 37 và 40, kỳ kế tiếp 2026-12-17. Trước Wave 2, hai lần chạy lúc 03:44 và 03:48 cho
kỳ đầu 2026-11-16 (defect `R1A-BIZDATE`).

Chưa kiểm trên stack thật (thuộc R1B): dừng JVM giữa `CORE_BOOKING`/`PROCESSING` rồi bật lại, Fineract timeout thật.
Hai nhánh này hiện được chứng minh bằng unit/integration test với clock giả và provider giả.

## 8. Rollout

- Migration Loan V28, Payment V12–V13 chỉ thêm cột/index; Flyway áp khi service khởi động.
- Biến môi trường mới đều có mặc định: `FINORA_BUSINESS_TIME_ZONE`, `PAYMENT_OUTBOX_PUBLISH_TIMEOUT`,
  `finora.loan.disbursement.*`, `finora.payment.disbursement.*`.
- Khoản vay đã booking theo ngày UTC trước Wave 2 giữ nguyên lịch Fineract hiện có; không sửa ngược dữ liệu core.
