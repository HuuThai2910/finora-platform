---
document_type: CROSS_SERVICE_REVIEW
owner: Thai
reviewer: Hai
status: WAITING_FOR_HAI_CONFIRMATION
updated_at: 2026-08-02
related_task: LN-007
---

# Hải xác nhận giúp contract AI v10 cho Loan

## 1. Bối cảnh ngắn

Loan sẽ gửi hồ sơ sang AI v10 để chấm điểm. Hai bên đã thống nhất:

- Model dùng là v10.
- Loan gửi 13 field.
- `int_rate` lấy từ lãi suất cố định của Product.
- `installment` lấy từ lịch Fineract; Loan không tự tính lại.
- `delinq_2yrs` và `pub_rec` là lịch sử tín dụng nội bộ FINORA, không phải CIC và không do borrower tự khai.
- Loan bỏ qua `suggested_rate`; admin/Loan quyết định approve hoặc reject.
- `ANNUITY` gửi installment cố định; `EQUAL_PRINCIPAL` tạm gửi kỳ có nghĩa vụ lớn nhất/kỳ đầu theo contract chốt cuối.

Thiết kế chung: [LOAN-SERVICE-DESIGN.md](LOAN-SERVICE-DESIGN.md).  
Mapping và cách lưu chi tiết: [LN-007-credit-profile-ai-assessment.md](LN-007-credit-profile-ai-assessment.md).

## 2. Request Loan dự kiến gửi

Hải kiểm tra giúp **tên field, kiểu dữ liệu và enum** trong JSON này có đúng với API/model v10 đang chạy không:

```json
{
  "person_age": 30,
  "emp_length": "5 years",
  "annual_inc": 300000000,
  "loan_amnt": 50000000,
  "home_ownership": "RENT",
  "purpose": "education",
  "int_rate": 15.0,
  "term_months": 12,
  "verification_status": "Not Verified",
  "dti": 15.5,
  "delinq_2yrs": 0,
  "pub_rec": 0,
  "installment": 4513000
}
```

Nguồn phía Loan:

| Field AI | Nguồn |
|---|---|
| `person_age` | Borrower profile; local hiện mock 30 |
| `emp_length` | Borrower tự khai trong Application |
| `annual_inc` | Thu nhập năm quy đổi từ dữ liệu tự khai |
| `loan_amnt` | Số tiền borrower yêu cầu |
| `home_ownership` | Tình trạng nhà ở tự khai |
| `purpose` | Ánh xạ từ `purposeCode` của Application |
| `int_rate` | `annualInterestRate` cố định của Product |
| `term_months` | Kỳ hạn borrower đã chọn |
| `verification_status` | KYC/profile snapshot |
| `dti` | Loan tính từ thu nhập và nghĩa vụ đã snapshot theo rule được duyệt |
| `delinq_2yrs` | `BorrowerCreditProfile.delinquenciesLast2Years`; lần đầu không có history là 0 |
| `pub_rec` | `BorrowerCreditProfile.adverseRecordCount` proxy nội bộ; lần đầu không có history là 0 |
| `installment` | Schedule snapshot do Fineract tính |

## 3. Bốn việc Hải cần trả lời

### Câu 1 — Request trên đã đúng contract v10 chưa?

Nếu chưa, Hải ghi ngắn gọn theo mẫu:

```text
Field hiện tại:
Cần đổi thành:
Kiểu/enum hợp lệ:
Ví dụ:
```

Đặc biệt xác nhận `emp_length`, `home_ownership`, `purpose` và `verification_status` vì đây là các field dễ sai enum/format.

### Câu 2 — Rule Engine có dùng đúng `installment` từ request không?

Loan cần câu trả lời rõ:

- Model/rule đang dùng trực tiếp `request.installment`; hoặc
- Code AI vẫn tự tính lại installment ở file/hàm nào và cần sửa thế nào.

Nếu AI tự tính lại, kết quả có thể lệch Fineract, nhất là `EQUAL_PRINCIPAL`. Đây là điểm chặn contract test LN-007.

### Câu 3 — Response thật của v10 là gì?

Hải gửi một JSON thành công thật hoặc fixture cố định, ví dụ:

```json
{
  "pd_probability": 0.31,
  "risk_score": 72,
  "evaluation_score": 70.2,
  "credit_grade": "B",
  "suggested_limit": 50000000,
  "suggested_rate": 0.15,
  "decision": "PENDING_REVIEW",
  "rejection_reason": null,
  "model_version": "10.0.0"
}
```

Loan cần biết:

- field nào luôn có, field nào có thể null;
- range của score/probability;
- enum của grade/decision;
- đơn vị của `suggested_limit`;
- format `model_version`;
- response lỗi validation và lỗi server.

`suggested_rate` có thể còn trong response, nhưng Loan sẽ không đưa vào domain/database/quyết định lãi suất.

### Câu 4 — API fixture để hai bên test là gì?

Hải gửi một trong hai:

- OpenAPI hiện hành; hoặc
- endpoint + một request/response success + một response validation error + một response server error.

Kèm theo timeout khuyến nghị, header/correlation ID nếu có và cách AI xử lý request trùng. Loan sẽ dùng fixture này cho contract test, không import trực tiếp DTO Python/Java của nhau.

## 4. Mẫu trả lời ngắn cho Hải

Hải có thể copy và điền:

```text
1. Request 13 field: Đúng / cần sửa ...
2. installment: dùng trực tiếp request / vẫn tự tính tại ...
3. Response fixture: <dán JSON hoặc đường dẫn file>
4. Error fixture/OpenAPI: <dán JSON hoặc đường dẫn file>
5. Điểm Loan cần lưu ý thêm: ...
```

## 5. Khi nào câu hỏi này được đóng?

Chuyển file sang `CONFIRMED` khi:

- request exact name/type/enum đã khóa;
- AI dùng installment từ request;
- có fixture success/error và model version;
- Hải xác nhận contract;
- LN-007 cập nhật theo câu trả lời và Thái duyệt trước code.
