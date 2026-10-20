# Registry cố định

Thay đổi giá trị trong bảng MUST cập nhật cấu hình, tài liệu liên quan và file này trong cùng change. Không tự đổi giá trị chỉ để tránh conflict local.

| Thành phần                    |                                 Port | Storage/định danh                                                                                                                                             |
| ----------------------------- | -----------------------------------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `finora-gateway`              |                                 8080 | Không có DB                                                                                                                                                   |
| `finora-loan`                 |                                 8081 | PostgreSQL/Neon Project `finora-loan`, DB `neondb`, default role của project                                                                                  |
| `finora-payment`              |                                 8082 | PostgreSQL/Neon Project `finora-payment`, DB `neondb`, default role của project                                                                               |
| `finora-blockchain`           |                                 8083 | PostgreSQL/Neon Project `finora-blockchain`, DB `neondb`, default role của project; Fabric channel `finora-channel`, chaincode `finora-ledger`, MSP `Org1MSP` |
| `finora-investment`           |                                 8084 | PostgreSQL/Neon Project `finora-investment`, DB `neondb`, default role của project                                                                            |
| `finora-user`                 |                                 8085 | PostgreSQL/Neon Project `finora-user`, DB `neondb`, default role của project                                                                                  |
| `finora-notification`         |                                 8086 | PostgreSQL/Neon DB riêng; in-app delivery + REST/JWT + Kafka consumer; SMTP internal                                                                            |
| `finora-ai`                   |                                 8000 | FastAPI; chưa có DB, kể cả trợ lý `AI-CHAT-001` (hạn mức, cache, chỉ mục kiến thức chỉ trong bộ nhớ; chạy một worker uvicorn)                              |
| Keycloak                      |                                 8180 | OIDC; realm `finora`, client `finora-user-client`. DB `keycloak` trên PostgreSQL host (không còn container `keycloak-db`)                                     |
| Kafka                         | 9092 host local; 29092 nội bộ Docker | Zookeeper 2181                                                                                                                                                |
| Loan PostgreSQL offline       |        15433 host local; 5432 nội bộ | PostgreSQL 17, `loan-postgres`, volume/user riêng                                                                                                             |
| Payment PostgreSQL offline    |        15434 host local; 5432 nội bộ | PostgreSQL 17, `payment-postgres`, volume/user riêng                                                                                                          |
| Blockchain PostgreSQL offline |        15435 host local; 5432 nội bộ | PostgreSQL 17, `blockchain-postgres`, volume/user riêng                                                                                                       |
| User PostgreSQL               |                       5432 host local | PostgreSQL cài trên máy host, DB `finora_user` (không còn container `user-postgres`)                                                                          |
| User Redis                    |         6381 host local; 6379 nội bộ | Redis 7, `user-redis`, volume riêng; OTP reset password + rate limit đăng nhập                                                                                |
| Investment PostgreSQL offline |        15437 host local; 5432 nội bộ | PostgreSQL 17, `investment-postgres`, volume/user riêng                                                                                                       |
| Notification PostgreSQL offline |      15438 host local; 5432 nội bộ | PostgreSQL 17, `notification-postgres`, volume/user riêng                                                                                                     |
| Apache Fineract 1.15.0        |   18443 host local; 8443 nội bộ HTTP | Tenant `default`; API prefix `/fineract-provider/api/v1`                                                                                                      |
| Fineract PostgreSQL           |        15432 host local; 5432 nội bộ | PostgreSQL 18.3 riêng; `fineract_tenants`, `fineract_default`                                                                                                 |
| Mailpit (tùy chọn, dev)       |  8025 UI; 1025 SMTP (host local)     | Mail server local bắt thư để test; không lưu trữ lâu dài, không gửi ra Internet                                                                               |

Realm role Keycloak hiện có: `ROLE_BORROWER`, `ROLE_INVESTOR`, `ROLE_ADMIN` — khớp enum `UserRole`
của `finora-user`. Nguồn chuẩn là `docker/keycloak/template/realm-finora.json`; secret của client được
render lúc chạy từ `KEYCLOAK_CLIENT_SECRET` trong `docker/.env`, không commit vào template.

Consumer group hiện có: `payment-group`, `blockchain-group`, `notification-group`,
`notification-investor-servicing`, `notification-domain-events` (W3F), `cic-loan-delinquency`.

Contract Loan–Investment đã được Thái chấp thuận triển khai ngày 2026-09-26; Hải phải review
phần Investment trước merge:

| Topic | Producer | Consumer group | Tác dụng |
|---|---|---|---|
| `finora.loan.funding-requested` | Loan | `investment-loan-funding` | Tạo market listing từ exact terms đã được borrower cho phép |
| `finora.investment.loan-fully-funded` | Investment | `loan-investment-funding` | Trả allocation bất biến để Loan lập hợp đồng nhiều bên |
| `finora.investment.loan-funding-expired` | Investment | `loan-investment-funding` | Đợt gọi vốn hết hạn chưa đủ vốn; Loan đặt `fundingStatus = EXPIRED`, không lập Contract |
| `finora.loan.funding-cancelled` | Loan | `investment-loan-funding` | Đã đủ vốn nhưng không giải ngân (Contract `DECLINED`/`EXPIRED` hoặc `DisbursementFailed` khi saga còn `WAITING_PAYMENT`); Investment hủy commitment, listing `CANCELLED` và nhả hold |
| `finora.loan.investor-signature-requested` | Loan | `investment-loan-funding`, `notification-domain-events` | Gắn Contract/PDF chung vào listing; Notification báo từng nhà đầu tư ký |
| `finora.loan.borrower-signature-requested` | Loan | `notification-domain-events` | Báo người vay khi tất cả lender đã ký (B24 đã sửa ở W3F) |
| `finora.loan.contract-activated` | Loan | `investment-loan-funding`, `blockchain-group` | Investment cập nhật Contract có hiệu lực; Blockchain neo SHA-256 data (proof `CONTRACT_DOCUMENT`, W3D) |
| `finora.loan.delinquency-changed` | Loan | `cic-loan-delinquency`, `investment-loan-funding` | CIC tách nhóm hiện tại/lịch sử; Investment cập nhật DPD/nhóm nợ cho Note projection |
| `finora.loan.settled` | Loan | `investment-loan-funding` | Investment lưu lifecycle projection; không dùng thay `RepaymentDistributed` để chia tiền/đóng Note |
| `finora.loan.reschedule-consent-requested` | Loan | `investment-loan-funding` | Mở phiếu đồng thuận cơ cấu cho từng chủ Note hiện tại; payload không chứa PII/lý do tự do |
| `finora.investment.reschedule-consent-completed` | Investment | `loan-investment-funding` | Trả kết quả đồng thuận cuối; Loan chỉ gọi Fineract khi tất cả nhà đầu tư chấp thuận |
| `finora.loan.rescheduled` | Loan | `investment-loan-funding` | Fineract đã approve lịch cơ cấu; Investment cập nhật maturity marker, không tự tính schedule |
| `finora.payment.repayment-distributed` | Payment | `loan-repayment-distributed`, `investment-loan-funding`, `blockchain-group` | Loan/Investment cập nhật projection; Blockchain chỉ neo SHA-256 data, không lưu payload tài chính thô |
| `finora.investment.note-servicing-changed` | Investment | `notification-investor-servicing` | Fan-out thông báo servicing cho chủ Note và lời mời biểu quyết cơ cấu (`RESCHEDULE_CONSENT_REQUESTED`, W3F P7); dedup delivery, không chứa PII |

Topic giải ngân và quyền sở hữu Note (đã chạy trong code; bổ sung vào registry ngày 2026-10-17 sau khi đối
chiếu `application.yml` của Loan/Payment/Investment):

| Topic | Producer | Consumer group | Tác dụng |
|---|---|---|---|
| `finora.loan.disbursement-requested` | Loan | `payment-group` | Saga đã bền vững; Payment capture hold và credit ví người vay theo `sagaId` |
| `finora.payment.disbursement-completed` | Payment | `loan-investment-funding` | Loan ghi disbursement vào Fineract bằng external ID |
| `finora.payment.disbursement-failed` | Payment | `loan-investment-funding` | Saga `PAYMENT_FAILED`; còn `WAITING_PAYMENT` thì Loan phát `finora.loan.funding-cancelled` |
| `finora.loan.disbursed` | Loan | `investment-loan-funding`, `payment-servicing`, `blockchain-group` | Investment phát hành Note; Payment mở tài khoản thu nợ; Blockchain neo proof `DISBURSEMENT` (W3D) |
| `finora.investment.note-ownership-changed` | Investment | `payment-servicing`, `blockchain-group` | Payment cập nhật projection chủ Note để chia tiền trả nợ; Blockchain neo proof `NOTE_OWNERSHIP` (W3D) |

Khoản trả kỳ trước hạn đang giữ (W3F P5, D77; Payment phát từ 2026-10-17, cùng transaction với bút toán ví, key
`loanApplicationId`):

