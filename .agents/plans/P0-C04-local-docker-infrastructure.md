---
task_id: P0-C04
title: Neon-first và Docker offline database theo service
owners: Thai, Hai
initiated_by: Thai
status: IN_PROGRESS
created_at: 2026-08-01
updated_at: 2026-08-08
approved_by: Thai, Hai
approved_at: 2026-08-08
reviewed_by:
reviewed_at:
---

# P0-C04 — Neon-first và PostgreSQL Docker offline

## 1. Quyết định hiện hành

Ngày 2026-08-08, Thái và Hải thống nhất hạ tầng database chung:

- Loan, Payment, Blockchain, User và Investment dùng PostgreSQL 17.
- Mỗi service có một Neon Project/endpoint/credential riêng; mỗi project dùng `neondb` và default role Neon trong giai đoạn khóa luận.
- Neon là database hằng ngày; không bắt buộc bật container PostgreSQL khi chạy IntelliJ.
- Docker PostgreSQL được giữ làm offline fallback, migration smoke và nền cho Testcontainers.
- Apache Fineract dùng Neon/PostgreSQL Project riêng với `fineract_tenants` và `fineract_default`.
- Keycloak dùng PostgreSQL 17 riêng, không dùng chung database User.
- Không service nào truy cập database service khác. Neon khóa luận một owner được phép dùng default role; production thật phải tách runtime role giới hạn quyền.

Quyết định này thay thế phần Loan/Payment MySQL trong bản P0-C04 ngày 2026-08-01. Bằng chứng lịch sử MySQL được ghi ở mục 11, không dùng để chạy mới.

## 2. Topology mục tiêu

### Managed development

```text
Neon finora-loan
└── neondb / default role của project

Neon finora-payment
└── neondb / default role của project

Neon finora-blockchain
└── neondb / default role của project

Neon finora-user
└── neondb / default role của project

Neon finora-investment
└── neondb / default role của project

Neon finora-keycloak
└── neondb / default role của project

Neon finora-fineract
├── fineract_tenants
└── fineract_default
```

### Docker offline

```text
core
├── keycloak-db (PostgreSQL 17, internal only)
├── keycloak
├── zookeeper
└── kafka

loan
└── loan-postgres (PostgreSQL 17, host 15433)

payment
├── payment-postgres (PostgreSQL 17, host 15434)
└── payment-redis (host 6380)

blockchain
└── blockchain-postgres (PostgreSQL 17, host 15435)

user
└── user-postgres (PostgreSQL 17, host 15436)

investment
└── investment-postgres (PostgreSQL 17, host 15437)

apps
└── finora-loan (mẫu app container)

fineract
├── fineract-postgres (PostgreSQL 18.3, host 15432)
└── fineract 1.15.0 (host 18443)
```

Docker host vẫn là failure domain chung của local. Neon Project riêng tách credential/quota/compute ở mức managed development nhưng Free plan không thay thế yêu cầu backup/SLA/security review trước production thật.

## 3. Ownership

- Thái và Hải đã cho phép chuyển datasource, driver, Flyway, Testcontainers và config của Loan/Payment/Blockchain/User/Investment.
- AI, Notification và Gateway không có database nên không thêm persistence ngoài nhu cầu nghiệp vụ.
- `docker/`, `.agents/` và README là vùng dùng chung; Hải review trước khi task `DONE`.
- Fineract schema do Liquibase của Fineract quản lý; Flyway FINORA không chạy vào Fineract DB.
- Mỗi service chỉ sở hữu schema/database của chính mình.

## 4. Workflow hằng ngày

### Dùng Neon

1. Tạo project/role/database theo [`NEON-POSTGRESQL-SETUP.md`](../../NEON-POSTGRESQL-SETUP.md).
2. Đặt `*_DB_URL`, `*_DB_USERNAME`, `*_DB_PASSWORD` trong IntelliJ/secret store.
3. Chạy service; không bật PostgreSQL Docker.
4. Flyway chạy vào đúng database service.

### Dùng Docker offline

```powershell
# Loan database riêng, không bật core
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan up -d loan-postgres

# Smoke scope có core
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -KeepRunning
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Payment -KeepRunning
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Blockchain -KeepRunning
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope User -KeepRunning
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Investment -KeepRunning
```

`All` bật toàn bộ storage PostgreSQL/Redis active và core infrastructure.

## 5. Credential contract

| Thành phần | Init/admin secret | Runtime credential |
|---|---|---|
| Loan PostgreSQL Docker | `LOAN_POSTGRES_ADMIN_PASSWORD` | `LOAN_DB_USERNAME/LOAN_DB_PASSWORD` |
| Payment PostgreSQL Docker | `PAYMENT_POSTGRES_ADMIN_PASSWORD` | `PAYMENT_DB_USERNAME/PAYMENT_DB_PASSWORD` |
| Blockchain PostgreSQL Docker | `BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD` | `BLOCKCHAIN_DB_USERNAME/BLOCKCHAIN_DB_PASSWORD` |
| Payment Redis | Không có admin riêng | `PAYMENT_REDIS_PASSWORD` |
| User PostgreSQL Docker | `USER_POSTGRES_ADMIN_PASSWORD` | `USER_DB_USERNAME/USER_DB_PASSWORD` |
| Investment PostgreSQL Docker | `INVESTMENT_POSTGRES_ADMIN_PASSWORD` | `INVESTMENT_DB_USERNAME/INVESTMENT_DB_PASSWORD` |
| Keycloak PostgreSQL | `KEYCLOAK_POSTGRES_ADMIN_PASSWORD` | `KEYCLOAK_DB_USERNAME/KEYCLOAK_DB_PASSWORD` |
| Fineract PostgreSQL | `FINERACT_POSTGRES_ADMIN_PASSWORD` | `FINERACT_DB_USERNAME/FINERACT_DB_PASSWORD` |

