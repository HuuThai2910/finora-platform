---
task_id: P0-A06
title: PostgreSQL 17 và Neon cho toàn bộ service có persistence
owners: Thai, Hai
status: REVIEW
created_at: 2026-08-03
updated_at: 2026-08-08
approved_by: Thai, Hai
approved_at: 2026-08-08
---

# P0-A06 — PostgreSQL 17 và Neon cho toàn bộ FINORA

## 1. Mục tiêu

Chuyển toàn bộ service có persistence của Thái và Hải sang PostgreSQL 17. Mỗi service dùng một Neon Project/endpoint/credential độc lập; trong giai đoạn khóa luận dùng `neondb` và default role của project. PostgreSQL Docker và Testcontainers chỉ phục vụ offline/test. Keycloak dùng PostgreSQL riêng, không dùng chung database User.

Thiết kế hạ tầng chi tiết nằm tại [P0-C04](P0-C04-local-docker-infrastructure.md). Chuyển đổi schema Loan chi tiết nằm tại [LN-001A](../../finora-loan/plans/LN-001A-postgresql-neon-migration.md).

## 2. Phạm vi theo service

| Service | Thay đổi bắt buộc | Neon Project | Docker fallback |
|---|---|---|---|
| Loan | PostgreSQL JDBC/Flyway/validate/Testcontainers; schema nghiệp vụ quản lý bằng Flyway | `finora-loan` | `loan-postgres:17.5`, host `15433` |
| Payment | Bỏ MySQL JDBC, thêm PostgreSQL JDBC, Flyway, Hibernate validate và PostgreSQL Testcontainers | `finora-payment` | `payment-postgres:17.5`, host `15434` |
| Blockchain | Thêm PostgreSQL JDBC/JPA/Flyway làm nền cho submission/reconciliation, PostgreSQL Testcontainers | `finora-blockchain` | `blockchain-postgres:17.5`, host `15435` |
| User | Bỏ MySQL JDBC/hard-code root, dùng PostgreSQL JDBC/Flyway/validate/Testcontainers | `finora-user` | `user-postgres:17.5`, host `15436` |
| Investment | Bỏ Mongo starter/config, dùng JPA PostgreSQL/Flyway/validate/Testcontainers | `finora-investment` | `investment-postgres:17.5`, host `15437` |
| Keycloak | Đổi database provider từ MySQL sang PostgreSQL, runtime role/volume riêng | `finora-keycloak` | `keycloak-db:17.5`, không publish host port |

Fineract có project `finora-fineract` riêng và do P0-C05/LN-006 triển khai. AI/Notification/Gateway hiện không có database nên không phát sinh datasource giả chỉ để đồng bộ công nghệ.

## 3. Quy tắc dữ liệu

- Mỗi service chỉ truy cập endpoint/database/credential của chính mình.
- Docker runtime không dùng tài khoản `postgres`. Neon khóa luận một owner dùng default role của đúng project; production thật phải tách runtime role giới hạn quyền.
- Schema thay đổi qua migration thuộc service; Hibernate luôn `ddl-auto: validate`.
- Java `Instant` dùng `TIMESTAMP(6) WITH TIME ZONE`; tiền dùng `DECIMAL(18,2)`.
- Integration test dùng đúng PostgreSQL 17, không dùng H2 và không gọi Neon qua mạng.
- Không ghi URL/password Neon thật vào Git, log hoặc Postman collection.

## 4. Thứ tự thực hiện

1. Đổi dependency và datasource của năm module có persistence.
2. Chuyển toàn bộ migration Loan đã duyệt sang PostgreSQL và giữ Hibernate ở chế độ validate.
3. Dùng Flyway cho Payment/Blockchain/User/Investment; migration nghiệp vụ được thêm cùng task tạo entity đầu tiên của từng service.
4. Tạo PostgreSQL Testcontainers test cho cả năm module.
5. Đổi Compose, port, volume, runtime role và smoke scope.
6. Đồng bộ registry, roadmap, Loan Design/PLAN/LN và hướng dẫn Neon.
7. Chạy compile, unit test, Testcontainers verify, Compose config, rule validation và diff check.

