# Luồng nghiệp vụ xuyên service

Mỗi flow mới hoặc thay đổi MUST xác định trigger, orchestrator, contract, state authority, idempotency, timeout, failure và compensation. Event name dưới đây là contract định hướng; khi triển khai thật phải đăng ký topic/version trong `05-registry.md` và đặc tả payload.

## Quy tắc chung

- Orchestrator sở hữu tiến trình, không đồng nghĩa sở hữu state của service tham gia.
- Mỗi service chỉ commit local transaction của mình; không giữ DB transaction khi chờ network.
- Producer ghi outbox cùng transaction với state; consumer ghi `processed_events` cùng transaction với side effect.
- `eventId` chống xử lý event trùng; API side effect dùng `idempotencyKey`; Saga dùng `sagaId` xuyên suốt.
- Timeout/retry chỉ cho lỗi tạm thời và operation idempotent. Business rejection không retry.
- Compensation là nghiệp vụ riêng, idempotent và audit được; không xóa lịch sử để giả lập rollback.
- Lời gọi service → service không có JWT người dùng (worker, đăng ký/quên mật khẩu) dùng client credentials của chính
  service gọi; service nhận kiểm client role, không tin mạng nội bộ (D34). Token service chỉ gắn cho đúng client gọi
  service FINORA, không gửi tới CIC hay dịch vụ bên ngoài.

## F01 — Định danh điện tử

**Trigger:** người dùng nộp hồ sơ eKYC. **Orchestrator:** User.

1. User tạo KYC application `SUBMITTED`, lưu metadata tài liệu an toàn.
2. User gọi AI eKYC với request ID/idempotency key.
3. AI trả OCR, face/liveness/forgery result và model/version.
4. User áp policy, chuyển `VERIFIED`, `REJECTED` hoặc `MANUAL_REVIEW`.
5. User phát `KycVerified`, `KycRejected` hoặc `KycManualReviewRequired`.
6. Notification gửi kết quả theo event.

**CURRENT STATE (2026-08-22):** đã triển khai xác minh trên `UserProfile`, chưa có KYC application entity và chưa phát event; Notification chưa nhận kết quả eKYC. Luồng đã **bỏ xác minh khuôn mặt/liveness** theo quyết định thiết kế — bằng chứng định danh là ảnh giấy tờ hai mặt:

eKYC là chức năng tuỳ chọn mở từ tab Hồ sơ (không ép sau đăng nhập) và chạy hai bước — quét ra bản nháp, người dùng xác nhận mới lưu:

1. **Quét:** client gửi `POST /api/v1/users/profile/ekyc-verify` gồm ảnh mặt trước và mặt sau CCCD. User chạy tuần tự và dừng ở bước đầu tiên trượt: rate limit → `POST /api/v1/ai/ekyc/ocr` trên ảnh mặt trước → đối chiếu HMAC số CCCD (`ID_MISMATCH` với hồ sơ có số cũ, `ID_TAKEN` nếu số thuộc tài khoản khác). Đạt thì cất kết quả OCR vào **bản nháp Redis (TTL 10 phút)** và trả `DRAFT_READY` kèm bản nháp — **hồ sơ chưa được ghi**.
2. Ảnh mặt sau không OCR (model chỉ đọc mặt trước) — nộp kèm làm bằng chứng cầm thẻ đầy đủ, phục vụ đối soát tay khi có nghi vấn.
3. **Xác nhận:** người dùng soát bản nháp trên client; sai thì quét lại, đúng thì gọi `POST /api/v1/users/profile/ekyc-confirm` (không mang dữ liệu — bản nháp đọc từ Redis để client không sửa được thông tin OCR). User kiểm tra trùng lần cuối, ghi bản nháp vào hồ sơ, chuyển `VERIFIED` (`documentVerified = true`) và tạo `user_cic_mapping_tasks` trong cùng transaction; chỉ xoá nháp Redis sau khi DB commit. Nháp hết hạn trả `DRAFT_EXPIRED`.
4. **Ánh xạ CIC:** worker User lấy task bằng lease, giải mã CCCD just-in-time rồi gọi API nội bộ CIC với `keycloakUserId` làm `borrowerId`. CIC tắt không rollback eKYC; task retry lũy tiến và request lặp được CIC xử lý idempotent. CCCD không đi qua Kafka/log/bảng task.
4. Các trường hợp trượt giữ nguyên trạng thái hồ sơ và trả `resultCode` (`OCR_FAILED`/`ID_MISMATCH`/`ID_TAKEN`/`RATE_LIMITED`/`AI_UNAVAILABLE`/`DRAFT_EXPIRED`) để client hướng dẫn chụp lại.

**State authority:** AI không giữ trạng thái; rate limit nằm ở User (Redis). Số CCCD là điều kiện đối chiếu duy nhất; họ tên và ngày sinh chỉ sinh cảnh báo `ocrWarnings`. Phần face-match/liveness phía AI đã xoá hẳn (2026-08-22); AI chỉ còn `/ocr` cho eKYC — engine duy nhất là Gemini vision, bắt buộc cấu hình `GEMINI_API_KEY` (thiếu key endpoint trả lỗi và User hiển thị AI_UNAVAILABLE).

**Chống lạm dụng:** rate limit 1 request/10 giây cho mỗi người dùng; mỗi CCCD chỉ gắn với một tài khoản toàn hệ thống.

**Idempotency:** `kycApplicationId + analysisType + modelVersion`. Hiện tại xác minh là thao tác trạng thái trên `UserProfile`, gọi lại khi đã `VERIFIED` trả kết quả cũ mà không gọi AI.

**Failure:** AI timeout → KYC giữ `PROCESSING`/`RETRY_PENDING`; retry có giới hạn, sau đó manual review/DLT. MUST NOT tự đánh dấu verified khi AI lỗi. Hiện tại AI lỗi trả `AI_UNAVAILABLE` và giữ nguyên trạng thái hồ sơ.

## F00 — Đồng bộ FINORA Product sang Fineract

**Trigger:** admin yêu cầu kích hoạt Product. **Orchestrator:** Loan.

