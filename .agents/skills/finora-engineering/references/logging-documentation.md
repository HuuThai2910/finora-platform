# Logging, observability và tài liệu code

## Logging và metric

- Log JSON ở môi trường triển khai; truyền `traceId` qua HTTP và Kafka MDC.
- `INFO`: sự kiện nghiệp vụ quan trọng; `DEBUG`: chi tiết kỹ thuật; `WARN`: lỗi có thể phục hồi; `ERROR`: tác vụ thất bại cần can thiệp.
- Chỉ log exception ở tầng chịu trách nhiệm xử lý; cấm log rồi ném tiếp làm lặp cùng lỗi.
- Log nghiệp vụ có ID tham chiếu như `loanId`, `transactionId`, `eventId`, `sagaId`; không log toàn bộ DTO/entity.
- Kafka consumer ghi topic, partition, offset, group và `eventId`; Saga ghi bước và compensation.
- External call ghi dependency, operation, latency và result class; cấm payload chứa PII.
- Mask CCCD, điện thoại, email và tài khoản. Cấm password, OTP, token, private key, ảnh giấy tờ.
- Theo dõi latency/error rate, DB pool, Kafka lag/retry/DLT, dependency latency và Saga.
- Có liveness cho tiến trình và readiness cho dependency bắt buộc.

## Comment và JavaDoc/docstring

- Tên code và thuật ngữ chuẩn dùng tiếng Anh; comment viết tiếng Việt có dấu, UTF-8.
- Comment giải thích **vì sao**, ràng buộc nghiệp vụ, công thức, quyết định kiến trúc hoặc failure path khó thấy.
- Cấm comment diễn giải lại câu lệnh hiển nhiên hoặc giữ code cũ đã comment-out.
- Public API, thuật toán tài chính, state transition, Saga, scoring rule và integration boundary phải có JavaDoc/docstring.
- Công thức tiền/lãi ghi đơn vị VND, scale, `RoundingMode`, thứ tự phân bổ và ví dụ khi dễ hiểu sai.
- TODO phải có task/owner và điều kiện hoàn thành, ví dụ `TODO(LOAN-12, Thai): ...`; cấm TODO chung chung.
- Khi thay đổi hành vi, cập nhật comment và tài liệu trong cùng change.

