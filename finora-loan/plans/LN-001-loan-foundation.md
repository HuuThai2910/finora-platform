---
task_id: LN-001
title: Nền tảng persistence MySQL cho Loan Service
owner: Thai
status: SUPERSEDED
roadmap_task: P0-A01
depends_on: []
created_at: 2026-08-01
updated_at: 2026-08-03
approved_by: Thai
approved_at: 2026-08-01
accepted_by: Thai
accepted_at: 2026-08-01
superseded_by: LN-001A-postgresql-neon-migration.md
---

# LN-001 — Nền tảng persistence MySQL cho Loan Service

> **TÀI LIỆU LỊCH SỬ.** LN-001 ghi lại baseline MySQL đã được nghiệm thu ngày 2026-08-01.
> Runtime hiện hành đã chuyển sang PostgreSQL 17/Neon tại
> [`LN-001A-postgresql-neon-migration.md`](LN-001A-postgresql-neon-migration.md). Không dùng hướng dẫn MySQL bên dưới để triển khai mới.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này từng giải quyết việc gì?

LN-001 không tạo chức năng vay cho borrower hay admin. Nó từng chuẩn bị “nền móng lưu dữ liệu” để Loan Service khởi động, kết nối database và kiểm tra schema có đúng với code hay không. Hướng MySQL này đã ngừng sử dụng; LN-001A là hướng hiện hành.

### Ai bị ảnh hưởng và nhìn thấy gì?

- Lập trình viên từng có thể chạy Loan với MySQL 8.4 và test trên database thật.
- Borrower/admin không có màn hình hoặc API mới từ LN này.
- Người bảo trì chỉ đọc file này để biết quyết định lịch sử, không dùng nó để tạo môi trường mới.

### Luồng vận hành và trường hợp lỗi

```text
Khởi động Loan → Flyway kiểm tra migration → Hibernate kiểm tra entity
    ├── khớp schema: ứng dụng sẵn sàng
    └── sai schema/kết nối: ứng dụng dừng, không tự sửa bảng
```

Nếu còn cấu hình MySQL theo LN-001, phải chuyển sang LN-001A; không tạo thêm migration MySQL để “chữa” runtime cũ.

### Ví dụ và điều kiện xem là hoàn tất

Ví dụ: một máy cũ chạy `localhost:13306` là bằng chứng của baseline lịch sử, không phải cấu hình Loan hiện tại. LN-001 đã hoàn tất về mặt lịch sử khi MySQL/Testcontainers từng chạy thành công; mọi phát triển mới phải được đánh giá theo PostgreSQL của LN-001A.

## 1. Ranh giới tài liệu

LN-001 chỉ chuẩn hóa MySQL, Flyway, Hibernate configuration và Testcontainers. Duyệt/triển khai/accept file này **không đồng nghĩa** duyệt Entity hoặc nghiệp vụ Loan.

Các thiết kế đã được tách:

- Thiết kế tổng thể: [`LOAN-SERVICE-DESIGN.md`](LOAN-SERVICE-DESIGN.md)
- Loan Product: [`LN-003-loan-product.md`](LN-003-loan-product.md)
- Loan Application: [`LN-004-loan-application.md`](LN-004-loan-application.md)

LN-001 không tạo bảng nghiệp vụ, Entity, Repository hay API.

## 2. Quyết định đã chốt

- Loan dùng MySQL, không dùng MariaDB.
- Baseline Loan và Docker Compose chung là MySQL 8.4 LTS; image/Testcontainers pin `mysql:8.4`, không dùng `latest`.
- Thái đã cho phép nâng image Compose trong LN-001. Đây là vùng dùng chung nên Hải vẫn phải review trước merge; P0-C04 tiếp tục chịu trách nhiệm smoke test/profile hạ tầng rộng hơn.
- Flyway là nguồn duy nhất thay đổi schema; Hibernate chỉ `validate`.
- Không tạo migration rỗng/V1 giả hoặc tạo sớm outbox/processed-event.
- Migration nghiệp vụ đầu tiên thuộc LN-003 sau khi Product plan được `APPROVED`.
- Không dùng Redis trong LN-001.
- Không sửa POM cha. Thay đổi vùng chung chỉ giới hạn image/config MySQL Compose và registry liên quan đã được Thái cho phép.

