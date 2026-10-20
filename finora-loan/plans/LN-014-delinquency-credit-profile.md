---
document_type: TASK_PLAN
task_id: LN-014
owner: Thai
reviewers:
  - Hai
status: BACKLOG
depends_on:
  - LN-012
  - LN-013
  - DPD/default policy
updated_at: 2026-09-05
---

# LN-014 — Quá hạn, default và cập nhật lịch sử tín dụng nội bộ

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

LN-014 biến lịch sử trả nợ đã xác nhận trong FINORA thành hai chỉ số nội bộ: số lần quá hạn trong hai năm và số khoản vay rơi vào trạng thái vi phạm nghiêm trọng/default. Borrower không tự khai hai số này. Đây không phải CIC hoặc hồ sơ công khai pháp lý. Contract AI v17 hiện không nhận hai chỉ số này; Loan lưu chúng để admin thẩm định và làm nền cho phiên bản contract tương lai nếu hai bên thống nhất bổ sung lại.

### Ai tham gia và nhìn thấy gì?

- Fineract là nguồn DPD, trạng thái và sự kiện servicing đã xác nhận.
- Loan lưu sự kiện tín dụng nội bộ, tính profile hiện hành và cung cấp snapshot cho LN-007.
- Admin thấy tên dễ hiểu, nguồn, policy version và ngày tính; borrower không được tự sửa counter.
- AI v17 không nhận `delinq_2yrs/pub_rec`. Chỉ ánh xạ lại khi contract AI mới ghi rõ ý nghĩa, nguồn dữ liệu và version tương ứng.

### Luồng chính

```text
Fineract xác nhận quá hạn/default/cure/reversal
→ Loan lưu credit event đúng một lần
→ tính/rebuild BorrowerCreditProfile
→ lần nộp hồ sơ sau LN-007 chụp hai giá trị + source/version để admin tra cứu
→ chỉ gửi AI nếu một contract sau v17 chính thức bổ sung lại hai trường
```

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Người đọc hiểu là gì | Ảnh hưởng |
|---|---|---|
| `daysPastDue/eventType/occurredAt` | Khoản vay trễ bao nhiêu ngày, loại sự kiện gì, lúc nào | Policy quyết định có tính vào cửa sổ hai năm không |
| `sourceEventId/loanId` | Sự kiện nguồn và khoản vay liên quan | Retry không tăng counter; default một loan không bị đếm nhiều lần |
| `delinquenciesLast2Years` | Số episode/kỳ quá hạn theo định nghĩa đã duyệt | Bằng chứng nội bộ cho admin; chưa gửi AI v17 |
| `adverseRecordCount` | Số khoản vay FINORA default nghiêm trọng | Bằng chứng nội bộ; không gọi là public record thật và chưa gửi AI v17 |
| `policyVersion/window/calculatedAt` | Cách tính, khoảng thời gian và độ mới | Có thể giải thích/rebuild khi policy đổi |

### Trường hợp lần đầu và lỗi

Borrower thực sự chưa có lịch sử sau một truy vấn thành công nhận `0/0` với nguồn `NO_HISTORY/FINORA_INTERNAL`. Nếu Fineract hoặc mapping lỗi, kết quả là unknown/stale chứ không tự biến thành 0. Event đảo/correction phải tham chiếu sự kiện gốc và kích hoạt tính lại.

### Ví dụ và điều kiện hoàn thành

Một borrower có một episode đạt ngưỡng policy trong 24 tháng và một loan bị default: profile là `1/1`. Cùng event được gửi lại vẫn là `1/1`; policy đổi có thể rebuild từ sự kiện bất biến. LN chỉ được triển khai sau khi Thái chốt ngưỡng DPD, cách đếm episode và default; hoàn tất khi các test biên hai năm, duplicate, out-of-order, cure/reversal và rebuild pass.

## 1. Mục tiêu nghiệp vụ

Từ dữ liệu servicing đã xác nhận của Fineract, Loan duy trì `BorrowerCreditProfile` để admin có lịch sử nội bộ có thể kiểm tra và để contract AI tương lai có nguồn dữ liệu đáng tin cậy nếu cần. Borrower không tự khai hai giá trị này.

Đây là proxy lịch sử trong FINORA, không phải CIC và không phải hồ sơ công khai pháp lý. UI/API phải đặt tên dễ hiểu và không tuyên bố sai nguồn dữ liệu.

### 1.1. Đối chiếu pháp lý và cách chuyển thành yêu cầu hệ thống

