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

**CURRENT STATE (2026-08-22):** đã triển khai xác minh trên `UserProfile`, chưa có KYC application entity và chưa phát event; Notification chưa nhận kết quả eKYC. Luồng đã **bỏ xác minh khuôn mặt/liveness** theo quyết định thiết kế — bằng chứng định danh là ảnh giấy tờ hai mặt:

1. Client gửi `POST /api/v1/users/profile/ekyc-verify` gồm ảnh mặt trước và mặt sau CCCD (`cccdFrontBase64`, `cccdBackBase64`).
2. User chạy tuần tự và dừng ở bước đầu tiên trượt: rate limit → `POST /api/v1/ai/ekyc/ocr` trên ảnh mặt trước → đối chiếu HMAC số CCCD với `idNumberHash`. Hồ sơ chưa có số CCCD thì lấy số từ OCR điền vào sau khi kiểm tra số đó chưa thuộc tài khoản khác (`ID_TAKEN`).
3. Ảnh mặt sau không OCR (model chỉ đọc mặt trước) — nộp kèm làm bằng chứng cầm thẻ đầy đủ, phục vụ đối soát tay khi có nghi vấn.
4. User áp policy: đạt → `VERIFIED` (`documentVerified = true`); các trường hợp còn lại giữ nguyên trạng thái và trả `resultCode` (`OCR_FAILED`/`ID_MISMATCH`/`ID_TAKEN`/`RATE_LIMITED`/`AI_UNAVAILABLE`) để người dùng chụp lại.

**State authority:** AI không giữ trạng thái; rate limit nằm ở User (Redis). Số CCCD là điều kiện đối chiếu duy nhất; họ tên và ngày sinh chỉ sinh cảnh báo `ocrWarnings`. Phần face-match/liveness phía AI đã xoá hẳn (2026-08-22); AI chỉ còn `/ocr` cho eKYC — engine duy nhất là Gemini vision, bắt buộc cấu hình `GEMINI_API_KEY` (thiếu key endpoint trả lỗi và User hiển thị AI_UNAVAILABLE).

**Chống lạm dụng:** rate limit 1 request/10 giây cho mỗi người dùng; mỗi CCCD chỉ gắn với một tài khoản toàn hệ thống.

**Idempotency:** `kycApplicationId + analysisType + modelVersion`. Hiện tại xác minh là thao tác trạng thái trên `UserProfile`, gọi lại khi đã `VERIFIED` trả kết quả cũ mà không gọi AI.

**Failure:** AI timeout → KYC giữ `PROCESSING`/`RETRY_PENDING`; retry có giới hạn, sau đó manual review/DLT. MUST NOT tự đánh dấu verified khi AI lỗi. Hiện tại AI lỗi trả `AI_UNAVAILABLE` và giữ nguyên trạng thái hồ sơ.

## F00 — Đồng bộ FINORA Product sang Fineract

**Trigger:** admin yêu cầu kích hoạt Product. **Orchestrator:** Loan.

1. Loan validate fixed rate, amount/term range và repayment method.
2. Loan tạo durable Fineract command với Product/version và external ID.
3. Loan đọc template/config hợp lệ của tenant rồi tạo core loan product qua REST.
4. Loan lưu `fineractProductId`, config/mapping version và sync status.
5. Chỉ mapping `SYNCED` mới cho Product chuyển `ACTIVE`.

**Idempotency:** unique theo `loanProductId + productVersion + commandType`; retry không tạo hai core products logic.

**Failure:** Fineract lỗi → Product giữ `DRAFT`, sync `FAILED/RETRY_PENDING`; không kích hoạt và không tự tính schedule bằng engine khác. Functional readiness MUST gọi API có xác thực thay vì chỉ kiểm tra cổng TCP. Product sync và schedule preview MUST dùng circuit breaker độc lập; validation/authentication 4xx MUST NOT làm mở circuit.

## F02 — Tạo hồ sơ và chấm điểm tín dụng

**Trigger:** người vay đã đủ điều kiện gửi hồ sơ. **Orchestrator:** Loan.

1. Borrower gọi preview; Loan dùng Fineract `calculateLoanSchedule`, chuẩn hóa schedule để UI hiển thị nhưng chưa tạo Application.
2. Khi borrower submit với idempotency key, Loan tạo thẳng Application `SUBMITTED` cùng immutable financial/Product/disclosure/Fineract-calculation snapshot; backend không tạo Draft.
3. Loan xác minh identity/KYC qua provider contract. Local development MAY dùng một mock provider tập trung có source rõ.
4. Loan chuyển `SCORING`, gọi AI credit v10 bằng immutable feature snapshot; `int_rate` lấy fixed Product rate và `installment` lấy Fineract calculation snapshot.
5. `delinq_2yrs/pub_rec` lấy từ `BorrowerCreditProfile` projection nội bộ; người vay không tự khai. Proxy `pub_rec` MUST mang source/policy version và MUST NOT được mô tả là CIC/hồ sơ pháp lý thật.
6. AI trả PD/score, grade, recommendation/reason và model/rule version. Nếu response còn `suggested_rate`, Loan MUST bỏ qua, không lưu vì pricing thuộc Product/Loan.
7. Loan lưu scoring snapshot, áp policy nghiệp vụ và chuyển `PENDING_REVIEW` hoặc `REJECTED`.
8. Loan phát `LoanApplicationScored`/`LoanApplicationRejected`; Notification consume nếu cần.

