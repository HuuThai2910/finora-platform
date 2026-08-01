---
task_id: P0-C04
title: Docker Compose local với database instance riêng cho từng service
owners: Thai, Hai
initiated_by: Thai
status: REVIEW
created_at: 2026-08-01
updated_at: 2026-08-01
approved_by: Thai
approved_at: 2026-08-01
reviewed_by:
reviewed_at:
---

# P0-C04 — Hạ tầng local tách database theo service

## 1. Quyết định kiến trúc

Thái yêu cầu mức cô lập rõ ràng hơn cho microservice trước khi phát triển nghiệp vụ:

- Mỗi service có database process/container, application user và volume riêng.
- Service không dùng MySQL root khi chạy và không truy cập database service khác.
- Chỉ khởi động database của service đang phát triển; không bắt máy local chạy toàn bộ storage mỗi ngày.
- Kafka/Zookeeper và Keycloak là hạ tầng dùng chung trong profile `core`.
- Keycloak cũng có MySQL riêng thay vì database dev nằm trong container Keycloak.
- Việc tách container xử lý failure isolation ở cấp database process; Docker host local vẫn là failure domain chung. Production cần managed database/cluster/failover hoặc host tách biệt.

Quyết định này thay thế bản P0-C04 đầu tiên dùng chung một MySQL instance và các database logic.

## 2. Sơ đồ local mục tiêu

```text
core
├── keycloak-db (MySQL 8.4, internal only, volume riêng)
├── keycloak (host 8180)
├── zookeeper (internal only)
└── kafka (host 9092, internal 29092)

loan
└── loan-mysql (host 13306, DB/user/volume riêng)

payment
├── payment-mysql (host 13307, DB/user/volume riêng)
└── payment-redis (host 6380, password + volume riêng)

user
└── user-mysql (host 13308, DB/user/volume riêng)

investment
└── investment-mongo (host 27018, root + app user + volume riêng)

apps
└── finora-loan (host 8081; mẫu container app đầu tiên)
```

## 3. Ownership và ranh giới

- Thái triển khai Compose/smoke/docs và Loan app mẫu trong task này.
- Không sửa source/config module `finora-user` hoặc `finora-investment` của Hải.
- Container User/Investment chỉ cung cấp hạ tầng rỗng, credential local và healthcheck; migration/index/schema nghiệp vụ vẫn do Hải sở hữu ở P0-B01/P1.
- Container Payment cung cấp hạ tầng và config runtime trỏ đúng app user; migration/Redis usage nghiệp vụ thật vẫn thuộc task Payment của Thái.
- Vùng `docker/`, `.agents/`, registry và README dùng chung phải được Hải review trước khi `DONE`.

## 4. Profile và lệnh hằng ngày

| Scope smoke | Profile được bật | Mục đích |
|---|---|---|
| `Core` | `core` | Chỉ Kafka/Keycloak |
| `Loan` | `core + loan` | Công việc hằng ngày của Thái với Loan |
| `Payment` | `core + payment` | Công việc Payment |
| `User` | `core + user` | Công việc User |
| `Investment` | `core + investment` | Công việc Investment |
| `All` | tất cả profile dữ liệu | Integration/smoke toàn hạ tầng |

```powershell
# Bắt đầu ngày làm Loan
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -KeepRunning

# Kết thúc ngày, giữ volume
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -Stop
```

## 5. Credential contract local

| Thành phần | Root/admin chỉ dùng init | Application credential |
|---|---|---|
| Keycloak DB | `KEYCLOAK_DB_ROOT_PASSWORD` | `keycloak` + `KEYCLOAK_DB_PASSWORD` |
| Loan MySQL | `LOAN_MYSQL_ROOT_PASSWORD` | `LOAN_DB_USERNAME/LOAN_DB_PASSWORD` |
| Payment MySQL | `PAYMENT_MYSQL_ROOT_PASSWORD` | `PAYMENT_DB_USERNAME/PAYMENT_DB_PASSWORD` |
| Payment Redis | Không có root riêng | `PAYMENT_REDIS_PASSWORD` |
| User MySQL | `USER_MYSQL_ROOT_PASSWORD` | `USER_DB_USERNAME/USER_DB_PASSWORD` |
| Investment Mongo | `INVESTMENT_MONGO_ROOT_*` | `INVESTMENT_DB_USERNAME/INVESTMENT_DB_PASSWORD` |

