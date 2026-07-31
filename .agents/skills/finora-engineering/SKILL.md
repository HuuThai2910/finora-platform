---
name: finora-engineering
description: Áp dụng chuẩn kỹ thuật FINORA khi thiết kế, triển khai hoặc review Java Spring Boot, Python FastAPI, REST, Kafka, database và tích hợp tài chính. Dùng cho mọi thay đổi code trong finora-platform, đặc biệt khi liên quan hiệu năng/N+1, logging, comment tiếng Việt, transaction, concurrency, idempotency, security, testing hoặc observability.
---

# FINORA Engineering

## Quy trình bắt buộc

1. Đọc `AGENTS.md`, `.agents/rules/00-rule-map.md` và các rule được map yêu cầu; xác định module, owner và phạm vi thay đổi.
   - Nếu lập kế hoạch, chọn task/giai đoạn hoặc phối hợp hai owner, đọc `.agents/plans/finora-team-roadmap.md`.
   - Nếu thêm entity/state/API/event hoặc quyết định ownership, đọc `07-service-boundaries.md`.
   - Nếu luồng đi qua từ hai service trở lên, đọc cả `08-cross-service-flows.md`.
2. Đọc đúng tài liệu tham chiếu theo loại thay đổi:
   - Truy vấn JPA/Mongo, endpoint danh sách, batch hoặc cache: `references/performance-data-access.md`.
   - Log, metric, trace, JavaDoc/docstring/TODO: `references/logging-documentation.md`.
   - Tiền, số dư, trạng thái, callback, webhook: `references/transaction-concurrency.md`.
   - REST, Kafka, gọi service ngoài, auth hoặc dữ liệu nhạy cảm: `references/integration-security.md`.
   - Viết mới hoặc sửa nghiệp vụ: `references/testing-quality.md`.
3. Tìm implementation tương tự trước khi tạo abstraction hoặc utility mới.
4. Thiết kế transaction boundary, query plan, idempotency và failure path trước khi code.
5. Viết test chứng minh happy path, validation và failure path có rủi ro cao.
6. Chạy kiểm tra đúng module; báo rõ kiểm tra nào chưa thể chạy.
7. Khi thay đổi rule/skill, chạy `.agents/scripts/validate-rules.ps1`.

## Điều kiện hoàn thành

- Không có N+1, unbounded query hoặc REST call trong vòng lặp.
- Không làm mất tiền, ghi sổ kép hoặc xử lý event/webhook hai lần khi retry.
- Log đủ để lần theo request/event/Saga nhưng không lộ PII hoặc secret.
- Comment tiếng Việt giải thích ràng buộc và lý do, không diễn giải lại cú pháp.
- API/event tương thích hợp đồng; lỗi ngoài hệ thống có timeout và chiến lược phục hồi.
- Test tương xứng với rủi ro và không phụ thuộc thứ tự/thời gian thực không kiểm soát.
