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

eKYC là chức năng tuỳ chọn mở từ tab Hồ sơ (không ép sau đăng nhập) và chạy hai bước — quét ra bản nháp, người dùng xác nhận mới lưu:

1. **Quét:** client gửi `POST /api/v1/users/profile/ekyc-verify` gồm ảnh mặt trước và mặt sau CCCD. User chạy tuần tự và dừng ở bước đầu tiên trượt: rate limit → `POST /api/v1/ai/ekyc/ocr` trên ảnh mặt trước → đối chiếu HMAC số CCCD (`ID_MISMATCH` với hồ sơ có số cũ, `ID_TAKEN` nếu số thuộc tài khoản khác). Đạt thì cất kết quả OCR vào **bản nháp Redis (TTL 10 phút)** và trả `DRAFT_READY` kèm bản nháp — **hồ sơ chưa được ghi**.
2. Ảnh mặt sau không OCR (model chỉ đọc mặt trước) — nộp kèm làm bằng chứng cầm thẻ đầy đủ, phục vụ đối soát tay khi có nghi vấn.
3. **Xác nhận:** người dùng soát bản nháp trên client; sai thì quét lại, đúng thì gọi `POST /api/v1/users/profile/ekyc-confirm` (không mang dữ liệu — bản nháp đọc từ Redis để client không sửa được thông tin OCR). User kiểm tra trùng lần cuối, ghi bản nháp vào hồ sơ, chuyển `VERIFIED` (`documentVerified = true`), xoá nháp. Nháp hết hạn trả `DRAFT_EXPIRED`.
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

## F03 — Duyệt, ký hợp đồng và đưa khoản vay lên sàn

**Trigger:** AI auto-approve hoặc admin xử lý hồ sơ `PENDING_REVIEW`. **Orchestrator:** Loan.
**Legal gates:** [`LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md).

1. Loan kiểm tra KYC/scoring snapshot, policy và optimistic version.
2. Loan từ chối với reason hoặc chốt exact final terms từ hai schedule snapshots. Không tạo `LoanOffer`/bảng sao chép điều khoản.
3. Nếu exact terms không bất lợi hơn, disclosure lúc submit cho phép tự tiếp tục. Nếu bất lợi hơn, borrower phải accept/decline trên Application theo version/hash/expiry; decline/expiry không tạo Contract.
4. Loan ghi outbox `LoanFundingRequested.v1` sau
   `AUTO_AUTHORIZED/ACCEPTED`; Investment tạo projection listing idempotent, xác định lender và phát
   `LoanFullyFunded.v2` cùng allocation bất biến. Loan chỉ lập Contract/PDF chung sau event này.
5. Nhà đầu tư ký cùng document hash trước; khi đủ chữ ký lender, Loan yêu cầu borrower ký một lần.
   Loan phát `LoanContractActivated.v1` sau khi mọi party đã ký; event này không đồng nghĩa đã giải ngân.

**Contract chuẩn:** xem
[`docs/integrations/LOAN-INVESTMENT-EVENTS.md`](../../docs/integrations/LOAN-INVESTMENT-EVENTS.md).

**CURRENT STATE (2026-09-21):** Loan đã ghi các event vòng đời Contract vào transactional outbox local cùng transaction với aggregate/history. Relay có lease, claim token, retry giới hạn và dead-letter; Kafka adapter đã có broker acknowledgement, partition key theo aggregate, trace headers và allowlist theo exact event/version. Publisher vẫn mặc định tắt và chưa có route/topic hoạt động. Các event Contract nội bộ chưa phải event listing, chưa có consumer và không tự kích hoạt Investment.

**Idempotency:** admin/sign command dùng key và optimistic version; một Application tối đa một Contract trong MVP; Investment unique theo `loanId + listingVersion`.

**Failure:** Contract hết hạn/decline → không listing; sign cạnh tranh chỉ một commit; Investment chưa tạo projection → outbox retry/DLT, Loan không publish event mới trùng để “chữa” lỗi. Listing projection phải rebuild được từ event/source API có kiểm soát.

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

**Failure/compensation:** Payment từ chối → order `REJECTED`; lỗi tạo commitment sau hold → Investment yêu cầu Payment release bằng reference hold; release được retry idempotently. Concurrent order MUST NOT làm overfund hoặc âm ví.

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
khóa. Thanh toán lỗi tạm thời → trade `PENDING`, retry backoff; Payment từ chối hẳn → `FAILED` và đối soát
tay, không đảo chủ Note.

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

**Failure/compensation:** transfer lỗi trước khi provider xác nhận → Payment retry hoặc phát failed, không tạo Note; tiền đã chuyển nhưng Fineract disburse lỗi → `REPAIR_REQUIRED`, retry/reconcile và tuyệt đối không chuyển tiền lần hai; Note activation lỗi sau disbursement → consumer retry/DLT/repair, không đảo ledger; Fabric/Notification lỗi không rollback giải ngân. Contract đã đủ chữ ký vẫn `EFFECTIVE`; lỗi giải ngân được xử lý trên Saga riêng.

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

**CURRENT STATE (2026-09-21):** Blockchain đã có durable proof foundation local: PostgreSQL lưu duy nhất hash/version và business reference, idempotent theo source event, claim lease, bounded retry/dead-letter và mock receipt được đánh dấu rõ. Worker và Kafka listener mặc định tắt. Fabric adapter hiện fail-closed; chưa có Kafka topic/consumer hoặc chaincode submission thật cho tới khi contract P4 được hai owner duyệt và phase gate P3 đạt.

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
