# Chạy hạ tầng FINORA local

## 1. Hai chế độ database FINORA

### Chế độ hằng ngày — Neon

Loan, Payment, Blockchain, User, Investment và hai database của Fineract kết nối thẳng PostgreSQL trên Neon bằng biến môi trường. Không cần bật container database local.

Hướng dẫn tạo từng Neon Project, DBeaver và IntelliJ: [NEON-POSTGRESQL-SETUP.md](../NEON-POSTGRESQL-SETUP.md).

### Chế độ offline/test — Docker

Docker Compose giữ PostgreSQL riêng từng service để làm việc khi mất mạng, kiểm tra migration hoặc chạy local integration:

| Profile | Container dữ liệu | Host port | Volume |
|---|---|---:|---|
| `loan` | `finora-loan-postgres` | `15433` | `loan-postgres-data` |
| `fineract-local-db` | `finora-fineract-postgres` | `15432` | `fineract-postgres-data` |
| `fineract` | `finora-fineract` | `18443` | không lưu DB trong app container |
| `ai` | `finora-ai` | `8000` | model v17 nằm trong image, không có database |
| `payment` | `finora-payment-postgres` | `15434` | `payment-postgres-data` (Payment không dùng Redis, B23) |
| `blockchain-local-db` | `finora-blockchain-postgres` | `15435` | `blockchain-postgres-data` |
| `fabric` | `finora-fabric-orderer`, `finora-fabric-peer0-org1/org2`, `finora-fabric-ccaas-org1/org2` | `7051` (gateway peer0.org1) | `fabric-orderer-data`, `fabric-peer0-org1-data`, `fabric-peer0-org2-data`; dựng bằng [`fabric-network/`](fabric-network/README.md) |
| `user` | `finora-user-redis` | `6381` | `user-redis-data` |
| `investment` | `finora-investment-postgres` | `15437` | `investment-postgres-data` |
| `core` | `finora-keycloak` | `8180` | `keycloak-import` (file realm đã render) |
| `demo` | Kafka + `finora-user` + Keycloak + Redis + AI | `9092`, `8085`, `8180`, `6381`, `8000` | Database User/Keycloak nằm ngoài Compose; CIC là Node đã deploy qua `CIC_SERVICE_URL` |
| `mail` | `finora-mailpit` | `8025` UI, `1025` SMTP | không lưu trữ lâu dài (tối đa 500 thư) |
| `ekyc` | `finora-seaweedfs` + `finora-seaweedfs-init` | `9100` (S3 API) | `seaweedfs-data` — ảnh CCCD/video liveness thử nghiệm (mục 4.3) |

Database PostgreSQL của Keycloak và `finora-user` không nằm trong Compose; chúng có thể
chạy trên máy host hoặc Neon. Container Keycloak vẫn chạy trong Docker. Xem mục 4.0 và 4.1.

## 1b. Chạy toàn bộ service trong Docker với DB Neon (profile `apps`)

Thay cho việc mở IntelliJ: sáu service Java (Loan, Payment, Investment, Notification, Blockchain, Gateway) chạy trong
container `eclipse-temurin:21-jre` bằng **jar đã build sẵn**, không build image. Mỗi service đọc `.env` của module (DB Neon
như khi chạy IntelliJ); Compose chỉ đổi địa chỉ `localhost` trong `.env` (Kafka, Keycloak, Fineract, AI, User, Payment) sang
tên service trong mạng Docker. Blockchain dùng DB Neon `finora_blockchain`; Fineract dùng DB Neon `fineract_tenants` và
`fineract_default`. Các container PostgreSQL local không thuộc profile hằng ngày; volume cũ chỉ giữ làm fallback offline.

```powershell
# Build jar một lần trước lần bật đầu tiên
mvn -B -ntp -pl finora-loan,finora-payment,finora-investment,finora-notification,finora-blockchain,finora-gateway -am package -DskipTests
# Bật hạ tầng + sáu service; Fineract và Blockchain tự kết nối Neon.
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile apps up -d
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile apps ps
# Sửa code một service: dừng container trước (container giữ khóa file jar, Maven không ghi đè được), build, bật lại
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile apps stop loan
mvn -B -ntp -pl finora-loan -am package -DskipTests
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile apps up -d loan
```

