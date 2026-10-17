# Ma trận môi trường FINORA

Tài liệu R9 (task plan [`W3E`](../../.agents/plans/W3E-ci-health-operations.md)). Trả lời ba câu hỏi cho từng môi trường:
dependency nào chạy ở đâu (Docker, IDE, URL ngoài, sandbox hay mock), cần đặt biến môi trường nào (chỉ ghi tên, không
ghi giá trị) và kiểm tra sức khỏe từng service bằng gì. Cổng và định danh storage là nguồn chuẩn ở
[`05-registry.md`](../../.agents/rules/05-registry.md); cách chạy stack E2E ở
[`E2E-VERTICAL-SLICE.md`](../testing/E2E-VERTICAL-SLICE.md); xử lý sự cố ở [`RUNBOOKS.md`](RUNBOOKS.md).

## 1. Bốn môi trường

| Môi trường | Dùng để làm gì | Cách dựng | `FINORA_ENVIRONMENT` | Dữ liệu |
|---|---|---|---|---|
| `local` | Phát triển trên máy từng người | Hạ tầng bằng `docker/docker-compose.yml` theo profile; service Java chạy trong IntelliJ hoặc `java -jar` | `local` (mặc định) | Neon dev dùng chung **hoặc** PostgreSQL Docker riêng của máy (xem mục 4) |
| `e2e` | Harness tự động `docker/e2e` (R1A/R1B, drill Q13) | `start-local-services.ps1` chạy jar với profile `local`, DB local riêng (`-Database local`, D24) | `local` (cổng môi trường cho phép cấu hình giả) | PostgreSQL Docker riêng cho Loan/Payment/Investment/Notification/Blockchain; tài khoản mới mỗi lượt |
| `test` | `mvn verify`, pytest, CI GitHub Actions ([`CI.md`](CI.md)) | Testcontainers dựng PostgreSQL 17.5 cho từng test; không có broker Kafka, Fineract hay Keycloak thật | không đặt | Tạo và hủy theo lượt test, không có dữ liệu thật |
| `demo` | Trình diễn đầy đủ (bảo vệ khóa luận) | `docker/docker-compose.app.yml` chạy 6 service trong Docker | `demo` | Neon dev; tiền là tiền giả lập/sandbox |

`staging`/`production` chưa có hạ tầng. Cổng môi trường (D37, W3A) đã chặn khởi động ở hai giá trị này khi còn khóa dev,
provider tiền `mock`, chữ ký `MOCK`, đồng hồ/điểm dừng E2E.

## 2. Dependency chạy ở đâu