1. Loan validate min/base/max rate, trần 20%, amount/term range (tối đa 24 tháng) và repayment method.
2. Loan tạo durable Fineract command với Product/version và external ID.
3. Loan đọc template/config hợp lệ của tenant rồi tạo core loan product qua REST.
4. Loan lưu `fineractProductId`, config/mapping version và sync status.
5. Chỉ mapping `SYNCED` mới cho Product chuyển `ACTIVE`.

**Idempotency:** unique theo `loanProductId + productVersion + commandType`; retry không tạo hai core products logic.

**Failure:** Fineract lỗi → Product giữ `DRAFT`, sync `FAILED/RETRY_PENDING`; không kích hoạt và không tự tính schedule bằng engine khác. Functional readiness MUST gọi API có xác thực thay vì chỉ kiểm tra cổng TCP. Product sync và schedule preview MUST dùng circuit breaker độc lập; validation/authentication 4xx MUST NOT làm mở circuit.

## F02 — Tạo hồ sơ và chấm điểm tín dụng

**Trigger:** người vay đã đủ điều kiện gửi hồ sơ. **Orchestrator:** Loan.
**Legal gates:** [`LEGAL-RATE-01`, `LEGAL-DISCLOSURE-01`, `LEGAL-AI-01`, `LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md).

1. Borrower gọi preview; Loan dùng Fineract `calculateLoanSchedule`, chuẩn hóa schedule để UI hiển thị nhưng chưa tạo Application.
2. Khi borrower submit với idempotency key, Loan tạo thẳng Application `SUBMITTED` cùng immutable financial/Product/disclosure/Fineract-calculation snapshot; backend không tạo Draft.
3. Loan xác minh identity/KYC qua provider contract. Local development MAY dùng một mock provider tập trung có source rõ.
4. Loan chuyển `SCORING`, gọi AI credit v17 `/api/v1/ai/credit/explain` bằng immutable snapshot; `int_rate` là base rate và `installment` lấy từ Fineract `SUBMISSION_SCORING` snapshot.
5. AI v17 không nhận `delinq_2yrs/pub_rec/effective_apr/suggested_rate`; `BorrowerCreditProfile` vẫn là evidence nội bộ. `so_cccd` chỉ gửi khi User contract hợp lệ, Loan không lưu CCCD thô.
6. AI trả PD/score, grade, decision, explanation và model/decision-policy version.
7. Loan áp grade pricing có version, clamp final rate trong Product min/max và trần 20%. Principal/term không tự đổi.
8. Nếu decision không phải `REJECTED`, Loan gọi Fineract ngoài transaction để tạo snapshot `CONTRACT` theo final rate.
9. Loan lưu toàn bộ evidence và chuyển `APPROVED/PENDING_REVIEW/REJECTED`. Với `APPROVED`, Loan so sánh exact terms: không bất lợi hơn thì tiếp tục theo disclosure đã chấp thuận; bất lợi hơn thì đặt `termsConfirmation=PENDING` và chờ borrower phản hồi.
10. Sau khi gate điều khoản đã `AUTO_AUTHORIZED` hoặc `ACCEPTED`, Loan luôn yêu cầu Investment huy động vốn; không tạo Contract/PDF sớm và không giả lập nhà đầu tư/chữ ký bên cho vay.
11. Loan phát event tương ứng qua transactional outbox sau local commit.

**Idempotency:** `loanApplicationId + scoringAttempt/version`; cùng feature snapshot và model version phải trả cùng artifact reference.

**Failure:** Fineract chưa functional-ready → preview trả lỗi phụ thuộc có thể thử lại, không tạo Application; UI giữ lựa chọn đã nhập và hướng dẫn thử lại sau. AI lỗi → giữ `SCORING_RETRY_PENDING`, không tạo điểm mặc định. Retry hết hạn → manual review hoặc failure state có audit.

**CURRENT STATE (2026-10-17, Wave 2):** trước khi ghi hồ sơ, Loan kiểm hạn mức dư nợ tại FINORA (D5,
[`LEGAL-LIMIT-01`](../../docs/LEGAL-COMPLIANCE.md)): gốc còn lại của khoản đang phục vụ + hồ sơ còn có thể thành khoản
vay + hồ sơ mới ≤ `finora.loan.borrower-exposure.max-outstanding` (policy demo 100.000.000), advisory lock theo
borrower trong transaction ghi hồ sơ; vượt thì `422 BORROWER_EXPOSURE_LIMIT_EXCEEDED`. Ngày nộp hồ sơ gửi AI theo ngày
nghiệp vụ `Asia/Ho_Chi_Minh`. Bước 3 (W2-KYC, D4): trước Fineract, Loan chuyển tiếp token của chính người vay tới
`GET /api/v1/users/me/kyc-summary` của User (chỉ trạng thái eKYC và tuổi, không PII); chỉ `VERIFIED` được nộp
(`409 BORROWER_KYC_REQUIRED`), User lỗi hoặc trả dữ liệu lệch người thì `503 USER_PROFILE_UNAVAILABLE`. Snapshot
eKYC/tuổi lưu trên hồ sơ (Loan V29) để worker kiểm điều kiện và AI dùng lại; provider `mock` chỉ còn cho test/E2E.

## F03 — Duyệt, ký hợp đồng và đưa khoản vay lên sàn

**Trigger:** AI auto-approve hoặc admin xử lý hồ sơ `PENDING_REVIEW`. **Orchestrator:** Loan.
**Legal gates:** [`LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md).

1. Loan kiểm tra KYC/scoring snapshot, policy và optimistic version.
2. Loan từ chối với reason hoặc chốt exact final terms từ hai schedule snapshots. Không tạo `LoanOffer`/bảng sao chép điều khoản.
3. Nếu exact terms không bất lợi hơn, disclosure lúc submit cho phép tự tiếp tục. Nếu bất lợi hơn, borrower phải accept/decline trên Application theo version/hash/expiry; decline/expiry không tạo Contract.
4. Loan ghi outbox `LoanFundingRequested.v1` sau
   `AUTO_AUTHORIZED/ACCEPTED`; Investment tạo projection listing idempotent, xác định lender và phát
   `LoanFullyFunded.v2` cùng allocation bất biến. Loan chỉ lập Contract/PDF chung sau event này.
   Trong lúc gọi vốn, nhà đầu tư đọc hồ sơ người vay ẩn danh bằng REST Loan theo `applicationNumber`
   của listing ([`LEGAL-DISCLOSURE-02`](../../docs/LEGAL-COMPLIANCE.md)); không có event hay bản sao ở Investment.