- Mọi lệnh `docker compose` của profile `apps` phải có `demo`, `fineract` và `apps`: `demo` cung cấp Kafka/Keycloak/User/AI,
  còn `fineract` bật core banking. Tắt riêng sáu service app, giữ hạ tầng: `... stop loan payment investment notification blockchain gateway`.

- Cần `FINERACT_DB_HOST`, hai tên DB Fineract, `BLOCKCHAIN_DB_URL`, username/password và SSL trong `docker/.env`.
  `*_POSTGRES_ADMIN_PASSWORD` chỉ dùng khi chủ động bật profile fallback `fineract-local-db`/`blockchain-local-db`.
- **Mặc định giả lập để test UI (D85)**, ghi đè `.env` của module: Loan ký `MOCK` và coi người vay đã eKYC (`mock`),
  Investment tắt kiểm eKYC nhà đầu tư và worker Auto-Invest. Đổi bằng `APPS_SIGNATURE_PROVIDER`,
  `APPS_BORROWER_PROFILE_PROVIDER`, `APPS_INVESTMENT_REQUIRE_EKYC`, `APPS_INVESTMENT_AUTO_INVEST_ENABLED` trong `docker/.env`
  rồi `up -d loan investment` (tạo lại container; `restart` giữ biến cũ). Ký SmartCA UAT ngay trong container Loan:
  `APPS_SIGNATURE_PROVIDER=VNPT_SMART_CA`, thông tin `VNPT_SMARTCA_*` đọc từ `finora-loan/.env`; không cần IntelliJ vì
  SmartCA không gọi ngược vào Loan.
- **Fabric thật (D86):** `powershell -ExecutionPolicy Bypass -File docker/fabric-network/fabric-network.ps1 -Action Up`
  (dựng mạng hai tổ chức, kiểm RAM, thêm khoảng 200–400 MB), đặt `BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC` trong
  `docker/.env` rồi `up -d blockchain`. Container Blockchain nối peer qua mạng Docker và đọc danh tính trong
  `docker/fabric-network` (mount chỉ đọc). Xong: xóa biến, `up -d blockchain`, rồi `fabric-network.ps1 -Action Down`.
- Muốn debug một service bằng IntelliJ: `docker stop finora-<service>` rồi chạy service đó trong IntelliJ (trùng cổng nếu không
  dừng container).
- Lần đầu chạy code mới, Flyway áp migration còn thiếu lên DB Neon (dùng chung với cả nhóm); migration đã áp không gỡ được.
- Các ca cần dời đồng hồ (quá hạn, nợ xấu) vẫn dùng launcher E2E với `-Database local` (`docs/testing/E2E-VERTICAL-SLICE.md`).

## 2. Chuẩn bị Docker offline một lần

```powershell
Copy-Item docker/.env.example docker/.env
```

Điền secret của đúng scope. File `docker/.env` bị Git ignore và không được chứa credential Neon/production dùng chung.

Nếu `docker/.env` được tạo từ bản MySQL/MongoDB cũ, đổi tên biến trước khi chạy:

| Biến cũ | Biến PostgreSQL mới | Giá trị port local |
|---|---|---:|
| `INVESTMENT_MONGO_ROOT_PASSWORD` | `INVESTMENT_POSTGRES_ADMIN_PASSWORD` | — |
| `INVESTMENT_MONGO_HOST_PORT` | `INVESTMENT_POSTGRES_HOST_PORT` | `15437` |

Xóa `INVESTMENT_MONGO_ROOT_USERNAME`; PostgreSQL admin local luôn là `postgres` và chỉ dùng khi khởi tạo. Không commit nội dung `docker/.env` sau khi đổi.

PostgreSQL container tạo:

- admin role `postgres` chỉ dùng để khởi tạo;
- runtime role `finora_<service>` không có quyền superuser;
- database và volume riêng của đúng service.

