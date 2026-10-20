---
document_type: TASK_PLAN
task_id: LN-017
owner: Thai
reviewers: []
status: REVIEW
depends_on: [LN-003, LN-006, LN-013]
updated_at: 2026-10-11
---

# LN-017 — Fineract Product V2/V3 và cổng trả trước một phần

## Bản đọc nhanh

Product/mapping tạo mới dùng `FINORA-FINERACT-V3`, ánh xạ sang
`advanced-payment-allocation-strategy`. Quy tắc mặc định ưu tiên nghĩa vụ quá hạn, rồi nghĩa vụ đến
hạn, rồi tiền trả trước; phần kỳ tương lai dùng `NEXT_INSTALLMENT` để ưu tiên giảm các kỳ gần nhất và
rút ngắn thời gian còn nợ. Khoản vay V2 giữ `REAMORTIZATION`; V1 tiếp tục `mifos-standard-strategy`.

API trả trước một phần mở cho khoản vay V2/V3. Fineract 1.15 chưa có endpoint preview nhận **số tiền
giả định** để trả lịch mới trước giao dịch; vì vậy quote chỉ snapshot nghĩa vụ hiện tại, gốc trả thêm,
phí, dư gốc và kỳ kế tiếp trước giao dịch. FINORA không tự dựng lịch. Sau POST, lịch mới chính thức lấy
từ Fineract và đi theo `RepaymentDistributed.v1`.

## Contract phiên bản

| `coreConfigVersion` | Strategy | Quy tắc |
|---|---|---|
| `FINORA-FINERACT-V1` | `mifos-standard-strategy` | Giữ nguyên cho mapping/application/loan cũ |
| `FINORA-FINERACT-V2` | `advanced-payment-allocation-strategy` | `loanScheduleType=PROGRESSIVE` + `paymentAllocation.DEFAULT` + `futureInstallmentAllocationRule=REAMORTIZATION` |
| `FINORA-FINERACT-V3` | `advanced-payment-allocation-strategy` | Như V2 nhưng `futureInstallmentAllocationRule=NEXT_INSTALLMENT` để ưu tiên rút ngắn kỳ |

`coreConfigVersion` được snapshot từ mapping vào `LoanApplication`, rồi truyền nguyên giá trị sang
schedule preview, final schedule và core booking. Version lạ fail closed; cấu hình runtime không được
ghi đè lịch sử của hồ sơ cũ.

## Thứ tự phân bổ V2/V3

1. `PAST_DUE_PRINCIPAL`, `PAST_DUE_INTEREST`, `PAST_DUE_FEE`, `PAST_DUE_PENALTY`.
2. `DUE_PRINCIPAL`, `DUE_INTEREST`, `DUE_FEE`, `DUE_PENALTY`.
3. `IN_ADVANCE_PRINCIPAL`, `IN_ADVANCE_INTEREST`, `IN_ADVANCE_FEE`, `IN_ADVANCE_PENALTY`.

Tên enum trên được khóa theo mã nguồn phát hành Fineract 1.15.0 (`PaymentAllocationType`). Tài liệu
giải thích có ví dụ cũ dùng tiền tố `DUE_PAST_*`, nhưng API/runtime 1.15.0 nhận `PAST_DUE_*`; FINORA
theo contract thực thi và có unit test khóa đủ 12 rule cùng thứ tự.

Thứ tự này là policy kỹ thuật của Product demo, phải được tạo thành Product mới. Fineract quy định
thay đổi Product chỉ áp dụng cho loan account tạo sau; không dùng SQL để sửa strategy của khoản đang
hoạt động.

## Contract API đã triển khai

- `POST /api/v1/repayments/partial-prepayment-quotes`: nhận application và phần gốc trả thêm.
- `GET /api/v1/repayments/partial-prepayment-quotes/{quoteId}`: chỉ borrower sở hữu được đọc.
- `POST /api/v1/repayments/partial-prepayment`: quote dùng một lần + `Idempotency-Key`.
- Payment thu `coreAmount + platformFee`, chỉ gửi `coreAmount` sang Fineract; V1 fail closed.
- Không thu lãi tương lai. `platformFee` tính trên phần gốc trả thêm theo policy V2 và tách
  `investorFeeShare=80%`, `finoraFeeShare=20%` trước khi ghi ledger/event.
