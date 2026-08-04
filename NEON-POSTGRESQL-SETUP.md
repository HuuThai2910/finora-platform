# Thiết lập PostgreSQL trên Neon cho các service của Thái

## 1. Kiến trúc được chốt

Mỗi microservice có một Neon Project và connection string riêng. Loan/Payment/Blockchain dùng luôn database và owner role mặc định Neon tạo trong đúng project:

| Neon Project | Database | Runtime role | Service sử dụng |
|---|---|---|---|
| `finora-loan` | `neondb` | Default role hiển thị trong **Connect** | `finora-loan` |
| `finora-payment` | `neondb` | Default role hiển thị trong **Connect** | `finora-payment` |
| `finora-blockchain` | `neondb` | Default role hiển thị trong **Connect** | `finora-blockchain` |
| `finora-fineract` | `fineract_tenants`, `fineract_default` | Default role hiển thị trong **Connect** | Apache Fineract |

Không tạo một project chứa cả Loan, Payment và Blockchain. Việc cả ba database cùng tên `neondb` không làm chúng dùng chung dữ liệu vì mỗi database nằm trên endpoint/project khác nhau. Theo [bảng giá Neon hiện hành](https://neon.com/pricing), Free plan có 0,5 GB storage và 100 CU-hours mỗi tháng **cho mỗi project**. Giới hạn này có thể thay đổi nên phải kiểm tra lại trang giá trước khi demo/deploy.

Fineract luôn nằm trong project riêng. Hai database Fineract dùng chung quota của project `finora-fineract`, nhưng không dùng chung database/schema với Loan.

## 2. Chuẩn bị

1. Tạo tài khoản tại [Neon](https://console.neon.tech/).
2. Bật xác thực hai lớp cho tài khoản nếu có thể.
3. Không dùng dữ liệu cá nhân thật trong môi trường khóa luận.
4. Không ghi connection string, password hoặc file `.env` thật vào Git.

Repository chỉ commit tên biến trong `docker/.env.example`; giá trị Neon được đặt trong IntelliJ, biến môi trường của máy hoặc secret store của môi trường deploy.

## 3. Tạo project cho Loan

1. Chọn **New project**.
2. Đặt tên `finora-loan`.
3. Chọn PostgreSQL 17, cloud provider và region gần nơi ứng dụng sẽ chạy nhất.
4. Chọn **Create Project**. Với giao diện/tài khoản của Thái ngày 2026-08-03, Neon tạo một branch mặc định tên `production`, database `neondb` và default owner role. Không giả định có sẵn branch `development`.
5. Chọn branch `production`. Trong giai đoạn khóa luận đây chỉ là **tên branch mặc định của Neon**, chưa phải database production có người dùng thật.
6. Không tạo thêm database hoặc role. Mở **Connect**, chọn:
   - Branch: `production`;
   - Database: `neondb`;
   - Role: default role Neon đang hiển thị;
   - Connection type: **Direct connection**;
   - Stack/driver: Java/JDBC nếu giao diện cung cấp lựa chọn.
7. Sao chép host, database, username, password và JDBC URL đúng từ **Connect**. Không tự đoán tên default role vì Neon quyết định tên cụ thể.

JDBC URL phải có dạng:

```text
jdbc:postgresql://<neon-host>/neondb?sslmode=require
```

Nếu Neon đưa URL bắt đầu bằng `postgresql://`, dùng mẫu Java/JDBC trong hộp Connect hoặc thêm tiền tố `jdbc:` trước URL. Không tự bỏ `sslmode=require`, `channel_binding=require` hoặc tham số bảo mật khác do Neon cung cấp. JDBC được Neon hỗ trợ; xem [Connection errors](https://neon.com/docs/connect/connection-errors).

## 4. Tạo project cho Payment và Blockchain

Lặp lại đúng quy trình trên cho từng project. Mỗi project dùng `neondb` và default role do chính project đó tạo; không lấy connection string của project này dùng cho project khác:

### Payment

```text
Project:  finora-payment
Database: neondb
Role:     Default role trong Connect của finora-payment
```

### Blockchain

```text
Project:  finora-blockchain
Database: neondb
Role:     Default role trong Connect của finora-blockchain
```

PostgreSQL của Blockchain chỉ lưu submission state, transaction/block reference, payload hash và reconciliation metadata. Không lưu private key, certificate, tài liệu gốc hoặc PII đầy đủ.

## 5. Tạo project cho Fineract

Tạo project `finora-fineract` riêng, sau đó tạo hai database mà Fineract yêu cầu:

```text
fineract_tenants
fineract_default
```

Tạo database bằng giao diện Neon và chọn default role của project làm owner; không cần tự tạo role phụ trong giai đoạn khóa luận một owner. Cùng username/password này được dùng cho metadata DB và tenant DB, nhưng tuyệt đối không dùng credential của Loan.

Không chạy Flyway của FINORA vào hai database này. Apache Fineract tự quản lý schema bằng Liquibase. Cấu hình managed sau này phải ánh xạ:

```text
FINERACT_HIKARI_JDBC_URL        -> fineract_tenants
FINERACT_DEFAULT_TENANTDB_NAME -> fineract_default
FINERACT_DEFAULT_TENANTDB_IDENTIFIER -> default
```

Host, role, password, tenant master password và SSL parameters chỉ đặt trong secret store. Trước khi dùng Neon cho Fineract, P0-C05 local phải smoke pass để không debug đồng thời adapter, Liquibase và network managed.

Free plan hiện có 0,5 GB cho toàn project, vì vậy `fineract_tenants` và `fineract_default` cùng chia sẻ quota này. Sau khi bootstrap phải đo dung lượng thực; nếu gần giới hạn thì nâng gói hoặc chuyển riêng Fineract sang PostgreSQL managed có dung lượng phù hợp, không chia một tenant core ra nhiều project để lách quota.

## 6. Cấu hình IntelliJ để chạy hằng ngày

### Loan

Mở Run Configuration của `FinoraLoanApplication`:

- Active profiles: `local`
- Environment variables:

```text
LOAN_DB_URL=jdbc:postgresql://<loan-host>/neondb?sslmode=require
LOAN_DB_USERNAME=<default role từ Connect của finora-loan>
LOAN_DB_PASSWORD=<password Neon>
```

Thêm từng biến bằng bảng Environment variables của IntelliJ; không dán password vào file cấu hình được commit.

### Payment

```text
PAYMENT_DB_URL=jdbc:postgresql://<payment-host>/neondb?sslmode=require
PAYMENT_DB_USERNAME=<default role từ Connect của finora-payment>
PAYMENT_DB_PASSWORD=<password Neon>
PAYMENT_REDIS_PASSWORD=<password Redis local/deploy>
```

### Blockchain

```text
BLOCKCHAIN_DB_URL=jdbc:postgresql://<blockchain-host>/neondb?sslmode=require
BLOCKCHAIN_DB_USERNAME=<default role từ Connect của finora-blockchain>
BLOCKCHAIN_DB_PASSWORD=<password Neon>
```

Khi các biến trên tồn tại, service kết nối thẳng Neon và không cần khởi động container PostgreSQL local. Flyway chạy bằng chính datasource của từng service.

## 7. Kết nối bằng DBeaver

1. Chọn **New Database Connection → PostgreSQL**.
2. Lấy Host, Database, Username và Password từ hộp **Connect** của đúng Neon Project.
3. Port là `5432` nếu connection detail không chỉ ra giá trị khác.
4. Bật SSL và chọn `require` nếu DBeaver không tự đọc từ URL.
5. Bấm **Test Connection**.

Không kết nối DBeaver vào role của service khác và không sửa schema bằng tay. Mọi thay đổi schema đi qua Flyway của service sở hữu database. Tên branch `production` không thay đổi quy tắc này.

## 8. Kiểm tra đúng database và dung lượng

Chạy trong DBeaver/SQL Editor:

```sql
SELECT current_database(), current_user, version();
```

Xem tổng dung lượng database:

```sql
SELECT pg_size_pretty(pg_database_size(current_database()));
```

Xem bảng và index lớn nhất:

```sql
SELECT
    relname AS table_name,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS data_size,
    pg_size_pretty(pg_total_relation_size(relid) - pg_relation_size(relid)) AS index_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;
```

Kiểm tra Loan sau lần khởi động đầu:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Phải thấy V1 và V2 thành công trước khi test Product/Application.

## 9. Direct connection, pool và scale-to-zero

- Giai đoạn hiện tại dùng **Direct connection** cho Spring Boot vì mỗi service có Hikari pool nhỏ và Flyway cần session ổn định. Neon cũng khuyến nghị direct connection cho migration/ORM; xem [Connection pooling](https://neon.com/docs/connect/connection-pooling).
- Giữ `maximum-pool-size` nhỏ khi chạy Free plan; chỉ thêm tuning sau khi đo connection/latency.
- Không dùng một pooled URL chung cho nhiều service.
- Neon Free có thể scale-to-zero khi không hoạt động; request đầu tiên sau thời gian nghỉ có thể chậm hơn. Đây là hành vi chấp nhận được ở local/demo.
- Nếu sau này dùng pooled endpoint, phải test Flyway, prepared statement, transaction và connection reset trước khi đổi production config.

## 10. Branch Neon

Project hiện tại của Thái chỉ có branch mặc định `production`; như vậy là đủ cho phát triển hằng ngày. Không cần tạo thêm branch `development`. Chỉ khi cần thử một migration có rủi ro mới tạo branch tạm:

1. Vào **Branches → New branch**, tạo child branch từ `production`, ví dụ `test-ln-003a`.
2. Lấy connection string của child branch.
3. Chạy migration/test trên child branch.
4. Xóa branch sau khi kiểm tra.

Branch tạm tách dữ liệu để thử nghiệm nhưng thay đổi phát sinh vẫn tính vào storage project. Xóa branch sau khi test và không tạo nhiều branch lâu dài trên Free plan.

## 11. Docker PostgreSQL offline fallback

Docker không còn bắt buộc cho database hằng ngày. Khi mất mạng hoặc cần kiểm tra độc lập:

```powershell
Copy-Item docker/.env.example docker/.env
```

Điền nhóm PostgreSQL tương ứng rồi chạy:

```powershell
# Loan
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan up -d loan-postgres

# Payment
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile payment up -d payment-postgres payment-redis

# Blockchain
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile blockchain up -d blockchain-postgres
```

Ứng dụng sẽ dùng URL mặc định local:

```text
Loan:       jdbc:postgresql://localhost:15433/finora_loan
Payment:    jdbc:postgresql://localhost:15434/finora_payment
Blockchain: jdbc:postgresql://localhost:15435/finora_blockchain
```

Docker vẫn cần cho PostgreSQL Testcontainers, Fineract local, Kafka, Redis, Keycloak hoặc Fabric khi các task đó được triển khai.

## 12. Chuyển dữ liệu MySQL cũ

Các migration Loan V1/V2 chưa merge nên đã được chuyển thẳng sang cú pháp PostgreSQL. Database MySQL local cũ không tự động chuyển sang Neon.

Trong giai đoạn hiện tại dữ liệu chỉ là dữ liệu test, hướng an toàn là:

1. Giữ nguyên volume MySQL cũ, không xóa tự động.
2. Dùng `neondb` sạch trong đúng Neon Project.
3. Để Flyway tạo schema từ V1/V2.
4. Tạo lại dữ liệu test qua Postman.

Nếu phát hiện dữ liệu cần giữ, phải export và lập mapping kiểu dữ liệu riêng trước khi import; không copy trực tiếp MySQL data directory sang PostgreSQL.

## 13. Checklist hoàn tất cho từng project

- [ ] Project đúng tên và không chứa database service khác.
- [ ] Database là `neondb` và username đúng giá trị **Connect** của project hiện tại.
- [ ] Connection dùng SSL.
- [ ] Secret chỉ ở IntelliJ/environment/secret store.
- [ ] DBeaver `current_database/current_user` trả đúng.
- [ ] Flyway chạy thành công.
- [ ] `pg_database_size` còn dưới quota với khoảng dự phòng.
- [ ] Service khác không được cấp quyền truy cập.
- [ ] Docker/Testcontainers fallback vẫn chạy được khi cần.