5. Nhà đầu tư ký cùng document hash trước; khi đủ chữ ký lender, Loan yêu cầu borrower ký một lần.
   Loan phát `LoanContractActivated.v1` sau khi mọi party đã ký; event này không đồng nghĩa đã giải ngân.

**Contract chuẩn:** xem
[`docs/integrations/LOAN-INVESTMENT-EVENTS.md`](../../docs/integrations/LOAN-INVESTMENT-EVENTS.md).

**CURRENT STATE (2026-10-17):** Loan ghi event vòng đời vào transactional outbox cùng transaction với aggregate/history. Relay có lease, claim token, retry giới hạn và dead-letter; Kafka adapter có broker acknowledgement, partition key theo aggregate, trace headers và route cấu hình theo exact event type + version trong `application.yml`. Publisher/consumer luôn bật (không còn cờ tắt). Chỉ event ghi bằng `recordForPublication` mới đi Kafka; event chỉ dùng nội bộ ghi bằng `record`. Investment consume `LoanFundingRequested`, `InvestorSignatureRequested`, `LoanContractActivated`, `LoanDisbursed`, `LoanFundingCancelled` và các event servicing.

**Idempotency:** admin/sign command dùng key và optimistic version; một Application tối đa một Contract trong MVP; Investment unique theo `loanId + listingVersion`.

**Failure:** decline/expiry điều khoản trước gọi vốn → không listing. Contract nhiều bên chỉ có sau khi đủ vốn, nên khi Contract bị `DECLINED`/`EXPIRED` thì Loan chuyển funding `FULLY_FUNDED → CANCELLED` và ghi `LoanFundingCancelled.v1` cùng transaction; Investment đối chiếu allocation, hủy commitment/order, listing `CANCELLED` và worker nhả hold. Không dùng event này khi đã có commitment `FINALIZED`. Sign cạnh tranh chỉ một commit; Investment chưa tạo projection → outbox retry/DLT, Loan không publish event mới trùng để “chữa” lỗi. Listing projection phải rebuild được từ event/source API có kiểm soát.

## F04 — Đặt vốn và giữ tiền

**Trigger:** investor đặt lệnh. **Orchestrator:** Investment.

1. Investment validate market/order và tạo order `PENDING_FUNDS`.
2. Investment yêu cầu Payment hold với `orderId` và idempotency key.
3. Payment khóa/cập nhật wallet an toàn, tạo ledger + hold transaction, trả `paymentTransactionId`.
4. Investment tạo commitment, chuyển order `COMMITTED`, phát `InvestmentCommitted`.
5. Nếu tổng valid commitments đạt target, Investment khóa allocation, ghi outbox và phát
   `LoanFullyFunded.v2` đúng một lần. Event mang logical investor/commitment ID và amount/share,
   không mang PII.
6. Loan consume, đối chiếu exact amount/hash/version, lưu funding snapshot và tự mở bước tạo
   Contract nhiều bên. `FUNDED` không có nghĩa đã giải ngân.

**Idempotency:** `investmentOrderId` cho hold; unique commitment theo order; funded event unique theo `loanId + fundingRound`.

**Failure/compensation:** Payment từ chối → order `REJECTED`; lỗi tạo commitment sau hold → Investment yêu cầu Payment release bằng reference hold; release được retry idempotently. Concurrent order MUST NOT làm overfund hoặc âm ví. Đã đủ vốn nhưng Loan phát `LoanFundingCancelled.v1` (Contract `DECLINED`/`EXPIRED` hoặc giải ngân thất bại trước capture) → Investment khóa listing, hủy commitment `ACTIVE` và order với mã `LOAN_FUNDING_CANCELLED`, listing `CANCELLED`; worker nhả hold như nhánh hết hạn.

**Hết hạn gọi vốn:** listing `OPEN` quá `fundingClosesAt` → Investment (cùng transaction, khóa listing) chuyển
`CLOSED`, huỷ commitment/order còn `ACTIVE`, trừ tiến độ và ghi outbox `LoanFundingExpired.v1`
(`finora.investment.loan-funding-expired`); Loan chuyển `fundingStatus = EXPIRED`, không lập Contract. Hold
không nhả trong transaction đó mà để worker gọi `release` tới khi Payment xác nhận.

**CURRENT STATE (2026-10-11):** `InvestmentMaintenanceWorker` (30s) đóng listing hết hạn, làm tiếp order kẹt
`PENDING_FUNDS` quá `pending-funds-timeout` (gọi lại `hold` cùng `orderReference` rồi chốt commitment), nhả
hold của order đã kết thúc có `payment_released_at IS NULL`. Lệnh lấp đúng phần còn thiếu được nhận dù dưới
mức tối thiểu. Đặt lệnh yêu cầu vai trò INVESTOR, claim `ekyc_status=VERIFIED` (mapper `ekyc-status` của
`finora-user-client`, finora-user ghi attribute khi eKYC và mỗi lần đăng nhập) và người đặt khác `borrowerId`
mà `LoanFundingRequested.v1` mang theo (trường bổ sung).

**CURRENT STATE (2026-10-17, Wave 2):** lệnh sơ cấp mới phải mang `riskAcknowledgementVersion` khớp
`finora.investment.risk-disclosure.version` (D6, [`LEGAL-DISCLOSURE-02`](../../docs/LEGAL-COMPLIANCE.md)); thiếu →
`400 RISK_ACKNOWLEDGEMENT_REQUIRED`, lệch → `409 RISK_DISCLOSURE_OUTDATED`, kiểm trước khi ghi lệnh hay giữ tiền.
Investment lưu version + thời điểm trên `investment_orders`. Gửi lại cùng `Idempotency-Key` của lệnh đã tạo trả lệnh
cũ, không kiểm lại version. Lệnh Auto-Invest đi qua `placeOrderFor` nên không mang xác nhận từng lệnh.

**CURRENT STATE (2026-10-17, Wave 2D, R3):** lệnh góp vốn đối soát với khoản giữ của Payment, không sửa DB Payment:

- Payment `GET /api/v1/transactions/holds?orderReference=` đọc khoản giữ theo mã lệnh, không đổi gì (service account
  `payment:hold:on_behalf` hoặc chính nhà đầu tư; khoản của người khác trả 404). Nhả khoản đã vào giải ngân trả
  `409 PAYMENT_HOLD_NOT_RELEASABLE` thay vì 500.
- Investment đọc `status` của mọi lần giữ tiền: chỉ `HELD` mới chốt commitment; `RELEASED` → lệnh `REJECTED`
  `PAYMENT_HOLD_ALREADY_RELEASED`; `CAPTURE_PENDING/CAPTURED` → `REJECTED` `PAYMENT_HOLD_NOT_HELD` và dừng cho vận hành.
  Chỉ 400/409/422 là từ chối chắc chắn; 401/403/404/408/429/5xx giữ lệnh `PENDING_FUNDS` để đối soát.
- Worker làm tiếp lệnh chờ hỏi `GET` trước: đang giữ đúng số tiền thì chốt, chưa có mới gửi lại lời gọi giữ tiền.
- Nhả tiền giữ (người dùng hủy, bị từ chối sau khi giữ) chạy sau khi transaction của lệnh commit; lỗi thì thử lại
  theo lịch lùi dần (30 giây tới 1 giờ), lỗi chắc chắn thì dừng (`release_blocked_at`). Investment V18; admin
  `GET /api/v1/investments/admin/order-incidents`, `POST /{orderReference}/retry`; web hiện trong tab "Sự cố giao dịch".

**CURRENT STATE (2026-09-28):** Payment đã có API wallet/top-up/hold/release/transfer, immutable balanced ledger và capture đúng từng `paymentHoldReference`. Investment mặc định gọi Payment qua HTTP và truyền hold reference trong `LoanFullyFunded.v2`; stub chỉ còn dùng trong test. Nạp tiền hỗ trợ provider `mock` và adapter ZaloPay sandbox có HMAC callback; disbursement hiện dùng provider `mock`, còn ZaloPay disbursement fail-closed vì chưa có quyền API chuyển tiền.

## F04b — Sổ lệnh chợ thứ cấp Notes

**Trigger:** nhà đầu tư đặt hoặc huỷ lệnh Ask/Bid. **Orchestrator:** Investment. **Plan:**
[`INV-E2`](../../finora-investment/plans/INV-E2-order-book-matching.md).

1. Lệnh mua: Investment ghi order `PENDING_FUNDS`, gọi Payment `hold` ngoài transaction (idempotent theo
   `orderReference`), rồi khóa sổ, cho lệnh vào sổ và khớp. Lệnh bán: Investment khóa Note của người bán,
   vào sổ và khớp trong một transaction.
2. Khớp chỉ ghi database Investment: đổi chủ Note, ghi trade `PENDING` (đóng vai outbox) và lịch sử chuyển
   nhượng trong cùng transaction. Không gọi mạng khi đang khóa sổ.
3. Worker Investment gọi Payment `settleFromHold` theo `tradeReference` để chuyển từ tiền giữ của người mua
   sang người bán và thu phí; khi lệnh mua kết thúc và mọi trade đã thanh toán thì gọi `release` nhả phần còn lại.

**State authority:** Investment cho lệnh, trade, Note ownership; Payment cho tiền giữ và ledger.

**Idempotency:** `(investorId, Idempotency-Key)` cho đặt lệnh; `orderReference` cho hold/release;
`tradeReference` cho thanh toán.

**Failure:** hold từ chối → order `REJECTED`; hold không chắc chắn → giữ `PENDING_FUNDS`, client gửi lại cùng
khóa; quá `pending-funds-timeout` mà chưa gửi lại thì worker gọi lại `hold` (idempotent) để lấy reference rồi
chuyển `REJECTED/PAYMENT_TIMEOUT` và nhả. Thanh toán lỗi tạm thời → trade `PENDING`, retry backoff; Payment
từ chối hẳn → `FAILED` và đối soát tay, không đảo chủ Note. Khoản vay chuyển nợ xấu (nhóm 3–5) → Note
`DEFAULTED`; lệnh mua chưa xác nhận cảnh báo bị huỷ `DEFAULT_NOT_ACKNOWLEDGED` khi chạm Note nợ xấu. Mọi
Note đã `CLOSED` → worker huỷ lệnh còn trong sổ (`BOOK_CLOSED`) và nhả tiền giữ.

**CURRENT STATE (2026-10-02):** cả hai phía đã triển khai. Payment có
`POST /transactions/holds/{holdReference}/settlements` (service account `payment:hold:on_behalf`) và `release`
chỉ nhả phần còn giữ — xem [`INVESTMENT-PAYMENT-ORDER-BOOK.md`](../../docs/integrations/INVESTMENT-PAYMENT-ORDER-BOOK.md).
Phần Payment do Hải viết theo yêu cầu, owner Payment (Thái) cần review trước merge.

## F05 — Saga giải ngân

**Trigger:** Loan ở `FUNDED` và đủ điều kiện/chữ ký. **Orchestrator:** Loan.

1. Khi tất cả các bên ký, Contract chuyển `EFFECTIVE`; trạng thái này chỉ nói hợp đồng có hiệu lực, chưa nói tiền đã chuyển.
2. Loan tạo durable `DisbursementSaga`, phát `DisbursementRequested` và hiển thị `DISBURSING`.
3. Payment capture các hold theo allocation snapshot và yêu cầu provider giải ngân cho borrower bằng cùng `sagaId`/idempotency key. Với chế độ ví FINORA đang dùng cho demo, sau provider success Payment ghi thêm bút toán cân bằng `clearing → borrower AVAILABLE`; dữ liệu legacy thiếu bút toán này được bù idempotent theo `sagaId`, không sửa/xóa ledger cũ.
4. Payment lưu reference/kết quả tài chính rồi phát `DisbursementCompleted` hoặc `DisbursementFailed` qua transactional outbox.
5. Sau khi tiền đã chuyển, Loan reconcile theo `paymentReference`, bảo đảm Fineract Client/Loan bằng external ID, approve và ghi disbursement core idempotently.
6. Loan chuyển saga `COMPLETED`, khoản vay nghiệp vụ thành `ACTIVE`, rồi phát `LoanDisbursed`.
7. Investment consume `LoanDisbursed`, chuyển commitment `ACTIVE -> FINALIZED` và phát hành Notes idempotently.
8. Loan phát audit event để Blockchain ghi proof; Blockchain phản hồi/reference bất đồng bộ.
9. Notification gửi kết quả; lỗi thông báo/proof không đảo tiền hoặc hạ trạng thái Contract.