- Áp dụng `LEGAL-DATA-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md): event/profile là dữ liệu dùng để đánh giá tín dụng nên phải có mục đích xử lý rõ, phân quyền, thời hạn lưu, khả năng truy vết và quy trình sửa dữ liệu sai. Nguồn đối chiếu là [Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15](https://vanban.chinhphu.vn/?docid=214590&pageid=27160&typegroupid=3) và [Nghị định 356/2025/NĐ-CP](https://vanban.chinhphu.vn/default.aspx?docid=216387&pageid=27160).
- Áp dụng `LEGAL-LIMIT-01`: [Điều 11 Nghị định 94/2025/NĐ-CP](https://vanban.chinhphu.vn/?classid=1&docid=213519&pageid=27160) yêu cầu mô hình P2P sandbox có biện pháp quản lý dư nợ và khai thác/báo cáo CIC. Dữ liệu FINORA tự tổng hợp trong LN-014 chỉ là một nguồn nội bộ, không thay thế adapter CIC hoặc kiểm soát tổng dư nợ trước production.
- Không hiển thị tên kỹ thuật `pub_rec` cho borrower/admin và không suy diễn một kỳ trả chậm thành “hồ sơ công khai”. UI dùng các tên “số lần quá hạn nội bộ” và “số khoản vay default nội bộ”, kèm nguồn và thời điểm tính.
- Raw payload từ Fineract không được lưu chỉ để tiện debug. Event chỉ giữ field nghiệp vụ cần thiết và hash truy vết; log không chứa PII. Retention/xóa/đính chính phải được legal và data owner duyệt trước khi triển khai LN-014.

## 2. Định nghĩa cần Thái duyệt trước code

- **Delinquency:** một nghĩa vụ/khoản vay đạt ngưỡng days-past-due (DPD) theo policy; phải chốt ngưỡng, cách đếm nhiều kỳ trong cùng khoản vay và cửa sổ hai năm.
- **Adverse/default record (`pub_rec` proxy):** số khoản vay FINORA đạt trạng thái tổn thất/vi phạm nghiêm trọng đã chốt, ví dụ charged-off/default; không mặc định mọi kỳ trễ hạn đều tăng chỉ số này.
- **Cure/reversal:** khi khoản vay trả đủ sau quá hạn, lịch sử sự kiện vẫn được audit nhưng current counters được tính theo policy/version rõ.

Không code bằng cách “cứ không trả được một hồ sơ thì +1” trước khi ngưỡng và event nguồn được khóa.

## 3. Nguồn và luồng cập nhật

1. Fineract event/reconcile cập nhật DPD/status/schedule vào servicing pipeline.
2. Loan chuẩn hóa thành internal credit event có source ID, borrower, loan, occurrence time, DPD/default type và policy version.
3. Insert event idempotently.
4. Trong cùng transaction, tính/update `BorrowerCreditProfile` hoặc đánh dấu cần rebuild.
5. LN-007 đọc profile hiện hành và snapshot hai chỉ số làm bằng chứng nội bộ; mapper AI v17 không đưa chúng vào request.
6. Nếu borrower chưa có profile/event sau khi truy vấn nguồn thành công, profile dùng `source=NO_HISTORY`, hai count bằng 0 và có policy version rõ; không dùng 0 để che lỗi dependency.

Không đọc Payment DB trực tiếp và không tăng counter chỉ từ một HTTP notification chưa được Fineract/ledger xác nhận.

## 4. Entity và field

`BorrowerCreditProfile` đã được tạo từ LN-007/V3 để scoring có một projection ban đầu `NO_HISTORY`. LN-014 **không tạo profile thứ hai**; task này bổ sung nguồn event bất biến và cập nhật đúng các field hiện hành. `BorrowerCreditEvent` bên dưới là entity dự kiến, chưa có trong V1–V5.

### `BorrowerCreditEvent`

| Field | Kiểu đề xuất | Tác dụng |
|---|---|---|
| `id` | `BIGINT` identity | ID local đồng nhất schema Loan |
| `sourceEventId` | `VARCHAR(100)`, unique | Chống trùng |
| `borrowerId` | logical ID | Chủ thể |
| `loanId/fineractLoanId` | logical/`BIGINT` | Khoản vay nguồn |
| `eventType` | `VARCHAR(40)` | DELINQUENCY/DEFAULT/CURED/REVERSED... |
| `daysPastDue` | `INT` | Bằng chứng DPD |
| `occurredAt` | `TIMESTAMPTZ` | Tính cửa sổ hai năm theo UTC |
| `policyVersion` | `VARCHAR(30)` | Giải thích cách phân loại |
| `sourcePayloadHash` | `CHAR(64)` | Audit mà không lưu raw PII |
| `createdAt` | `TIMESTAMPTZ` | Audit |

### `BorrowerCreditProfile`

| Field | Kiểu đề xuất | Tác dụng |
|---|---|---|
| `borrowerId` | logical ID, unique | Một current profile mỗi borrower |
| `internalDelinquenciesLast2Years` | `INT` | Chỉ số quá hạn nội bộ; chưa ánh xạ vào AI v17 |
| `internalDefaultedLoanCount` | `INT` | Chỉ số default nội bộ; không phải `pub_rec` pháp lý và chưa ánh xạ vào AI v17 |
| `source` | `VARCHAR(40)` | `NO_HISTORY` hoặc `FINERACT_INTERNAL` |
| `policyVersion` | `VARCHAR(30)` | Rule đã dùng |
| `windowStart/windowEnd` | `TIMESTAMPTZ` | Cửa sổ thống kê; bổ sung nếu query/audit cần lưu |
| `calculatedAt` | `TIMESTAMPTZ` | Độ mới |
| `lastSourceEventId` | `VARCHAR(100)` | Truy vết |
| `version` | `BIGINT` | Concurrency |

Counter không nullable và có check `>= 0`.

### 4.1 Quan hệ dữ liệu dự kiến

```text
FinoraLoan/FineractLoanMapping (1) ── (N) BorrowerCreditEvent
Borrower logical ID             (1) ── (N) BorrowerCreditEvent
Borrower logical ID             (1) ── (0..1) BorrowerCreditProfile
                                                   │
                                                   └── snapshot vào CreditScoringAssessment [LN-007]