Docker init script tạo runtime PostgreSQL role `NOSUPERUSER/NOCREATEDB/NOCREATEROLE`. Trên Neon khóa luận, username là default role hiển thị trong Connect của đúng project; không hard-code hoặc dùng username project khác.

Connection string Neon không xuất hiện trong `.env.example` hoặc registry vì có host/credential cụ thể. Chỉ tên biến được commit.

## 6. Port và endpoint

| Service | Neon | Docker host | Docker network |
|---|---|---:|---|
| Loan | URL qua `LOAN_DB_URL` | 15433 | `loan-postgres:5432` |
| Payment | URL qua `PAYMENT_DB_URL` | 15434 | `payment-postgres:5432` |
| Blockchain | URL qua `BLOCKCHAIN_DB_URL` | 15435 | `blockchain-postgres:5432` |
| User | URL qua `USER_DB_URL` | 15436 | `user-postgres:5432` |
| Investment | URL qua `INVESTMENT_DB_URL` | 15437 | `investment-postgres:5432` |
| Fineract DB | Project riêng, planned | 15432 | `fineract-postgres:5432` |
| Fineract API | Không áp dụng | 18443 | `fineract:8443` |

Port host có thể override trong `docker/.env`; port internal không đổi.

## 7. File thay đổi

- `finora-loan/payment/blockchain/user/investment` POM và `application.yml`.
- Loan PostgreSQL Testcontainer; V1/V2 cuối chờ LN-003/LN-004 được duyệt.
- `docker/docker-compose.yml`, `.env.example`, `postgresql/init-service-user.sh`.
- `docker/smoke-infra.ps1` và tài liệu chạy.
- Registry, roadmap, Loan Design/PLAN/LN và Neon guide.

## 8. Failure path

- Thiếu secret Docker: smoke fail trước Compose và nêu đúng biến thiếu.
- Neon unavailable: readiness DOWN; không tự fallback sang local DB vì có thể tạo split-brain dữ liệu.
- Đổi credential nhưng giữ volume cũ: init script không chạy lại; đổi role có kiểm soát hoặc recreate volume sau backup.
- Cổng host bị chiếm: đổi biến port; không dừng process ngoài scope.
- Docker daemon không chạy: Neon workflow vẫn chạy; Testcontainers được báo chưa kiểm tra.
- Migration lỗi/checksum lệch: dừng startup; không dùng Hibernate update hoặc Flyway repair để che lỗi.
- PostgreSQL volume mới và volume MySQL/MongoDB cũ độc lập; không xóa volume cũ tự động.
- Neon quota gần đầy: đo từng project, dọn branch/test data hoặc nâng gói; không shard một service qua project khác.

## 9. Acceptance criteria

| ID | Tiêu chí |
|---|---|
| AC-01 | Loan/Payment/Blockchain/User/Investment dùng PostgreSQL JDBC; không còn MySQL/MongoDB dependency active |
| AC-02 | Toàn bộ migration Loan hiện hành migrate và Hibernate validate trên PostgreSQL 17 |
| AC-03 | Payment/Blockchain/User/Investment dùng Flyway + validate, không `ddl-auto:update` |
| AC-04 | Năm service và Keycloak có PostgreSQL container/volume/runtime role riêng |
| AC-05 | Scope Loan/Payment/Blockchain/User/Investment/All resolve đúng service và secret |
| AC-06 | Neon guide có Project riêng, `neondb`/default role, IntelliJ, DBeaver, quota và security |
| AC-07 | User/Investment/Keycloak đều dùng PostgreSQL riêng và old-engine volume không bị tự động xóa |
| AC-08 | Compose config, Maven verify phù hợp, rules validation và diff check pass |
| AC-09 | Không secret thật hoặc URL Neon cụ thể trong Git |
| AC-10 | Hải review vùng dùng chung trước `DONE` |

## 10. Bằng chứng bàn giao ngày 2026-08-08

- Compose config cho tất cả profile: pass, exit code 0.
- Loan Maven verify và PostgreSQL Testcontainers: đã pass trong LN-008/P0-A06.
- Payment/Blockchain/User/Investment PostgreSQL Testcontainers verify: pass, bốn module SUCCESS.
- Docker smoke User + Keycloak PostgreSQL: pass; health và readiness đạt.
- Docker smoke Investment + Keycloak PostgreSQL: pass; health và readiness đạt.
- Ba volume PostgreSQL chỉ dùng credential smoke đã được xóa sau kiểm thử; old-engine volume và dữ liệu service khác không bị tác động.
- Rule validation và `git diff --check`: pass; không có lỗi whitespace/rule.
- User/Investment chưa có entity nghiệp vụ nên Flyway chưa tạo bảng giả; `V1` phải đi cùng entity đầu tiên.
- Neon thật chưa được gọi từ quality gate vì credential không lưu trong repository; owner kiểm tra connection riêng theo guide.

## 11. Lịch sử không dùng để triển khai mới

Ngày 2026-08-01, P0-C04 từng chạy Loan/Payment bằng MySQL 8.4 container riêng và pass smoke trên máy Thái; Loan test pass trên MySQL. Các volume cũ như `docker_loan-mysql-data` và `docker_payment-mysql-data` được giữ để tránh mất dữ liệu nhưng không còn gắn Compose.

Lịch sử này giải thích vì sao một số LN-001/LN-003/LN-004 cũ vẫn nhắc MySQL. Tất cả task mới phải theo PostgreSQL/LN-001A.
