# Luồng nghiệp vụ xuyên service

Mỗi flow mới hoặc thay đổi MUST xác định trigger, orchestrator, contract, state authority, idempotency, timeout, failure và compensation. Event name dưới đây là contract định hướng; khi triển khai thật phải đăng ký topic/version trong `05-registry.md` và đặc tả payload.

## Quy tắc chung

- Orchestrator sở hữu tiến trình, không đồng nghĩa sở hữu state của service tham gia.
- Mỗi service chỉ commit local transaction của mình; không giữ DB transaction khi chờ network.
- Producer ghi outbox cùng transaction với state; consumer ghi `processed_events` cùng transaction với side effect.
- `eventId` chống xử lý event trùng; API side effect dùng `idempotencyKey`; Saga dùng `sagaId` xuyên suốt.
- Timeout/retry chỉ cho lỗi tạm thời và operation idempotent. Business rejection không retry.
- Compensation là nghiệp vụ riêng, idempotent và audit được; không xóa lịch sử để giả lập rollback.

## F01 — Định danh điện tử

**Trigger:** người dùng nộp hồ sơ eKYC. **Orchestrator:** User.

1. User tạo KYC application `SUBMITTED`, lưu metadata tài liệu an toàn.
2. User gọi AI eKYC với request ID/idempotency key.
3. AI trả OCR, face/liveness/forgery result và model/version.
4. User áp policy, chuyển `VERIFIED`, `REJECTED` hoặc `MANUAL_REVIEW`.
5. User phát `KycVerified`, `KycRejected` hoặc `KycManualReviewRequired`.
6. Notification gửi kết quả theo event.

**Idempotency:** `kycApplicationId + analysisType + modelVersion`.

**Failure:** AI timeout → KYC giữ `PROCESSING`/`RETRY_PENDING`; retry có giới hạn, sau đó manual review/DLT. MUST NOT tự đánh dấu verified khi AI lỗi.

## F02 — Tạo hồ sơ và chấm điểm tín dụng

**Trigger:** người vay đã đủ điều kiện gửi hồ sơ. **Orchestrator:** Loan.

1. Loan xác minh identity/KYC theo contract đã thống nhất và tạo application `SUBMITTED`.
2. Loan chuyển `SCORING`, gọi AI credit bằng immutable feature snapshot.
3. AI trả PD/score, suggested grade, reason codes, model/rule version.
4. Loan lưu scoring snapshot, áp policy nghiệp vụ và chuyển `PENDING_REVIEW` hoặc `REJECTED`.
5. Loan phát `LoanApplicationScored`/`LoanApplicationRejected`; Notification consume nếu cần.

**Idempotency:** `loanApplicationId + scoringAttempt/version`; cùng feature snapshot và model version phải trả cùng artifact reference.

**Failure:** AI lỗi → giữ `SCORING_RETRY_PENDING`, không tạo điểm mặc định. Retry hết hạn → manual review hoặc failure state có audit.

## F03 — Duyệt và đưa khoản vay lên sàn

**Trigger:** admin duyệt hồ sơ đang `PENDING_REVIEW`. **Orchestrator:** Loan.

1. Loan kiểm tra KYC/scoring snapshot, policy và optimistic version.
2. Loan chuyển `APPROVED`, sau đó tạo listing intent và chuyển `ON_MARKET` theo state machine.
3. Loan phát `LoanListed` chứa dữ liệu market tối thiểu, không chứa PII.
4. Investment consume idempotently, tạo market listing projection.

**Idempotency:** admin command dùng key; Investment unique theo `loanId + listingVersion`.

**Failure:** Investment chưa tạo projection → outbox retry/DLT; Loan không publish event mới trùng để “chữa” lỗi. Listing projection phải rebuild được từ event/source API có kiểm soát.

## F04 — Đặt vốn và giữ tiền

**Trigger:** investor đặt lệnh. **Orchestrator:** Investment.

1. Investment validate market/order và tạo order `PENDING_FUNDS`.
2. Investment yêu cầu Payment hold với `orderId` và idempotency key.
3. Payment khóa/cập nhật wallet an toàn, tạo ledger + hold transaction, trả `paymentTransactionId`.
4. Investment tạo commitment, chuyển order `COMMITTED`, phát `InvestmentCommitted`.
5. Nếu tổng valid commitments đạt target, Investment đóng listing và phát `LoanFullyFunded` đúng một lần.
6. Loan consume và tự chuyển `ON_MARKET → FUNDED` nếu version/state hợp lệ.

**Idempotency:** `investmentOrderId` cho hold; unique commitment theo order; funded event unique theo `loanId + fundingRound`.

**Failure/compensation:** Payment từ chối → order `REJECTED`; lỗi tạo commitment sau hold → Investment yêu cầu Payment release bằng reference hold; release được retry idempotently. Concurrent order MUST NOT làm overfund hoặc âm ví.

## F05 — Saga giải ngân

**Trigger:** Loan ở `FUNDED` và đủ điều kiện/chữ ký. **Orchestrator:** Loan.