## 3. Chạy riêng database Loan

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan up -d loan-postgres
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan ps loan-postgres
```

Sau đó chạy `FinoraLoanApplication` trong IntelliJ:

- Active profiles: `local`;
- `LOAN_DB_PASSWORD`: cùng giá trị trong `docker/.env`;
- không đặt `LOAN_DB_URL` nếu dùng URL local mặc định.

URL local mặc định:

```text
jdbc:postgresql://localhost:15433/finora_loan
```

Dừng container nhưng giữ volume:

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan stop loan-postgres
```

## 4. Các scope khác

### Chạy bộ tích hợp User + Keycloak + AI

Compose chuẩn dùng profile `demo`; không cần file compose thứ hai:

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo ps
```

Trước khi chạy, cập nhật `docker/.env` theo `docker/.env.example`:

- `KEYCLOAK_DB_URL` là JDBC URL đầy đủ của database Keycloak; Neon cần
  hậu tố `?sslmode=require`.
- `USER_DB_URL`, `USER_DB_USERNAME`, `USER_DB_PASSWORD` trỏ tới database User.
- `CIC_SERVICE_URL` trỏ tới CIC Node đã deploy, mặc định
  `https://cic-service-node.vercel.app/`; Compose không còn build CIC Java cũ.
- `CIC_INTERNAL_API_KEY` bảo vệ API nội bộ tạo mapping borrowerId–CCCD; phải dùng cùng giá trị
  ở client nội bộ gọi CIC và không commit secret thật.
- `USER_CIC_MAPPING_ENABLED` (mặc định `false`, B70) bật worker User. Sau eKYC, User tự đăng ký mapping
  `borrowerId ↔ CCCD` vào CIC; CIC tạm tắt thì task nằm trong PostgreSQL User và retry, không làm eKYC thất bại.
  Chỉ bật khi CIC đích lưu mapping riêng tư, ví dụ CIC Node chạy local cho E2E. Bản CIC Node deploy trên Vercel là
  mock công khai: API đọc hồ sơ/nhật ký không xác thực, dữ liệu nằm trong Blob `public`, tra một CCCD lạ là lưu hồ sơ
  mới kèm nhật ký. Không eKYC hay tra cứu bằng CCCD thật khi FINORA trỏ tới bản này.
- `KEYCLOAK_CLIENT_SECRET` phải đúng client `finora-user-client` trong realm đang dùng.
- `FINORA_AES_SECRET` và `FINORA_HMAC_SECRET` phải đúng với dữ liệu User hiện có.
- `GEMINI_API_KEY` chỉ bắt buộc khi test OCR/eKYC.

Health:

```text
Keycloak: http://localhost:8180/realms/finora/.well-known/openid-configuration
User:     http://localhost:8085/actuator/health/readiness   (liveness: /actuator/health/liveness)
AI:       http://localhost:8000/health
CIC:      https://cic-service-node.vercel.app/
```

AI và User chỉ gọi CIC qua REST bằng `CIC_SERVICE_URL`, không đọc database CIC.
`CIC_INTERNAL_API_KEY` vẫn phải khớp với cấu hình endpoint nội bộ của bản Node khi bật
đồng bộ borrower mapping. Migration User V4 tự backfill task cho hồ sơ `VERIFIED` cũ;
endpoint tạm lỗi thì task được giữ trong PostgreSQL User để retry.

### Chạy AI v17 cho LN-007

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai up -d --build ai
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai ps ai
```

Health: `http://localhost:8000/health`; OpenAPI: `http://localhost:8000/docs`.

Lần đầu Docker build image `finora/ai:17.0.0-local`. Container chạy non-root, chỉ copy source,
gói credit model v17, fraud model hiện hành và cấu hình policy runtime; không copy CSV huấn luyện.
Do có `restart: unless-stopped`, sau lần tạo đầu
chỉ cần mở Docker Desktop thì container tự chạy lại, trừ khi người dùng đã chủ động stop nó.
AI không cần username/password hay database riêng.

### Chạy Fineract 1.15.0 hằng ngày