**Idempotency:** `loanApplicationId + scoringAttempt/version`; cùng feature snapshot và model version phải trả cùng artifact reference.

**Failure:** Fineract chưa functional-ready → preview trả lỗi phụ thuộc có thể thử lại, không tạo Application; UI giữ lựa chọn đã nhập và hướng dẫn thử lại sau. AI lỗi → giữ `SCORING_RETRY_PENDING`, không tạo điểm mặc định. Retry hết hạn → manual review hoặc failure state có audit.

## F03 — Duyệt, ký hợp đồng và đưa khoản vay lên sàn

**Trigger:** admin/Loan policy xử lý hồ sơ đang `PENDING_REVIEW`. **Orchestrator:** Loan.

1. Loan kiểm tra KYC/scoring snapshot, policy và optimistic version.
2. Loan từ chối với reason hoặc tạo một `LoanContract PENDING_SIGNATURE` chứa exact amount/term/fixed Product rate/repayment method/Fineract schedule snapshot/fee/expiry; AI không định giá.
3. Borrower đọc và ký hoặc từ chối chính LoanContract. Không tạo `LoanOffer` hoặc bước accept riêng. Contract `SIGNED` bất biến nhưng chỉ `EFFECTIVE` sau giải ngân.
4. Chỉ sau Contract signed, Loan tạo listing intent, chuyển state phù hợp sang `ON_MARKET` và phát `LoanListed` chứa dữ liệu market tối thiểu, không chứa PII.
5. Investment consume idempotently, tạo market listing projection.

**Idempotency:** admin/sign command dùng key và optimistic version; một Application tối đa một Contract trong MVP; Investment unique theo `loanId + listingVersion`.

**Failure:** Contract hết hạn/decline → không listing; sign cạnh tranh chỉ một commit; Investment chưa tạo projection → outbox retry/DLT, Loan không publish event mới trùng để “chữa” lỗi. Listing projection phải rebuild được từ event/source API có kiểm soát.

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
3. Loan bảo đảm Fineract client mapping, submit core loan bằng `contractNumber` làm external ID và approve core loan idempotently.
4. Loan yêu cầu Payment capture held funds và giải ngân cho borrower.
5. Payment tạo ledger entries, phát `DisbursementCompleted` hoặc `DisbursementFailed`.
6. Sau khi tiền đã chuyển, Loan ghi disbursement vào Fineract; response/event cập nhật core projection và official schedule.
7. Loan yêu cầu/đợi Investment activate Notes theo kết quả tài chính.
8. Loan phát audit event để Blockchain ghi proof; Blockchain phản hồi/reference bất đồng bộ.
9. Loan chuyển `ACTIVE` và Contract `EFFECTIVE` khi các bước bắt buộc hoàn tất; Notification gửi kết quả.

**Correlation:** mọi command/event mang `sagaId`, `loanId`, `step`, `attempt`, `eventId`.

**Failure/compensation:** finalize/core-approve lỗi → chưa capture tiền; capture/transfer lỗi → Payment tự bảo toàn ledger và Loan ra lệnh release/unfinalize thích hợp; tiền đã chuyển nhưng Fineract disburse lỗi → `CORE_DISBURSEMENT_REPAIR_REQUIRED`, retry/reconcile, không đánh ACTIVE giả hoặc tự đảo tiền; Note activation lỗi sau disbursement → Saga retry/repair; Fabric lỗi → retry/DLT/reconciliation và không rollback giải ngân.

**Restart:** Saga state phải durable; restart tiếp tục từ bước cuối đã xác nhận, không chạy lại side effect không idempotent.

## F06 — Thu nợ và phân bổ

**Trigger:** borrower thanh toán hoặc auto-debit đến hạn. **Orchestrator:** Loan cho nghĩa vụ kỳ hạn; Payment cho execution tài chính.

1. Loan cung cấp core loan/schedule reference; Fineract là nguồn nghĩa vụ và balance chính thức.
2. Payment collect tiền idempotently và commit FINORA ledger/provider reference.
3. Sau thu thành công, Payment ghi repayment vào Fineract bằng external transaction reference; Fineract phân bổ borrower payment vào principal/interest/fee/penalty và trả core transaction/breakdown.
4. Payment lấy ownership snapshot/version từ Investment và phân bổ investor wallets dựa trên kết quả core hợp lệ cùng policy FINORA.
5. Payment phát `RepaymentDistributed` với breakdown/reference tối thiểu.
6. Loan cập nhật servicing projection từ Fineract response/reliable event; Investment cập nhật Note/portfolio; Blockchain ghi proof; Notification gửi thông báo.

**Invariant:** Payment ledger là nguồn chuyển tiền; Fineract là nguồn allocation/balance. Tổng tiền thu phải đối chiếu với core transaction và tổng phân bổ investor + platform; mismatch tạo reconciliation incident, không thu hoặc ghi repayment lần hai.

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