## 3. Mục tiêu

1. Chuẩn hóa kết nối MySQL và cấu hình runtime cho `finora-loan`.
2. Loại `ddl-auto:update` và credential root hard-code.
3. Thêm Flyway để schema có version và Hibernate chỉ kiểm tra mapping.
4. Dùng MySQL 8.4 Testcontainer chứng minh context, datasource và Flyway hoạt động trên engine thật.
5. Chốt dependency tree/version compatibility trước khi mở migration nghiệp vụ.

## 4. Hiện trạng trước khi triển khai

- Parent dùng Spring Boot `3.2.5`, Java `21`.
- `finora-loan/pom.xml` đã có `com.mysql:mysql-connector-j` scope runtime.
- `application.yml` đang dùng `spring.jpa.hibernate.ddl-auto:update` và hard-code local root credential.
- Chưa có Flyway, Testcontainers, `db/migration`, Entity, Repository hoặc integration test.
- `LoanController` chỉ có health endpoint; `LoanService` là interface rỗng.
- Không có schema/dữ liệu Loan cũ cần migrate.

## 5. Phạm vi triển khai

- Thêm `flyway-core` và `flyway-mysql`; cả hai dùng version do Spring Boot BOM quản lý.
- Thêm Testcontainers JUnit/MySQL và Spring Boot Testcontainers nếu dùng `@ServiceConnection`.
- Giữ MySQL Connector/J ở POM Loan; không thêm MariaDB driver.
- Cấu hình Hibernate `ddl-auto:validate`, tắt Open Session in View và dùng UTC.
- Đọc URL/username/password từ environment; fallback chỉ dành cho local development.
- Tạo migration location nhưng không tạo migration nghiệp vụ giả.
- Viết integration test khởi động context trên MySQL 8.4 và kiểm tra Flyway history.
- Ghi dependency tree làm bằng chứng version tương thích.
- Nâng `docker/docker-compose.yml` từ `mysql:8.0` lên `mysql:8.4`, đồng bộ local Loan credential và cập nhật registry MySQL 8.4.

## 6. Ngoài phạm vi

- Không tạo bảng/Entity/Repository/API Product, Application, History, Scoring, Offer, LoanAccount hay Repayment.
- Không tạo `outbox_events`, `processed_events`, Kafka producer/consumer hoặc relay.
- Không triển khai security/error/logging baseline, KYC, AI, market, payment, saga hoặc Fabric.
- Không thêm Redis/cache/distributed lock/Redis idempotency.
- Không đổi các service/image Compose khác, network/port/volume hoặc POM cha.
- Không sửa module của Hải, Common, Gateway, Payment hoặc Blockchain.
- Không migrate dữ liệu vì chưa có dữ liệu nguồn.

## 7. Dependency và POM cha–con

### 7.1 Dependency Loan trực tiếp sử dụng

- `com.mysql:mysql-connector-j` — runtime JDBC driver.
- `org.flywaydb:flyway-core`.
- `org.flywaydb:flyway-mysql` — module nhận diện và xử lý MySQL, dùng cùng version với `flyway-core` từ Spring Boot BOM.
- `org.testcontainers:junit-jupiter` — test.
- `org.testcontainers:mysql` — test.
- `org.springframework.boot:spring-boot-testcontainers` — test nếu dùng `@ServiceConnection`.

Không tự pin version khi Spring Boot BOM đã quản lý. Override version phải có plan amendment, lý do và bằng chứng tương thích.

### 7.2 Vì sao MySQL driver không đặt trong parent `<dependencies>`?

Dependency trong parent `<dependencies>` bị truyền xuống mọi module, khiến Gateway/Common/Blockchain/Investment/Notification nhận driver dù không dùng. Điều đó làm classpath lớn, mờ storage boundary và mở rộng phạm vi dependency scan/nâng cấp.

Quy ước:

- Parent `<dependencyManagement>` chỉ quản lý version/chính sách, không tự kéo dependency.
- Child `<dependencies>` khai báo thư viện module thực sự dùng.
- Spring Boot parent đã quản lý Connector/J nên chưa cần lặp ở root `dependencyManagement` nếu không override.
- Thay đổi POM cha là task chung cần Thái + Hải review.

## 8. Runtime configuration mục tiêu

```yaml
spring:
  datasource:
    url: ${LOAN_DB_URL:jdbc:mysql://localhost:13306/finora_loan}
    username: ${LOAN_DB_USERNAME:finora_loan}
    password: ${LOAN_DB_PASSWORD}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
```

Yêu cầu:

- Không hard-code production credential hoặc log URL chứa secret.
- Không dùng MariaDB URL/driver.
- Không hard-code dialect nếu Hibernate tự nhận đúng; chỉ thêm khi có lỗi và bằng chứng.
- Connection/session timezone thống nhất UTC.
- Database local `finora_loan`, port mặc định `3306` theo registry.

## 9. Vì sao chọn công nghệ này?

| Quyết định | Vấn đề giải quyết | Lý do phù hợp | Đánh đổi |
|---|---|---|---|
| MySQL 8.4 LTS | Persistence quan hệ có constraint/transaction | ACID, unique/FK/check, `DECIMAL`; LTS ổn định trong thời gian khóa luận | Mỗi service phải có DB riêng; không FK/join xuyên service |
| Spring Data JPA/Hibernate | Giảm persistence boilerplate | Stack dự án sẵn có, quản lý transaction/entity tốt | Không tự chống N+1; task nghiệp vụ phải có fetch plan/query test |
| Flyway | Schema version và tái lập | Mọi môi trường chạy cùng migration, review được trong Git | Migration đã merge không sửa; rollback bằng migration sau |
| Hibernate `validate` | Phát hiện mapping lệch schema | Fail fast nhưng không tự sửa DB | Developer phải viết migration trước |
| Testcontainers MySQL 8.4 | Tránh test pass giả trên DB khác | Kiểm tra đúng dialect/constraint/Flyway | Cần Docker, chậm hơn unit test |
| UTC + tắt OSIV | Query/transaction boundary rõ và thời gian nhất quán | Ngăn lazy query ở controller, audit không lệch múi giờ | Service nghiệp vụ phải chọn DTO/fetch plan rõ |
| Chưa dùng Redis | Tránh distributed state sớm | Chưa có query/bottleneck; MySQL đơn giản và nhất quán | Xem lại bằng task riêng khi có metric |

Không dùng H2 thay Testcontainers vì H2 không chứng minh hành vi MySQL. Không dùng `ddl-auto:update` thay Flyway vì schema tự thay đổi không có lịch sử review/rollout đáng tin cậy.

## 10. Test strategy LN-001

- MySQL Testcontainer pin `mysql:8.4`, không dùng H2/MariaDB.
- Spring context khởi động bằng datasource container.
- Flyway tạo/đọc `flyway_schema_history` trên DB sạch dù chưa có migration nghiệp vụ.
- Hibernate `validate` thành công khi chưa có Entity nghiệp vụ.
- Khởi động lại không tạo schema side effect ngoài Flyway history.
- Test deterministic, không credential/PII thật; Testcontainers quản lý lifecycle.

LN-003/LN-004 tự chịu test migration, entity, constraint, index, nghiệp vụ và N+1 trong plan riêng; không tính chúng vào nghiệm thu LN-001.

## 11. File dự kiến thay đổi

| Thao tác | File | Lý do |
|---|---|---|
| MODIFY | `finora-loan/pom.xml` | Flyway và Testcontainers; Connector/J ở module sử dụng |
| MODIFY | `finora-loan/src/main/resources/application.yml` | Env datasource, Flyway, validate, UTC, OSIV |
| ADD có điều kiện | `finora-loan/src/main/resources/db/migration/.gitkeep` | Chỉ giữ location; bỏ nếu gây nhiễu/không cần, LN-003 sẽ tạo thư mục cùng V1 |
| ADD | `finora-loan/src/test/java/com/finora/loan/FinoraLoanApplicationIT.java` | Context/Flyway/MySQL integration test |
| MODIFY | `docker/docker-compose.yml` | Pin MySQL 8.4 và local Loan DB user phù hợp config |
| MODIFY | `docker/mysql/init.sql` | Sửa mô tả schema manager từ Hibernate sang Flyway |
| MODIFY | `.agents/rules/05-registry.md` | Registry ghi đúng MySQL 8.4 |

