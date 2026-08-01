# Chạy FINORA local

## 1. Kiến trúc database local

Mỗi service sở hữu một database instance, application user và volume riêng:

| Profile | Container dữ liệu | Host port | Volume |
|---|---|---:|---|
| `core` | `finora-keycloak-mysql` | không publish | `keycloak-mysql-data` |
| `loan` | `finora-loan-mysql` | `13306` | `loan-mysql-data` |
| `payment` | `finora-payment-mysql` | `13307` | `payment-mysql-data` |
| `payment` | `finora-payment-redis` | `6380` | `payment-redis-data` |
| `user` | `finora-user-mysql` | `13308` | `user-mysql-data` |
| `investment` | `finora-investment-mongo` | `27018` | `investment-mongo-data` |

Kafka, Zookeeper và Keycloak thuộc profile `core`. Việc tách container loại bỏ lỗi chung ở cấp database process; khi tất cả vẫn chạy trên cùng một máy, Docker Desktop/máy host vẫn là failure domain chung của local development.

## 2. Chuẩn bị một lần

Docker Desktop phải chạy. Từ thư mục gốc `finora-platform`:

```powershell
Copy-Item docker/.env.example docker/.env
```

Điền secret của `core` và scope bạn sẽ chạy; ví dụ Thái làm Loan chỉ cần nhóm Core và Loan. Script sẽ báo chính xác biến còn thiếu của scope. File này bị Git ignore và không được dùng credential production.

## 3. Công việc hằng ngày của Thái với Loan

Khởi động `core + loan-mysql`, kiểm tra health và giữ chúng chạy:

```powershell
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -KeepRunning
```

Sau đó chạy `FinoraLoanApplication` từ IntelliJ với environment:

```text
LOAN_DB_USERNAME=finora_loan
LOAN_DB_PASSWORD=<giá trị trong docker/.env>
```

Loan dùng `jdbc:mysql://localhost:13306/finora_loan` khi chạy từ IDE.

Kết thúc ngày làm việc, dừng đúng scope nhưng giữ volume:

```powershell
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -Stop
```

Không xóa volume nếu chưa chủ động chấp nhận mất dữ liệu local.

## 4. Scope khác

```powershell
# Hải phát triển User
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope User -KeepRunning

# Hải phát triển Investment
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Investment -KeepRunning

# Thái phát triển Payment
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Payment -KeepRunning

# Kiểm tra toàn bộ database local
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope All
```

Mỗi scope luôn kèm `core` để Kafka và Keycloak sẵn sàng. Không có `-KeepRunning` thì script smoke xong sẽ dừng và xóa container của đúng scope, nhưng giữ volume.

## 5. Chạy Loan bằng container

```powershell
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile core --profile loan --profile apps build loan
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile core --profile loan --profile apps up -d loan
```

Trong Docker network, Loan kết nối `loan-mysql:3306` và `kafka:29092`. Trên Windows, client dùng `localhost:13306` và `localhost:9092`.

## 6. DBeaver cho Loan

| Thuộc tính | Giá trị |
|---|---|
| Host | `localhost` |
| Port | `13306` |
| Database | `finora_loan` |
| Username | `finora_loan` |
| Password | `LOAN_DB_PASSWORD` trong `docker/.env` |

Không dùng MySQL root cho Loan hoặc thao tác hằng ngày.

## 7. File cần nhớ

| File | Vai trò |
|---|---|
| `docker/.env` | Credential và host port local; không commit |
| `docker/smoke-infra.ps1` | Start/healthcheck/stop theo scope |
| `docker/docker-compose.yml` | Định nghĩa container, profile, network và volume |
| `finora-loan/src/main/resources/application.yml` | Cấu hình Loan khi chạy từ IDE |

## 8. Lỗi thường gặp

- Thiếu secret: Compose fail sớm và nêu tên biến.
- Cổng bị chiếm: đổi biến `*_HOST_PORT` trong `docker/.env`, đồng thời đổi URL của client chạy trên host.
- Đổi password sau khi volume đã được khởi tạo: environment không tự đổi credential trong database cũ; phải đổi user trong database hoặc tạo volume mới sau khi đã sao lưu/chấp nhận mất dữ liệu.
- Container lỗi: chạy `docker compose --env-file docker/.env -f docker/docker-compose.yml --profile <profile> logs --tail 100 <service>`.
- Các volume cũ `docker_mysql-data` và `docker_mongo-data` được giữ lại sau lần chuyển kiến trúc để tránh mất dữ liệu; chúng không còn được Compose mới sử dụng và chỉ được xóa thủ công sau khi kiểm tra dữ liệu.