Fineract không dùng DB Loan. Trong `docker/.env`, cấu hình direct endpoint Neon,
`fineract_tenants`, `fineract_default`, `sslmode=require`, default role/password và giữ
credential API `mifos/password` nếu chưa đổi fixture. Sau đó bật bằng:

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile fineract up -d fineract
```

Container `fineract` dùng `restart: unless-stopped`; Docker Desktop sẽ tự bật lại app và app luôn kết nối Neon.
Container `fineract-postgres` không nằm trong profile này và có `restart: "no"`. Lần đầu với DB trống, Liquibase có thể
mất vài phút để tạo schema. Script smoke/fallback bootstrap idempotent currency `VND` cho tenant `default` trước khi Loan đồng bộ Product.
API `PUT /currencies` thay toàn bộ allowlist nên script đọc danh sách hiện hành, giữ các mã đang có và
chỉ bổ sung `VND`; chạy lại không tạo dữ liệu trùng hoặc loại bỏ `USD`. Script cũng tạo idempotent Client
kỹ thuật `FINORA-PREVIEW-CLIENT`, vì API `calculateLoanSchedule` của Fineract bắt buộc `clientId`.
Client này chỉ dùng tính lịch dự kiến; khoản vay thật phải mapping đúng Fineract Client của borrower.

Muốn tự kiểm tra cấu hình tiền tệ, gọi `GET /fineract-provider/api/v1/currencies` với Basic Auth của
fixture và header `Fineract-Platform-TenantId: default`. `selectedCurrencyOptions` là các mã tenant
đang cho phép sử dụng; `currencyOptions` chỉ là danh sách mã Fineract hỗ trợ. Vì vậy nhìn thấy `VND`
trong `currencyOptions` chưa đủ: `VND` phải xuất hiện trong `selectedCurrencyOptions` trước khi sync Product.

Không bật profile `fineract-local-db` trong luồng hằng ngày; profile này chỉ dùng fallback offline và giữ volume cũ.

Image Docker Hub không có tag cho commit phát hành `1.15.0`, nên lần thiết lập
đầu Compose build `finora/fineract:1.15.0` từ binary release chính thức trong
`docker/fineract/Dockerfile` và bắt buộc xác minh SHA-512. Các lần sau Docker dùng
image local đã build, không tải lại.

Lần đầu Fineract có thể mất vài phút để migration hai database `fineract_tenants`
và `fineract_default`. Healthcheck của app container gọi API `/offices` bằng Basic Auth + tenant và chỉ báo
`healthy` sau HTTP 200; nó đồng thời hoàn tất lần khởi tạo Jersey đầu tiên. Vì vậy không chạy Loan preview khi
container còn `starting`. Sau khi container `healthy`, vẫn phải chạy smoke một lần trên volume mới để chọn `VND`
và tạo Preview Client; functional readiness không tự sửa dữ liệu bootstrap:

```text
Health: http://localhost:18443/fineract-provider/actuator/health
API:    http://localhost:18443/fineract-provider/api/v1
```

Loan chạy từ IntelliJ dùng URL trên theo mặc định. Loan chạy trong Docker network
cần `FINERACT_BASE_URL=http://fineract:8443/fineract-provider/api/v1`.

```powershell
# Payment PostgreSQL
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Payment -KeepRunning

# Blockchain PostgreSQL
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Blockchain -KeepRunning

# Hải phát triển User
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope User -KeepRunning

# Hải phát triển Investment
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Investment -KeepRunning

# Kiểm tra toàn bộ local infrastructure
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope All
```

Datasource local tương ứng:

```text
Payment:    jdbc:postgresql://localhost:15434/finora_payment
Blockchain: jdbc:postgresql://localhost:15435/finora_blockchain
Investment: jdbc:postgresql://localhost:15437/finora_investment
```

Smoke script luôn thêm profile `core` để kiểm tra Keycloak; profile `core` không có Kafka nên smoke này
không chứng minh broker sẵn sàng. Kafka nằm ở profile `kafka`/`demo` và được kiểm tra trong runbook
[`docs/testing/E2E-VERTICAL-SLICE.md`](../docs/testing/E2E-VERTICAL-SLICE.md). Nếu chỉ cần database Loan hằng
ngày, dùng lệnh `docker compose ... loan-postgres` ở mục 3 để không bật core.

Không có `-KeepRunning`, script sẽ dừng và xóa container của đúng scope sau smoke nhưng giữ volume. `-Stop` dùng để dừng scope đã chọn.

