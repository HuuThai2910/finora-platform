---
task_id: W2D
title: Sự cố giao dịch cho vận hành, khắc phục quá hạn đầy đủ, xác nhận rủi ro Auto-Invest và fixture quá hạn/cơ cấu trên đồng hồ E2E
owner: Thai
status: READY_FOR_REVIEW
depends_on: [W2C, R2-W2, R3-W2]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W2D — Sự cố giao dịch, khắc phục quá hạn và Auto-Invest

Thái duyệt gói Q và cho agent tự chọn phương án tốt nhất (D23). Plan làm phần R2 còn lại (màn sự cố cho vận hành),
Q5 (xác nhận rủi ro Auto-Invest), R1B fixture 2/5/6 trên đồng hồ E2E, và sửa ba lỗi phát hiện khi chạy fixture: B31,
B32, B35 ([`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 3a, 3b). Phần 2 làm R3 lệnh góp vốn ↔
khoản giữ (B38–B41, D30), ngày đáo hạn từ lúc giải ngân (B34), drill lỗi thật Q13 (D31) và lỗi drill phát hiện (B42, D32).
Kiểm soát pháp lý: [`LEGAL-SERVICING-01`](../../docs/LEGAL-COMPLIANCE.md), [`LEGAL-PAYMENT-01`](../../docs/LEGAL-COMPLIANCE.md),
[`LEGAL-DISCLOSURE-02`](../../docs/LEGAL-COMPLIANCE.md). Wave này sửa module Investment của Hải (Q5, R3) theo D2: Hải review
trước merge.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Vận hành xử lý được tiền đã đi mà một bên chưa ghi xong.** Web quản trị có tab "Sự cố giao dịch" gồm ba hàng đợi:
   trả nợ chưa ghi xong vào hệ thống lõi (Payment), giải ngân chưa có kết quả chuyển tiền (Payment), đã giải ngân nhưng
   Fineract chưa ghi khoản vay (Loan). Mọi thao tác chỉ tra cứu hoặc chạy lại đúng bước còn dở theo mã đã gửi; không
   trừ tiền hay chuyển tiền lần hai.
2. **Khoản trả nợ Fineract chưa từng nhận không còn kẹt mãi (B32).** Trước đây khoản ở `RECONCILIATION_REQUIRED` mà
   Fineract không có giao dịch thì đứng yên vĩnh viễn: tiền của người vay nằm ở tài khoản trung gian, khoản vay bị khóa
   không trả tiếp được (chặn của B30), quá hạn vẫn tăng. Nay sau 15 phút vận hành bấm "Ghi lại": FINORA tra cứu lại, chỉ
   ghi khi Fineract chắc chắn chưa có, dùng đúng mã, số tiền và ngày ghi nhận ban đầu.
3. **Khắc phục quá hạn thu đủ phần quá hạn (B31).** Quá hạn nhiều kỳ, app trước đây chỉ thu kỳ cũ nhất nhưng vẫn ghi
   "Khắc phục quá hạn"; trả xong khoản vay vẫn nợ xấu. Nay số mặc định là toàn bộ phần quá hạn; người vay vẫn được chọn
   chỉ trả kỳ cũ nhất và được báo rõ khoản vay còn quá hạn.
4. **Không thu phí trả trước trên tiền trả nợ quá hạn (B35).** Khoản vay đang quá hạn không lập được báo giá trả trước
   một phần; tất toán toàn bộ vẫn được.
5. **Nợ xấu trả bớt vẫn là nợ xấu ở mọi nơi (B36, D29).** Loan giữ khoản vay `DEFAULTED` nhưng trước đây gửi nhóm nợ
   tính lại từ DPD, nên Note của nhà đầu tư về `ACTIVE` nhóm 2 và chợ Notes thôi cảnh báo nợ xấu. Nay nhóm của đợt quá
   hạn không giảm cho tới khi trả đủ; cơ cấu đổi DPD cũng được báo ngay (B37).
6. **Auto-Invest đặt lệnh thay nhà đầu tư chỉ khi đã xác nhận đúng bản công bố rủi ro (Q5).** Bật quy tắc phải xác nhận;
   đổi bản công bố thì quy tắc tạm dừng tới khi xác nhận lại; lệnh tự động mang version đã xác nhận.
7. **Tiền giữ của nhà đầu tư không bị kẹt hay lệch khi mạng/Payment chập chờn (R3, B38–B41).** Lệnh chờ giữ tiền được đối
   chiếu với khoản giữ thật ở Payment trước khi làm tiếp; lệnh chỉ chốt khi tiền đang được giữ; nhả tiền chạy sau khi lệnh
   đã hủy xong; lỗi lặp lại thì lùi lịch, lỗi chắc chắn thì dừng và hiện cho vận hành.
8. **Ngày đáo hạn của vị thế có ngay từ lúc giải ngân (B34).**
9. **Chứng minh bằng lỗi thật (Q13):** tắt hẳn Loan/Payment/Investment đúng lúc nguy hiểm, cắt phản hồi hoặc request giữa
   Payment và Fineract; kết quả cuối không có tiền, khoản vay, giao dịch hay Note thứ hai. Drill phát hiện B42: mất phản
   hồi hay bị ngắt kết nối sau khi đã gửi lệnh bị Payment ghi là lỗi "không xác định", còn Loan dừng hẳn việc ghi khoản vay
   thay vì thử lại. Nay cả hai coi là "chưa biết kết quả": đối soát hoặc thử lại theo đúng mã đã gửi.
10. **Fixture quá hạn, nợ xấu và cơ cấu chạy trên Fineract thật** với đồng hồ E2E và DB local riêng (D24, D25).

### Ví dụ (Fineract 1.15 local, 10.000.000, 12 kỳ, 12%/năm, kỳ 1.726.000)

- DPD 91, ba kỳ quá hạn (5.178.000). Trước sửa: `scheduled-due` trả `amount=1.726.000` dù `overdueAmount=5.178.000`;
  trả xong vẫn `DEFAULTED`, DPD 61 (`w2d-defaultcure-20261017-122503`). Sau sửa: mặc định 5.178.000; chọn "Chỉ kỳ cũ
  nhất" thì trả 1.726.000, khoản vay vẫn `DEFAULTED`, DPD 61, nhóm 3 (lượt `w2d-defaultcure-20261017-131232` trước
  D29 cho thấy Note về `ACTIVE` nhóm 2); trả nốt 3.452.000 thì về `ACTIVE`, nhóm 1.
- Khoản trả kỳ 1.726.000 gặp mất kết nối khi ghi Fineract → `RECONCILIATION_REQUIRED`. Worker tra cứu mỗi 30 giây không
  thấy. Sau 15 phút vận hành bấm "Ghi lại": tra cứu lần nữa không thấy → ghi với mã `FINORA-REPAY-<id>` và ngày ghi nhận
  ban đầu → `CORE_POSTED` → chia tiền cho nhà đầu tư. Nếu Fineract từ chối mà tra lại vẫn không có giao dịch → hoàn
  1.726.000 về ví. Nếu Fineract từ chối vì giao dịch cũ vừa vào → ghi nhận giao dịch đó, không hoàn tiền.

### Ai thấy gì?

- **Admin (web):** tab "Sự cố giao dịch" ở trang Vận hành khoản vay, có số việc trên tab; ba bảng kèm lỗi gần nhất, số lần
  thử, lượt kế tiếp. Trả nợ chờ đối soát có menu "Đối soát" và "Ghi lại vào hệ thống lõi" (khóa tới hết 15 phút, ghi rõ
  giờ mở); giải ngân hết lượt tra cứu tự động có "Tra cứu lại"; saga cần sửa có "Ghi lại" kèm ghi chú bắt buộc.
- **Người vay (mobile):** quá hạn nhiều kỳ thì chọn "Toàn bộ quá hạn" hoặc "Chỉ kỳ cũ nhất" (cảnh báo khoản vay vẫn quá
  hạn); màn trả trước một phần báo "đang quá hạn" với nút "Thanh toán phần quá hạn".
- **Nhà đầu tư (mobile):** bật Auto-Invest hiện câu xác nhận rủi ro; quy tắc tạm dừng hiện "Chờ xác nhận rủi ro".

## 1. Tách pháp luật, policy và quyết định

| Nội dung | Loại | Nguồn |
|---|---|---|
| Khắc phục quá hạn là trả đủ phần quá hạn; trả thiếu chỉ giảm số quá hạn | Policy FINORA | LOAN-SERVICING-POLICY §3.4, D26 |
| Không hạ nhóm nợ khi chưa trả đủ nợ quá hạn | Tham chiếu thực tiễn ngân hàng; FINORA áp dụng trong một đợt quá hạn (D29), chưa có thời gian theo dõi sau khi trả đủ | LOAN-SERVICING-POLICY mục nhóm nợ |
| Trả trước gốc chỉ khi không có nợ quá hạn | Policy FINORA (D27), theo thực tiễn ngân hàng; không phải trích dẫn luật | — |
| Không ghi trả nợ hai lần, không hoàn tiền khi kết quả core chưa chắc | Kiểm soát tài chính | `LEGAL-PAYMENT-01`, D28 |
| Khoảng an toàn 15 phút trước khi ghi lại | Cấu hình vận hành (`PAYMENT_CORE_REPOST_SAFE_AFTER`), không phải luật | D28 |
| Xác nhận rủi ro trước khi để hệ thống đặt lệnh thay | Thông tin khoản vay trước giao kết (Điều 22 khoản 8 điểm c NĐ 94/2025); câu chữ là policy FINORA, cần legal review | `LEGAL-DISCLOSURE-02`, Q5 |
| Đồng hồ giả, DB local, job quá hạn trong E2E | Quy định kiểm thử | Q12, D24, D25 |

## 2. Thiết kế

### Payment

- **B31/D26:** `ScheduledRepaymentCoreQuote.payableAmount() = max(template, totalOverdue)` khi có quá hạn;
  `partialOverdueAmount()` = kỳ cũ nhất khi nhỏ hơn số khắc phục; `accepts()` chỉ nhận đúng một trong hai.
  `scheduled-due` trả `amount` mới và field mới `partialOverdueAmount` (null khi không áp dụng). Lệnh trả vẫn loại
  `OVERDUE_CURE`, ghi Fineract ngay.
- **B35/D27:** báo giá trả trước một phần `409 PARTIAL_PREPAYMENT_LOAN_OVERDUE` khi kỳ chưa trả cũ nhất đã quá ngày đến
  hạn (đọc từ lịch, không chờ job quá hạn của Fineract). Tất toán không đổi.
- **B32/D28:** `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/repost` (ADMIN). Thứ tự: khóa dòng, kiểm
  `RECONCILIATION_REQUIRED` và đủ `core-repost-safe-after` (mặc định 15 phút) tính từ lần đổi trạng thái gần nhất →
  tra cứu theo externalId (có thì `CORE_POSTED`, không ghi) → nhận việc (`CORE_POSTING`, `attempt_count++`,
  `core_repost_count++`, ai/lúc nào) → POST cùng externalId, `corePostingDate`, `coreAmount` → thành công `CORE_POSTED`;
  không rõ kết quả `RECONCILIATION_REQUIRED`; 4xx thì tra cứu lại: có giao dịch → `CORE_POSTED` (trùng externalId),
  không có → hoàn tiền `FAILED`, không tra được → giữ `RECONCILIATION_REQUIRED` (`FINERACT_REPOST_REJECTED_UNVERIFIED`).
  Lỗi đọc Fineract ở bước đầu → `503 FINERACT_RECONCILIATION_UNAVAILABLE`, không đổi gì. Payment **V17** chỉ thêm cột
  `core_repost_count`, `last_core_repost_by/at` + CHECK.
- Fineract 1.15 không nhận hai repayment cùng externalId: probe ngày 2026-10-17 trên Fineract local (loan 55, externalId
  `FINORA-REPAY-f03c1445-…` đã có) trả `403 error.msg.loan.repayment.duplicate.externalId`, không ghi gì. Gateway map lỗi
  này thành `FINERACT_REPAYMENT_DUPLICATE_EXTERNAL_ID` loại không rõ kết quả: lệnh chuyển sang đối soát và được ghi nhận
  theo giao dịch đã có, không bao giờ bị hoàn tiền (áp dụng cho cả lần ghi thường).
- Hàng đợi trả nợ nhận `status` lọc trong `CORE_POSTING/RECONCILIATION_REQUIRED/FAILED` (400 nếu ngoài); response thêm
  `corePostingDate`, `coreRepostCount`, `lastCoreRepostBy/At`, `coreRepostAvailableAt`.
- **R2 giải ngân:** `GET /api/v1/admin/disbursement-reconciliation` (PROCESSING/RETRY_PENDING/RECONCILIATION_REQUIRED),
  `POST /{sagaId}/recheck` chỉ cho `RECONCILIATION_REQUIRED`: đặt lại lượt tra cứu, `nextAttemptAt=now`, không gọi chuyển
  tiền. Payment **V16** thêm `manual_recheck_count`, `last_manual_recheck_by/at` + index hàng đợi.

### Loan

- **R2 saga:** `GET /api/v1/admin/disbursement-sagas?status=` (mặc định `REPAIR_REQUIRED`), `POST /{sagaId}/requeue`
  với `note` bắt buộc (≤500): `REPAIR_REQUIRED → RETRY_PENDING`, `nextRetryAt=now`, giới hạn tự động tính lại từ
  `repair_attempt_base`; booking đối chiếu theo số hợp đồng (externalId) nên không tạo khoản vay thứ hai. Loan **V31**.
- **D24:** `FINERACT_PRODUCT_NAMESPACE` (chữ hoa/số, tối đa 8 ký tự): externalId `FINORA-<ns>-LP-<id>-V<n>`, tên và
  shortName riêng; rỗng giữ định dạng cũ.
- **Q12:** `finora.e2e.clock-offset` của Loan chỉ được nhận khi chữ ký `MOCK` và profile `local`.

### Loan (D29, B36, B37)

- `LoanCollectionCase.observe`: nhóm = `max(nhóm đã chạm của hồ sơ, nhóm theo DPD)`, mức thu hồi theo nhóm
  (`CollectionStage.fromDebtGroup`). Hồ sơ đóng khi trả đủ phần quá hạn; đợt mới mở hồ sơ mới.
- `CollectionCaseService.apply(loan, projection, previousDpd, observedAt)` trả `DebtGroupChange(previous, current)`;
  đồng bộ servicing, `RepaymentDistributed` và hoàn tất cơ cấu dùng số này cho `LoanDelinquencyChanged.v1` (không đổi
  schema). Hoàn tất cơ cấu phát event khi DPD đổi (B37).
- Thống kê: tổng hợp dư nợ theo nhóm `LEFT JOIN` hồ sơ thu hồi `OPEN` (tối đa một hồ sơ mở mỗi khoản vay); chuỗi lịch sử
  dùng `LoanServicingHistory.debtGroupAt`, tính cả DPD trong ngày trả trước khoản trả của ngày đó.

### Investment (Q5)

- Investment **V17**: `auto_invest_configs.risk_acknowledged_version/at` + CHECK.
- Bật quy tắc phải gửi `riskAcknowledgementVersion` = bản hiện hành (`400 RISK_ACKNOWLEDGEMENT_REQUIRED`,
  `409 RISK_DISCLOSURE_OUTDATED`). Chỉ bật quy tắc đang tắt hoặc xác nhận bản công bố khác mới ghi thời điểm mới; sửa tiêu
  chí của quy tắc đang bật giữ nguyên thời điểm đã xác nhận.
- Matcher bỏ qua quy tắc chưa xác nhận bản hiện hành (`RISK_ACKNOWLEDGEMENT_OUTDATED`), lệnh tự động mang version đã xác
  nhận. Response thêm `riskAcknowledgedVersion/At`, `riskDisclosureVersion`, `pausedForRiskAcknowledgement`.

### Investment và Payment (R3: lệnh góp vốn ↔ khoản giữ, D30)

- Payment `GET /api/v1/transactions/holds?orderReference=`: đọc khoản giữ, không đổi gì; nhả khoản `CAPTURE_PENDING/
  CAPTURED` → `409 PAYMENT_HOLD_NOT_RELEASABLE`, lệch chủ → `403 PAYMENT_OWNER_MISMATCH` (trước đây 500).
- `RestPaymentClient`: đọc `status` của khoản giữ trả về (B38); chỉ 400/409/422 là từ chối chắc chắn (B39); `findHold`
  (404 `PAYMENT_HOLD_NOT_FOUND` → rỗng); `release` ném `PaymentCallException(code, retryable)`, lỗi chắc chắn là
  `PAYMENT_HOLD_NOT_RELEASABLE/NOT_FOUND/ORDER_MISMATCH/OWNER_MISMATCH`.
- `FundingServiceImpl`: lệnh chờ hỏi `findHold` trước (đúng số tiền thì chốt, đã nhả thì từ chối, đã vào giải ngân thì dừng);
  nhả tiền sau khi transaction của lệnh commit (người dùng hủy, bị từ chối sau khi giữ — B40).
- `FundingMaintenanceService`: hàng đợi `findPendingDue`/`findReleaseDue` theo lượt kế tiếp, lùi 30 giây → 1 giờ; lỗi
  chắc chắn đặt `release_blocked_at` (B41). Investment **V18**: `pending_attempt_count/next_attempt_at`,
  `release_attempt_count/next_attempt_at/error_code/blocked_at` + CHECK, index `ix_order_release_due`, `ix_order_pending_due`
  (thay `ix_order_release_pending` của V12).
- Admin `GET /api/v1/investments/admin/order-incidents` (dừng nhả, hoặc từ 3 lần thử) và `POST /{orderReference}/retry`
  (bỏ trạng thái dừng/lịch lùi, giữ mã lỗi và số lần thử); web: mục "Lệnh góp vốn chưa khớp với ví" trong tab sự cố.

### Loan → Investment (B34)

`CoreLoanBookingResult.maturityDate` lấy `timeline.expectedMaturityDate` từ lần đọc cuối của bước ghi core (lịch theo ngày
giải ngân thực tế, không thêm lời gọi Fineract); `LoanDisbursed.v1` thêm field optional `loanNumber`, `maturityDate`;
Investment tạo hoặc cập nhật trạng thái servicing với ngày đáo hạn, không ghi đè ngày do cơ cấu đặt.

### Drill lỗi thật (Q13, D31)

- Điểm dừng `finora.e2e.pause-point`/`pause-duration` (`FINORA_E2E_PAUSE_POINT/DURATION`): Loan
  `core-booked-before-save` (sau `bookAndDisburse`, trước khi lưu saga), Payment `provider-disbursed-before-save` (sau provider
  thành công, trước `finish`). Loan chỉ nhận với chữ ký `MOCK` + profile `local`; Payment chỉ nhận khi provider giải ngân và
  nạp tiền đều `mock`. Log dòng ASCII `E2E_PAUSE point=...` để harness bắt.
- Toxiproxy: service `toxiproxy` (profile `e2e-faults`) trong `docker-compose.yml`; launcher `-WithToxiproxy` tạo proxy
  `fineract-payment` (18444 → `fineract:8443`) và đặt `FINERACT_BASE_URL` của riêng Payment, ghi `fineractVia` vào state để
  các lần chạy lại giữ nguyên. `-DrillEnv` chỉ nhận khóa trong danh sách cho phép.
- Harness `-FaultDrill`: `FineractResponseLost` (toxic `timeout` chiều về), `FineractRequestLost` (toxic `reset_peer` chiều
  đi, khoảng an toàn ghi lại 1 phút), `LoanCrashDuringBooking`, `PaymentCrashDuringDisbursement`,
  `InvestmentDownDuringDisbursement`; tắt JVM bằng `Stop-Process -Force` theo PID trong state launcher.
- B42 (D32): Spring 6.1.6 `RestClient` + `SimpleClientHttpRequestFactory` chỉ bọc lỗi I/O lúc gửi request thành
  `ResourceAccessException`; với POST có body, status đọc sau khi gửi nên timeout/reset lúc chờ phản hồi đến dưới dạng
  `RestClientException("Error while extracting response")`. Payment `postRepayment` và Loan `FineractRequestExecutor`,
  `AiCreditScoringHttpClient` duyệt cause chain: có `IOException` (trừ lỗi đọc JSON của Jackson) là kết quả chưa biết —
  Payment `FINERACT_REPAYMENT_OUTCOME_UNKNOWN`, Loan `FINERACT_CONNECTION_LOST` (retry được, `bookAndDisburse` tra theo số
  hợp đồng trước khi ghi), AI `AI_UNAVAILABLE`. POST có body chạy streaming mode nên JDK không tự gửi lại request.
- Payment `GET /api/v1/admin/disbursement-reconciliation/{sagaId}` (ADMIN, chỉ đọc, mọi trạng thái): drill
  `PaymentCrashDuringDisbursement` kiểm `attemptCount = 1` (provider chỉ được gọi chuyển tiền một lần) và
  `errorCode = PAYMENT_PROCESSING_INTERRUPTED` (bật lại thấy lease hết hạn nên tra cứu theo `sagaId`).
  `reconciliationAttemptCount` chỉ đếm lần tra cứu chưa ra kết quả, nên lần tra cứu thành công đầu tiên để 0; lượt chạy
  lại đầu tiên đòi giá trị này ≥ 1 và hỏng vì kỳ vọng sai của harness, không phải lỗi Payment. Launcher giữ log của lần
  bật trước ở `out/logs/archive/` (10 bản mỗi service): log JVM bị tắt có `E2E_PAUSE point=provider-disbursed-before-save`,
  log JVM mới có dòng hết lease chuyển đối soát.

### Gateway, web, mobile

- Gateway: route Payment thêm `/api/v1/admin/disbursement-reconciliation/**` (đứng trước `/api/v1/admin/**` của Loan).
- Web: slice `paymentApi` riêng (cookie, `withReauth`), tab "Sự cố giao dịch" (`IncidentsPanel`, `SagaRequeueModal`,
  `RepaymentRepostModal`), Vite proxy hai đường admin của Payment trước `/api/v1/admin`, "Làm mới" làm mới cả hai slice.
  Hàng đợi trả nợ chỉ đọc việc còn mở (không gồm `FAILED` đã hoàn tiền) để số trên tab không tăng mãi.
- Mobile: `LoanPaymentScreen` chọn số tiền khi quá hạn nhiều kỳ (key idempotency riêng cho từng lựa chọn);
  `PartialPrepaymentScreen` chặn khi quá hạn; `AutoInvestScreen` hỏi xác nhận khi bật, khi tạm dừng hoặc khi version đổi.

### Harness E2E

- `-Database local|shared` (mặc định local, D24), `-LoanClockOffset`, launcher `Start-Process` ghi file (runbook mục 8).
- `Move-E2EClock -Date -Services loan,payment` (chỉ DB local, tối đa 999 ngày): bật business date Fineract, chạy
  `LA_ARAG`, khởi động lại service với độ lệch; `Reset-E2EClock` trong `finally` và preflight.
- Fixture `OverdueCure`, `DefaultCure` (10 → 91 → trả bớt kỳ cũ nhất → khắc phục toàn bộ → 181 → 361 → trả hết),
  `Reschedule` (người vay xin gia hạn, admin duyệt, nhà đầu tư đồng ý, Fineract đổi lịch); check
  `delinquency.<mốc>-quote-covers-overdue` cho B31.

## 3. Vòng đời và ca lỗi

| Ca | Xử lý |
|---|---|
| Hai admin bấm "Ghi lại" cùng lúc | Khóa dòng; người sau thấy `CORE_POSTING` → `409 REPAYMENT_NOT_RECONCILABLE` |
| Worker đối soát tìm thấy giao dịch trong lúc admin tra cứu | Bước nhận việc kiểm lại trạng thái, không còn chờ đối soát thì dừng |
| Payment chết giữa lúc ghi lại | `CORE_POSTING` quá 1 phút → worker đưa về đối soát → tra cứu thấy thì ghi nhận |
| Request cũ tới Fineract muộn | Khoảng an toàn 15 phút; nếu vẫn trùng thì 4xx → tra cứu thấy → ghi nhận, không hoàn tiền |
| Fineract từ chối thật (khoản vay đã đổi, ngày trước ngày cơ cấu…) | Tra lại không có → hoàn tiền, `FAILED` |
| Bấm "Tra cứu lại" giải ngân không ở trạng thái đối soát | `409 DISBURSEMENT_NOT_RECONCILABLE` |
| Requeue saga không ở `REPAIR_REQUIRED` | `409 DISBURSEMENT_SAGA_NOT_REPAIRABLE` |
| `totalOverdue` cũ vì job quá hạn chưa chạy | Số mặc định không nhỏ hơn kỳ cũ nhất; job chạy cuối ngày (production) hoặc theo đồng hồ E2E (D25) |
| Chọn kỳ cũ nhất rồi đổi sang toàn bộ sau lỗi mạng | Mỗi lựa chọn một idempotency key, không gặp `IDEMPOTENCY_KEY_REUSED` |
| Đổi bản công bố rủi ro | Quy tắc tạm dừng, bản ghi `RISK_ACKNOWLEDGEMENT_OUTDATED`, app yêu cầu xác nhận lại |
| Payment đã giữ tiền nhưng Investment mất kết quả | Lệnh `PENDING_FUNDS`; worker hỏi `GET` khoản giữ, `HELD` đúng số tiền thì chốt, không giữ lần hai |
| Lần chốt trước đã nhả tiền rồi rollback | Gọi lại giữ tiền trả `RELEASED` → từ chối `PAYMENT_HOLD_ALREADY_RELEASED`, không cam kết |
| Token/quyền lỗi khi giữ tiền | Không từ chối lệnh; giữ chờ và đối soát theo `GET` |
| Nhả tiền khi khoản đã vào giải ngân | `409 PAYMENT_HOLD_NOT_RELEASABLE` → dừng, hiện trên màn sự cố; vận hành đối chiếu rồi bấm "Thử lại" |
| Payment chậm khi nhả | Lùi 30 giây, 1, 2, 4... phút, tối đa 1 giờ; lệnh khác không bị chặn |
| Loan chết sau khi Fineract ghi khoản vay | Hết lease saga được nhận lại, `bookAndDisburse` tìm thấy khoản vay theo số hợp đồng; drill `LoanCrashDuringBooking` |
| Payment chết sau khi provider chuyển tiền | Hết lease → `RECONCILIATION_REQUIRED` → tra provider theo `sagaId`; drill `PaymentCrashDuringDisbursement` |
| Investment tắt khi có `LoanDisbursed` | Consumer đọc tiếp offset khi bật lại; drill `InvestmentDownDuringDisbursement` |
| Fineract nhận repayment rồi mất phản hồi/ngắt kết nối (B42) | `FINERACT_REPAYMENT_OUTCOME_UNKNOWN` → `RECONCILIATION_REQUIRED`, không hoàn tiền |
| Fineract ngắt kết nối khi Loan chờ phản hồi ghi khoản vay (B42) | `FINERACT_CONNECTION_LOST` retry, tra theo số hợp đồng trước khi ghi lại; trước đây dừng `REPAIR_REQUIRED` |

## 4. Acceptance và bằng chứng

| Hạng mục | Bằng chứng |
|---|---|
| B31/D26 | `ScheduledRepaymentCoreQuoteTest` (3), IT `overdueSeveralInstallmentsQuotesTheFullCureAndOnlyAcceptsTheOldestInstallmentAsPartial`; E2E DefaultCure mục 5 |
| B35/D27 | IT `overdueLoanCannotQuotePartialPrepaymentSoNoFeeIsChargedOnMoneyThatOnlyCuresArrears` |
| B32/D28 | `FineractRepaymentGatewayTest.duplicateExternalIdMeansTheRepaymentIsAlreadyInCoreSoItIsReconciledNeverRefunded`, `PaymentRepaymentTest.coreRepostOnlyStartsFromReconciliationAfterTheSafeWindowAndRecordsWhoAndWhen`, `AdminRepaymentReconciliationServiceTest` (7), IT `repaymentTheCoreNeverReceivedIsRepostedOnceWithTheSameReferenceAfterTheSafeWindow`, `repostRejectedAsDuplicateOfAPostingThatLandedLateIsNeverRefunded`, `repostTheCoreRejectsAndStillDoesNotHaveIsRefundedToTheBorrower` |
| R2 giải ngân/saga | `PaymentDisbursementTest.manualRecheckOnlyRearmsTheProviderLookupOfAReconciliationCase`, `DisbursementSagaRepairTest`, `LoanMigrationUpgradeIT` (V31) |
| B36/D29, B37 | `LoanCollectionCaseTest.debtGroupNeverDropsWithinAnEpisodeWhenTheBorrowerOnlyPaysPartOfTheArrears`, `CollectionCaseServiceTest.partialPaymentOfArrearsKeepsTheDefaultedLoanInItsBadDebtGroup`, `CollectionStageTest`, `AdminLoanStatisticsServiceTest.loanStillInAnOverdueEpisodeKeepsItsEpisodeDebtGroupInThePortfolio`, `LoanServicingHistoryTest.debtGroupKeepsTheHighestGroupOfTheEpisodeUntilArrearsAreFullyPaid`; E2E DefaultCure, Reschedule mục 5 |
| Q5 | `AutoInvestConfigTest`, `AutoInvestMatcherTest.pausesRulesWithoutTheCurrentRiskAcknowledgement`, IT `enablingRequiresTheCurrentRiskAcknowledgementAndOrdersCarryIt` |
| D24 | `FineractPayloadMapperTest.namespacedEnvironmentNeverReusesTheFineractNameOrShortNameOfAnotherEnvironment`, `CoreProductSyncStateServiceTest`, `LoanTimeConfigTest` |
| Maven | Loan 244 unit + 17 IT (1 bỏ qua: AI live), Payment 74 + 24, Investment 70 + 42, common 33, gateway xanh |
| Web / mobile | `npm run build` sạch; mobile `tsc --noEmit` sạch |
| Migration nâng cấp tại chỗ | Stack E2E trên DB local có dữ liệu cũ: Loan V30→V31, Payment V15→V17, Investment V16→V17 (phần 2: V18) |
| R3 (D30, B38–B41) | Payment `HoldTransferServiceOnBehalfTest` (đọc khoản giữ không side effect, 409 khi đã vào giải ngân); Investment `RestPaymentClientTest` (5), `FundingFlowIT` (5 ca mới) |
| B34 | `FineractCoreLoanBookingGatewayTest`, `DisbursementSagaServiceTest.completedCoreBookingPublishesLoanDisbursedWithContractFeePolicy`, `FundingFlowIT.loanDisbursedFinalizesInvestmentExactlyOnce` |
| Q13 | `E2EPausePointsTest`, `PaymentE2EPausePointsTest`; drill trên stack thật ở mục 5 |
| B42 (D32) | Payment `FineractRepaymentConnectionLossTest` (socket thật: mất phản hồi, RST sau khi nhận đủ request); Loan `FineractHttpClientTest` (3 ca mới), `AiCreditScoringHttpClientTest` (2 ca mới); drill chạy lại mục 5 |
| Drill Payment chết | `AdminDisbursementReconciliationServiceTest` (3); check `drill.payment-crash.provider-called-once` |
| Maven phần 2 | Loan 260 unit + 17 IT (1 bỏ qua: AI live), Payment 84 + 24, Investment 75 + 47, common 33 |

## 5. Lượt chạy

Stack local ngày 2026-10-17: Fineract 1.15 thật, ký `MOCK`, nạp/giải ngân provider `mock`, proof `MOCK`, DB local riêng
(`-Database local`, D24), jar của wave này. Bộ hồi quy cuối chạy liên tục 13:35–14:10 trên cùng stack.

| Run ID | Fixture | Kiểm tra | Hồ sơ / Contract | Fineract loan | Kết quả |
|---|---|---|---|---|---|
| `w2d-installment-20261017-133641` (`-Drills`) | Trả kỳ trước hạn, dời đồng hồ, phát lại event | 54/54 | `LA-C3958664873348B1B371` / `LC-ED5FDF858862493FBDED` | 56 | PASS |
| `w2d-partialprepayment-20261017-134117` | Trả trước một phần | 38/38 | `LA-6DB8C53F1CA745A79861` / `LC-E24749A7219949268D1B` | 57 | PASS |
| `w2d-earlysettlement-20261017-134344` | Tất toán | 38/38 | `LA-A6F125DE8FA7468F96EE` / `LC-8A48B0772CC745BCBCE3` | 58 | PASS |
| `w2d-overduecure-20261017-134600` | Quá hạn 10 ngày → khắc phục | 36/36 | `LA-AED0CBECF09141DAA90F` / `LC-DA9603B923F5499B893F` | 59 | PASS |
| `w2d-reschedule-20261017-135734` | Gia hạn 2 kỳ, nhà đầu tư đồng ý | 39/39 | `LA-CD7258502A8947C6BF18` / `LC-D245DCEBC9F44FE3B794` | 61 | PASS |
| `w2d-defaultcure-20261017-140137` | 10 → 91 → trả bớt → khắc phục → 181 → 361 → trả hết | 45/45 | `LA-F9BBCF18CDFE467A8002` / `LC-7961AF172C954C2F9B15` | 62 | PASS |

Trạng thái tại từng SoR của lượt DefaultCure cuối (loan 62): DPD 91 báo `amount=5.178.000`, `partialOverdueAmount=1.726.000`;
trả bớt 1.726.000 (Fineract gốc 1.624.000, lãi 102.000, ngày 2027-02-16) → Loan `DEFAULTED` DPD 61, danh mục `DEFAULTED`
nhóm 3; trả 3.452.000 → `ACTIVE`, nhóm 1; DPD 181 nhóm 4 và 361 nhóm 5 với quá hạn 5.324.000 (gồm 49.000 lãi trên gốc
quá hạn do `LA_RINT` tính); một lần trả 5.324.000 (gốc 5.125.000, lãi 199.000, ngày 2028-02-13) → Fineract đóng khoản vay,
Loan `SETTLED`, vị thế `CLOSED`, nhà đầu tư nhận đủ 10.000.000 gốc; 7 event `LoanDelinquencyChanged` khác `eventId`. Sau lượt:
business date Fineract tắt, Loan/Payment chạy đồng hồ thật.

### Phần 2 (R3, B34, Q13)

Cùng stack, jar phần 2, Payment → Fineract đi qua Toxiproxy (`-WithToxiproxy`) cho cả sáu fixture hồi quy lẫn drill. Chạy
liên tục 14:50–15:34.

| Run ID | Fixture | Kiểm tra | Hồ sơ / Contract | Fineract loan | Kết quả |
|---|---|---|---|---|---|
| `w2d-installment-20261017-145032` | Trả kỳ trước hạn, dời đồng hồ, phát lại event (`-Drills`) | 54/54 | `LA-E664C73E050C4A7CB241` / `LC-59D907113D284636B6D4` | 63 | PASS |
| `w2d-partialprepayment-20261017-145544` | Trả trước một phần | 38/38 | `LA-330AE011C69F4B11A2F8` / `LC-B4D7EE884EF84ECA8591` | 64 | PASS |
| `w2d-earlysettlement-20261017-145825` | Tất toán | 38/38 | `LA-6398B622F43A4B74A148` / `LC-6EB7590F8C1F4CDEA818` | 65 | PASS |
| `w2d-overduecure-20261017-150042` | Quá hạn 10 ngày → khắc phục | 36/36 | `LA-C608280AD2E64F349F9F` / `LC-1B91E4D201C6405BAABC` | 66 | PASS |
| `w2d-defaultcure-20261017-150455` | 10 → 91 → trả bớt → khắc phục → 181 → 361 → trả hết | 45/45 | `LA-94201DEAF5194F7699E1` / `LC-F155142F399B44679BB7` | 67 | PASS |
| `w2d-reschedule-20261017-151140` | Gia hạn 2 kỳ, nhà đầu tư đồng ý | 39/39 | `LA-3CBF0F751E2940658593` / `LC-1AFE7189592E410DB4E7` | 68 | PASS |
| `w2d-drill-responselost-20261017-151445` | Drill `FineractResponseLost`: Fineract ghi, Payment mất phản hồi → đối soát tự tìm thấy | 53/53 | `LA-1AAF148FEE5D416A8C41` / `LC-7C372FAE5FDF4F40AE35` | 69 | PASS |
| `w2d-drill-requestlost-20261017-151845` | Drill `FineractRequestLost`: request không tới Fineract → admin "Ghi lại" sau 1 phút (B32) | 54/54 | `LA-124C23F4D0084F139124` / `LC-BBFA9460E0454057A69E` | 70 | PASS |
| `w2d-drill-loancrash-20261017-152330` | Drill `LoanCrashDuringBooking`: tắt Loan sau khi Fineract ghi khoản vay | 34/34 | `LA-34972B3D30C94B4E8184` / `LC-3BD87B2BE34C4A9EBFE2` | 71 | PASS |
| `w2d-drill-paymentcrash-20261017-152650` | Drill `PaymentCrashDuringDisbursement`: tắt Payment sau khi provider chuyển tiền | 32/32 | `LA-ECEC7FA78C97407D892A` / `LC-6E1D5A14E1F749FEA2BB` | 72 | PASS |
| `w2d-drill-investmentdown-20261017-153015` | Drill `InvestmentDownDuringDisbursement`: Investment tắt khi có `LoanDisbursed` | 32/32 | `LA-D8B429857DC54DB5A67D` / `LC-9C6F7FB46A714CCD9DCF` | 73 | PASS |

Hai drill Fineract đạt nhưng mã lỗi lúc vào đối soát là `FINERACT_REPAYMENT_UNEXPECTED` thay vì "chưa biết kết quả" (B42).
Sau khi sửa (D32), harness đòi đúng `FINERACT_REPAYMENT_OUTCOME_UNKNOWN` và drill Payment chết kiểm thêm số lần gọi provider;
chạy lại trên jar mới:

| Run ID | Fixture | Kiểm tra | Hồ sơ / Contract | Fineract loan | Kết quả |
|---|---|---|---|---|---|
| `w2d-drill-responselost-20261017-154203` | Drill `FineractResponseLost`: Fineract ghi, Payment mất phản hồi → đối soát tự tìm thấy | 53/53 | `LA-EFDBC62C93AD4837BFA5` / `LC-D763FAE6FD7A41958170` | 74 | PASS |
| `w2d-drill-requestlost-20261017-154552` | Drill `FineractRequestLost`: request không tới Fineract → admin "Ghi lại" sau 1 phút (B32) | 54/54 | `LA-809A14806697429787FB` / `LC-3ED7145D6D334180ACC7` | 75 | PASS |
| `w2d-drill-paymentcrash-20261017-155545` | Drill `PaymentCrashDuringDisbursement`: tắt Payment sau khi provider chuyển tiền | 33/33 | `LA-63CEF6DBFA9A4C5886DD` / `LC-83EE5C9CDEB844BE8245` | 77 | PASS |

Trạng thái tại từng SoR sau drill (ví dụ `w2d-drill-responselost-20261017-154203`): Fineract đúng một giao dịch theo externalId, ghi đúng ngày đến hạn
2026-11-17; Payment `COMPLETED` sau khi đối soát; mỗi topic Kafka một event ID. Drill tắt Loan: Fineract đã có khoản vay
trước khi tắt, sau khi bật lại vẫn một khoản (saga lượt 2). Drill tắt Payment: ví người vay cộng đúng 10.000.000 một lần,
hold nhà đầu tư capture hết. Drill tắt Investment: một `LoanDisbursed`, một `NoteOwnershipChanged`, Note đủ sau khi bật lại.

### Lượt hỏng

Mỗi lượt hỏng dẫn tới một sửa đổi:

| Run ID | Lỗi | Xử lý |
|---|---|---|
| `w2d-overduecure-20261017-115342` | DPD đứng ở 0 sau khi dời ngày | D25: chạy `LA_ARAG` |
| `w2d-reschedule-20261017-120916` | Không thấy consent cơ cấu | B33: Investment trả `content` |
| `w2d-defaultcure-20261017-122503` (loan 54) | DPD 91 chỉ thu 1.726.000, vẫn `DEFAULTED` | B31/D26 |
| `w2d-defaultcure-20261017-131232` (loan 55) | Trả bớt xong Note về `ACTIVE` nhóm 2 | B36/D29 |
| `w2d-defaultcure-20261017-135019` (loan 60) | Trả đủ 5.275.000 ở DPD 361 còn 49.000 quá hạn | D25: EOD chạy cả `LA_RINT`; vòng trả hết của harness trả tiếp phần còn lại thay vì dừng |
| `w2d-drill-responselost-20261017-151445`, `w2d-drill-requestlost-20261017-151845` | Đạt nhưng mã vào đối soát là `FINERACT_REPAYMENT_UNEXPECTED` | B42/D32: phân loại theo cause chain; harness đòi đúng mã |
| `w2d-drill-paymentcrash-20261017-155038` (loan 76) | Check mới đòi `reconciliationAttemptCount ≥ 1`, thực tế 0 | Kỳ vọng sai của harness: bộ đếm chỉ đếm lần tra cứu chưa ra kết quả; check đổi sang `errorCode = PAYMENT_PROCESSING_INTERRUPTED`, log lưu trữ xác nhận đường hết lease → đối soát |

## 6. Giới hạn còn lại

- "Ghi lại" (B32) có lượt E2E trên Fineract thật cho nhánh Fineract chưa nhận (drill `FineractRequestLost`); nhánh Fineract
  từ chối lần ghi lại vẫn chỉ có IT với gateway giả.
- Provider giải ngân là mock trả lời tra cứu tất định theo `sagaId`: drill tắt Payment chứng minh Payment không gọi chuyển
  tiền lần hai và không cộng ví hai lần, không chứng minh idempotency của một ngân hàng/ví thật (R4).
- Toxiproxy chỉ đặt giữa Payment và Fineract. Lỗi mạng Loan → Fineract (B42 phần Loan) và Investment → Payment mới có unit
  test/IT, chưa có drill.
- Hàng đợi sự cố chưa phân quyền chi tiết (mọi ADMIN đều thao tác được) và chưa có duyệt hai người; thuộc R8.
- Số trên tab "Sự cố giao dịch" gồm cả lệnh giải ngân đang chuyển tiền bình thường (`PROCESSING`); chúng tự hết sau vài giây.
- Trả đủ phần quá hạn thì nhóm nợ về 1 ngay; thực tiễn ngân hàng còn theo dõi thêm một thời gian trả đúng hạn trước
  khi hạ nhóm. Cần owner quyết định trước production.
- Fineract V4 không tính thêm lãi sau ngày đáo hạn: quá hạn giữ 5.324.000 từ DPD 181 tới 361 (lượt
  `w2d-defaultcure-20261017-140137`). Khớp D9 (lãi phạt bằng 0) nhưng người vay chậm trả sau đáo hạn không chịu thêm chi
  phí; owner cần quyết định lãi quá hạn sau đáo hạn trước production.
- DPD, nhóm nợ và số khắc phục phụ thuộc job cuối ngày của Fineract (`LA_RINT`, `LA_ARAG`); giám sát job thuộc R9.
