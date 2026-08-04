# P0-C05 — Apache Fineract local fixture

## 1. Mục tiêu nghiệp vụ

Cung cấp một Core Banking thật để Loan Service có thể:

1. đồng bộ Loan Product sang Fineract trước khi mở bán;
2. nhận lịch trả dự kiến do Fineract tính, thay vì tự viết công thức song song;
3. kiểm tra contract adapter trước khi tích hợp các luồng booking/repayment sau này.

## 2. Phạm vi đã triển khai

- Pin Apache Fineract `1.15.0`, tự build image local từ binary release chính thức
  và xác minh SHA-512; không dùng `latest`. Docker Hub không có manifest cho
  commit/tag release nên không được giữ cấu hình pull không tồn tại.
- Dùng PostgreSQL `18.3` cho riêng Fineract theo fixture chính thức của release.
- Tạo riêng `fineract_tenants` và `fineract_default`; không dùng Loan DB.
- Health endpoint và port host `18443` chỉ bind loopback.
- Secret nằm trong `docker/.env`, không commit.
- Smoke tạo idempotent Client kỹ thuật `FINORA-PREVIEW-CLIENT` để Fineract tính
  schedule dự kiến; không dùng Client này để booking khoản vay thật.

## 3. File thực thi

- `docker/docker-compose.yml`
- `docker/fineract/Dockerfile`
- `docker/postgresql/fineract/init-databases.sh`
- `docker/.env.example`
- `docker/README.md`
- `docker/smoke-fineract.ps1`

## 4. Cách kiểm tra

```powershell
powershell -ExecutionPolicy Bypass -File docker/smoke-fineract.ps1 -KeepRunning
```

Kết quả bắt buộc:

- `finora-fineract-postgres` healthy;
- `finora-fineract` healthy;
- actuator trả `UP`;
- API Basic Auth `mifos/password` + tenant `default` truy cập được;
- LN-006 contract test tạo Product và tính được schedule thật.

## 5. Ownership và gate

- Thái triển khai adapter và fixture.
- Hải review `docker/*` vì đây là vùng dùng chung.
- Không đánh `DONE` trước khi smoke thật qua Docker và review vùng chung.

## 6. Trạng thái thực thi

`IN_PROGRESS` — Compose validate pass; Fineract/PostgreSQL healthy và tenant authentication
smoke pass ngày 2026-08-03. Còn contract test tạo Product/tính schedule thật và
Hải review vùng Docker dùng chung trước khi chuyển `DONE`.