## 4.1. Đăng nhập / đăng ký / quên mật khẩu (finora-user)

Luồng auth cần 4 thành phần. Chỉ 2 thành phần đầu chạy trong Docker:

| Thành phần | Chạy ở đâu | Ghi chú |
|---|---|---|
| Keycloak `8180` | Docker, profile `core` | Realm `finora`, client `finora-user-client` |
| Redis `6381` | Docker, profile `user` | OTP quên mật khẩu (TTL 5 phút) + rate limit đăng nhập |
| PostgreSQL `5432` | **Máy host** | Database `finora_user`; Flyway tự migrate khi app khởi động |
| `finora-user` `8085`, `finora-notification` `8086` | IntelliJ / `java -jar` | Đọc cấu hình từ `finora-user/.env` |

### Bước 1 — Chuẩn bị PostgreSQL trên máy

```powershell
# Hai database này phải tồn tại trước khi bật Docker
psql -h localhost -U postgres -c "CREATE DATABASE finora_user;"
psql -h localhost -U postgres -c "CREATE DATABASE keycloak;"
```

Keycloak trong container kết nối host qua `host.docker.internal`. Docker Desktop NAT
kết nối này về loopback nên `pg_hba.conf` mặc định (chỉ cho `127.0.0.1`) vẫn chấp nhận
— không cần sửa `pg_hba.conf` hay mở firewall.

### Bước 2 — Điền secret

Trong `docker/.env`:

```properties
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=<mật khẩu admin console>
KEYCLOAK_DB_HOST=host.docker.internal
KEYCLOAK_DB_PORT=5432
KEYCLOAK_DB_NAME=keycloak
KEYCLOAK_DB_USERNAME=postgres
KEYCLOAK_DB_PASSWORD=<mật khẩu postgres trên máy>
KEYCLOAK_CLIENT_SECRET=<chuỗi ngẫu nhiên>
USER_REDIS_HOST_PORT=6381
USER_REDIS_PASSWORD=
```

Sau đó `Copy-Item finora-user/.env.example finora-user/.env` và điền tiếp. Giá trị
`KEYCLOAK_CLIENT_SECRET` ở hai file **bắt buộc giống nhau**: `docker/.env` nạp secret
vào realm Keycloak, `finora-user/.env` để service tự xác thực với realm đó.

### Bước 3 — Bật hạ tầng

```powershell
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope User -KeepRunning
```

Container `finora-keycloak-realm-init` chạy một lần: nó đọc
`docker/keycloak/template/realm-finora.json`, thay `__KEYCLOAK_CLIENT_SECRET__` bằng giá trị
trong `docker/.env`, rồi ghi kết quả vào volume `keycloak-import`. Keycloak khởi động với
`--import-realm` và nạp file đó. Nhờ vậy secret không nằm trong file commit lên Git.

Realm import tạo sẵn:

- realm `finora`, `sslRequired=none` (local chạy HTTP);
- 3 realm role `ROLE_BORROWER`, `ROLE_INVESTOR`, `ROLE_ADMIN` — khớp `UserRole` của service;
- confidential client `finora-user-client`: bật *direct access grant* (đăng nhập bằng
  password grant) và *service account* với quyền `manage-users`, `view-users`, `query-users`,
  `query-groups`, `view-realm` để tạo user / gán role / đổi mật khẩu qua Admin REST API.