| Dependency | `local` | `e2e` | `test` | `demo` |
|---|---|---|---|---|
| PostgreSQL Loan/Payment/Investment/Blockchain/Notification | Neon dev hoặc container `*-postgres` (PostgreSQL 17.5, profile theo service) | Container `*-postgres` riêng của stack (mật khẩu sinh vào `docker/e2e/out/.env.e2e`) | Testcontainers `postgres:17.5-alpine` | Neon dev |
| PostgreSQL User và Keycloak | PostgreSQL cài trên máy host (`5432`) | Như `local` | Testcontainers (User) | Như `local` (host hoặc Neon qua `KEYCLOAK_DB_URL`) |
| Keycloak 23 | Docker, profile `core`/`demo` | Docker | Không chạy: test dùng `jwt()` của spring-security-test | Docker |
| Kafka 3.9 (KRaft) | Docker, profile `kafka`/`demo` | Docker | Không chạy: listener tắt; health test trỏ `localhost:1` | Docker |
| Redis của User | Docker `user-redis`, profile `user`/`demo` | Docker (harness đọc OTP ở đây) | Testcontainers `redis:7-alpine` (`UserHealthProbesIT`) | Docker |
| Redis của Payment | **Không có** (B23: Payment không dùng Redis) | — | — | — |
| Apache Fineract 1.15 + PostgreSQL 18.3 | Docker, profile `fineract`; Loan mặc định `FINERACT_BOOKING_PROVIDER=mock` | Docker, `FINERACT_BOOKING_PROVIDER=fineract`, Product namespace `E2E` | Mock (`MockRestServiceServer`, gateway giả) | Docker (`docker-compose.app.yml`, PostgreSQL host) |
| Job cuối ngày Fineract (`LA_RINT`, `LA_ARAG`) | Fixture local không bảo đảm cron đang bật; health `fineractEndOfDay` (khi booking `fineract`) cho biết, chạy tay theo runbook mục 5 | Harness chạy khi dời đồng hồ (D25) | — | Phải bật cron 00:01 hoặc chạy tay mỗi ngày; theo dõi health `fineractEndOfDay` |
| AI v17 (FastAPI) | Docker, profile `ai`/`demo`, hoặc `uvicorn` | Docker | pytest trong venv/CI; CIC và Keycloak được giả lập trong test | Docker |
| CIC Node | URL ngoài `CIC_SERVICE_URL` (`https://cic-service-node.vercel.app`) | URL ngoài | Giả lập (`pytest-httpx`) | URL ngoài |
| Ký hợp đồng | `FINORA_SIGNATURE_PROVIDER=MOCK` (click-wrap) hoặc VNPT SmartCA UAT (sandbox ngoài) | `MOCK` | Mock | SmartCA UAT nếu đã có thông tin `VNPT_SMARTCA_*`, nếu không `MOCK` |
| Nạp tiền | `PAYMENT_TOPUP_PROVIDER=mock` hoặc ZaloPay sandbox | `mock` | Mock | ZaloPay sandbox hoặc `mock` |
| Giải ngân ra ngoài | `PAYMENT_DISBURSEMENT_PROVIDER=mock` (ZaloPay disbursement fail-closed) | `mock` | Mock | `mock` |
| Rút tiền (W3B) | `PAYMENT_PAYOUT_PROVIDER=mock`, callback HMAC thật tới Payment | `mock` | Mock | `mock` |
| Blockchain ledger | `BLOCKCHAIN_PROOF_PROVIDER=MOCK` | `MOCK` (listener + worker bật) | Mock | `MOCK`; Fabric local chỉ khi bật theo D14/Q10 |
| Email | Mailpit (profile `mail`) hoặc Gmail SMTP | Không cần (OTP đọc từ Redis) | `@MockBean` | Gmail SMTP hoặc Mailpit |
| Fault injection | — | Toxiproxy (profile `e2e-faults`, Q13) | — | Không bật |

## 3. Biến môi trường theo service (chỉ tên)

"Bắt buộc" là biến không có giá trị mặc định: thiếu thì service không khởi động hoặc chức năng tương ứng báo lỗi rõ.
"Phải đổi ở demo" là biến có mặc định chỉ dành cho máy dev; cổng môi trường D37 chặn chúng ở `staging`/`production`.
Danh sách đầy đủ (topic, nhịp worker) nằm trong `application.yml` của từng module; mọi secret chỉ đặt trong `.env`
git-ignore của module hoặc `docker/.env`.