```

- Một loan có nhiều event quá hạn/cure/reversal nhưng default count phải deduplicate theo loan/policy.
- Một borrower có nhiều event xuyên nhiều loan và chỉ một profile hiện hành để scoring đọc nhanh.
- Profile là projection có thể rebuild; event là bằng chứng append-only, không sửa/xóa để “chỉnh counter”. Correction/reversal tham chiếu source event gốc.
- Assessment giữ snapshot count/source/version tại lúc chấm nên profile thay đổi sau đó không làm quyết định cũ đổi nghĩa.
- `borrowerId` không FK sang User DB; `loanId` mới có FK local khi `FinoraLoan` được LN-009 tạo.

## 5. Cách tính dự kiến

`internalDelinquenciesLast2Years` đếm các delinquency occurrence hợp lệ trong `[asOf - 2 năm, asOf]` theo quy tắc deduplicate đã chốt (theo kỳ hoặc theo episode).

`internalDefaultedLoanCount` đếm loan đạt adverse terminal policy; deduplicate theo loan và policy version để một event retry không tăng nhiều lần.

Khi policy thay đổi, rebuild profile từ immutable credit events; không sửa tay counter mà mất lịch sử.

## 6. Index và hiệu năng

- Unique `source_event_id` chống broker/reconcile đưa cùng sự kiện vào hai lần.
- `(borrower_id, occurred_at, id)` cho cửa sổ hai năm/rebuild: lọc người vay trước rồi quét đúng khoảng thời gian.
- `(borrower_id, loan_id, event_type, occurred_at)` chỉ thêm nếu query deduplicate thực tế dùng; index làm mỗi insert tốn thêm chi phí.
- Unique profile borrower ID.
- Batch rebuild phân trang/keyset; không load mọi event của mọi borrower vào bộ nhớ.
- LN-007 đọc profile bằng một query/batch, không query từng feature riêng.

Insert event, cập nhật/đánh dấu rebuild profile và ghi processed marker phải cùng local transaction. Hai event của cùng borrower có thể chạy đồng thời nên lock/version profile và phép tính phải cho kết quả giống nhau bất kể thứ tự nhận; event có `occurredAt` cũ vẫn có thể làm thay đổi cửa sổ và cần recompute, không chỉ “+1”.

N+1 cần tránh khi scoring/rebuild: lấy batch borrower/profile bằng một query, không query riêng `delinq` rồi `default` cho từng Application; rebuild phân trang event theo borrower/time và không gọi Fineract trong vòng lặp DB đang giữ transaction.

## 7. Failure và tính đúng

- Event trùng/out-of-order không tăng counter sai.
- Event reversal/correction phải tham chiếu event gốc và kích hoạt recompute.
- Thiếu source mapping đưa quarantine, không gán borrower ngẫu nhiên.
- Fineract unavailable giữ profile cũ kèm `calculatedAt`; hồ sơ thẩm định phải giữ độ mới/source để admin đánh giá, không đưa dữ liệu stale vào AI v17.
- Không tự biến null/unknown có nguồn lỗi thành 0. Chỉ dùng 0 cho borrower thật sự chưa có lịch sử theo query thành công.

## 8. API và audit

- Profile nội bộ không cần endpoint borrower sửa.
- Admin có thể xem summary, source, policy version và `calculatedAt`; không hiển thị raw technical payload.
- Manual rebuild/repair cần role, audit và idempotency.
- Hồ sơ/assessment lưu chính xác hai giá trị cùng source/version để audit nội bộ; không được ghi chú sai rằng chúng đã gửi sang AI v17.

## 9. Kiểm thử và nghiệm thu

- Borrower lần đầu nhận `0/0` do “không có history”, không phải do dependency lỗi.
- Cửa sổ đúng biên hai năm và timezone UTC.
- Duplicate, out-of-order, cured/reversed và policy rebuild.
- Nhiều kỳ trễ cùng loan tuân đúng cách đếm đã duyệt.
- Test contract khẳng định mapper AI v17 không phát sinh `delinq_2yrs/pub_rec`; nếu contract tương lai bổ sung lại thì phải thêm test versioned riêng.
- Query/index và batch rebuild có bằng chứng.
- Thái duyệt DPD/default policy; Hải chỉ cần xác nhận khi một contract AI sau v17 thực sự dùng lại dữ liệu proxy nội bộ.