- `PartialPrepaymentQuote` snapshot policy/TTL/strategy và current core values; response repayment mang
  schedule summary chính thức sau POST.

## Bản đồ code và kiểm thử hiện tại

- `FineractAllocationPolicy`: ánh xạ version → strategy/rules ở một nơi duy nhất.
- `FineractProductPayloadMapper`: gửi `loanScheduleType=PROGRESSIVE` và `paymentAllocation` khi tạo V2/V3.
- `FineractSchedulePayloadMapper` và `FineractCoreLoanBookingGateway`: dùng strategy từ snapshot.
- `ApplicationSubmissionContext`, `ScheduleCalculationRequest`, `CoreLoanBookingCommand`: mang
  `coreConfigVersion` xuyên luồng, không đọc lại cấu hình mới giữa chừng.
- `FineractPayloadMapperTest`: khóa payload V1/V2/V3, gồm `PROGRESSIVE` và rule tương lai theo version.
- Payment V9 tạo quote schema, account snapshot `coreConfigVersion` và enum `PARTIAL_PREPAYMENT`;
  V10 mở rộng snapshot tên allocation strategy mà không sửa checksum migration cũ.
- `FineractCoreLoanBookingGateway` gửi `legalFormId`, cùng interest calculation policy với Product và
  fail fast nếu thiếu Office/Payment Type; tenant demo chuẩn dùng các ID `1/1/1`.
- Khi retry đồng bộ Product, Loan không chỉ so `externalId`: adapter đọc lại `/loanproducts/{id}` và
  chỉ tái sử dụng V2/V3 nếu strategy là `advanced-payment-allocation-strategy`, lịch là `PROGRESSIVE`,
  rule kỳ tương lai khớp version và đủ 12 thứ tự phân bổ. Sai bất biến sẽ fail closed với
  `FINERACT_CONTRACT_MISMATCH`, không tạo Loan trên Product cấu hình nhầm.
- Payment integration test phủ quote → debit ví → post core → phân phối investor/Note.
- Hợp đồng mới dùng `LOAN_TERMS_V2`/`CLICK_WRAP_TEXT_V3`/`CONTRACT_PDF_V2`; cả text và PDF đóng băng
  `FINORA_SERVICING_TERMS_V2`, gồm bảng phí đúng thời hạn, không thu lãi tương lai và cách chia 80/20.
  Hợp đồng đã tồn tại không render lại nên giữ nguyên bytes/hash và điều khoản cũ.

## Nghiệm thu còn lại

- [x] Ngày 2026-10-04, tenant Fineract local 1.15.0 đã chấp nhận Product V2 với
  `advanced-payment-allocation-strategy`, `REAMORTIZATION` và đủ 12 rule; đọc lại Product cho kết quả
  đầu/cuối lần lượt là `PAST_DUE_PRINCIPAL`/`IN_ADVANCE_PENALTY`.
- [x] Tạo Loan ID 1 bằng Product V2/`PROGRESSIVE`, giải ngân 50.000.000 VND và post repayment thật
  5.000.000 VND (`FINORA-V2-E2E-PARTIAL-01`). Fineract ghi principal portion 5.000.000 VND,
  outstanding principal 45.000.000 VND và re-amortize khoảng 833.000 VND gốc trên mỗi kỳ.
- [x] Integration test quote → consent → thu tiền → core breakdown → phân phối Note.
- [x] Kafka broker local publish/consume envelope v1 thành công; consumer contract/idempotency nằm trong
  regression của từng service.
- [x] Ngày 2026-10-11, compile reactor Loan/Payment/Investment và unit test mapping V3/policy phí 80/20
  thành công. Loan account V2 cũ không bị đổi ngầm; kiểm thử UX rút ngắn kỳ phải dùng hồ sơ V3 mới.
- [x] Contract renderer text/PDF công bố cùng policy mà Payment dùng; test đọc PDF khóa các câu không thu
  lãi tương lai, chia 80/20 và đồng thuận nhà đầu tư.
