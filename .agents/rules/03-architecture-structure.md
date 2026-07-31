# Kiến trúc và cấu trúc thư mục

## Ranh giới service

- `finora-user`: hồ sơ người dùng, trạng thái eKYC; Keycloak là nguồn xác thực.
- `finora-loan`: sản phẩm vay, hồ sơ, vòng đời, lịch trả nợ và Saga giải ngân.
- `finora-investment`: market, order, commitment, Note và auto-invest.
- `finora-payment`: ví, hold/release/transfer, bút toán và phân bổ tiền; không sở hữu vòng đời khoản vay.
- `finora-blockchain`: adapter Fabric, proof/audit/reconciliation; không là nguồn state nghiệp vụ.
- `finora-notification`: consumer và chiến lược kênh thông báo.
- `finora-ai`: một deployment nhưng tách bounded context credit/eKYC/fraud trong code.
- `finora-gateway`: routing, edge security/rate limit; không chứa business logic.
- `finora-common`: chỉ hạ tầng/contract thật sự ổn định; MUST NOT trở thành shared domain model làm coupling service.

## Java package-by-feature

```text
com.finora.<service>/
├── config/
├── application/<feature>/
│   ├── <Feature>Controller.java
│   ├── <Feature>Service.java
│   ├── dto/
│   └── event/
├── domain/
├── repository/
└── integration/
```

- Controller chỉ validate/map/dispatch; service/application chứa use case và transaction; repository chỉ truy cập persistence.
- Entity/document MUST NOT ra khỏi service layer; map DTO tại biên.
- Chỉ tạo interface service khi có từ hai implementation hoặc là boundary có giá trị kiểm thử/kiến trúc rõ ràng.
- Không trộn thêm cấu trúc `controller/service/entity/feign` kiểu cũ cho code mới; chuyển dần theo feature trong phạm vi task.

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