`--import-realm` dùng chiến lược IGNORE_EXISTING: realm đã tồn tại thì Keycloak bỏ qua file
import. Muốn nạp lại realm sau khi sửa template, phải xoá realm cũ:

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile core --profile user down
psql -h localhost -U postgres -c "DROP DATABASE keycloak;"
psql -h localhost -U postgres -c "CREATE DATABASE keycloak;"
```

### Bước 4 — Chạy service

Chạy `FinoraNotificationApplication` (8086) rồi `FinoraUserApplication` (8085) trong IntelliJ,
working directory đặt ở thư mục module để `spring.config.import` đọc được `.env`.

Cấu hình SMTP cho notification: `Copy-Item finora-notification/.env.example finora-notification/.env`
rồi chọn một trong hai chế độ ở mục 4.2.

Không chạy notification thì đăng ký và quên mật khẩu **vẫn thành công** vì gọi best-effort — chỉ là
không có email gửi đi; khi đó đọc OTP trực tiếp trong Redis:

```powershell
docker exec finora-user-redis redis-cli KEYS "reset_otp:*"
docker exec finora-user-redis redis-cli GET "reset_otp:<userId>"
```

### Endpoint

```text
POST http://localhost:8085/api/v1/auth/register          {email, password, fullName, role}
POST http://localhost:8085/api/v1/auth/login             {email, password}
POST http://localhost:8085/api/v1/auth/forgot-password   {email}
POST http://localhost:8085/api/v1/auth/reset-password    {email, otp, newPassword}
```

Web client nhận token qua HttpOnly cookie; mobile gửi header `X-Client-Type: mobile` để nhận
token trong response body. Keycloak admin console: `http://localhost:8180` (realm `master`).

## 4.2. Gửi email OTP / welcome (finora-notification)

Code gửi mail đã đầy đủ (`EmailServiceImpl` + template HTML); chỉ cần cấu hình SMTP trong
`finora-notification/.env`. Có hai chế độ, chọn một.

### Chế độ A — Mailpit: test không cần tài khoản thật

Mailpit là mail server chạy local, bắt mọi email service gửi đi và hiển thị ở web UI.
Nó **không** chuyển thư ra Internet, nên dùng địa chỉ giả (`abc@finora.test`) vẫn đọc được thư.

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile mail up -d mailpit
```

Trong `finora-notification/.env`:

```properties
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_SMTP_AUTH=false
MAIL_SMTP_STARTTLS=false
MAIL_USERNAME=finora.noreply@finora.local
MAIL_PASSWORD=
```

Đọc thư tại `http://localhost:8025`. Xoá sạch hộp thư:
`curl -X DELETE http://localhost:8025/api/v1/messages`.

### Chế độ B — Gmail SMTP: gửi vào hộp thư thật

Gmail chặn đăng nhập SMTP bằng mật khẩu tài khoản, bắt buộc dùng **App Password**:

1. Bật xác minh 2 bước: <https://myaccount.google.com/signinoptions/twosv>
2. Tạo App Password: <https://myaccount.google.com/apppasswords>
3. Google trả về 16 ký tự dạng `abcd efgh ijkl mnop` — **xoá hết khoảng trắng** khi dán vào `.env`

```properties
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
MAIL_USERNAME=<địa chỉ Gmail dùng để gửi>
MAIL_PASSWORD=<App Password 16 ký tự, đã xoá khoảng trắng>
```

`MAIL_USERNAME` vừa dùng đăng nhập SMTP vừa là địa chỉ hiển thị ở mục From.
Không thấy mục App Password nghĩa là tài khoản chưa bật xác minh 2 bước, hoặc là tài khoản
Google Workspace bị admin chặn.

Gmail giới hạn khoảng 500 thư/ngày cho tài khoản thường. Thư đầu tiên thường rơi vào Spam
vì domain gửi không có SPF/DKIM khớp — kiểm tra Spam trước khi kết luận là lỗi cấu hình.

### Kiểm tra nhanh không cần chạy finora-user

```powershell
curl.exe -X POST http://localhost:8086/api/internal/notifications/otp-email `
  -H "Content-Type: application/json" `
  -d '{\"email\":\"dia-chi-cua-ban@gmail.com\",\"otp\":\"123456\"}'
```

Endpoint luôn trả `200` kể cả khi gửi lỗi (best-effort để không chặn luồng nghiệp vụ).
Vì vậy **phải đọc log của `finora-notification`** để biết kết quả thật:

```text
Đã gửi email thành công: subject='...', to='di***@gmail.com'     -> OK
Lỗi gửi email: ... lỗi=Authentication failed                      -> sai MAIL_PASSWORD
Lỗi gửi email: ... lỗi=Couldn't connect to host                   -> sai MAIL_HOST/MAIL_PORT hoặc chưa bật Mailpit
```

Địa chỉ email trong log được mask theo rule không log PII.

## 4.3. Lưu bằng chứng eKYC (SeaweedFS, profile `ekyc`)