Secret chỉ nằm trong `docker/.env` bị Git ignore. `.env.example` chỉ liệt kê tên biến và để trống secret.

## 6. File thay đổi

| Thao tác | File | Lý do |
|---|---|---|
| MODIFY | `docker/docker-compose.yml` | Tách profile/container/user/volume theo service |
| MODIFY | `docker/.env.example` | Credential contract riêng từng database |
| ADD | `docker/mongodb/investment/init.js` | Tạo app user chỉ có `readWrite` trên DB Investment |
| DELETE | `docker/mysql/init.sql` | Không còn tạo nhiều database trong một MySQL chung |
| MODIFY | `docker/smoke-infra.ps1` | Smoke/start/stop theo scope, không xóa volume |
| MODIFY | `docker/README.md`, `README.md` | Lệnh hằng ngày, DBeaver và failure domain |
| MODIFY | `finora-payment/src/main/resources/application.yml` | Payment dùng app user, MySQL/Redis riêng và secret qua environment |
| MODIFY | `.agents/rules/05-registry.md` | Registry host/internal port và storage owner mới |
| MODIFY | `.agents/plans/finora-team-roadmap.md` | Ghi quyết định và trạng thái |
| RETAIN | `docker/Dockerfile.java-service`, `.dockerignore` | Loan app container mẫu không đổi mục tiêu |

## 7. Failure path bắt buộc

- Thiếu secret: smoke script fail trước khi gọi Compose và chỉ nêu biến bắt buộc của scope được chọn.
- Một database lỗi: scope smoke fail; database container của service khác không bị dừng/xóa.
- Cổng host bị chiếm: không dừng process bên ngoài; đổi `*_HOST_PORT` trong `.env`.
- Volume cũ: không tự xóa. Hai volume kiến trúc cũ được giữ để tránh mất dữ liệu và không gắn vào Compose mới.
- Đổi password trong `.env`: không giả định credential trong volume cũ tự đổi.
- Docker daemon chưa chạy: fail rõ ràng.
- Không có `-KeepRunning`: dừng/xóa container đúng scope vừa test, giữ volume.
- `-Stop`: dừng/xóa container đúng scope, giữ volume.

## 8. Acceptance criteria

| ID | Tiêu chí |
|---|---|
| AC-01 | Loan, Payment, User, Investment và Keycloak có database container/volume riêng |
| AC-02 | Mỗi app database có non-root application user; không có credential thật trong Git |
| AC-03 | `Scope Loan` chỉ chạy `core + loan-mysql`, không chạy database Payment/User/Investment |
| AC-04 | `Scope All` làm tất cả database/cache và core đạt health/readiness |
| AC-05 | Port chỉ bind loopback; database nội bộ vẫn dùng port chuẩn |
| AC-06 | Loan chạy IDE kết nối `localhost:13306`; Loan container kết nối `loan-mysql:3306` |
| AC-07 | Host Kafka dùng 9092; container dùng `kafka:29092` |
| AC-08 | Smoke có timeout, exit code đúng, `-KeepRunning`, `-Stop`, không xóa volume |
| AC-09 | DBeaver đăng nhập Loan bằng `finora_loan`, không dùng root |
| AC-10 | Loan image Java 21 chạy non-root và health endpoint HTTP 200 |
| AC-11 | Compose config, Maven verify, rule validator và diff check pass |
| AC-12 | Không sửa module Hải; Hải review vùng chung trước khi `DONE` |

## 9. Cổng bàn giao

Chuyển `REVIEW` khi AC-01 đến AC-11 có bằng chứng. Chỉ chuyển `DONE` sau khi Hải review profile/credential/port thuộc User và Investment cùng vùng dùng chung.

## 10. Bằng chứng triển khai

### 10.1. Config và failure path