1. Loan tạo durable `DisbursementSaga`, chuyển `DISBURSING`.
2. Loan yêu cầu Investment khóa/finalize commitments.
3. Loan yêu cầu Payment capture held funds và giải ngân cho borrower.
4. Payment tạo ledger entries, phát `DisbursementCompleted` hoặc `DisbursementFailed`.
5. Loan yêu cầu/đợi Investment activate Notes theo kết quả tài chính.
6. Loan phát audit event để Blockchain ghi proof; Blockchain phản hồi/reference bất đồng bộ.
7. Loan chuyển `ACTIVE` khi các bước bắt buộc hoàn tất; Notification gửi kết quả.

**Correlation:** mọi command/event mang `sagaId`, `loanId`, `step`, `attempt`, `eventId`.

**Failure/compensation:** finalize commitment lỗi → chưa capture tiền; capture/transfer lỗi → Payment tự bảo toàn ledger và Loan ra lệnh release/unfinalize thích hợp; Note activation lỗi sau disbursement → Saga retry/repair, không tự ý đảo giao dịch ngân hàng; Fabric lỗi → retry/DLT/reconciliation và không rollback giải ngân trừ khi policy sau này quy định Fabric là precondition.

**Restart:** Saga state phải durable; restart tiếp tục từ bước cuối đã xác nhận, không chạy lại side effect không idempotent.

## F06 — Thu nợ và phân bổ

**Trigger:** borrower thanh toán hoặc auto-debit đến hạn. **Orchestrator:** Loan cho nghĩa vụ kỳ hạn; Payment cho execution tài chính.

1. Loan cung cấp installment/payment instruction có version và amount breakdown mong đợi.
2. Payment collect tiền idempotently, áp waterfall đã version hóa: phí → lãi/phạt → gốc theo policy.
3. Payment lấy ownership snapshot/version từ Investment theo contract phù hợp và phân bổ cho investor wallets.
4. Payment commit ledger cân bằng, phát `RepaymentDistributed` với breakdown/reference tối thiểu.
5. Loan consume, cập nhật installment/loan state; Investment cập nhật Note/portfolio projection; Blockchain ghi proof; Notification gửi thông báo.

**Invariant:** tổng tiền thu = tổng fee + interest/penalty + principal + rounding remainder được ghi rõ; không làm mất tiền do rounding.

**Idempotency:** `repaymentInstructionId` hoặc provider transaction ID unique.

**Failure:** thiếu tiền → kết quả partial/rejected theo policy, không giả completed; lỗi projection sau ledger commit → event retry/rebuild, không chạy lại collection.

## F07 — Tất toán sớm hoặc tái cơ cấu

**Trigger:** borrower yêu cầu hoặc admin khởi tạo theo policy. **Orchestrator:** Loan.

1. Loan tính quote có `quoteId`, expiry và rule version.
2. Với tất toán: Payment collect theo quote, Loan đóng schedule/loan sau event thành công, Investment cập nhật Notes.
3. Với tái cơ cấu: Loan thu thập approval/consent cần thiết, tạo schedule version mới; lịch sử cũ bất biến.
4. Loan phát `LoanSettledEarly` hoặc `LoanRestructured`; Blockchain/Notification consume.

**Idempotency:** command theo `requestId`; payment theo `quoteId`; chỉ một active quote/transition theo policy.

**Failure:** quote hết hạn hoặc version thay đổi → reject và tính lại; Payment thất bại → Loan không đổi schedule/state; consumer phụ trợ lỗi → retry từ outbox.

## F08 — Audit Blockchain và đối chiếu

**Trigger:** domain event cần proof hoặc scheduled reconciliation. **Orchestrator:** Blockchain.

1. Blockchain canonicalize payload được phép và tính hash/version.
2. Submit Fabric idempotently, lưu transaction/block reference và confirmation state.
3. Reconciliation định kỳ lấy record/hash qua API/event contract của owner, không đọc DB chéo.
4. Sai lệch tạo incident/audit result; MUST NOT tự sửa dữ liệu nguồn.

**Idempotency:** unique theo `sourceEventId + proofType + schemaVersion`.

**Failure:** Fabric unavailable → retry có backoff, DLT và cảnh báo; mismatch → điều tra/audit workflow, không overwrite bằng giá trị “khớp”.

## F09 — Notification từ domain event

**Trigger:** event nghiệp vụ đã commit. **Orchestrator:** Notification cho delivery.

1. Notification resolve template/version và recipient reference.
2. Áp preference/policy, tạo delivery theo từng channel.
3. Gửi, lưu trạng thái/attempt/provider reference; retry lỗi tạm thời.
4. Permanent failure chuyển terminal/DLT và cảnh báo theo mức độ.

**Idempotency:** `sourceEventId + recipientId + channel + templateVersion`.

**Failure:** gửi lỗi không rollback nghiệp vụ nguồn; MUST NOT log payload chứa PII hoặc secret.

## Checklist khi thêm flow

- SoR và state authority đã khớp `07-service-boundaries.md`.
- Orchestrator và participant rõ ràng; không có orchestration vòng tròn.
- API/event version, producer, consumer, partition key và PII classification rõ.
- Local transaction/outbox/processed event đã xác định.
- Idempotency key, unique constraint và duplicate response rõ.
- Timeout, retryable/non-retryable error, DLT và compensation rõ.
- Có test happy path, duplicate, timeout, concurrent request, restart và compensation phù hợp rủi ro.