| Service | Bắt buộc | Phải đổi ở demo/production | Tùy chọn quan trọng |
|---|---|---|---|
| Loan | `LOAN_DB_PASSWORD`, `FINERACT_API_USERNAME`, `FINERACT_API_PASSWORD` | `FINORA_PIN_TOKEN_SECRET`, `KEYCLOAK_LOAN_CLIENT_SECRET` | `LOAN_DB_URL`, `LOAN_DB_USERNAME`, `FINERACT_BASE_URL`, `FINERACT_BOOKING_PROVIDER`, `FINERACT_OFFICE_ID`, `FINERACT_DISBURSEMENT_PAYMENT_TYPE_ID`, `FINERACT_PRODUCT_NAMESPACE`, `FINERACT_RESCHEDULE_REASON_ID`, `FINORA_SIGNATURE_PROVIDER`, `VNPT_SMARTCA_*`, `AI_SERVICE_URL`, `USER_SERVICE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `LOAN_FINERACT_EOD_CHECK_ENABLED`, `LOAN_FINERACT_EOD_STALE_AFTER`, `LOAN_FINERACT_EOD_REFRESH_INTERVAL` |
| Payment | `PAYMENT_DB_PASSWORD` | `FINORA_PIN_TOKEN_SECRET`, `PAYMENT_ACCOUNT_ENCRYPTION_KEY` (W3B), `PAYMENT_MOCK_PAYOUT_CALLBACK_SECRET` (W3B), `ZALOPAY_KEY1`, `ZALOPAY_KEY2` | `PAYMENT_DB_URL`, `PAYMENT_DB_USERNAME`, `FINERACT_BASE_URL`, `FINERACT_API_USERNAME`, `FINERACT_API_PASSWORD`, `PAYMENT_TOPUP_PROVIDER`, `PAYMENT_DISBURSEMENT_PROVIDER`, `PAYMENT_PAYOUT_PROVIDER`, `PAYMENT_WITHDRAWAL_*`, `ZALOPAY_APP_ID`, `ZALOPAY_CALLBACK_URL`, `PAYMENT_CORE_REPOST_SAFE_AFTER`, `KAFKA_BOOTSTRAP_SERVERS` |
| Investment | `INVESTMENT_DB_URL`, `INVESTMENT_DB_USERNAME`, `INVESTMENT_DB_PASSWORD` | `FINORA_PIN_TOKEN_SECRET`, `KEYCLOAK_INVESTMENT_CLIENT_SECRET` | `PAYMENT_SERVICE_URL`, `INVESTMENT_PAYMENT_MODE`, `INVESTMENT_REQUIRE_EKYC`, `INVESTMENT_AUTO_INVEST_ENABLED`, `INVESTMENT_RISK_DISCLOSURE_VERSION`, `KEYCLOAK_TOKEN_URL`, `KAFKA_BOOTSTRAP_SERVERS` |
| Blockchain | `BLOCKCHAIN_DB_PASSWORD` | — | `BLOCKCHAIN_DB_URL`, `BLOCKCHAIN_DB_USERNAME`, `BLOCKCHAIN_KAFKA_LISTENER_ENABLED`, `BLOCKCHAIN_PROOF_WORKER_ENABLED`, `BLOCKCHAIN_PROOF_PROVIDER`, `FABRIC_*`, `KAFKA_BOOTSTRAP_SERVERS` |
| User | `USER_DB_PASSWORD` | `FINORA_PIN_TOKEN_SECRET`, `FINORA_HMAC_SECRET`, `FINORA_AES_SECRET`, `KEYCLOAK_CLIENT_SECRET` | `USER_DB_URL`, `USER_DB_USERNAME`, `USER_REDIS_HOST`, `USER_REDIS_PORT`, `USER_REDIS_PASSWORD`, `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM`, `AI_SERVICE_URL`, `NOTIFICATION_SERVICE_URL`, `CIC_SERVICE_URL`, `CIC_INTERNAL_API_KEY`, `USER_CIC_MAPPING_ENABLED`, `AUTH_COOKIE_DOMAIN`, `AUTH_COOKIE_SECURE` |
| Notification | `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_USERNAME`, `NOTIFICATION_DB_PASSWORD` | `MAIL_PASSWORD` (khi dùng Gmail) | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, `KAFKA_BOOTSTRAP_SERVERS` |
| Gateway | — | — | Profile `docker` (`SPRING_PROFILES_ACTIVE=docker`) đổi URI route sang tên service Compose |
| AI | `GEMINI_API_KEY` (chỉ OCR eKYC) | `KEYCLOAK_CLIENT_SECRET` (client `finora-ai-client`) | `CIC_SERVICE_URL`, `USER_SERVICE_URL`, `KEYCLOAK_TOKEN_URL`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `FINORA_ENVIRONMENT` |
| Docker (`docker/.env`) | `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_DB_URL`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_INVESTMENT_CLIENT_SECRET`, `FINERACT_POSTGRES_ADMIN_PASSWORD`, `FINERACT_DB_PASSWORD`, `*_POSTGRES_ADMIN_PASSWORD`/`*_DB_PASSWORD` của container DB đang bật | `KEYCLOAK_LOAN_CLIENT_SECRET`, `KEYCLOAK_AI_CLIENT_SECRET` (script `add-service-clients.sh` sinh) | `*_HOST_PORT`, `FINORA_ENVIRONMENT`, `CIC_SERVICE_URL` |

