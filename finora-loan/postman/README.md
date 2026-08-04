# Chạy và test Loan Service bằng Postman

## Cần bật gì?

Luồng LN-003 + LN-006 + LN-004 + LN-005 + LN-007 cần bốn thành phần:

1. PostgreSQL Loan: Neon hoặc container `loan-postgres`;
2. Apache Fineract + PostgreSQL riêng của Fineract;
3. `FinoraLoanApplication` chạy bằng JDK 21, profile `local`.
4. FINORA AI v10 chạy bằng Docker ở `localhost:8000`.

Không cần Keycloak, Kafka, Redis hay MongoDB cho bộ request này.

Nếu dùng database Loan local, từ thư mục gốc:

```powershell
Copy-Item docker/.env.example docker/.env
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile loan --profile fineract up -d loan-postgres fineract-postgres fineract
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai up -d --build ai
```

Trong `docker/.env` phải điền `LOAN_POSTGRES_ADMIN_PASSWORD`, `LOAN_DB_PASSWORD`,
`FINERACT_POSTGRES_ADMIN_PASSWORD` và `FINERACT_DB_PASSWORD`. Đợi ba container `healthy`.

Trong IntelliJ, chạy `com.finora.loan.FinoraLoanApplication` với:

```text
JRE: Java 21
Active profiles: local
LOAN_DB_PASSWORD=<mật khẩu Loan DB local>
```

Nếu dùng Neon cho Loan, thay ba biến `LOAN_DB_URL`, `LOAN_DB_USERNAME`,
`LOAN_DB_PASSWORD`; Fineract local vẫn chạy ở `localhost:18443`.

## Cách test thủ công

Import:

- `FINORA-Loan-Manual.postman_collection.json`;
- `FINORA-Loan-Local.postman_environment.json`.

Chọn environment `FINORA Loan Local`, sau đó mở và Send từng request theo thứ tự 00–14.
Collection không có script tự chạy.

Sau mỗi bước, copy thủ công các giá trị sau vào environment:

- request 01: `id` -> `productId`, `version` -> `productVersion`;
- request 02: `product.version` -> `productVersion`;
- request 03: không cần sửa body cũ;
- request 06: `applicationNumber` và `version`;
- request 10: phải dùng `applicationVersion` hiện tại.
- request 12: chờ tối đa vài giây để worker chấm xong, copy `data[0].id` vào `assessmentId`;
- request 13: xem đủ input/source/model/result đã lưu;
- request 14: chỉ dùng khi assessment đang ở trạng thái lỗi cho phép retry, đồng thời cập nhật
  `applicationVersion` và dùng `scoringRetryIdempotencyKey` mới. Assessment `SUCCEEDED` bị từ chối
  retry là hành vi đúng.

`Idempotency-Key` phải mới khi tạo hồ sơ khác. Request 07 cố ý dùng lại
cùng key và cùng body để chứng minh hệ thống trả hồ sơ cũ, không tạo trùng.

Hai điều kiện quan trọng:

- Product chỉ activate sau khi `core-sync` trả `SUCCEEDED`;
- nộp hồ sơ tạo thẳng `SUBMITTED`, không còn Draft backend;
- worker tự xử lý `SUBMITTED → ELIGIBILITY_PENDING → SCORING → PENDING_REVIEW`;
- response AI được lưu trong assessment nhưng `suggested_rate` bị loại khỏi Loan database/API.

Swagger local: `http://localhost:8081/swagger-ui.html`.