| Topic | Producer | Consumer group | Tác dụng |
|---|---|---|---|
| `finora.payment.repayment-collected` | Payment | `notification-domain-events` (chỉ khi `NOTIFICATION_DUE_REMINDER_ENABLED=true`) | `RepaymentCollected.v1`: đã trừ ví cho một kỳ trả trước hạn, giữ tới `dueDate` (D16); Notification báo đã nhận và không nhắc kỳ đó |
| `finora.payment.repayment-cancelled` | Payment | `notification-domain-events` (như trên) | `RepaymentCancelled.v1`: người vay hủy khoản đang giữ, tiền đã hoàn về ví; kỳ đó lại được nhắc |

Consumer lỗi của `blockchain-group` (contract sai, xung đột hash) vào `<topic>.DLT` theo quy ước chung. Consumer của Payment
(`payment-group`, `payment-servicing`) từ B58 (D79) vào DLT riêng `<topic>.payment.DLT` (contract sai: ngay; lỗi hạ tầng: sau
khi thử lại lùi dần tối đa 3 phút), vì các topic nguồn của Payment cũng là nguồn của Investment/Blockchain (`<topic>.DLT`).

Mạng Hyperledger Fabric local (profile Compose `fabric`, W3D; chỉ chạy khi `BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC`):

| Thành phần | Port | Định danh |
|---|---|---|
| `finora-fabric-orderer` | nội bộ 7050, admin 7053, ops 9443 | `orderer.finora.local`, `OrdererMSP`, Raft một node |
| `finora-fabric-peer0-org1` | **127.0.0.1:7051** (`FABRIC_PEER0_ORG1_HOST_PORT`), ops 9444 | `peer0.org1.finora.local`, `Org1MSP` (FINORA, ghi proof) |
| `finora-fabric-peer0-org2` | nội bộ 9051, ops 9445 | `peer0.org2.finora.local`, `Org2MSP` (kiểm toán, endorse/đọc) |
| `finora-fabric-ccaas-org1/org2` | nội bộ 9999 | CCaaS `finora-ledger_1.0`, alias `peer0org1-finora-ledger`, `peer0org2-finora-ledger` |

Channel `finora-channel`, chaincode `finora-ledger` v1.0 sequence 1, endorsement policy `MAJORITY Endorsement`.
Crypto/genesis/package sinh bởi `docker/fabric-network/fabric-network.ps1` vào thư mục git-ignore.

Payload và consumer hiện có được mô tả tại
[`docs/integrations/LOAN-INVESTMENT-EVENTS.md`](../../docs/integrations/LOAN-INVESTMENT-EVENTS.md).
Notification (W3F, D60) đọc bằng group `notification-domain-events` các topic: `finora.loan.funding-requested`,
`finora.investment.loan-fully-funded`, `finora.investment.loan-funding-expired`, `finora.loan.funding-cancelled`,
`finora.loan.investor-signature-requested`, `finora.loan.borrower-signature-requested`, `finora.loan.contract-activated`,
`finora.loan.disbursed`, `finora.payment.repayment-distributed`, `finora.loan.delinquency-changed`, `finora.loan.settled`,
`finora.loan.reschedule-consent-requested`, `finora.investment.reschedule-consent-completed`, `finora.loan.rescheduled`,
`finora.payment.withdrawal-completed`, `finora.payment.withdrawal-failed`; group `notification-investor-servicing` giữ
`finora.investment.note-servicing-changed`. Record lỗi vào `<topic>.notification.DLT` (không dùng chung `.DLT` của
consumer khác). `finora.payment.repayment-collected`/`repayment-cancelled` (P5) có producer Payment từ 2026-10-17 (bảng trên);
Notification chỉ đọc khi bật nhắc lịch (D64). Payment và Blockchain chỉ là consumer ở các topic đã đăng ký rõ trong
bảng/contract; không suy diễn subscription khác.

Mapping `borrowerId ↔ CCCD` được đăng ký trực tiếp vào CIC qua API nội bộ có
`X-Finora-Internal-Key`; tuyệt đối không đưa CCCD thô vào Kafka. Event đến trước mapping
được giữ trong `cic_pending_delinquency_events` và replay sau khi mapping được tạo.
User tự tạo `user_cic_mapping_tasks` cùng transaction xác nhận eKYC, dùng ID hồ sơ (`user_profiles.id`, chính là
claim `user_id`) làm `borrowerId` và retry có lease. Task chỉ giữ `user_profile_id`; không sao chép CCCD thô.

