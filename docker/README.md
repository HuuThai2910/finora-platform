# Chạy hạ tầng FINORA local

## 1. Hai chế độ database FINORA

### Chế độ hằng ngày — Neon

Loan, Payment, Blockchain, User và Investment kết nối thẳng PostgreSQL trên Neon bằng biến môi trường. Không cần bật container database local.

Hướng dẫn tạo từng Neon Project, DBeaver và IntelliJ: [NEON-POSTGRESQL-SETUP.md](../NEON-POSTGRESQL-SETUP.md).

### Chế độ offline/test — Docker

Docker Compose giữ PostgreSQL riêng từng service để làm việc khi mất mạng, kiểm tra migration hoặc chạy local integration:

| Profile | Container dữ liệu | Host port | Volume |
|---|---|---:|---|
| `loan` | `finora-loan-postgres` | `15433` | `loan-postgres-data` |
| `fineract` | `finora-fineract-postgres` | `15432` | `fineract-postgres-data` |
| `fineract` | `finora-fineract` | `18443` | không lưu DB trong app container |
| `ai` | `finora-ai` | `8000` | model v10 nằm trong image, không có database |
| `payment` | `finora-payment-postgres` | `15434` | `payment-postgres-data` |
| `payment` | `finora-payment-redis` | `6380` | `payment-redis-data` |
| `blockchain` | `finora-blockchain-postgres` | `15435` | `blockchain-postgres-data` |
| `user` | `finora-user-redis` | `6381` | `user-redis-data` |
| `investment` | `finora-investment-postgres` | `15437` | `investment-postgres-data` |
| `core` | `finora-keycloak` | `8180` | `keycloak-import` (file realm đã render) |
| `mail` | `finora-mailpit` | `8025` UI, `1025` SMTP | không lưu trữ lâu dài (tối đa 500 thư) |

Keycloak và PostgreSQL của `finora-user` KHÔNG còn chạy trong Docker: cả hai dùng PostgreSQL cài trực tiếp trên máy host. Xem mục 4.1.

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

### Chạy AI v10 cho LN-007

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai up -d --build ai
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai ps ai
```

Health: `http://localhost:8000/health`; OpenAPI: `http://localhost:8000/docs`.

Lần đầu Docker build image `finora/ai:10.0.0-local`. Container chạy non-root, chỉ copy source
runtime và model v10, không copy CSV huấn luyện. Do có `restart: unless-stopped`, sau lần tạo đầu
chỉ cần mở Docker Desktop thì container tự chạy lại, trừ khi người dùng đã chủ động stop nó.
AI không cần username/password hay database riêng.

### Chạy Fineract 1.15.0 cho LN-006

Fineract có PostgreSQL riêng, không dùng chung `finora_loan`. Trong `docker/.env`
cần điền `FINERACT_POSTGRES_ADMIN_PASSWORD`, `FINERACT_DB_PASSWORD` và giữ
credential API local `mifos/password` nếu chưa đổi fixture:

```powershell
powershell -ExecutionPolicy Bypass -File docker/smoke-fineract.ps1 -KeepRunning
```

Lệnh trên chỉ cần chạy trong lần thiết lập đầu hoặc khi cần smoke lại. Hai service
`fineract-postgres` và `fineract` dùng `restart: unless-stopped`, vì vậy các lần sau
chỉ cần mở Docker Desktop; Docker sẽ tự chạy lại hai container đã được tạo.
Smoke script bootstrap idempotent currency `VND` cho tenant `default` trước khi Loan đồng bộ Product.
API `PUT /currencies` thay toàn bộ allowlist nên script đọc danh sách hiện hành, giữ các mã đang có và
chỉ bổ sung `VND`; chạy lại không tạo dữ liệu trùng hoặc loại bỏ `USD`. Script cũng tạo idempotent Client
kỹ thuật `FINORA-PREVIEW-CLIENT`, vì API `calculateLoanSchedule` của Fineract bắt buộc `clientId`.
Client này chỉ dùng tính lịch dự kiến; khoản vay thật phải mapping đúng Fineract Client của borrower.

Muốn tự kiểm tra cấu hình tiền tệ, gọi `GET /fineract-provider/api/v1/currencies` với Basic Auth của
fixture và header `Fineract-Platform-TenantId: default`. `selectedCurrencyOptions` là các mã tenant
đang cho phép sử dụng; `currencyOptions` chỉ là danh sách mã Fineract hỗ trợ. Vì vậy nhìn thấy `VND`
trong `currencyOptions` chưa đủ: `VND` phải xuất hiện trong `selectedCurrencyOptions` trước khi sync Product.

Không chạy `smoke-fineract.ps1 -Stop` sau mỗi ngày nếu muốn giữ cơ chế tự khởi động;
`-Stop` chỉ dùng khi chủ đích tắt Fineract trong những ngày không phát triển Loan.

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
# Payment PostgreSQL + Redis
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

Smoke script luôn thêm profile `core` để kiểm tra Kafka/Keycloak. Nếu chỉ cần database Loan hằng ngày, dùng lệnh `docker compose ... loan-postgres` ở mục 3 để không bật core.

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

## 5. Chạy Loan application bằng container

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan --profile apps up -d --build loan
```

Trong Docker network:

```text
jdbc:postgresql://loan-postgres:5432/finora_loan
```

Postman gọi `http://localhost:8081`. DBeaver kết nối `localhost:15433`.

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
| `docker/Dockerfile.ai` | Đóng gói FINORA AI v10, không kèm CSV huấn luyện |
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
