# Convention và hợp đồng

## Java, Python và TypeScript

- Java package `com.finora.<service>...`; class `PascalCase`; method/field `camelCase`; constant `UPPER_SNAKE_CASE`; boolean bắt đầu `is/has/can`.
- Hậu tố: `Controller`, `Service`, `Repository`, `Request`, `Response`, `Event`, `Config`, `Exception`, `Mapper`.
- Entity số ít, không hậu tố `Entity`; MUST NOT dùng Lombok `@Data` trên JPA entity.
- Java service dùng layered package theo `03-architecture-structure.md`; tên class service/controller phải phản ánh resource hoặc use case, tránh class tổng quát phình lớn.
- MUST NOT tạo hậu tố `ServiceImpl` nếu chỉ có một implementation và không có boundary cần interface.
- Unit test `*Test`; integration/Testcontainers `*IT`.
- Python theo PEP 8, type hint cho public function, format bằng Ruff; FastAPI/Pydantic, không Django.
- TypeScript tương lai: component `PascalCase.tsx`, hook `useX.ts`, file khác kebab-case; API qua `src/api/`, server state dùng TanStack Query, không fetch trong component hoặc Redux nếu chưa có quyết định mới.

## Database

- MySQL table/column `snake_case`, table số nhiều; PK `id BIGINT AUTO_INCREMENT`; FK `<resource>_id`.
- Mọi bảng có `created_at`, `updated_at`; money `DECIMAL(18,2)`.
- Index `idx_<table>_<columns>`, unique `uq_<table>_<columns>`.
- Mongo collection/field `snake_case`; document có `schema_version`.

## Tiền và thời gian

- Java money: `BigDecimal`, scale 2, `RoundingMode.HALF_UP`, đơn vị VND. MUST NOT dùng float/double ở bất kỳ tầng nào.
- API/event SHOULD truyền money dạng chuỗi decimal hoặc integer minor unit theo contract thống nhất.
- DB lưu UTC; API/event ISO-8601 có offset; hiển thị `Asia/Ho_Chi_Minh`.
- Java dùng `Instant`/`OffsetDateTime`; MUST NOT dùng `java.util.Date` hoặc `LocalDateTime` cho event/audit xuyên hệ thống.

## REST

- Path `/api/v1/<resources>` kebab-case, resource số nhiều.
- Error `{code, message, details, traceId}`, code `UPPER_SNAKE_CASE`.
- Pagination query `page`, `size`, `sort`; response `{data, page, size, totalElements}`. Kích thước phải có giới hạn.
- DTO tại biên, validation rõ ràng; không trả entity hoặc stack trace.

## Kafka

- Topic `finora.<service>.<past-event>`; consumer group `<service>-<purpose>`.
- Envelope `{eventId, occurredAt, version, data}`; `eventId` UUID, thời gian ISO-8601.
- Event name ở quá khứ, payload tối thiểu, tránh PII; partition key theo aggregate khi cần giữ thứ tự.
- Schema evolution ưu tiên field optional; MUST NOT đổi nghĩa field trong cùng version.

## Comment và logging

- Tên code/thuật ngữ bằng tiếng Anh; comment/JavaDoc/docstring bằng tiếng Việt có dấu, UTF-8.
- Comment giải thích lý do, invariant, công thức hoặc failure path; không diễn giải cú pháp.
- Logging chi tiết tuân theo skill `finora-engineering`; MUST mask PII và MUST NOT log secret/token/OTP/private key/ảnh giấy tờ.
