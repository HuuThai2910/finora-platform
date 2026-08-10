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
| `user` | `finora-user-postgres` | `15436` | `user-postgres-data` |
| `investment` | `finora-investment-postgres` | `15437` | `investment-postgres-data` |
| `core` | `finora-keycloak-postgres` | không publish | `keycloak-postgres-data` |

Thái và Hải đã thống nhất dùng PostgreSQL riêng cho User, Investment và Keycloak. Kafka, Zookeeper và Keycloak thuộc profile `core`.

## 2. Chuẩn bị Docker offline một lần

```powershell
Copy-Item docker/.env.example docker/.env
```

Điền secret của đúng scope. File `docker/.env` bị Git ignore và không được chứa credential Neon/production dùng chung.

Nếu `docker/.env` được tạo từ bản MySQL/MongoDB cũ, đổi tên biến trước khi chạy:

| Biến cũ | Biến PostgreSQL mới | Giá trị port local |
|---|---|---:|
| `KEYCLOAK_DB_ROOT_PASSWORD` | `KEYCLOAK_POSTGRES_ADMIN_PASSWORD` | không publish |
| `USER_MYSQL_ROOT_PASSWORD` | `USER_POSTGRES_ADMIN_PASSWORD` | — |
| `USER_MYSQL_HOST_PORT` | `USER_POSTGRES_HOST_PORT` | `15436` |
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
và `fineract_default`. Sau khi cả hai container `healthy`, vẫn phải chạy smoke để chọn `VND` và tạo
Preview Client; chỉ container healthy chưa đủ để Product FINORA đồng bộ thành công:

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
User:       jdbc:postgresql://localhost:15436/finora_user
Investment: jdbc:postgresql://localhost:15437/finora_investment
```

Smoke script luôn thêm profile `core` để kiểm tra Kafka/Keycloak. Nếu chỉ cần database Loan hằng ngày, dùng lệnh `docker compose ... loan-postgres` ở mục 3 để không bật core.

Không có `-KeepRunning`, script sẽ dừng và xóa container của đúng scope sau smoke nhưng giữ volume. `-Stop` dùng để dừng scope đã chọn.

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
- **Volume engine cũ:** các volume MySQL/MongoDB đã tạo trước đây không còn gắn vào Compose mới và không bị tự động xóa. Chỉ xóa sau khi hai owner xác nhận không còn dữ liệu cần giữ.