**Correlation:** mọi command/event mang `sagaId`, `loanId`, `step`, `attempt`, `eventId`.

**Failure/compensation:** transfer lỗi trước khi provider xác nhận → Payment retry hoặc phát failed, không tạo Note. `DisbursementFailed` đến khi saga còn `WAITING_PAYMENT` (Payment đã trả hold về `HELD`, chưa capture) → saga `PAYMENT_FAILED`, Loan funding `CANCELLED` và `LoanFundingCancelled.v1` (`DISBURSEMENT_FAILED`) để Investment hủy commitment và nhả tiền cho nhà đầu tư; event trùng không phát lần hai. Tiền đã chuyển nhưng Fineract disburse lỗi → `REPAIR_REQUIRED`, retry/reconcile và tuyệt đối không chuyển tiền lần hai; hoàn tiền sau khi đã capture là thao tác đối soát thủ công thuộc R2, không đi qua event bù trừ. Note activation lỗi sau disbursement → consumer retry/DLT/repair, không đảo ledger; Fabric/Notification lỗi không rollback giải ngân. Contract đã đủ chữ ký vẫn `EFFECTIVE`; lỗi giải ngân được xử lý trên Saga riêng và client hiển thị theo funding `CANCELLED`.

**Restart:** Saga state phải durable; restart tiếp tục từ bước cuối đã xác nhận, không chạy lại side effect không idempotent.

**CURRENT STATE (2026-10-17, Wave 2):** ngày nghiệp vụ của Loan/Payment là `Asia/Ho_Chi_Minh`
(`finora.business-time-zone`). Booking Fineract dùng một ngày giải ngân thực tế cho submitted/approved/expected/actual
disbursement, đúng điều khoản “lịch chính thức tính từ ngày giải ngân thực tế”. Bước `CORE_BOOKING` có lease
(`core-booking-lease`, mặc định 5 phút) và claim token theo lần thử: worker chết thì worker khác nhận lại và reconcile
Fineract theo externalId, kết quả muộn của lần thử cũ bị bỏ qua, quá `max-attempts` thì `REPAIR_REQUIRED
(CORE_BOOKING_STALLED)`. Payment giải ngân có lease `PROCESSING` (mặc định 2 phút): hết lease thì chuyển
`RECONCILIATION_REQUIRED` và tra cứu provider theo `sagaId` (`PaymentProvider.lookup`), không bao giờ gọi chuyển tiền
lại; `finish` chỉ ghi kết quả khi còn `PROCESSING/RECONCILIATION_REQUIRED`. `DisbursementFailed` chỉ hợp lệ khi saga còn
`WAITING_PAYMENT`, event mâu thuẫn vào DLT. Investment nhận `LoanDisbursed` trước event Contract (khác topic) vẫn phát
hành Note; event Contract đến muộn không làm trạng thái lùi.

**CURRENT STATE (2026-10-17, Wave 2D, plan [`W2D`](../plans/W2D-incident-operations-overdue-cure-and-auto-invest-acknowledgement.md)):**
`LoanDisbursed.v1` mang thêm field optional `loanNumber` và `maturityDate` (ngày đáo hạn Fineract theo ngày giải ngân thực
tế, B34); Investment ghi ngày đáo hạn của vị thế ngay lúc giải ngân. Drill Q13 tắt hẳn JVM Loan sau khi Fineract đã ghi
khoản vay (saga chưa lưu), tắt Payment sau khi provider đã chuyển tiền (Payment chưa lưu), và tắt Investment khi
`LoanDisbursed` được phát: kết quả cuối chỉ có một khoản vay Fineract, một lần cộng ví người vay, một bộ Note. Thao tác sự
cố cho ADMIN, không cái nào chuyển tiền lần hai:

- Loan `GET /api/v1/admin/disbursement-sagas?status=` (mặc định `REPAIR_REQUIRED`) và `POST /{sagaId}/requeue`
  (`note` bắt buộc): `REPAIR_REQUIRED → RETRY_PENDING`, chỉ chạy lại bước ghi Fineract, booking đối chiếu theo số hợp
  đồng; giới hạn tự động tính lại từ `repair_attempt_base`; lưu `repair_count`, ai, lúc nào, ghi chú (Loan V31).
- Payment `GET /api/v1/admin/disbursement-reconciliation` (lệnh `PROCESSING/RETRY_PENDING/RECONCILIATION_REQUIRED`) và
  `POST /{sagaId}/recheck`: chỉ với `RECONCILIATION_REQUIRED`, đặt lại lượt tra cứu provider theo `sagaId`, ghi ai và lúc
  nào (Payment V16). `GET /{sagaId}` đọc một lệnh ở mọi trạng thái kể cả đã xong (`attemptCount` = số lần gọi provider
  chuyển tiền, `reconciliationAttemptCount` = số lần tra cứu chưa ra kết quả, `errorCode` giữ lý do vào đối soát như
  `PAYMENT_PROCESSING_INTERRUPTED`), dùng để tra lại sự cố đã đóng và cho drill Q13. Gateway route
  đường này về Payment trước `/api/v1/admin/**` của Loan.

## F06 — Thu nợ và phân bổ

**Trigger:** borrower thanh toán hoặc auto-debit đến hạn. **Orchestrator:** Loan cho nghĩa vụ kỳ hạn; Payment cho execution tài chính.