## 12. Điều kiện bắt buộc

- Không dùng `ddl-auto:update/create/create-drop`, H2 hoặc MariaDB.
- Không tạo Entity/package rỗng, migration giả hoặc bảng outbox.
- Không sửa migration đã merge.
- Không log password/secret/JDBC URL chứa secret/PII.
- Không sửa POM cha; thay đổi vùng chung không vượt image/config MySQL và registry đã duyệt.
- Comment tiếng Việt chỉ giải thích invariant/lý do/failure path, không diễn giải cú pháp.

## 13. Acceptance criteria

| ID | Tiêu chí | Bằng chứng |
|---|---|---|
| AC-01 | Connector/J tồn tại trong Loan; không MariaDB driver/config | Dependency tree + config |
| AC-02 | Hibernate dùng `validate`, không `update` | Diff + context test |
| AC-03 | Flyway là schema manager duy nhất và tạo history trên DB sạch | Integration test + Flyway history |
| AC-04 | Test dùng MySQL 8.4, không H2/MariaDB | Test source + dependency tree |
| AC-05 | OSIV tắt, JDBC timezone UTC | Config + context test |
| AC-06 | Credential lấy từ environment, không secret production | Review diff/config |
| AC-07 | Không V1 giả, Entity hoặc outbox ngoài scope | File/diff summary |
| AC-08 | `mvn -pl finora-loan -am verify` pass | Command/exit code/test count |
| AC-09 | Rule validator pass | Validator output |
| AC-10 | LN-001 không còn chứa/duyệt thay thiết kế task khác | Links + scope review |
| AC-11 | Compose và registry pin MySQL 8.4; Loan local credential không dùng root | Compose config + registry + smoke test khi Docker sẵn sàng |

## 14. Lệnh kiểm tra

```powershell
mvn -pl finora-loan -am verify
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\scripts\validate-rules.ps1
```

Nếu Docker/Testcontainers không khả dụng, không được báo đạt AC-03/AC-04/AC-08; phải ghi command, lỗi, ảnh hưởng và cách chạy lại.

## 15. Rủi ro và xử lý

| Rủi ro | Ảnh hưởng | Xử lý |
|---|---|---|
| Docker không chạy | Không test MySQL thật | Không đổi H2; ghi blocker và hướng dẫn bật Docker |
| Flyway module/version không tương thích | Context fail | Kiểm tra dependency tree; ưu tiên BOM; override qua amendment |
| MySQL local tranh port | Không kết nối đúng server | Dừng service không dùng/đổi port có chủ đích; không tự đổi registry |
| Volume local đã khởi tạo bằng MySQL 8.0/root-only | User mới không tự được tạo khi dùng lại volume; upgrade có thể cần backup/check | Không tự xóa volume; backup dữ liệu nếu có, tạo/grant user hoặc chỉ recreate volume khi Thái xác nhận không có dữ liệu cần giữ |
| Foundation scope creep | LN-001 vô tình tạo nghiệp vụ | Entity/migration nghiệp vụ nằm trong LN-003/LN-004 và cần approval riêng |

## 16. Rollback

- Trước merge: hoàn nguyên dependency/config/test trong phạm vi task nếu thiết kế bị bác bỏ.
- Không dùng `git reset --hard` hoặc xóa database người dùng.
- LN-001 chưa có migration nghiệp vụ nên rollback không được tạo/xóa bảng domain.

## 17. Dependency và phối hợp

- LN-001 không yêu cầu Hải sửa code; Hải nên review pattern vì User cũng dùng MySQL.
- POM cha/Compose là vùng chung; thay đổi cần cả hai review.
- Payment áp dụng pattern bằng task riêng sau khi LN-001 accepted; không gộp.
- Outbox/processed-event thuộc P0-A02, không gộp.
- LN-003 chỉ mở sau LN-001/LN-002 theo cổng trong `PLAN.md`.

