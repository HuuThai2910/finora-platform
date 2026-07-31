# Ranh giới và quyền sở hữu service

File này là nguồn chuẩn để quyết định một chức năng, entity, state transition, API hoặc event thuộc service nào. Nếu một thay đổi không ánh xạ rõ vào đây, MUST làm rõ ownership trước khi code.

## Nguyên tắc chung

- Mỗi aggregate/dữ liệu nghiệp vụ chỉ có một **System of Record** (SoR).
- Chỉ service sở hữu aggregate mới được thay đổi trạng thái của aggregate đó.
- Service khác chỉ giữ logical ID, immutable snapshot hoặc read model tối thiểu có nguồn/version rõ ràng.
- MUST NOT có foreign key, transaction hoặc truy vấn database xuyên service.
- Command yêu cầu một hành động; event mô tả điều đã xảy ra. Consumer không được diễn giải event thành quyền sửa state ngoài ownership.
- Dữ liệu duplicate để đọc MUST có owner, nguồn event, version, cơ chế rebuild và mức chấp nhận stale.
- AI, Blockchain và Notification hỗ trợ quyết định/thực thi; không chiếm quyền sở hữu state nghiệp vụ của User, Loan, Investment hoặc Payment.

## `finora-gateway`

**Sở hữu:** routing, edge authentication, CORS, rate limit, request-size limit, trace propagation.

**Không sở hữu:** business state/database, resource ownership authorization, orchestration nghiệp vụ hoặc dashboard join phức tạp.

**Invariant:** request từ Gateway không tự động đáng tin; mỗi resource service vẫn MUST xác minh JWT và authorization cần thiết.

## `finora-user`

**Sở hữu:** user profile, `keycloakUserId`, KYC application/document metadata, KYC workflow/status, contact/preference thuộc hồ sơ FINORA.

**Không sở hữu:** password/session/token, loan application, wallet balance, credit decision cuối cùng.

**State authority:** chỉ User chuyển trạng thái KYC. AI trả OCR/face/liveness/forgery result; User áp policy để quyết định `VERIFIED`, `REJECTED` hoặc `MANUAL_REVIEW`.

## `finora-loan`

**Sở hữu:** loan product, loan application, approval decision, loan state machine, repayment schedule, overdue/NPL, restructuring, early settlement và Disbursement Saga.

**Không sở hữu:** wallet/ledger, investment order/commitment/Note, AI model hoặc Fabric ledger.

**State authority:** chỉ Loan đổi `LoanStatus`. Investment/Payment/Blockchain phát kết quả; Loan kiểm tra current state/version rồi quyết định transition.

**Invariant:** scoring result dùng để duyệt phải được lưu snapshot gồm model/rule version và reason codes; model thay đổi sau đó không được làm thay đổi quyết định lịch sử.

## `finora-investment`

**Sở hữu:** market listing projection, investment order, matching, commitment, Note ownership, portfolio projection, auto-invest và secondary market.

**Không sở hữu:** loan state nguồn chuẩn, wallet balance/ledger, KYC state nguồn chuẩn hoặc repayment schedule nguồn chuẩn.

**State authority:** chỉ Investment đổi order/commitment/Note state. Việc đủ vốn được Investment xác nhận bằng event; Loan tự chuyển loan state.

**Invariant:** Investment MUST NOT tự trừ tiền. Mọi hold/release/capture đi qua Payment và lưu payment transaction reference.

## `finora-payment`

**Sở hữu:** wallet, available/held balance, immutable wallet transaction, hold/release/capture, deposit/withdrawal, disbursement execution, repayment collection/distribution và financial idempotency.

**Không sở hữu:** loan approval/state machine, matching, commitment/Note ownership hoặc quyết định bắt đầu giải ngân.

**State authority:** chỉ Payment đổi wallet/financial transaction state.

**Invariant:** mọi biến động số dư và ledger entry phải commit trong cùng local transaction; tổng debit/credit phải cân bằng; retry không tạo side effect lần hai; số dư không âm trừ khi một sản phẩm được thiết kế và phê duyệt rõ.

## `finora-blockchain`

**Sở hữu:** Fabric connection, submission/confirmation state, transaction/block reference, payload hash, proof query và reconciliation result.

**Không sở hữu:** loan/user/payment/investment business state, PII đầy đủ hoặc tài liệu gốc.

**Invariant:** chỉ ghi hash, ID tham chiếu và metadata tối thiểu. Fabric tạm lỗi không được rollback local transaction đã commit; dùng retry/DLT/reconciliation trừ khi flow quy định chứng cứ on-chain là precondition bắt buộc.

## `finora-notification`

**Sở hữu:** template, delivery request/status/history, channel strategy, retry và notification idempotency.

**Không sở hữu:** quyết định nghiệp vụ, loan/payment/KYC state hoặc user profile nguồn chuẩn.

**Invariant:** idempotency theo `sourceEventId + recipientId + channel + templateVersion`; gửi thất bại không được đổi ngược state nghiệp vụ nguồn.

## `finora-ai`

**Sở hữu:** model package/version, feature preprocessing, prediction, explanation/reason codes, OCR/face/liveness/forgery/fraud technical result và model evaluation.

**Không sở hữu:** KYC status cuối cùng, loan approval/state, wallet/transaction hoặc hành động khóa tài khoản.

**Invariant:** predictor dùng đúng feature order và median trong model package; response phải có model version. AI đưa ra score/evidence; User, Loan hoặc Payment áp policy và quyết định nghiệp vụ.

## `finora-common`

**Sở hữu:** primitive/hạ tầng thật sự ổn định và contract kỹ thuật chung được cả hai owner phê duyệt.

**Không sở hữu:** entity, repository, state machine hoặc domain enum riêng của một service.

**Invariant:** thêm shared type phải chứng minh ổn định và không làm các service deploy phụ thuộc cùng một domain model.

## Ma trận System of Record

| Dữ liệu | SoR | Bản sao được phép |
|---|---|---|
| Credential, session, realm role | Keycloak | Service chỉ dùng JWT claims cần thiết |
| User profile, KYC status | User | Loan giữ `userId`, KYC snapshot/reference cần cho quyết định |
| Model và prediction kỹ thuật | AI | Loan/User giữ immutable result snapshot đã sử dụng |
| Loan application, `LoanStatus`, schedule | Loan | Investment market projection; Payment giữ `loanId` reference |
| Order, commitment, Note ownership | Investment | Payment giữ transaction/reference; Loan giữ funding summary |
| Wallet, balance, ledger | Payment | Service khác chỉ giữ payment transaction ID và result |
| Hash/proof/Fabric transaction | Blockchain | Service nguồn giữ Fabric transaction reference |
| Delivery status/history | Notification | Service nguồn giữ notification request/reference nếu cần |

## Ma trận quyền đổi trạng thái

| Aggregate/state | Service duy nhất được đổi |
|---|---|
| User/KYC | User |
| Loan/application/schedule | Loan |
| Investment order/commitment/Note | Investment |
| Wallet/financial transaction | Payment |
| Fabric submission/reconciliation | Blockchain |
| Notification delivery | Notification |
| Model lifecycle/prediction artifact | AI |

Ví dụ bắt buộc: Payment phát `DisbursementCompleted`; Payment MUST NOT sửa Loan thành `ACTIVE`. Loan consume event idempotently và tự thực hiện transition hợp lệ.

