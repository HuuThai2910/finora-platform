# Transaction, concurrency và tính đúng đắn tài chính

- Application service xác định transaction boundary; controller/consumer chỉ validate, map và dispatch.
- Mọi thay đổi số dư phải đồng thời tạo bút toán bất biến trong cùng local transaction.
- Tiền dùng `BigDecimal`, scale 2, `RoundingMode.HALF_UP`; DB dùng `DECIMAL(18,2)`.
- Dùng `@Version` cho aggregate cập nhật cạnh tranh; pessimistic lock phải có lý do rõ ràng.
- Luôn kiểm tra affected-row/version; conflict trả lỗi có thể retry, không âm thầm ghi đè.
- API tạo giao dịch, webhook và consumer phải idempotent bằng key bền vững có unique constraint.
- Check-then-act phải nằm trong transaction và được bảo vệ bằng constraint/lock.
- State transition phải được whitelist; cấm gán trạng thái tùy ý hoặc quay ngược không có nghiệp vụ bù.
- Ghi state và outbox trong cùng transaction rồi publish bất đồng bộ; không dùng distributed transaction.
- Consumer ghi `processed_events` cùng transaction với side effect.
- Saga định nghĩa timeout, retry, bước bù và terminal state; compensation cũng idempotent.
- Không retry validation, authorization, business rejection hoặc thao tác không idempotent.
- Lưu UTC bằng `Instant`/`OffsetDateTime`; không dùng `LocalDateTime` cho event/audit xuyên hệ thống.