1. Loan cung cấp core loan/schedule reference; Fineract là nguồn nghĩa vụ và balance chính thức.
2. Payment collect tiền idempotently và commit FINORA ledger/provider reference.
3. Sau thu thành công, Payment ghi repayment vào Fineract bằng external transaction reference; Fineract phân bổ borrower payment vào principal/interest/fee/penalty và trả core transaction/breakdown.
4. Payment lấy ownership snapshot/version từ Investment và phân bổ investor wallets dựa trên kết quả core hợp lệ cùng policy FINORA.
5. Payment phát `RepaymentDistributed` với breakdown/reference tối thiểu.
6. Loan cập nhật servicing projection từ Fineract response/reliable event; Investment cập nhật Note/portfolio và phát projection event theo chủ Note; Blockchain ghi proof; Notification lưu delivery in-app idempotent.
7. Worker Loan đồng bộ Fineract định kỳ; khi DPD thay đổi, Loan ghi outbox
   `LoanDelinquencyChanged.v1`. CIC Node deploy ngoài Compose consume idempotent để thêm một phiên bản lịch sử
   quá hạn/nhóm nợ. Event chỉ mang `borrowerId`; mapping CCCD nằm riêng trong CIC và event
   đến trước mapping được giữ bền vững để replay.

**Invariant:** Payment ledger là nguồn chuyển tiền; Fineract là nguồn allocation/balance. Tổng tiền thu phải đối chiếu với core transaction và tổng phân bổ investor + platform; mismatch tạo reconciliation incident, không thu hoặc ghi repayment lần hai.

**Idempotency:** `repaymentId`/`Idempotency-Key`, wallet ledger transaction và Fineract external
reference `FINORA-REPAY-<repaymentId>` là unique.

**Failure:** thiếu tiền → kết quả partial/rejected theo policy, không giả completed; lỗi projection sau ledger commit → event retry/rebuild, không chạy lại collection. Fineract trả 4xx cho POST repayment → từ chối chắc chắn, hoàn tiền; 5xx/mất kết nối, hoặc POST đã có `resourceId` mà đọc breakdown lỗi → `RECONCILIATION_REQUIRED`, đối soát theo externalId, không hoàn tiền (Wave 2). Client lấy số tiền trả kỳ từ `GET /api/v1/repayments/scheduled-due` (đúng nghĩa vụ Fineract mà POST sẽ kiểm), không tự cộng từ projection.

**Chủ Note đổi giữa chừng:** Payment phân bổ theo projection ownership (cập nhật bất đồng bộ qua
`NoteOwnershipChanged`, chỉ đổi chủ — dư nợ chỉ lấy từ event lúc tạo, sau đó Payment tự giảm). Investment
nhận `RepaymentDistributed` mà người được trả không còn là chủ Note nhưng đã từng bán Note đó → ghi
`note_repayment_redirects` cùng transaction; worker gọi Payment `POST /transactions/transfers` bằng service
account (`REPAY-REDIRECT:<repaymentId>:<noteId>`, idempotent) chuyển từ chủ cũ sang chủ hiện tại; ví chủ cũ
thiếu tiền thì giữ `PENDING` và thử lại. Người được trả chưa từng sở hữu Note → conflict, event vào DLT.

**CURRENT STATE (2026-10-11):** scheduled/overdue/partial/early-settlement repayment, core breakdown,
investor distribution và Loan/Investment projection đã có. Payment có admin reconciliation; integration
test PostgreSQL chứng minh borrower → clearing → current Note owner và outbox cân bằng. Product mới dùng
`FINORA-FINERACT-V3` với `PROGRESSIVE` + advanced allocation + `NEXT_INSTALLMENT` để ưu tiên rút ngắn
kỳ; V2 `REAMORTIZATION` và V1 vẫn được đọc tương thích. Trả trước không thu lãi tương lai; tổng phí theo
policy được chia 80% cho chủ Note theo dư nợ và 20% cho FINORA, có field/event riêng để đối soát. Loan đã phát
`LoanDelinquencyChanged.v1`; CIC Node ngoài Compose đã có consumer, processed-event và pending-mapping
queue. Loan V21 tự mở/đóng từng collection episode, tăng stage theo DPD, chuyển `DEFAULTED`
từ DPD 91, cure về `ACTIVE` và cung cấp admin queue/audit action append-only. Loan V23 đã fail-closed và
lưu incident riêng khi servicing snapshot sai core identity, external ID, principal giải ngân hoặc tổng
breakdown; V24 giữ/replay event hợp lệ nhưng thiếu mapping. Blockchain đã consume repayment hash-only;
Kafka broker local publish/consume envelope v1 đã xanh. Notification đã consume
`InvestorNoteServicingChanged.v1`, lưu delivery PostgreSQL và cung cấp REST cho mobile; push hệ điều hành
vẫn cần device-token/provider receipt riêng.

**Đính chính 2026-10-17 (B27):** câu “CIC Node ngoài Compose đã có consumer, processed-event và pending-mapping
queue” mô tả bản CIC Java cũ. Bản CIC Node đang deploy (`/api/v1/suc-khoe` trả kho tệp, 1000 hồ sơ) và source
`cic-service-node` chỉ có API tra cứu điểm theo CCCD, không consume Kafka và không có endpoint đăng ký mapping. Loan
vẫn phát `LoanDelinquencyChanged.v1` nhưng hiện không có consumer CIC; lịch sử quá hạn của FINORA chưa được ghi sang
CIC. Theo D1 không phát triển lại CIC trong repo này; owner quyết định phạm vi. Định danh mapping thống nhất là
`user_id` (B26 sửa ở Wave 2C, D18).

**Đính chính 2026-10-17 (B28, B29):** với sản phẩm core V1–V3 (tắt `isInterestRecalculationEnabled`), báo giá tất
toán (`prepayLoan`) gồm toàn bộ lãi các kỳ còn lại và khoản trả thêm theo `NEXT_INSTALLMENT` trả trọn kỳ tương lai kể
cả lãi (fixture `r1b-*-20261017-063*`). Payment đúng khi đọc nghĩa vụ từ Fineract; lỗi nằm ở cấu hình core.

**CURRENT STATE (2026-10-17, Wave 2C, plan [`W2C`](../plans/W2C-daily-interest-core-and-early-installment.md)):**

- Sản phẩm mới đồng bộ core V4: Fineract tính lại lãi hằng ngày trên dư nợ thật, nên trả trước một phần và tất toán
  chỉ thu lãi đến ngày giao dịch (D15). Khoản vay đã giải ngân ở core V1–V3 bị chặn tự trả trước/tất toán
  (`409 PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN`, D17).
