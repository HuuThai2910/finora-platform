# Lập plan dễ hiểu và kiểm chứng được

Đọc file này khi tạo, sửa hoặc review Service Design, task plan, LN/PM/INV/USR/BC task. Tuân thủ
`09-planning-documentation.md`; reference này cung cấp trình tự và khuôn dùng lại, không tạo nguồn luật mới.

## Quy trình

1. Đọc code, migration, API/event contract và plan hiện hành; ghi rõ current state trước khi thiết kế.
2. Viết lớp nghiệp vụ trước: actor, mục tiêu, luồng, dữ liệu người dùng thấy, failure và ví dụ.
3. Viết lớp kỹ thuật bám từng bước nghiệp vụ; không tạo entity/index/cơ chế chỉ vì “best practice”.
4. Vẽ ERD hiện hành từ migration/constraint, sau đó mới vẽ phần planned bằng nét/nhãn riêng.
5. Chốt query, transaction, concurrency và external failure trước khi liệt kê file dự kiến.
6. Sau khi code, đọc lại implementation và bổ sung đúng chuỗi hàm, transaction, state, test evidence.
7. Dùng checklist cuối file; nếu câu trả lời phải suy đoán, giữ plan ở `DRAFT/CHANGES_REQUESTED`.

## Khuôn task plan

```markdown
---
task_id: <SERVICE-ID>
title: <kết quả nghiệp vụ>
owner: <Thai|Hai|Both>
status: DRAFT
depends_on: []
created_at: YYYY-MM-DD
updated_at: YYYY-MM-DD
---

# <Task> — <Tên dễ hiểu>

## Bản đọc nhanh theo nghiệp vụ
### Chức năng giải quyết việc gì?
### Ai tham gia và nhìn thấy gì?
### Luồng chính và luồng không thành công
### Dữ liệu nào ảnh hưởng kết quả?
### Ví dụ thực tế
### Điều kiện hoàn thành nghiệp vụ

## 1. Current state, phạm vi và ngoài phạm vi
## 2. Ownership, SoR và dependency
## 3. Entity/value object/snapshot/projection
## 4. ERD và lý do cardinality
## 5. State machine và invariant
## 6. API/event/worker contract
## 7. Query, pagination, N+1 và index
## 8. Transaction, concurrency và idempotency
## 9. External failure, retry, reconciliation/compensation
## 10. Security, audit, logging và PII
## 11. Migration, file dự kiến và rollout
## 12. Test plan và acceptance criteria
## 13. Nghiệm thu
## 14. Bản đồ code thực tế sau triển khai
## 15. Sai khác so với dự kiến và bằng chứng
```

Không bắt buộc giữ đúng số mục khi service đã có cấu trúc hợp lý, nhưng không được bỏ nội dung tương ứng.

## Mẫu bảng dữ liệu

### Lớp nghiệp vụ

| Dữ liệu | Người dùng hiểu là gì | Ai/nguồn cung cấp | Dùng ở bước nào | Nếu thiếu/sai | Vì sao phải lưu |
|---|---|---|---|---|---|
| `annualInterestRate` | Lãi suất năm được công bố | Admin | Preview, hồ sơ, hợp đồng, core | Product không được kích hoạt | Giữ đúng điều khoản đã công bố |

### Lớp kỹ thuật

| Field | Java/PostgreSQL | Null/constraint | Ghi bởi | Đọc bởi/query | Audit/version |
|---|---|---|---|---|---|

Không dùng “khóa nội bộ” làm giải thích duy nhất. ID liên kết dữ liệu nào, API có lộ hay không và việc
đối chiếu/retry nào cần nó phải được nói rõ.

## Mẫu ERD và cardinality

```text
LoanProduct 1 ───── 0..N FineractProductMapping
    │                          │
    │ currentCoreMappingId     └──── 0..N FineractCommand
    │ chọn 0..1 mapping hiện hành
    └──── 0..N LoanApplication
```

Với mỗi cạnh, ghi thêm:

| Quan hệ | Vì sao cần | Database bảo vệ | Lifecycle/xóa |
|---|---|---|---|
| Product → Mapping `1:0..N` | Mỗi phiên bản cấu hình có mapping lịch sử | FK + unique Product/version | Không cascade xóa lịch sử |