## 18. Plan amendment

### PA-001 — MySQL và hoãn schema nghiệp vụ

```text
Reason: Thái xác nhận MySQL; không tạo Entity/outbox/migration nghiệp vụ trước khi plan tương ứng được duyệt.
Scope: MySQL 8.4, parent/child POM, Flyway/Hibernate/Testcontainers.
Deferred: V1 nghiệp vụ sang LN-003; outbox/processed-event sang P0-A02.
Approval: APPROVED by Thai on 2026-08-01 for MySQL direction; toàn bộ LN-001 vẫn chờ plan review.
```

### PA-005 — Tách domain design khỏi LN-001

```text
Reason: File LN-001 trước đây chứa thiết kế LN-003/LN-004/LN-007/LN-008/LN-011/LN-013, làm mơ hồ phạm vi duyệt và nghiệm thu.
Result: LN-001 chỉ còn persistence; Service Design giữ bản đồ tổng; LN-003/LN-004 có plan BACKLOG riêng; future task còn DỰ KIẾN/CHỜ CONTRACT trong Service Design.
Approval: PENDING by Thai
```

### PA-007 — Đồng bộ Docker Compose MySQL 8.4 trong LN-001

```text
Reason: Thái cho phép chuyển Compose về cùng baseline MySQL 8.4 và yêu cầu bắt đầu LN-001.
Scope: Pin mysql:8.4, thêm local non-root Loan DB user, cập nhật init comment/registry; không đổi service/image/port/volume khác.
Coordination: Compose/registry là vùng chung, Hải review trước merge; P0-C04 vẫn giữ smoke test/profile hạ tầng tổng thể.
Approval: APPROVED by Thai on 2026-08-01 in conversation.
```

## 19. Bằng chứng triển khai

### 19.1 File thực tế đã thay đổi

- `finora-loan/pom.xml`
- `finora-loan/src/main/resources/application.yml`
- `finora-loan/src/main/resources/db/migration/.gitkeep`
- `finora-loan/src/test/java/com/finora/loan/FinoraLoanApplicationIT.java`
- `docker/docker-compose.yml`
- `docker/mysql/init.sql`
- `.agents/rules/05-registry.md`
- Các file plan/roadmap liên quan để phản ánh trạng thái `READY_FOR_REVIEW` và PA-007.

### 19.2 Command và kết quả ngày 2026-08-01

| Command | Exit code | Kết quả |
|---|---:|---|
| Parse `finora-loan/pom.xml` bằng PowerShell XML parser | `0` | POM hợp lệ về cú pháp XML |
| `docker compose -f docker/docker-compose.yml config` | `0` | Compose hợp lệ; MySQL resolve thành `mysql:8.4`, user `finora_loan` |
| `.agents/scripts/validate-rules.ps1` | `0` | `FINORA rules validation: OK` |
| Maven 3.9.14, Java 21: `-pl finora-loan -am -DskipITs test` | `0` | Main source và `FinoraLoanApplicationIT` compile thành công; reactor build success |
| Maven dependency tree | `0` | Connector/J `8.3.0`; Flyway core/MySQL `9.22.3`; Testcontainers `1.19.7`; tất cả do Spring Boot BOM quản lý |
| Maven 3.9.14, Java 21: lần `verify` đầu | `1` | 1 integration test được phát hiện; lỗi môi trường trước khi mở context vì Docker daemon chưa chạy |
| `docker version` sau khi bật Docker Desktop | `0` | Docker Desktop `4.34.3`, Engine `27.2.0` sẵn sàng |
| Maven 3.9.14, Java 21: `-pl finora-loan -am verify` | `0` | `BUILD SUCCESS`; 1 test, 0 failure, 0 error, 0 skip trên MySQL `8.4.11` |
| Thử tạm Flyway `10.12.0`, `10.15.2`, `10.20.1`, `11.7.2` bằng CLI property | `0` mỗi lần | Tất cả chạy được nhưng vẫn cảnh báo MySQL 8.4 ngoài dải đã kiểm thử; không ghi override vào POM |
| `docker compose ... up -d mysql` | `1` | Cấu hình hợp lệ nhưng host port `3306` đang bị PID `24580` chiếm; container chưa khởi động |
| Compose one-off smoke không publish port | `0` | MySQL `8.4.11`; đăng nhập thành công bằng `finora_loan@%`; database hiện tại `finora_loan` |

