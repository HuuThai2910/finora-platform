---
task_id: P0-A06
title: PostgreSQL 17 và Neon cho các service do Thái sở hữu
owner: Thai
reviewers:
  - Hai
status: IN_PROGRESS
created_at: 2026-08-03
updated_at: 2026-08-03
approved_by: Thai
approved_at: 2026-08-03
---

# P0-A06 — PostgreSQL 17 và Neon cho Loan, Payment, Blockchain

## 1. Mục tiêu

Chuyển toàn bộ persistence thuộc phạm vi Thái từ MySQL hoặc cấu hình database chưa thống nhất sang PostgreSQL 17. Mỗi service dùng một Neon Project/endpoint/credential độc lập; trong giai đoạn khóa luận dùng `neondb` và default role của project. PostgreSQL Docker và Testcontainers chỉ phục vụ offline/test.

Thiết kế hạ tầng chi tiết nằm tại [P0-C04](P0-C04-local-docker-infrastructure.md). Chuyển đổi schema Loan chi tiết nằm tại [LN-001A](../../finora-loan/plans/LN-001A-postgresql-neon-migration.md).

## 2. Phạm vi theo service

| Service | Thay đổi bắt buộc | Neon Project | Docker fallback |
|---|---|---|---|
| Loan | PostgreSQL JDBC/Flyway/validate/Testcontainers; V1/V2 cuối chờ duyệt LN-003/LN-004 | `finora-loan` | `loan-postgres:17.5`, host `15433` |
| Payment | Bỏ MySQL JDBC, thêm PostgreSQL JDBC, Flyway, Hibernate validate và PostgreSQL Testcontainers | `finora-payment` | `payment-postgres:17.5`, host `15434` |
| Blockchain | Thêm PostgreSQL JDBC/JPA/Flyway làm nền cho submission/reconciliation, PostgreSQL Testcontainers | `finora-blockchain` | `blockchain-postgres:17.5`, host `15435` |

Không đổi User/Keycloak MySQL, Investment MongoDB hoặc source service của Hải. Fineract có project `finora-fineract` riêng và do P0-C05/LN-006 triển khai.

## 3. Quy tắc dữ liệu

- Mỗi service chỉ truy cập endpoint/database/credential của chính mình.
- Docker runtime không dùng tài khoản `postgres`. Neon khóa luận một owner dùng default role của đúng project; production thật phải tách runtime role giới hạn quyền.
- Schema thay đổi qua migration thuộc service; Hibernate luôn `ddl-auto: validate`.
- Java `Instant` dùng `TIMESTAMP(6) WITH TIME ZONE`; tiền dùng `DECIMAL(18,2)`.
- Integration test dùng đúng PostgreSQL 17, không dùng H2 và không gọi Neon qua mạng.
- Không ghi URL/password Neon thật vào Git, log hoặc Postman collection.

## 4. Thứ tự thực hiện

1. Đổi dependency và datasource của ba module.
2. Tháo V1/V2 thử nghiệm; viết V1/V2 PostgreSQL cuối sau khi LN-003/LN-004 được duyệt.
3. Thêm Flyway baseline cho Payment/Blockchain; migration nghiệp vụ được thêm cùng task nghiệp vụ tương ứng.
4. Tạo PostgreSQL Testcontainers test cho cả ba module.
5. Đổi Compose, port, volume, runtime role và smoke scope.
6. Đồng bộ registry, roadmap, Loan Design/PLAN/LN và hướng dẫn Neon.
7. Chạy compile, unit test, Testcontainers verify, Compose config, rule validation và diff check.

## 5. Acceptance criteria

| ID | Tiêu chí |
|---|---|
| AC-01 | POM của Loan/Payment/Blockchain không còn MySQL/MariaDB driver |
| AC-02 | Ba datasource dùng PostgreSQL JDBC và secret qua environment |
| AC-03 | V1/V2 cuối của LN-003/LN-004 migrate + Hibernate validate thành công trên PostgreSQL 17 |
| AC-04 | Payment/Blockchain khởi động với Flyway + Hibernate validate trên PostgreSQL 17 |
| AC-05 | Ba integration test xác nhận đúng PostgreSQL 17 và không còn migration pending |
| AC-06 | Compose config có database/volume/port/runtime credential riêng từng service |
| AC-07 | Neon guide đủ bước tạo project, IntelliJ, DBeaver, quota, SSL và fallback |
| AC-08 | User/Keycloak/Investment và source service của Hải không bị chuyển engine |
| AC-09 | Rules, registry và plan active thống nhất PostgreSQL/Neon |
| AC-10 | Không có secret thật; validation/diff check pass |

## 6. Bằng chứng hiện tại

| Kiểm tra | Kết quả ngày 2026-08-03 |
|---|---|
| Compile Loan/Payment/Blockchain và dependency `finora-common` bằng JDK 21 | Pass, exit code 0 |
| Biên dịch toàn bộ test source | Pass, exit code 0 |
| Unit test `finora-common` + Loan | Pass, 16 test, 0 failure/error |
| Dependency tree MySQL/MariaDB của ba service | Pass, không có artifact khớp |
| Compose config toàn bộ profile | Pass, exit code 0 |
| PostgreSQL Testcontainers verify của Loan | Pass, Maven exit code 0 |
| Fineract PostgreSQL 18.3 health/auth smoke | Pass |

Task giữ `IN_PROGRESS` cho tới khi Payment/Blockchain có migration/test theo task
nghiệp vụ tương ứng và vùng dùng chung được Hải review. Thái chuyển task
sang `ACCEPTED` sau khi kết nối Neon và nghiệm thu.

## 7. Failure path

- Neon lỗi không được tự chuyển âm thầm sang local DB; readiness phải DOWN để tránh ghi dữ liệu vào hai nơi.
- Migration lỗi/checksum lệch phải dừng startup và điều tra; không dùng `ddl-auto:update` hoặc `flyway repair` để che lỗi.
- Docker tắt chỉ chặn Testcontainers/offline smoke, không chặn workflow Neon.
- MySQL volume cũ được giữ nguyên cho tới khi xác nhận không có dữ liệu cần phục hồi.
- Nếu quota gần đầy, đo từng Neon Project, dọn branch/test data hoặc nâng gói; không cho service dùng ké database khác.