Nếu code chỉ lưu scalar ID thay vì JPA association, vẫn vẽ quan hệ dữ liệu và ghi rõ lựa chọn này tránh
load graph/N+1. Nếu quan hệ chỉ logic qua external ID và không có FK, phải ghi rõ.

## Mẫu query và index

| Use case | Query/filter/sort thực tế | Index/constraint | Vì sao đúng thứ tự | Đánh đổi/không index |
|---|---|---|---|---|
| Worker lấy retry đến hạn | `status`, `nextRetryAt <= now`, `ORDER BY id`, `LIMIT n` | `(status,next_retry_at,id)` | Lọc trạng thái trước, range thời gian sau, cuối cùng đọc ổn định theo ID | Tốn thêm ghi; không index error detail |

Giải thích index là “mục lục giúp DB không đọc toàn bảng”, nhưng vẫn giữ tên composite/selectivity/query
plan để developer tra cứu. Không tuyên bố index tối ưu nếu chưa có query thật hoặc dữ liệu đủ để đo.

## Mẫu transaction và concurrency

```text
Transaction 1: lưu aggregate + command + mapping → cùng commit/rollback
Ngoài transaction: gọi HTTP dependency
Transaction 2: khóa/kiểm tra version → lưu success/retry/failure
```

| Tình huống đồng thời | Nguy cơ dễ hiểu | Cơ chế | Hàm/constraint | Client/state nhận gì |
|---|---|---|---|---|
| Hai admin sửa cùng Product | Người lưu sau ghi đè người trước | Optimistic locking | `version`, `requireVersion()` | `409`, UI tải bản mới |
| Hai worker lấy một command | Gọi side effect hai lần | Row lock ngắn + processing lease | `startExecution()` | Worker sau bỏ qua hoặc tiếp quản sau lease |

Transaction được giải thích là “nhóm thay đổi cùng thành công hoặc cùng hoàn tác”. Concurrency là “hai
request/worker chạm cùng dữ liệu gần như đồng thời”. Sau câu dễ hiểu phải chỉ ra cơ chế kỹ thuật thật.

## Mẫu cơ chế integration/worker

| Thuật ngữ/cơ chế | Cách hiểu đời thường | Vấn đề ngăn chặn | Trigger/hàm | State/evidence | Hết hạn hoặc lỗi |
|---|---|---|---|---|---|
| Durable command | Phiếu việc lưu trong DB, restart không mất | Quên external side effect | API/outbox tạo command | status/attempt/hash | Worker retry hoặc manual repair |
| Processing lease | Quyền xử lý có thời hạn, không phải lock DB kéo dài | Hai worker cùng gọi | `startExecution()` | `PROCESSING`, `updatedAt` | Hết lease thì reconcile rồi tiếp quản |
| Reconciliation | Hỏi lại nguồn chuẩn xem việc gì đã thật sự xảy ra | Timeout nhưng dependency có thể đã thành công | `findByExternalId()` | external ID/response | Hoàn tất state cũ, không POST mù |

MUST ghi rõ cơ chế nào **chưa triển khai**. Không dùng từ “retry” nếu chưa chốt lỗi nào retry, số lần,
backoff, điểm dừng và state cuối.

## Checklist review

- [ ] Current state khớp code/migration; planned được đánh dấu riêng.
- [ ] Actor, mục tiêu, UI/operation result và ví dụ đủ để người nghiệp vụ duyệt.
- [ ] Field quan trọng có nguồn, bước sử dụng, failure và lý do lưu.
- [ ] ERD đủ cardinality; FK/unique và quan hệ logic không bị nhập làm một.
- [ ] Entity/value object/snapshot/projection/command được phân biệt.
- [ ] Mỗi query danh sách/worker có pagination/batch và index hoặc lý do không index.
- [ ] Transaction không bao external call không cần thiết; dữ liệu cùng invariant commit cùng nhau.
- [ ] Có kịch bản concurrent cụ thể và response/state sau conflict.
- [ ] Idempotency, timeout không chắc chắn, retry/reconcile/compensation có điểm dừng.
- [ ] API/event ghi owner, version, auth, producer/consumer và dữ liệu nhạy cảm.
- [ ] Test chứng minh happy, validation, duplicate, timeout, concurrency/restart theo rủi ro.
- [ ] Sau code có bản đồ hàm, transaction, dữ liệu thay đổi, sai khác và evidence.