| Kiểm tra | Kết quả |
|---|---|
| Compose config `core + loan` bằng `docker/.env` | exit `0` |
| Compose config toàn bộ profile bằng `docker/.env` | exit `0` |
| Smoke `Scope Loan` bằng `.env.example` trống | exit `1` trước Docker; liệt kê đúng 4 secret Core + 2 secret Loan |
| PowerShell parser Windows 5.1 | pass; script lưu UTF-8 BOM |

### 10.2. Scope và health

| Kiểm tra | Kết quả |
|---|---|
| `smoke-infra.ps1 -Scope Loan -KeepRunning` | exit `0`; chỉ `keycloak-db`, Keycloak, Zookeeper, Kafka, `loan-mysql` chạy |
| Storage Payment/User/Investment sau Scope Loan | không có container hoặc volume mới của ba scope này |
| `smoke-infra.ps1 -Scope All` | exit `0`; 3 MySQL service, Mongo Investment, Redis Payment và toàn bộ Core healthy/ready |
| `smoke-infra.ps1 -Scope All -Stop` | exit `0`; container bị xóa, toàn bộ volume mới/cũ vẫn còn |

### 10.3. Cô lập credential và volume

- Loan xác thực `finora_loan@%`, chỉ có privilege trên `finora_loan.*`.
- Payment xác thực `finora_payment@%`, chỉ có privilege trên `finora_payment.*`.
- User xác thực `finora_user@%`, chỉ có privilege trên `finora_user.*`.
- Investment xác thực Mongo user `finora_investment` tại database `finora_investment`.
- Payment Redis bắt buộc password và trả `PONG` bằng credential local.
- Keycloak dùng `keycloak@%` trên MySQL riêng; database port không publish ra host.
- Volume mới: `docker_keycloak-mysql-data`, `docker_loan-mysql-data`, `docker_payment-mysql-data`, `docker_payment-redis-data`, `docker_user-mysql-data`, `docker_investment-mongo-data`.
- Volume kiến trúc cũ `docker_mysql-data`, `docker_mongo-data` được giữ nguyên và không gắn Compose mới.

### 10.4. App và quality gate

| Kiểm tra | Kết quả |
|---|---|
| Loan container với `loan-mysql:3306` | HTTP `200`; runtime UID `10001` non-root |
| `mvn -pl finora-loan -am verify` | exit `0`; `1/1` integration test pass trên MySQL 8.4 |
| `mvn -pl finora-payment -am verify` | exit `0`; compile/package pass sau khi bỏ root credential |
| `.agents/scripts/validate-rules.ps1` | exit `0` |
| `git diff --check` | exit `0` |
| Secret scan file mới, loại `docker/.env` | không thấy local credential bị đưa vào Git |

### 10.5. Acceptance

- AC-01 đến AC-11: `PASS`.
- AC-12: không sửa module Hải; Hải review vùng dùng chung vẫn `PENDING`, nên trạng thái là `REVIEW`, chưa phải `DONE`.

### 10.6. Giới hạn và việc owner tiếp theo phải làm

- Tất cả database local vẫn chạy trên một Docker host; đây không phải HA production.
- Keycloak dùng `start-dev`; production cần TLS, secret store và database/cluster có backup/failover.
- Flyway 9.22.3 vẫn cảnh báo MySQL 8.4 mới hơn dải đã test; Loan integration test thực tế pass.
- `finora-user` và config Mongo của `finora-investment` phải được Hải đổi sang environment/app credential trước khi chạy ứng dụng với profile mới; P0-C04 không sửa module Hải.
- Payment vẫn còn nợ chuyển `ddl-auto: update` sang Flyway/Testcontainers và thu hẹp Kafka trusted packages tại P0-A03; task này chỉ sửa runtime credential/endpoint.
- Compose dùng một file để dễ nhớ; chạy qua smoke script để kiểm tra secret theo scope. Gọi `docker compose up` trực tiếp với secret trống sẽ để entrypoint/app tự fail.

## 11. Nghiệm thu

```text
Thai: APPROVED DATABASE INSTANCE PER SERVICE on 2026-08-01
Hai review: PENDING
Final decision: REVIEW
```
