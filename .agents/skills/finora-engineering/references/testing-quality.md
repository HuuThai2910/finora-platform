# Testing và chất lượng

## Mức test

- Unit test domain rule, công thức tiền, state transition và mapper có logic.
- Slice test controller validation/security và repository query tùy chỉnh.
- Integration test bằng Testcontainers cho transaction, lock, migration, query và consumer quan trọng.
- Contract test cho REST/event giữa các owner; không mock sai hợp đồng thật.
- End-to-end chỉ giữ cho vertical slice cốt lõi, không thay thế test tầng thấp.

## Ca bắt buộc

- Happy path, boundary value, validation failure và business rejection.
- Retry cùng idempotency key/event ID không tạo side effect lần hai.
- Concurrent update/insufficient balance không làm âm ví hoặc mất bút toán.
- Timeout/partial failure kích hoạt trạng thái hoặc compensation đúng.
- Query danh sách không N+1 và luôn phân trang.
- Đúng role nhưng sai owner vẫn bị từ chối.

## Quy tắc và cổng chất lượng

- Test độc lập, deterministic; không phụ thuộc thứ tự, mạng công cộng hoặc đồng hồ thật.
- Inject `Clock`, UUID generator và external client khi cần kiểm soát.
- Không dùng `Thread.sleep`; dùng polling có timeout hoặc primitive phù hợp.
- Dữ liệu test không chứa PII thật.
- Java chạy `mvn -pl <module> -am verify`; Python chạy `ruff check`, `ruff format --check`, `pytest`.
- Schema change phải test migration từ DB rỗng và nâng cấp từ version trước.
- Không báo hoàn thành nếu bỏ qua test lỗi; nêu nguyên nhân, ảnh hưởng và cách chạy lại.