Maven không có trong `PATH`; bằng chứng trên dùng Maven 3.9.14 đã có tại local wrapper cache và đặt `JAVA_HOME` theo tiến trình về JDK 21. Không thay đổi biến môi trường hệ thống của máy.

### 19.3 Trạng thái acceptance criteria

| AC | Trạng thái | Bằng chứng/việc còn lại |
|---|---|---|
| AC-01 | ĐẠT | Dependency tree có Connector/J, không có MariaDB/H2; runtime config dùng JDBC MySQL |
| AC-02 | ĐẠT | Config dùng `validate`, không còn runtime `update`; context MySQL 8.4 pass |
| AC-03 | ĐẠT | Flyway tạo `flyway_schema_history`; test xác nhận không có bảng nghiệp vụ khác |
| AC-04 | ĐẠT | Test chạy thật trên image MySQL 8.4; không H2/MariaDB và không skip |
| AC-05 | ĐẠT | Config tắt OSIV, JDBC timezone UTC và context pass |
| AC-06 | ĐẠT | Credential lấy từ environment, fallback được ghi rõ chỉ cho local |
| AC-07 | ĐẠT | File scan không có Entity, V1 nghiệp vụ, Repository hoặc outbox |
| AC-08 | ĐẠT | Lần chạy lại `verify` exit `0`; 1/1 integration test pass |
| AC-09 | ĐẠT | Rule validator exit `0` |
| AC-10 | ĐẠT | Foundation không chứa implementation Product/Application |
| AC-11 | ĐẠT | Compose/registry pin 8.4; smoke xác nhận MySQL 8.4.11 và non-root Loan user; host port conflict được ghi riêng |

### 19.4 Giới hạn và cách chạy lại

- LN-001 đã được Thái nghiệm thu `ACCEPTED`; Compose/registry dùng chung tiếp tục được chuẩn hóa và chờ Hải review trong P0-C04 trước merge.
- Docker Desktop đã được bật để chạy test. Testcontainers tự xóa container test; image `mysql:8.4` và `testcontainers/ryuk:0.6.0` còn trong local image cache.
- Host port `3306` đang bị PID `24580` chiếm nên `docker compose up -d mysql` thông thường chưa chạy được. Không tự dừng tiến trình đó và không tự đổi registry port; Thái cần xác định/dừng MariaDB/MySQL đang dùng cổng khi muốn chạy Compose có publish port.
- Healthcheck Compose chỉ báo healthy sau khi non-root Loan user truy vấn được database, tránh dừng container giữa giai đoạn entrypoint init.
- Flyway `9.22.3` chạy pass trên MySQL `8.4.11` nhưng phát cảnh báo version 8.4 chưa nằm trong dải Flyway đã kiểm thử. Các bản thử tới Flyway `11.7.2` vẫn cảnh báo; giữ BOM để tránh override không mang lại lợi ích. LN-003 phải chạy migration DDL thật trên MySQL 8.4 và đánh giá lại nếu cảnh báo trở thành lỗi/chính sách release.
- Dùng JDK 21 rồi chạy lại:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
& 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd' -pl finora-loan -am verify
```

- Tại thời điểm nghiệm thu LN-001, volume dùng chung `docker_mysql-data` đã khởi tạo MySQL 8.4.11/non-root user. Sau quyết định P0-C04 tách database instance theo service, Loan chuyển sang `docker_loan-mysql-data`; volume cũ vẫn được giữ để tránh mất dữ liệu nhưng không còn gắn vào Compose mới.

## 20. Nghiệm thu của Thái

```text
Plan decision: APPROVED on 2026-08-01
Implementation decision: ACCEPTED on 2026-08-01
Reviewer: Thai
Reviewed at: 2026-08-01
Notes: Thái xác nhận LN-001 ổn và cho phép bắt đầu P0-C04.
```
