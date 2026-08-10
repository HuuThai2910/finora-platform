# Chuẩn lập kế hoạch và tài liệu triển khai

Rule này áp dụng khi tạo, sửa hoặc review plan/task chi tiết của bất kỳ FINORA service nào. Mục tiêu là
để owner nghiệp vụ, developer và AI cùng hiểu một thiết kế mà không phải suy đoán từ thuật ngữ hoặc code.

## Nguồn chuẩn và vòng đời

- Roadmap trả lời **làm task nào, ai làm, phụ thuộc ai và trạng thái nào**.
- Service Design trả lời **service làm gì, ranh giới và quan hệ tổng thể ra sao**.
- Task plan trả lời **một chức năng cụ thể được thiết kế, triển khai và kiểm chứng thế nào**.
- Plan MUST dùng trạng thái rõ: `DRAFT → APPROVED → IN_PROGRESS → READY_FOR_REVIEW → ACCEPTED`;
  `BLOCKED`, `CHANGES_REQUESTED`, `SUPERSEDED` chỉ dùng đúng nghĩa.
- Không mô tả dự kiến như code đang chạy. Sau triển khai MUST thêm bản đồ API/event/worker đến đúng hàm,
  transaction và dữ liệu thực tế trước khi chuyển `READY_FOR_REVIEW`.

## Ba lớp nội dung bắt buộc

1. **Lớp nghiệp vụ:** ai khởi tạo, họ muốn gì, từng bước nhìn thấy gì, dữ liệu ảnh hưởng quyết định/tiền
   thế nào, failure họ nhận gì và ví dụ cụ thể.
2. **Lớp kỹ thuật:** scope, ownership/SoR, entity/field, state, API/event, ERD, migration, query/index,
   transaction/concurrency, integration/retry, bảo mật, test và acceptance criteria.
3. **Lớp code thực tế:** endpoint/consumer/scheduler → controller/consumer → service → domain →
   repository/gateway/mapper; chỉ rõ transaction boundary, external call và khác biệt so với dự kiến.

Task hạ tầng không có UI vẫn MUST có lớp nghiệp vụ theo tác động vận hành: ai chạy, dependency nào được
mở, lỗi làm chặn workflow nào và cách phục hồi.

## Thuật ngữ phải đi cùng cách hiểu

- Lần đầu xuất hiện thuật ngữ như snapshot, idempotency, projection, reconciliation, outbox, Saga,
  worker, processing lease, optimistic lock, N+1, composite index hoặc circuit breaker, plan MUST giải
  thích ngay bằng câu đời thường và một tình huống FINORA.
- Không chỉ ghi “để truy vết”, “phục vụ worker”, “tối ưu query” hoặc “chống concurrency”. MUST nói rõ
  hành động nào dùng dữ liệu/cơ chế đó và điều gì sai nếu thiếu.
- Giữ tên kỹ thuật chuẩn để developer tra cứu; phần dễ hiểu bổ sung ngữ cảnh, không thay thế thuật ngữ.

## Dữ liệu và quan hệ

- Field quan trọng MUST ghi: người dùng hiểu gì, ai/nguồn nào cung cấp, được dùng ở bước nào, nếu
  thiếu/sai thì hệ thống làm gì, vì sao cần lưu; kiểu/constraint/index đặt ở phần kỹ thuật.
- Mỗi plan thêm hoặc đổi entity MUST có ERD/cardinality `1`, `0..1`, `1..N`, `0..N` và giải thích vì sao
  là quan hệ đó. Phân biệt quan hệ nghiệp vụ với FK/unique thực sự được database bảo vệ.
- MUST phân biệt entity có lifecycle riêng, value object/embedded columns, immutable snapshot, read
  projection, integration command và entity mới chỉ planned.
- Service Design MUST có hai sơ đồ tách biệt khi cần: **hiện hành theo migration/code** và **mục tiêu
  tương lai**. Không vẽ entity chưa tồn tại như thể đã triển khai.

## Query, index, transaction và concurrency

- Mỗi index MUST gắn với query/use case thật: filter, sort/join, thứ tự cột, lý do và đánh đổi ghi/dung
  lượng. Nếu không thêm index cũng ghi lý do dựa trên quy mô/selectivity/query plan.
- Transaction MUST được minh họa theo nhóm thay đổi “cùng thành công hoặc cùng rollback”; external call
  nằm trong hay ngoài transaction và hậu quả giữ lock/connection phải rõ.
- Concurrency MUST có ít nhất một kịch bản hai request/worker đồng thời, cơ chế bảo vệ cụ thể
  (`@Version`, unique constraint, row lock, lease...) và response/state khi conflict.
- Với integration/worker MUST mô tả trigger, command/event, trạng thái bền vững, retryable/non-retryable,
  timeout không chắc chắn, reconciliation, giới hạn attempt, restart và manual repair nếu có.

## Cổng duyệt

Plan chưa đủ `APPROVED` nếu người đọc không thể trả lời: ai làm gì, dữ liệu nào đổi, quan hệ nào tồn tại,
query/index nào cần, transaction/concurrency được bảo vệ ra sao và failure giữ trạng thái gì.

Plan chưa đủ `ACCEPTED` nếu thiếu bằng chứng test, bản đồ hàm thực tế, sai khác implementation, comment
cho logic khó và cập nhật Service Design/roadmap/contract liên quan.

Khuôn viết và ví dụ bắt buộc nằm tại
`../skills/finora-engineering/references/planning-documentation.md`. Mỗi service MAY có standard mở rộng,
nhưng MUST kế thừa rule này và không được nới lỏng các cổng trên.
