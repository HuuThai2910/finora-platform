# Registry cố định

Thay đổi giá trị trong bảng MUST cập nhật cấu hình, tài liệu liên quan và file này trong cùng change. Không tự đổi giá trị chỉ để tránh conflict local.

| Thành phần                    |                                 Port | Storage/định danh                                                                                                                                             |
| ----------------------------- | -----------------------------------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `finora-gateway`              |                                 8080 | Không có DB                                                                                                                                                   |
| `finora-loan`                 |                                 8081 | PostgreSQL/Neon Project `finora-loan`, DB `neondb`, default role của project                                                                                  |
| `finora-payment`              |                                 8082 | PostgreSQL/Neon Project `finora-payment`, DB `neondb`, default role của project; Redis nội bộ 6379                                                            |
| `finora-blockchain`           |                                 8083 | PostgreSQL/Neon Project `finora-blockchain`, DB `neondb`, default role của project; Fabric channel `finora-channel`, chaincode `finora-ledger`, MSP `Org1MSP` |
| `finora-investment`           |                                 8084 | PostgreSQL/Neon Project `finora-investment`, DB `neondb`, default role của project                                                                            |
| `finora-user`                 |                                 8085 | PostgreSQL/Neon Project `finora-user`, DB `neondb`, default role của project                                                                                  |
| `finora-notification`         |                                 8086 | PostgreSQL/Neon DB riêng; in-app delivery + REST/JWT + Kafka consumer; SMTP internal                                                                            |
| `finora-ai`                   |                                 8000 | FastAPI; chưa có DB                                                                                                                                           |
| Keycloak                      |                                 8180 | OIDC; realm `finora`, client `finora-user-client`. DB `keycloak` trên PostgreSQL host (không còn container `keycloak-db`)                                     |
| Kafka                         | 9092 host local; 29092 nội bộ Docker | Zookeeper 2181                                                                                                                                                |
| Loan PostgreSQL offline       |        15433 host local; 5432 nội bộ | PostgreSQL 17, `loan-postgres`, volume/user riêng                                                                                                             |
| Payment PostgreSQL offline    |        15434 host local; 5432 nội bộ | PostgreSQL 17, `payment-postgres`, volume/user riêng                                                                                                          |
| Blockchain PostgreSQL offline |        15435 host local; 5432 nội bộ | PostgreSQL 17, `blockchain-postgres`, volume/user riêng                                                                                                       |
| User PostgreSQL               |                       5432 host local | PostgreSQL cài trên máy host, DB `finora_user` (không còn container `user-postgres`)                                                                          |
| User Redis                    |         6381 host local; 6379 nội bộ | Redis 7, `user-redis`, volume riêng; OTP reset password + rate limit đăng nhập                                                                                |
| Investment PostgreSQL offline |        15437 host local; 5432 nội bộ | PostgreSQL 17, `investment-postgres`, volume/user riêng                                                                                                       |
| Notification PostgreSQL offline |      15438 host local; 5432 nội bộ | PostgreSQL 17, `notification-postgres`, volume/user riêng                                                                                                     |
| Payment Redis                 |         6380 host local; 6379 nội bộ | Redis 7, password/volume riêng của Payment                                                                                                                    |
| Apache Fineract 1.15.0        |   18443 host local; 8443 nội bộ HTTP | Tenant `default`; API prefix `/fineract-provider/api/v1`                                                                                                      |
| Fineract PostgreSQL           |        15432 host local; 5432 nội bộ | PostgreSQL 18.3 riêng; `fineract_tenants`, `fineract_default`                                                                                                 |
| Mailpit (tùy chọn, dev)       |  8025 UI; 1025 SMTP (host local)     | Mail server local bắt thư để test; không lưu trữ lâu dài, không gửi ra Internet                                                                               |

Realm role Keycloak hiện có: `ROLE_BORROWER`, `ROLE_INVESTOR`, `ROLE_ADMIN` — khớp enum `UserRole`
của `finora-user`. Nguồn chuẩn là `docker/keycloak/template/realm-finora.json`; secret của client được
render lúc chạy từ `KEYCLOAK_CLIENT_SECRET` trong `docker/.env`, không commit vào template.

Consumer group hiện có: `payment-group`, `blockchain-group`, `notification-group`,
`notification-investor-servicing`, `cic-loan-delinquency`.

Contract Loan–Investment đã được Thái chấp thuận triển khai ngày 2026-09-26; Hải phải review
phần Investment trước merge:

| Topic | Producer | Consumer group | Tác dụng |
|---|---|---|---|
| `finora.loan.funding-requested` | Loan | `investment-loan-funding` | Tạo market listing từ exact terms đã được borrower cho phép |
| `finora.investment.loan-fully-funded` | Investment | `loan-investment-funding` | Trả allocation bất biến để Loan lập hợp đồng nhiều bên |
| `finora.loan.investor-signature-requested` | Loan | `investment-loan-funding` | Gắn Contract/PDF chung vào listing; Notification consumer bổ sung sau |
| `finora.loan.borrower-signature-requested` | Loan | Chưa đăng ký | Báo tất cả lender đã ký; Notification consumer bổ sung sau |
| `finora.loan.contract-activated` | Loan | `investment-loan-funding` | Investment cập nhật Contract có hiệu lực; Payment/Blockchain bổ sung sau |
| `finora.loan.delinquency-changed` | Loan | `cic-loan-delinquency`, `investment-loan-funding` | CIC tách nhóm hiện tại/lịch sử; Investment cập nhật DPD/nhóm nợ cho Note projection |
| `finora.loan.settled` | Loan | `investment-loan-funding` | Investment lưu lifecycle projection; không dùng thay `RepaymentDistributed` để chia tiền/đóng Note |
| `finora.loan.rescheduled` | Loan | `investment-loan-funding` | Fineract đã approve lịch cơ cấu; Investment cập nhật maturity marker, không tự tính schedule |
| `finora.payment.repayment-distributed` | Payment | `loan-repayment-distributed`, `investment-loan-funding`, `blockchain-group` | Loan/Investment cập nhật projection; Blockchain chỉ neo SHA-256 data, không lưu payload tài chính thô |
| `finora.investment.note-servicing-changed` | Investment | `notification-investor-servicing` | Fan-out thông báo servicing cho chủ Note; dedup delivery, không chứa PII |

Payload và consumer hiện có được mô tả tại
[`docs/integrations/LOAN-INVESTMENT-EVENTS.md`](../../docs/integrations/LOAN-INVESTMENT-EVENTS.md).
Notification chỉ là consumer ở topic servicing đã đăng ký rõ. Payment và Blockchain chỉ là consumer
ở các topic đã đăng ký rõ trong bảng/contract; không suy diễn subscription khác.

Mapping `borrowerId ↔ CCCD` được đăng ký trực tiếp vào CIC qua API nội bộ có
`X-Finora-Internal-Key`; tuyệt đối không đưa CCCD thô vào Kafka. Event đến trước mapping
được giữ trong `cic_pending_delinquency_events` và replay sau khi mapping được tạo.
User tự tạo `user_cic_mapping_tasks` cùng transaction xác nhận eKYC, dùng `keycloakUserId`
làm `borrowerId` và retry có lease. Task chỉ giữ `user_profile_id`; không sao chép CCCD thô.

CURRENT STATE: Keycloak và `finora-user` dùng PostgreSQL cài trực tiếp trên máy host
(`localhost:5432`, database `keycloak` và `finora_user`); Docker chỉ còn chạy Keycloak và
`user-redis` cho luồng auth. Đăng ký / đăng nhập / quên mật khẩu đã smoke pass end-to-end
ngày 2026-08-20.

CURRENT STATE: Fineract 1.15.0/PostgreSQL 18.3 local đã healthy và tenant authentication
smoke pass ngày 2026-08-03; Product V2 `PROGRESSIVE` + advanced allocation đã được Fineract local chấp
nhận ngày 2026-10-04. Loan V2 50 triệu và repayment thật 5 triệu đã chứng minh principal còn 45 triệu
và `REAMORTIZATION` trên 6 kỳ; Kafka broker local cũng đã publish/consume envelope v1. Chưa có
MinIO hoặc Fabric network/chaincode. Mail local dùng Mailpit (profile `mail`), thay cho MailHog.

## Phân bổ dự kiến — chưa phải thành phần đang chạy

| Thành phần                |              Port dự kiến | Storage/định danh             | Trạng thái                               |
| ------------------------- | ------------------------: | ----------------------------- | ---------------------------------------- |
| Fineract managed database | Endpoint qua secret store | Neon/PostgreSQL Project riêng | `PLANNED`; local fixture phải pass trước |

Fineract 1.15 dùng PostgreSQL; fixture release 1.15.0 được pin PostgreSQL 18.3. Fineract và Loan MUST dùng Project/database/credential/lifecycle riêng dù cùng dùng PostgreSQL.

Port host local có thể override bằng environment để tránh xung đột máy cá nhân; port nội bộ/service contract trong Docker không đổi. Mọi override dùng chung phải được ghi trong `docker/.env` và không commit file này.

Mỗi service MUST chỉ dùng endpoint/database/credential thuộc storage của mình (`loan-postgres`, `payment-postgres`, `blockchain-postgres`, `user-postgres`, `investment-postgres`, `payment-redis`). Không dùng chung Neon Project hoặc lấy connection string project khác. Trong môi trường Neon khóa luận một owner, mỗi service MAY dùng default role do đúng project đó cấp để giảm cấu hình; trước production thật MUST tạo runtime role giới hạn quyền. Docker fallback và Keycloak PostgreSQL vẫn MUST dùng runtime role không có superuser.
