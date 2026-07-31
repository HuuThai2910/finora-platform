# Quy trình và cổng chất lượng

## Trước khi code

1. Xác định module, owner, files dự kiến và contract bị ảnh hưởng.
2. Đọc rule được `00-rule-map.md` định tuyến và skill `finora-engineering` nếu task có code/review.
3. Tìm implementation tương tự; kiểm tra dirty worktree và bảo toàn thay đổi người dùng.
4. Xác định failure path, transaction, query plan, idempotency và security trước khi triển khai.

## Trong khi code

- Viết test cùng thay đổi, ưu tiên invariant và rủi ro tài chính.
- Không dùng `ddl-auto`, `System.out.println`, catch rỗng, secret hard-code hoặc trusted deserialization wildcard ngoài local.
- Không đưa workaround ngoài phạm vi để che lỗi; ghi rõ blocker hoặc nợ kỹ thuật.
- Cập nhật OpenAPI/event/migration/documentation khi contract thay đổi.

## Trước khi báo hoàn thành

- Java: `mvn -pl finora-<service> -am verify`.
- Python: `ruff check`, `ruff format --check`, `pytest`.
- Rule/skill: chạy `.agents/scripts/validate-rules.ps1`.
- Schema: test migrate database rỗng và nâng cấp từ version trước.
- Query quan trọng: kiểm chứng không N+1, pagination/index và query count/plan khi phù hợp.
- Financial/event flow: kiểm chứng idempotency, retry, concurrency và compensation.
- MUST báo test đã chạy, kết quả và phần chưa chạy. MUST NOT tuyên bố pass nếu command lỗi hoặc bị bỏ qua.

## Điều cấm tuyệt đối

- Commit secret hoặc dữ liệu PII thật.
- Float/double cho tiền; update số dư không có ledger transaction.
- Sửa migration đã merge hoặc schema ngoài Flyway.
- Nuốt exception, log rồi ném lặp ở nhiều tầng, hoặc log PII/secret.
- Truy cập DB service khác, sửa module owner khác hoặc đổi contract không phối hợp.
- Tự commit/push/merge khi chưa được yêu cầu rõ trong lượt hiện tại.

