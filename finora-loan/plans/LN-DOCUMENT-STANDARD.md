# Cấu trúc chuẩn bắt buộc cho tài liệu LN

File này mở rộng [chuẩn plan dùng chung](../../.agents/rules/09-planning-documentation.md) cho mọi task
`LN-*` của Loan Service. Khi tạo hoặc review LN, phải đọc thêm
[reference planning](../../.agents/skills/finora-engineering/references/planning-documentation.md).
Mục tiêu là để Thái, Hải hoặc người mới vào dự án hiểu nghiệp vụ trước khi gặp entity, API và database.

## 1. Thứ tự nội dung bắt buộc

Mỗi LN phải có hai lớp khi lập kế hoạch và thêm lớp C sau khi đã viết code.

### Lớp A — Người đọc nghiệp vụ

1. **Chức năng này giải quyết việc gì?** Viết bằng ngôn ngữ người dùng, tránh mở đầu bằng tên bảng hoặc công nghệ.
2. **Ai tham gia và nhìn thấy gì?** Nêu borrower, admin, service liên quan và kết quả họ nhận được.
3. **Luồng chính từng bước.** Chỉ rõ điểm bắt đầu, xử lý chính và kết quả cuối.
4. **Dữ liệu quan trọng ảnh hưởng thế nào?** Giải thích theo hành vi hệ thống, không chỉ ghi kiểu dữ liệu.
5. **Quan hệ với dữ liệu/task khác.** Nói rõ vì sao cần quan hệ đó và service nào là nguồn chuẩn.
6. **Trường hợp không thành công.** Người dùng thấy gì, hệ thống giữ trạng thái nào và có được retry hay không.
7. **Ví dụ thực tế.** Ít nhất một tình huống có số liệu hoặc trạng thái cụ thể.
8. **Điều kiện hoàn thành về nghiệp vụ.** Viết sao cho người duyệt có thể đối chiếu mà chưa cần đọc code.

### Lớp B — Người triển khai kỹ thuật

1. Phạm vi và ngoài phạm vi.
2. Entity/field/type/constraint; field phải ghi nguồn, người dùng hiểu gì, ảnh hưởng nghiệp vụ và lý do cần lưu.
3. State machine và invariant.
4. API/event/request/response và quyền.
5. Quan hệ bảng, migration và chiến lược nâng cấp.
6. Query/index; mỗi index phải chỉ ra query thật sự phục vụ.
7. Transaction, concurrency, idempotency, timeout, retry, reconciliation và compensation.
8. N+1, pagination, cache nếu thật sự cần, logging/audit/PII.
9. File dự kiến, test plan, acceptance criteria và bằng chứng bàn giao.

Mục quan hệ MUST có ERD/cardinality hiện hành theo migration (`1`, `0..1`, `1..N`, `0..N`), giải thích
vì sao cần quan hệ, FK/unique nào bảo vệ và có cascade/xóa lịch sử hay không. Entity planned phải nằm ở
sơ đồ riêng; value object/embedded snapshot không được vẽ như bảng độc lập.

### Lớp C — Code thực tế sau triển khai

Lớp này bắt buộc được cập nhật trước khi báo task sẵn sàng nghiệm thu:

1. **Bản đồ API → hàm:** ghi đúng endpoint, Controller method, Service interface/implementation,
   domain method, repository/gateway và mapper theo thứ tự thực tế.
2. **Transaction boundary:** đánh dấu method/đoạn nào là read-only transaction, write transaction
   và đoạn gọi mạng bắt buộc nằm ngoài transaction.
3. **Dữ liệu thay đổi:** ở mỗi bước ghi entity/snapshot/status nào được tạo hoặc cập nhật.
4. **Failure/idempotency path:** chỉ rõ timeout, request lặp, concurrent request và retry đi qua
   method nào, cuối cùng giữ trạng thái gì.
5. **Vai trò hàm quan trọng:** giải thích hàm chịu trách nhiệm gì và không được làm gì để tránh
   dồn nghiệp vụ sai tầng.
6. **Sai khác so với plan:** nếu implementation thay đổi tên/lớp/thứ tự so với dự kiến, cập nhật
   tài liệu theo code cuối; không giữ hai phiên bản mô tả mâu thuẫn.
7. **Comment trong implementation:** public use case và đoạn logic khó thấy phải có comment/JavaDoc
   tiếng Việt giải thích lý do, invariant, transaction, idempotency hoặc failure path. Không comment
   lại cú pháp hiển nhiên và không chuyển toàn bộ tài liệu dài vào source code.

Nếu LN có gọi hệ thống ngoài hoặc chạy worker, lớp C phải có thêm một bảng cơ chế vận hành với tối thiểu:

| Cơ chế | Vấn đề cần ngăn | Hàm thực thi | Dữ liệu/trạng thái dùng để kiểm soát | Khi hết hạn hoặc lỗi |
|---|---|---|---|---|
| Ví dụ processing lease | Hai worker gọi cùng side effect | `startExecution()` | `status`, `updatedAt`, `processingLease` | Worker mới chỉ tiếp quản sau khi lease hết và phải reconcile trước khi tạo lại |

Không được chỉ ghi tên kỹ thuật như `hash`, `lease`, `retry` hoặc `circuit breaker`. Tài liệu phải giải
thích bằng ngôn ngữ đời thường vấn đề mà cơ chế đó ngăn chặn, điều kiện kích hoạt và giới hạn hiện tại.
Nếu plan từng dự kiến một cơ chế nhưng code chưa có, phải ghi rõ “chưa triển khai”, không mô tả như
trạng thái đang chạy.

## 2. Bảng field dành cho người đọc nghiệp vụ

Không dùng bảng chỉ gồm `field/type/tác dụng` với mô tả như “khóa nội bộ” hoặc “truy vết”. Với field quan trọng, ưu tiên mẫu:

| Dữ liệu | Người dùng hiểu là gì | Ai cung cấp | Hệ thống dùng lúc nào | Nếu thiếu hoặc sai | Vì sao phải lưu |
|---|---|---|---|---|---|
| Ví dụ `annualInterestRate` | Base rate được công bố trước thẩm định | Admin | Preview ban đầu, AI input và so sánh với final rate | Không cho kích hoạt Product | Người vay hiểu mức ban đầu không phải cam kết cuối |

Kiểu Java/PostgreSQL, độ dài, scale và index đặt ở lớp kỹ thuật phía sau.

## 3. Quy tắc chống thuật ngữ khó hiểu

- Lần đầu dùng thuật ngữ phải giải thích ngay bằng một câu đời thường.
- `snapshot` phải được giải thích là “bản chụp dữ liệu tại thời điểm xảy ra giao dịch, không tự đổi theo dữ liệu mới”.
- `idempotency` phải được giải thích là “bấm/gửi lại cùng yêu cầu không tạo bản ghi hay trừ tiền lần hai”.
- `projection` phải được giải thích là “bản sao chỉ đọc tối thiểu để hiển thị hoặc điều phối; nguồn chuẩn vẫn ở service sở hữu”.
- `reconciliation` phải được giải thích là “đối chiếu lại với nguồn chuẩn khi hai bên có thể lệch trạng thái”.
- Không dùng tên entity để thay cho hành động của con người. Ví dụ phải nói “người vay nộp hồ sơ”, sau đó mới nói hồ sơ được lưu thành `LoanApplication`.
- `index` phải được giải thích là “mục lục giúp database tìm đúng bản ghi mà không đọc toàn bảng”; sau
  đó ghi query/filter/sort, thứ tự cột composite và chi phí ghi/dung lượng.
- `transaction` phải được giải thích là “nhóm thay đổi cùng thành công hoặc cùng hoàn tác”; chỉ rõ đoạn
  gọi mạng nằm ngoài transaction và dữ liệu nào đã commit trước khi gọi.
- `concurrency` phải được giải thích bằng tình huống hai request/worker cùng chạm dữ liệu; ghi cơ chế
  version/unique/lock/lease và trạng thái hoặc lỗi mà request thua nhận được.
- `worker` phải được giải thích là “tiến trình làm việc nền lấy các công việc đến hạn”; không mô tả như
  một người dùng hoặc một service mới nếu nó chỉ là component trong Loan.

## 4. Cổng duyệt tài liệu

Một LN chưa đủ điều kiện `APPROVED` nếu người đọc chưa trả lời được năm câu:

1. Ai khởi tạo chức năng này và họ muốn đạt kết quả gì?
2. Người dùng nhập/nhìn thấy dữ liệu nào, dữ liệu đó ảnh hưởng quyết định hoặc tiền ra sao?
3. Service nào có quyền thay đổi từng trạng thái hoặc số liệu quan trọng?
4. Nếu gọi service ngoài thất bại hoặc request bị gửi lặp, hệ thống sẽ làm gì?
5. Dùng test nào để chứng minh luồng nghiệp vụ đúng?

LN lịch sử hoặc task hạ tầng vẫn phải có lớp A, nhưng có thể tập trung vào tác động vận hành thay vì form người dùng.

Sau khi code xong, LN chưa đủ điều kiện `ACCEPTED` nếu thiếu lớp C hoặc bản đồ hàm không còn khớp
implementation hiện tại.