Biến chung mọi service Java: `FINORA_ENVIRONMENT` (`local`/`e2e`/`demo`/`staging`/`production`),
`FINORA_BUSINESS_TIME_ZONE` (Loan/Payment), `finora.operations.metrics.*` (nhịp gauge vận hành, mặc định 60 giây, trần
đếm 10.000) và `finora.operations.health.kafka.*` (timeout 3 giây, cache 30 giây). `FINORA_E2E_*` chỉ harness E2E đặt.

## 4. Tách database theo môi trường (B21, B22)

- **Loan DB và Fineract phải đi theo cặp.** Bảng mapping Product↔Fineract, `fineract_loan_id` của saga và projection
  servicing trỏ vào đúng một Fineract. Dùng DB Loan dùng chung (Neon dev) cùng Fineract local của từng máy làm worker sync
  của máy này gặp khoản vay của máy khác (B21: một dòng `warn` mỗi khoản, mỗi chu kỳ). Quy tắc: chạy Fineract thật thì
  dùng DB local riêng (`-Database local` của launcher, hoặc container `loan-postgres` khi chạy IntelliJ) và
  `FINERACT_PRODUCT_NAMESPACE` khác rỗng nếu nhiều DB Loan dùng chung một Fineract; DB dùng chung chỉ dùng với
  `FINERACT_BOOKING_PROVIDER=mock`.
- **Migration không được trễ ở DB dùng chung (B22).** CI chạy toàn bộ Flyway từ DB rỗng ở mọi job Java (Testcontainers),
  nên migration hỏng bị chặn trước merge. Người merge migration mới chạy service đó một lần với DB dev dùng chung (Flyway tự
  migrate khi khởi động) và ghi version vào mô tả PR; health readiness `db` không thay được bước này vì Flyway chạy trước
  khi service nhận traffic.

## 5. Health check theo service

Mọi service Java có `/actuator/health/liveness` (chỉ JVM còn chạy, không dependency) và `/actuator/health/readiness`
(dependency mà mọi request cần). Gọi không cần token; tên và trạng thái thành phần hiện cho mọi người, chi tiết (lý do,
thời điểm, lỗi) chỉ hiện với token `ROLE_ADMIN`. Nhóm `operations` chứa thứ vận hành phải xem nhưng không được rút service
khỏi traffic (D56, D57). Compose healthcheck dùng readiness, không chỉ kiểm cổng mở.