- Trả kỳ trước ngày đến hạn trên khoản vay V4: Payment thu tiền (`COLLECTED`, `post_on` = ngày đến hạn, Payment V15),
  worker chỉ ghi Fineract khi tới `post_on` và đề `transactionDate = post_on`, nên lãi kỳ giữ đúng lịch và
  `RepaymentDistributed` phát vào ngày đến hạn (D16). Người vay hủy được trước ngày đó
  (`POST /api/v1/repayments/{id}/cancel`, PIN), bút toán hoàn `REPAYMENT-CANCEL-REFUND:<id>`, trạng thái `CANCELLED`.
- Mỗi khoản vay tối đa một khoản đã thu mà core chưa ghi xong (B30): lệnh trả kỳ khóa dòng `payment_loan_accounts`,
  trả thêm cho kỳ đang giữ → `409 INSTALLMENT_ALREADY_PAID_PENDING`, lệnh mới khi lệnh trước đang ghi →
  `409 REPAYMENT_IN_PROGRESS`, báo giá khi còn khoản giữ → `409 INSTALLMENT_PENDING_POSTING` (hủy trước, D22), thực
  hiện báo giá sau khi có khoản mới → `409 *_QUOTE_STALE`.
- `RepaymentDistributed.v1` không đổi schema; consumer Loan/Investment dùng `completedAt` nên phân phối ngày đến hạn
  không đổi cách chiếu. Notification (R7) phải đọc khoản đang giữ trước khi nhắc lịch.

**CURRENT STATE (2026-10-17, Wave 2D, plan [`W2D`](../plans/W2D-incident-operations-overdue-cure-and-auto-invest-acknowledgement.md)):**

- Khắc phục quá hạn (D26, sửa B31): `scheduled-due.amount` khi quá hạn là toàn bộ phần quá hạn
  (`max(template, summary.totalOverdue)`); template Fineract chỉ báo kỳ chưa trả cũ nhất. Quá hạn nhiều kỳ thì
  `partialOverdueAmount` là kỳ cũ nhất; POST chỉ nhận một trong hai số. Loan chỉ hồi phục `DEFAULTED → ACTIVE` khi DPD
  và tiền quá hạn đều về 0.
- Báo giá trả trước một phần khi khoản vay quá hạn → `409 PARTIAL_PREPAYMENT_LOAN_OVERDUE` (D27, sửa B35).
- Nhóm nợ trong `LoanDelinquencyChanged.v1` là nhóm của đợt quá hạn (D29, sửa B36): hồ sơ thu hồi còn mở thì nhóm không
  giảm khi người vay trả bớt; Investment giữ Note `DEFAULTED` khi nhóm 3–5 nên khớp `DEFAULTED` của Loan. Hoàn tất cơ cấu
  mà DPD đổi thì Loan phát event ngay (B37).
- Khoản `RECONCILIATION_REQUIRED` mà Fineract chưa từng nhận (B32): admin
  `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/repost` sau `PAYMENT_CORE_REPOST_SAFE_AFTER` (mặc định 15
  phút): tra cứu theo externalId → chưa có thì POST lại cùng externalId, `transactionDate = corePostingDate`, cùng số
  tiền; 4xx thì tra cứu lại, có giao dịch → ghi nhận, không có → hoàn tiền `FAILED`, không tra được → giữ đối soát.
  Lưu `core_repost_count`, ai, lúc nào (Payment V17). Hàng đợi trả nợ nhận `status` để web chỉ đọc việc còn mở.

## F07 — Tất toán sớm hoặc tái cơ cấu

**Trigger:** borrower yêu cầu hoặc admin khởi tạo theo policy. **Orchestrator:** Loan.

1. Loan tính quote có `quoteId`, expiry và rule version.
2. Với tất toán: Payment collect theo quote, Loan đóng schedule/loan sau event thành công, Investment cập nhật Notes.
3. Với tái cơ cấu: borrower gửi request và consent version; lịch cũ tiếp tục có hiệu lực. FINORA/admin
   thẩm định đạt thì Loan phát `LoanRescheduleConsentRequested.v1`. Investment snapshot chủ Note/dư nợ,
   yêu cầu từng nhà đầu tư quyết định và phát `LoanRescheduleConsentCompleted.v1`. Chỉ đồng thuận toàn bộ
   mới cho Loan chuyển `RESTRUCTURING`, reconcile/create/approve Fineract reschedule ngoài DB transaction,
   đọc servicing snapshot mới rồi trở lại `ACTIVE`; một nhà đầu tư từ chối thì giữ lịch cũ.
4. Loan phát `LoanSettled.v1` hoặc `LoanRescheduled.v1`; consumer phụ trợ không được làm rollback core state.

**Idempotency:** command theo `requestId`; payment theo `quoteId`; chỉ một active quote/transition theo policy.

**Failure:** quote hết hạn hoặc version thay đổi → reject và tính lại; Payment thất bại → Loan không đổi schedule/state; consumer phụ trợ lỗi → retry từ outbox.

**CURRENT STATE (2026-10-11):** backend Loan đã có `loan_reschedule_requests`, API borrower/admin,
processing lease, bounded retry, reconcile bằng marker Fineract và outbox `LoanRescheduled.v1`. Worker mặc
định fail-closed; chỉ bật sau khi tạo reason code trong Fineract và cấu hình `FINERACT_RESCHEDULE_REASON_ID`.
Loan/Investment đã nối cổng đồng thuận nhà đầu tư bằng hai event trên; mobile nhà đầu tư có màn biểu quyết
trong tab Ví. Investment consume idempotent `LoanRescheduled.v1`/`LoanSettled.v1` thành lifecycle read model.
Notification delivery không thuộc transaction tái cơ cấu; Investment phát outbox projection và
Notification consume idempotent sau khi lịch mới đã commit.