Luồng eKYC (EKYC-002) lưu ảnh hai mặt CCCD, video liveness, khung hình và `result.json` qua S3 API.
Dev local dùng **SeaweedFS** (`chrislusf/seaweedfs:4.48`, Apache-2.0) thay MinIO — MinIO đã ngừng phát hành
image/binary. Khi deploy dùng Amazon S3: cùng một code, chỉ đổi biến môi trường. Bản Azure hiện để
`EKYC_EVIDENCE_ENABLED=false` (chưa có bucket) nên eKYC dừng ở `STORAGE_UNAVAILABLE`.

### Bước 1 — Điền khoá trong `docker/.env`

```properties
SEAWEEDFS_ADMIN_ACCESS_KEY=<tự sinh>     # tài khoản quản trị, chỉ seaweedfs-init dùng
SEAWEEDFS_ADMIN_SECRET_KEY=<tự sinh>
EKYC_S3_ACCESS_KEY=<tự sinh>             # tài khoản của finora-user: chỉ Read/Write/List/Tagging trên bucket
EKYC_S3_SECRET_KEY=<tự sinh>
EKYC_S3_BUCKET=finora-ekyc
```

Khoá chỉ gồm chữ, số và `. _ ~ + / = -` (container sinh `s3.json` từ các biến này lúc khởi động; ký tự
khác sẽ làm hỏng JSON nên container dừng và báo lỗi). Sinh nhanh:
`python -c "import secrets; print(secrets.token_hex(20))"`.

