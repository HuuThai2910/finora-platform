# Tích hợp, Kafka và bảo mật

## REST và external dependency

- API dùng DTO riêng; entity không vượt service boundary.
- Validate cú pháp ở DTO và invariant nghiệp vụ ở domain/application service.
- Response lỗi theo `{code, message, details, traceId}`; không trả stack trace hoặc SQL message.
- External client có timeout, circuit breaker và retry có jitter chỉ cho lỗi tạm thời/idempotent.
- Không gọi service khác để join dữ liệu theo từng phần tử; dùng batch endpoint, read model hoặc event.
- Thay đổi contract phải có version/migration plan và owner liên quan chấp thuận.

## Kafka

- Envelope bắt buộc: `eventId`, `occurredAt`, `version`, `data`.
- Producer dùng outbox; consumer idempotent và có retry topic/DLT cùng quy trình replay.
- Event là sự kiện quá khứ; payload chỉ chứa dữ liệu tối thiểu và ID tham chiếu.
- Cấm `spring.json.trusted.packages: "*"` ngoài local development.
- Partition key giữ thứ tự theo aggregate khi cần, ví dụ `loanId` hoặc `walletId`.
- Schema evolution ưu tiên thêm field optional; cấm đổi nghĩa field cùng version.

## Security

- Gateway và từng resource service đều xác minh JWT; không tin identity header từ client.
- Authorization kiểm tra role và quyền sở hữu tài nguyên tại service sở hữu dữ liệu.
- Secret chỉ qua environment/secret store; không có mật khẩu thật hoặc fallback credential trong repo.
- Cấu hình CORS allowlist, request-size limit và rate limit cho auth/scoring/upload/webhook.
- Upload eKYC kiểm tra MIME thực, kích thước và tên file; lưu ngoài public path.
- Audit admin gồm actor, action, target, before/after đã lọc PII và timestamp UTC.
- Dùng deserialization allowlist; validate webhook signature và chống replay.

