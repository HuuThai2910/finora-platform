# Kiến trúc và cấu trúc thư mục

## Ranh giới service

Chi tiết quyền sở hữu, System of Record và state authority được định nghĩa tại `07-service-boundaries.md`; luồng phối hợp được định nghĩa tại `08-cross-service-flows.md`. File này chỉ giữ tóm tắt cấu trúc, không được dùng để ghi đè hai nguồn đó.

- `finora-user`: hồ sơ người dùng, trạng thái eKYC; Keycloak là nguồn xác thực.
- `finora-loan`: FINORA Product/Application/Contract/lifecycle, bản chiếu lịch trả nợ và Saga giải ngân; Apache Fineract là nguồn chuẩn core account/schedule/balance khi tích hợp.
- `finora-investment`: market, order, commitment, Note và auto-invest.
- `finora-payment`: ví, hold/release/transfer, bút toán và phân bổ tiền; không sở hữu vòng đời khoản vay.
- `finora-blockchain`: adapter Fabric, proof/audit/reconciliation; không là nguồn state nghiệp vụ.
- `finora-notification`: consumer và chiến lược kênh thông báo.
- `finora-ai`: một deployment nhưng tách bounded context credit/eKYC/fraud trong code.
- `finora-gateway`: routing, edge security/rate limit; không chứa business logic.
- `finora-common`: chỉ hạ tầng/contract thật sự ổn định; MUST NOT trở thành shared domain model làm coupling service.

## Java layered architecture

```text
com.finora.<service>/
├── controller/
├── service/
├── domain/
├── repository/
├── dto/
│   ├── request/
│   └── response/
├── mapper/
├── integration/
├── messaging/
│   ├── producer/
│   ├── consumer/
│   └── event/
├── config/
└── exception/
```

Đây là cấu trúc chuẩn cho code Java mới. Không bắt buộc tạo đủ thư mục ngay từ đầu; chỉ tạo khi có file thật thuộc trách nhiệm đó.

### Trách nhiệm từng layer

- `controller/`: nhận HTTP request, validate DTO, lấy identity từ security context, gọi service và trả response. MUST NOT gọi repository/integration trực tiếp hoặc chứa business logic.
- `service/`: triển khai use case, transaction boundary, authorization nghiệp vụ, state transition và phối hợp repository/integration/outbox.
- `domain/`: JPA entity/Mongo document, value object, domain enum và invariant. Domain MUST NOT phụ thuộc controller, DTO HTTP hoặc integration client.
- `repository/`: truy cập database của chính service; MUST NOT chứa business decision, gọi REST/Kafka hoặc bị controller gọi trực tiếp.
- `dto/request/`, `dto/response/`: contract tại biên. Entity/document MUST NOT được trả trực tiếp từ controller.
- `mapper/`: chuyển đổi entity/domain/DTO; mapper không chứa query hoặc business decision.
- `integration/`: client/adapter cho AI, service khác, Fabric, Keycloak, payment provider hoặc external system; chia thư mục con theo hệ thống đích khi có nhiều integration.
- `messaging/`: Kafka producer/consumer/event mapping. Ghi state + outbox vẫn thuộc transaction do service điều phối.
- `config/`: security, Kafka, Jackson, OpenAPI và bean configuration.
- `exception/`: exception riêng của module; response lỗi vẫn theo contract chung.

### Chiều dependency

Chiều gọi chuẩn:

```text
controller → service → repository
                     → integration
                     → messaging/outbox
service    → mapper/domain
```

MUST NOT tạo các chiều:

```text
controller → repository
controller → integration
repository → service
domain     → controller/integration
mapper     → repository
```

### Chia class theo use case

- Layered architecture không có nghĩa mỗi service chỉ có một class `LoanService`, `PaymentService` hoặc `UserService` khổng lồ.
- MUST chia service theo nhóm use case có trách nhiệm rõ, ví dụ `LoanApplicationService`, `LoanApprovalService`, `DisbursementSagaService`, `RepaymentService`.
- SHOULD chia controller theo resource/use case khi endpoint không còn cùng một responsibility.
- Chỉ tạo interface service khi có ít nhất hai implementation, là integration boundary cần thay thế, hoặc có lý do kiểm thử/kiến trúc được ghi rõ.
- MUST NOT tạo cặp `XService`/`XServiceImpl` theo thói quen khi chỉ có một implementation.

### Entity, DTO và persistence

- Entity có thể nằm trực tiếp trong `domain/` và dùng annotation JPA để giảm boilerplate; không cần tách `JpaEntity` nếu task không yêu cầu clean architecture đầy đủ.
- JPA association mặc định `LAZY`; MUST NOT dùng `EAGER` để che N+1.
- MUST NOT dùng Lombok `@Data` hoặc Jackson serialization annotation để biến entity thành API model.
- Mapper thực hiện chuyển đổi DTO; mapping cần lazy data phải diễn ra trong service transaction với fetch plan rõ ràng.
- Migration thuộc service sở hữu database và đặt tại `src/main/resources/db/migration/`.

### Tạo và mở rộng thư mục

- MUST NOT tạo hàng loạt package rỗng hoặc `.gitkeep` chỉ để giống cây mục tiêu.
- Tạo thư mục khi task đầu tiên phát sinh file thật; test tương ứng phản chiếu package production.
- Khi một layer có quá nhiều file, MAY thêm thư mục con theo nhóm nghiệp vụ bên trong layer, ví dụ `service/repayment/`, nhưng không được đổi toàn bộ kiến trúc nếu chưa có nhu cầu và review chung.
- Skeleton cũ được di chuyển dần trong task nghiệp vụ đầu tiên liên quan; MUST NOT mở refactor toàn repository chỉ để đổi package.

## Cấu trúc test Java

`src/test/java/com/finora/<service>/` SHOULD phản chiếu package production. Unit test dùng `*Test`; integration/Testcontainers dùng `*IT`. Không gom mọi test không phân loại vào một package chung.

## Python

```text
finora-ai/
├── main.py
├── app/api/
├── app/schemas/
├── app/ml/
├── app/services/
├── scripts/
├── models/
├── data/        # không commit dữ liệu huấn luyện
└── tests/
```

Mỗi bounded context có router riêng. Logic ML không đặt trong router; predictor và preprocessing phải dùng cùng model package/feature contract.

## Database và integration patterns

- Schema mới/chỉnh schema dùng migration `V<n>__<mo_ta>.sql`; MUST NOT sửa migration đã merge.
- Mọi event publish dùng transactional outbox; consumer ghi `processed_events` cùng transaction với side effect.
- Saga giải ngân là orchestration trong Loan; mỗi bước có timeout, idempotency và compensation.
- Gọi AI/Fabric/service ngoài dùng timeout, circuit breaker và retry có điều kiện.
- Mọi thay đổi số dư trong Payment phải cùng transaction với bút toán bất biến.