| Service | Cổng | Readiness gồm | Nhóm `operations` | Ghi chú |
|---|---:|---|---|---|
| Gateway | 8080 | `readinessState` | — | Không gộp health service phía sau |
| Loan | 8081 | `readinessState`, `db` | `fineractEndOfDay`, `kafka` | `fineractEndOfDay`: UP/DOWN/UNKNOWN (mục 6) |
| Payment | 8082 | `readinessState`, `db` | `kafka` | Không còn `redis` (B23) |
| Blockchain | 8083 | `readinessState`, `db` | — | `kafka` chỉ có khi `BLOCKCHAIN_KAFKA_LISTENER_ENABLED=true`; không hiện chi tiết vì đường health bỏ qua chain bảo mật (sau W3D có JWT thì chuyển sang `permitAll()` + `when-authorized` như service khác) |
| Investment | 8084 | `readinessState`, `db` | `kafka` | |
| User | 8085 | `readinessState`, `db`, `redis` | — | Keycloak không vào readiness (dùng chung mọi service) |
| Notification | 8086 | `readinessState`, `db` | `kafka` | Health `mail` tắt: không mở kết nối SMTP mỗi lần health |
| AI | 8000 | `GET /health` (tĩnh) | — | Không kiểm Gemini/CIC; lỗi CIC hiện ở hồ sơ `PENDING_REVIEW` |
| Keycloak | 8180 | `GET /health/ready` (cổng nội bộ 8080) | — | |
| Fineract | 18443 | `/usr/local/bin/finora-fineract-readiness` (gọi API có xác thực) | — | `/fineract-provider/actuator/health` chỉ là tiến trình |
| Kafka | 9092 | `kafka-topics.sh --list` | — | |
| PostgreSQL container | 1543x | `psql ... SELECT 1` bằng user ứng dụng | — | |

Vì sao Kafka không vào readiness: event đi qua transactional outbox trong DB, broker tắt chỉ làm event đến muộn; readiness
chỉ quyết định có nhận HTTP traffic hay không, nên Kafka trong readiness sẽ rút mọi service (dùng chung một broker) khỏi
traffic cùng lúc mà không giúp gì cho consumer. Độ trễ thật hiện ở gauge `finora.outbox.oldest.pending.age`.

## 6. Health nghiệp vụ `fineractEndOfDay` (Loan)

| Trạng thái | Khi nào | Việc cần làm |
|---|---|---|
| `UP` | `LA_RINT` và `LA_ARAG` có lần chạy thành công trong `LOAN_FINERACT_EOD_STALE_AFTER` (mặc định 26 giờ) | — |
| `DOWN` `FINERACT_EOD_STALE` | Một job chưa từng thành công hoặc lần thành công gần nhất cũ hơn ngưỡng | Runbook mục 5 |
| `DOWN` `FINERACT_EOD_JOB_MISSING` | Fineract không có job bắt buộc | Kiểm tenant/phiên bản Fineract |
| `UNKNOWN` | Chưa kiểm lần nào, không đọc được Fineract (`checkError`), thiếu lịch sử, hoặc provider booking `mock` (`FINERACT_EOD_NOT_APPLICABLE`) | Xem `checkError`; tài khoản API cần quyền `READ_SCHEDULER` |

Lý do 26 giờ: một chu kỳ cron 24 giờ cộng 2 giờ dư cho job chạy chậm hoặc lệch giờ. Tuổi được tính theo giờ thật (không
theo đồng hồ E2E), mỗi 5 phút Loan đọc `GET /jobs` một lần; health chỉ đọc kết quả đã lưu nên không chờ Fineract.

## 7. Metric vận hành

`/actuator/metrics/<tên>` ở Loan, Payment, Investment cần token `ROLE_ADMIN`. Giá trị làm mới mỗi 60 giây trên luồng
riêng; `NaN` nghĩa là chưa đọc được (không phải 0). Ý nghĩa và cách xử lý từng hàng đợi ở [`RUNBOOKS.md`](RUNBOOKS.md) mục 2.

| Metric | Tag | Service |
|---|---|---|
| `finora.outbox.events` | `service`, `status` = `pending`/`dead_letter` | Loan, Payment, Investment |
| `finora.outbox.oldest.pending.age` (giây) | `service` | Loan, Payment, Investment |
| `finora.operations.incidents` | `service`, `type` | Loan, Payment, Investment |
| `finora.fineract.eod.last.success.age` (giây) | `job` | Loan |
| `finora.proof.submissions`, `finora.proof.oldest.pending.age` | `service`, `status` | Blockchain (chưa mở endpoint; sau W3D có chain JWT nên mở được cùng quy tắc `/actuator/** hasRole("ADMIN")`) |
| `finora.operations.gauge.refresh.failures`, `finora.operations.task.failures` | `service`/`metric`, `task` | Mọi service có gauge |