**CURRENT STATE (2026-10-17, G-POLICY-W2):** phí trả trước một phần và tất toán tính theo biểu phí của chính hợp
đồng: Loan ghi `loan_contracts.early_settlement_policy_version` lúc tạo hợp đồng (V30), `LoanDisbursed` mang giá trị
này, Payment lưu một lần trên `payment_loan_accounts` (V14) và tra danh mục bất biến `EarlySettlementPolicyCatalog`.
Tỷ lệ/mức tối thiểu/cách chia không còn cấu hình bằng biến môi trường. Hợp đồng không ghi biểu phí (`LOAN_TERMS_V1`)
hoặc tài khoản mở trước V14 dùng `NO_FEE_DISCLOSED` (phí 0); phiên bản Payment chưa biết → `409
EARLY_SETTLEMENT_POLICY_UNKNOWN`, trả kỳ thường không bị ảnh hưởng.

## F08 — Audit Blockchain và đối chiếu

**Trigger:** domain event cần proof hoặc scheduled reconciliation. **Orchestrator:** Blockchain.

1. Blockchain canonicalize payload được phép và tính hash/version.
2. Submit Fabric idempotently, lưu transaction/block reference và confirmation state.
3. Reconciliation định kỳ lấy record/hash qua API/event contract của owner, không đọc DB chéo.
4. Sai lệch tạo incident/audit result; MUST NOT tự sửa dữ liệu nguồn.

**Idempotency:** unique theo `sourceService + sourceEventId` (event giao trùng) và theo mốc nghiệp vụ
`proofType + aggregateType + aggregateId` (cùng mốc phát lại với eventId mới); proofId là UUID v5 của mốc nên
chaincode cũng chỉ giữ một bản ghi. Version hash scheme nằm trong `payloadVersion`.

**Failure:** Fabric unavailable → retry có backoff, DLT và cảnh báo; mismatch → điều tra/audit workflow, không overwrite bằng giá trị “khớp”.

**CURRENT STATE (2026-10-17, W3D):** Blockchain consume bốn mốc `LoanContractActivated.v1` (CONTRACT_DOCUMENT),
`LoanDisbursed.v1` (DISBURSEMENT), `RepaymentDistributed.v1` (REPAYMENT), `NoteOwnershipChanged.v1` (NOTE_OWNERSHIP,
`ISSUED`/`TRANSFERRED`) bằng group `blockchain-group`, hash SHA-256 của `data` theo FINORA canonical JSON v2 (không
lưu payload). Lỗi consume retry có backoff rồi vào `<topic>.DLT`. Provider `MOCK` vẫn mặc định; `HYPERLEDGER_FABRIC`
ghi lên mạng Fabric local (2 tổ chức, endorsement MAJORITY, chaincode `finora-ledger`, launcher `-WithFabric`), đối
chiếu trước khi ghi lại sau timeout và worker đối chiếu DB ↔ chain opt-in. API ADMIN `GET /api/v1/blockchain/proofs`,
`POST .../{proofId}/verify|retry`. Chi tiết: [`W3D`](../plans/W3D-fabric-proofs.md).

## F09 — Notification từ domain event

**Trigger:** event nghiệp vụ đã commit. **Orchestrator:** Notification cho delivery.

1. Notification resolve template/version và recipient reference.
2. Áp preference/policy, tạo delivery theo từng channel.
3. Gửi, lưu trạng thái/attempt/provider reference; retry lỗi tạm thời.
4. Permanent failure chuyển terminal/DLT và cảnh báo theo mức độ.

**Idempotency:** `sourceEventId + recipientId + channel + templateVersion`.

**Failure:** gửi lỗi không rollback nghiệp vụ nguồn; MUST NOT log payload chứa PII hoặc secret.

**Hiện trạng W3F (D60–D64):** xử lý một lần theo `eventId` (bảng processed event); người nhận lấy từ event, thiếu thì
từ bảng tra dựng từ event gọi vốn; event chờ người nhận tối đa 24 giờ rồi hết hạn; payload sai vào
`<topic>.notification.DLT`. In-app luôn tạo; push Expo theo preference từng nhóm, nội dung không có số tiền/tên/số khoản
vay, retry 6 lần rồi `DEAD`, receipt kiểm sau. Provider mặc định `log` là giả lập, không phải push thật.

## F10 — Rút tiền về tài khoản ngân hàng

**Trigger:** người vay/nhà đầu tư rút số dư khả dụng (PIN `WITHDRAW`, `Idempotency-Key`). **Orchestrator:** Payment.

1. Payment khóa dòng ví, kiểm policy (tối thiểu, hạn mức theo ngày nghiệp vụ, số dư), ghi bút toán dự trữ
   `AVAILABLE` → `PAYOUT_PROVIDER_CLEARING` và lệnh `REQUESTED` trong một transaction.
2. Worker nhận lệnh có lease, gọi provider payout ngoài transaction; provider nhận → `PROVIDER_PENDING`.
3. Provider báo kết quả bằng callback ký HMAC: thành công → `COMPLETED`; thất bại → bút toán hoàn + `FAILED` cùng transaction.
4. Không rõ kết quả (timeout, hết lease, mất callback) → tra cứu theo `withdrawalId`; chỉ gửi lại đúng mã khi provider
   khẳng định chưa nhận. Hết lượt tự động → `RECONCILIATION_REQUIRED` chờ vận hành (`/api/v1/admin/withdrawals`).
5. Outbox `WithdrawalCompleted.v1`/`WithdrawalFailed.v1` (chỉ số tài khoản che) cho Notification.

**Idempotency:** `(ownerId, Idempotency-Key)` unique + hash nội dung (dấu vân tay HMAC của số tài khoản); bút toán khóa theo
`WITHDRAWAL:<id>:RESERVE/REFUND`; callback lặp không ghi/hoàn hai lần.

**Failure:** chữ ký sai `401`, callback mâu thuẫn kết quả đã chốt `409`; payout thật chỉ mở sau legal review `LEGAL-PAYMENT-01`.

## Checklist khi thêm flow

- SoR và state authority đã khớp `07-service-boundaries.md`.
- Orchestrator và participant rõ ràng; không có orchestration vòng tròn.
- API/event version, producer, consumer, partition key và PII classification rõ.
- Local transaction/outbox/processed event đã xác định.
- Idempotency key, unique constraint và duplicate response rõ.
- Timeout, retryable/non-retryable error, DLT và compensation rõ.
- Có test happy path, duplicate, timeout, concurrent request, restart và compensation phù hợp rủi ro.