### Bước 2 — Bật kho

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ekyc up -d
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ekyc logs seaweedfs-init
```

- `seaweedfs`: S3 API chỉ mở ở `127.0.0.1:9100` (không dùng `9000` vì trùng CIC); mã hoá khi lưu bằng
  `-s3.encryptVolumeData`; truy cập ẩn danh bị từ chối vì đã khai báo tài khoản.
- `seaweedfs-init` (`amazon/aws-cli:2.38.1`, chạy một lần): tạo bucket bằng tài khoản quản trị và đặt
  vòng đời theo tag `stage=pending` 1 ngày, `stage=final` `EKYC_EVIDENCE_RETENTION_DAYS` ngày. SeaweedFS
  nhận cấu hình nhưng chưa chắc thực thi lọc theo tag, nên đây chỉ là lớp phòng thủ thêm: worker quét dọn
  của finora-user (mỗi giờ) mới là nguồn chuẩn, dựa trên cột `ekyc_evidence.stage`.
- Tài khoản `finora-user` không tạo được bucket khác và chỉ thấy bucket eKYC.

### Bước 3 — Cấu hình finora-user

Chạy trong IntelliJ, đặt trong `finora-user/.env` (xem `.env.example`):

```properties
EKYC_EVIDENCE_ENABLED=true
EKYC_S3_ENDPOINT=http://localhost:9100
EKYC_S3_PATH_STYLE=true
EKYC_S3_REGION=us-east-1
EKYC_S3_BUCKET=finora-ekyc
EKYC_S3_ACCESS_KEY=<giống docker/.env>
EKYC_S3_SECRET_KEY=<giống docker/.env>
```

User gọi `/api/v1/ai/ekyc/*` bằng service account `finora-user-client` (client role `ai:ekyc:ocr`, D34/D35): cần
`KEYCLOAK_CLIENT_SECRET` đúng trong `finora-user/.env`, không có token nội bộ riêng.

Chạy trong Docker (profile `demo` hoặc `docker-compose.app.yml --profile ekyc`), compose tự trỏ
`EKYC_S3_ENDPOINT=http://seaweedfs:8333`. Object key có dạng
`ekyc/{EKYC_S3_ENV}/{userProfileId}/{attemptId}/{file}`, không chứa số định danh hay họ tên.

Kiểm tra nhanh bằng AWS CLI trên máy (nếu có): `aws --endpoint-url http://localhost:9100 s3 ls s3://finora-ekyc --recursive`
với khoá của tài khoản `finora-user`. Test tích hợp tuỳ chọn của finora-user:

```powershell
$env:EKYC_S3_IT_ENDPOINT="http://127.0.0.1:9100"; $env:EKYC_S3_IT_ACCESS_KEY="<EKYC_S3_ACCESS_KEY>"; $env:EKYC_S3_IT_SECRET_KEY="<EKYC_S3_SECRET_KEY>"
mvn -pl finora-user verify "-Dit.test=S3EkycEvidenceStorageIT" "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Dữ liệu trên SeaweedFS local chỉ là dữ liệu thử nghiệm; xoá toàn bộ bằng
`docker compose ... --profile ekyc down -v` (mất volume `seaweedfs-data`).

## 5. Chạy Loan application bằng container

Loan chạy trong container qua profile `apps` (mục 1b) cùng năm service còn lại và đọc DB từ `finora-loan/.env` (Neon dev).
Bật hoặc tạo lại riêng Loan:

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile apps up -d loan
```

Postman gọi `http://localhost:8081`. PostgreSQL offline `loan-postgres` (profile `loan`, mục 6, DBeaver `localhost:15433`)
chỉ dùng khi chạy Loan ngoài container với `LOAN_DB_URL` trỏ cổng đó.

## 6. DBeaver cho PostgreSQL Loan offline

| Thuộc tính | Giá trị |
|---|---|
| Driver | PostgreSQL |
| Host | `localhost` |
| Port | `15433` |
| Database | `finora_loan` |
| Username | `finora_loan` |
| Password | `LOAN_DB_PASSWORD` trong `docker/.env` |
| SSL | không bắt buộc cho loopback local |

Không dùng role `postgres` cho ứng dụng hoặc thao tác hằng ngày.

## 7. File cần nhớ

| File | Vai trò |
|---|---|
| `NEON-POSTGRESQL-SETUP.md` | Tạo Neon Project và cấu hình IntelliJ/DBeaver |
| `docker/.env` | Credential Docker local; không commit |
| `docker/.env.example` | Danh sách biến cấu hình |
| `docker/docker-compose.yml` | Offline containers/profile/network/volume |
| `docker/postgresql/init-service-user.sh` | Tạo runtime role PostgreSQL không có superuser |
| `docker/postgresql/fineract/init-databases.sh` | Tạo hai database và runtime role riêng của Fineract |
| `docker/smoke-fineract.ps1` | Kiểm tra health và tenant authentication, không bật Keycloak/Kafka |
| `docker/Dockerfile.ai` | Đóng gói FINORA AI v17 cùng model runtime/config, không kèm CSV huấn luyện |
| `docker/smoke-infra.ps1` | Start/healthcheck/stop theo scope |
| `finora-*/src/main/resources/application.yml` | Datasource mặc định local và environment override |
| `finora-loan/postman/README.md` | Chạy và test Loan bằng Postman |

## 8. Lỗi thường gặp

- **Thiếu secret:** smoke script dừng trước khi gọi Compose và nêu đúng biến còn thiếu.
- **Cổng bị chiếm:** đổi `*_POSTGRES_HOST_PORT` trong `docker/.env`, đồng thời đổi JDBC URL khi chạy app từ host.
- **Đổi password sau khi volume đã tạo:** PostgreSQL không tự đổi runtime role; đổi role trong DB hoặc tạo volume mới sau khi đã sao lưu/chấp nhận mất dữ liệu.
- **Neon không kết nối:** kiểm tra JDBC prefix, database/role đúng project và `sslmode=require`.
- **Scale-to-zero:** request đầu sau thời gian nghỉ có thể chậm; ứng dụng phải kết nối lại, không đổi sang database local âm thầm.
- **Container lỗi:** chạy `docker compose --env-file docker/.env -f docker/docker-compose.yml --profile <profile> logs --tail 100 <service>`.
- **Fineract còn `starting`:** chờ functional healthcheck hoàn tất; không bấm preview liên tục. Nếu kéo dài, kiểm tra
  log Fineract và xác nhận `FINERACT_API_USERNAME/PASSWORD` trong `docker/.env` đúng với tenant local.
- **Volume engine cũ:** các volume MySQL/MongoDB đã tạo trước đây không còn gắn vào Compose mới và không bị tự động xóa. Chỉ xóa sau khi hai owner xác nhận không còn dữ liệu cần giữ.