## 5. Acceptance criteria

| ID | Tiêu chí |
|---|---|
| AC-01 | POM active không còn MySQL/MariaDB/MongoDB driver hoặc starter |
| AC-02 | Năm datasource dùng PostgreSQL JDBC và secret qua environment |
| AC-03 | Toàn bộ migration Loan hiện hành migrate + Hibernate validate thành công trên PostgreSQL 17 |
| AC-04 | Payment/Blockchain/User/Investment khởi động với Flyway + Hibernate validate trên PostgreSQL 17 |
| AC-05 | Năm integration test xác nhận đúng PostgreSQL 17 và không còn migration pending |
| AC-06 | Compose config có database/volume/port/runtime credential riêng từng service |
| AC-07 | Neon guide đủ bước tạo project, IntelliJ, DBeaver, quota, SSL và fallback |
| AC-08 | User/Investment/Keycloak đã chuyển PostgreSQL với database/credential/volume riêng |
| AC-09 | Rules, registry và plan active thống nhất PostgreSQL/Neon |
| AC-10 | Không có secret thật; validation/diff check pass |

## 6. Bằng chứng hiện tại

| Kiểm tra | Kết quả |
|---|---|
| Compile Loan/Payment/Blockchain và dependency `finora-common` bằng JDK 21 | Pass, exit code 0 |
| Biên dịch toàn bộ test source | Pass, exit code 0 |
| Unit test `finora-common` + Loan | Pass, 16 test, 0 failure/error |
| Dependency tree MySQL/MariaDB của ba service | Pass, không có artifact khớp |
| Compose config toàn bộ profile | Pass, exit code 0 |
| PostgreSQL Testcontainers verify của Loan | Pass, Maven exit code 0 |
| Fineract PostgreSQL 18.3 health/auth smoke | Pass |
| `mvn verify` Payment/Blockchain/User/Investment với PostgreSQL 17.5 Testcontainers (2026-08-08) | Pass; 4 module và `finora-common` đều SUCCESS |
| Compose config sau khi đổi User/Investment/Keycloak (2026-08-08) | Pass, exit code 0 |
| Docker smoke User + Keycloak PostgreSQL (2026-08-08) | Pass; health và Keycloak readiness đạt, container được dừng; volume smoke tạm đã xóa để owner tự đặt credential |
| Docker smoke Investment + Keycloak PostgreSQL (2026-08-08) | Pass; health và Keycloak readiness đạt, container được dừng; volume smoke tạm đã xóa để owner tự đặt credential |
| Rule validation và `git diff --check` (2026-08-08) | Pass; chỉ có cảnh báo chuyển LF/CRLF của Git trên Windows |

User và Investment chưa có entity nghiệp vụ, vì vậy Flyway hiện khởi tạo lịch sử
nhưng chưa tạo bảng giả. Migration `V1` của từng service phải đi cùng entity đầu tiên
để schema phản ánh đúng nghiệp vụ. Task ở `REVIEW` sau khi local smoke và quality
gate đạt; kết nối Neon thật được nghiệm thu riêng vì repository không lưu credential.

## 7. Failure path

- Neon lỗi không được tự chuyển âm thầm sang local DB; readiness phải DOWN để tránh ghi dữ liệu vào hai nơi.
- Migration lỗi/checksum lệch phải dừng startup và điều tra; không dùng `ddl-auto:update` hoặc `flyway repair` để che lỗi.
- Docker tắt chỉ chặn Testcontainers/offline smoke, không chặn workflow Neon.
- Volume MySQL/MongoDB cũ được giữ nguyên cho tới khi hai owner xác nhận không có dữ liệu cần phục hồi.
- Nếu quota gần đầy, đo từng Neon Project, dọn branch/test data hoặc nâng gói; không cho service dùng ké database khác.