Loan/Investment/Payment/AI dùng claim `user_id` (ID hồ sơ) làm `borrowerId`; từ Wave 2C mapping CIC cũng dùng đúng
khóa này (B26 đã sửa ở `CicMappingWorker`, trước đó gửi `keycloakUserId`). Bản CIC Node đang deploy chỉ có API tra cứu
điểm theo CCCD, chưa có endpoint mapping hay consumer Kafka (B27); nhánh `feat/finora-delinquency-intake` của
`cic-service-node` bổ sung cả hai (D65, chờ Hải review). Worker mapping của User mặc định tắt
(`USER_CIC_MAPPING_ENABLED=false`, B70): chỉ bật khi CIC đích lưu mapping riêng tư.

Định danh nội bộ của `finora-ai` (từ `AI-CHAT-001`):

| Định danh | Giá trị | Ghi chú |
|---|---|---|
| Danh tính Loan → AI | Access token `client_credentials` của `finora-loan-client`, client role `ai:credit:score` (W3A, D34) | Chỉ dùng cho `POST /api/v1/ai/credit/explain`; secret `KEYCLOAK_LOAN_CLIENT_SECRET` chỉ ở `.env`. Header `X-Internal-Token` của bản đầu AI-CHAT-001 đã bỏ khi gộp `main` (2026-10-20) |
| API trợ lý | `/api/v1/ai/assistant/chat`, `/suggestions`, `/feedback` | Đi qua route `ai-service` có sẵn của Gateway (`/api/v1/ai/**`) |
| Header relay của trợ lý | `X-Client-Type: assistant` | Service đích nhận biết request do trợ lý gọi thay người dùng |
| Kiểm JWT trong `finora-ai` | Trợ lý: `KEYCLOAK_JWKS_URL`, `KEYCLOAK_ISSUERS` (`app/services/auth/`); router chấm điểm, cấu hình, eKYC, fraud: `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI` (`app/security.py`, D35) | Trong docker JWKS tải qua `keycloak:8080`, issuer giữ `http://localhost:8180/realms/finora` như các service Java |
| Nhà cung cấp LLM | Id cấu hình `cloudflare_gemma26b`, `groq_gptoss120b`, `groq_qwen27b`, `groq_gptoss20b`, `openrouter_gemma31b`, `gemini_gemma31b`, `ollama_local` | Thứ tự trong `ASSISTANT_PROVIDERS`; khóa API chỉ ở `finora-ai/.env` của máy chạy, không đưa vào web/mobile; `ollama_local` chỉ trên máy dev |

CURRENT STATE: Keycloak và `finora-user` dùng PostgreSQL cài trực tiếp trên máy host
(`localhost:5432`, database `keycloak` và `finora_user`); Docker chỉ còn chạy Keycloak và
`user-redis` cho luồng auth. Đăng ký / đăng nhập / quên mật khẩu đã smoke pass end-to-end
ngày 2026-08-20.

CURRENT STATE: Fineract 1.15.0/PostgreSQL 18.3 local đã healthy và tenant authentication
smoke pass ngày 2026-08-03; Product V2 `PROGRESSIVE` + advanced allocation đã được Fineract local chấp
nhận ngày 2026-10-04. Loan V2 50 triệu và repayment thật 5 triệu đã chứng minh principal còn 45 triệu
và `REAMORTIZATION` trên 6 kỳ; Kafka broker local cũng đã publish/consume envelope v1. Chưa có
MinIO. Fabric network/chaincode local có từ W3D (2026-10-17, bảng trên). Mail local dùng Mailpit (profile `mail`), thay cho MailHog.

## Phân bổ dự kiến — chưa phải thành phần đang chạy

| Thành phần                |              Port dự kiến | Storage/định danh             | Trạng thái                               |
| ------------------------- | ------------------------: | ----------------------------- | ---------------------------------------- |
| Fineract managed database | Endpoint qua secret store | Neon/PostgreSQL Project riêng | `PLANNED`; local fixture phải pass trước |

Fineract 1.15 dùng PostgreSQL; fixture release 1.15.0 được pin PostgreSQL 18.3. Fineract và Loan MUST dùng Project/database/credential/lifecycle riêng dù cùng dùng PostgreSQL.

Port host local có thể override bằng environment để tránh xung đột máy cá nhân; port nội bộ/service contract trong Docker không đổi. Mọi override dùng chung phải được ghi trong `docker/.env` và không commit file này.

Mỗi service MUST chỉ dùng endpoint/database/credential thuộc storage của mình (`loan-postgres`, `payment-postgres`, `blockchain-postgres`, `user-postgres`, `investment-postgres`, `user-redis`). Không dùng chung Neon Project hoặc lấy connection string project khác. Trong môi trường Neon khóa luận một owner, mỗi service MAY dùng default role do đúng project đó cấp để giảm cấu hình; trước production thật MUST tạo runtime role giới hạn quyền. Docker fallback và Keycloak PostgreSQL vẫn MUST dùng runtime role không có superuser.
