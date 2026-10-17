---
task_id: W3B
title: Rút tiền từ ví về tài khoản ngân hàng — provider payout giả lập có callback HMAC, đối soát và hàng đợi sự cố (R4)
owner: Thai
status: IN_PROGRESS
depends_on: [W3A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W3B — Rút tiền (R4)

Thái duyệt Q4 trong gói Q (D19): policy rút tiền demo cấu hình được — tối thiểu 50.000 đ, không phí, tối đa 50 triệu/ngày,
chỉ rút số dư khả dụng, số tài khoản che và mã hóa, provider giả lập có callback HMAC. Payout thật chỉ mở sau legal review
[`LEGAL-PAYMENT-01`](../../docs/LEGAL-COMPLIANCE.md); W3B không nối ngân hàng thật. Phụ thuộc W3A (PIN `WITHDRAW` gắn với
`Idempotency-Key`, cổng môi trường chặn provider mock ở production).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Người vay nhận tiền giải ngân và nhà đầu tư nhận tiền trả nợ vào ví FINORA nhưng chưa rút ra được: màn "Rút tiền" của app
chỉ là giả lập không gọi backend. W3B cho rút số dư khả dụng về một tài khoản ngân hàng:

1. Người dùng chọn ngân hàng, nhập số tài khoản, tên chủ tài khoản, số tiền, xác nhận PIN.
2. Payment kiểm hạn mức (tối thiểu 50.000 đ, cộng dồn trong ngày nghiệp vụ không quá 50.000.000 đ, không vượt số dư khả dụng)
   rồi trừ ví ngay trong cùng một transaction: tiền không còn dùng được cho lệnh khác.
3. Worker gửi lệnh chi cho provider; provider báo kết quả bằng callback có chữ ký HMAC.
4. Thành công: lệnh `COMPLETED`. Thất bại (ví dụ tài khoản không hợp lệ): lệnh `FAILED`, tiền hoàn lại ví ngay.
5. Không rõ kết quả (mất phản hồi, mất callback): lệnh vào đối soát, worker hỏi lại provider theo mã lệnh; hết lượt tự động
   thì hiện ở tab "Sự cố giao dịch" cho vận hành tra cứu lại. Không bao giờ tự gửi lệnh chi thứ hai khi chưa biết kết quả lần
   đầu.

### Ví dụ

Nhà đầu tư có 1.050.000 đ khả dụng, đã rút 49.500.000 đ hôm nay. Rút 600.000 đ: bị từ chối `422 WITHDRAWAL_DAILY_LIMIT`
(còn được rút 500.000 đ hôm nay). Rút 500.000 đ: ví còn 550.000 đ khả dụng, lệnh `REQUESTED` → `PROVIDER_PENDING` →
callback thành công → `COMPLETED`. Gửi lại đúng lệnh (cùng `Idempotency-Key`): nhận kết quả cũ, ví không bị trừ lần hai.

### Ai thấy gì?

- App: chính sách rút (tối thiểu, hạn mức còn lại hôm nay, phí 0), danh sách ngân hàng, lịch sử lệnh rút với số tài khoản đã
  che (`******1234`). Màn hình ghi rõ đây là môi trường demo, tiền giả lập.
- Web quản trị: mục "Lệnh rút tiền chờ đối soát" trong tab sự cố, nút "Tra cứu lại".

## 1. Pháp luật, policy, giới hạn demo

| Nội dung | Loại | Nguồn |
|---|---|---|
| Rút tiền đi qua tổ chức được phép; ví FINORA không phải tài khoản pháp lý | Pháp luật | `LEGAL-PAYMENT-01` |
| Tối thiểu 50.000 đ, tối đa 50.000.000 đ/ngày, phí 0 | Policy demo cấu hình được (`PAYMENT_WITHDRAWAL_*`) | Q4/D19, không phải trích dẫn luật |
| Số tài khoản mã hóa AES-GCM, chỉ trả bản che | Kiểm soát dữ liệu | `LEGAL-DATA-01` |
| Provider payout mock | Giới hạn kỹ thuật demo | Cổng môi trường D37 chặn ở staging/production |

## 2. Quyết định (D41–D44)

| # | Quyết định |
|---|---|
| D41 | Trừ ví lúc nhận lệnh (`AVAILABLE` → clearing `PAYOUT_PROVIDER_CLEARING`, type `WITHDRAWAL`) trong transaction khóa dòng ví, cùng lúc kiểm hạn mức ngày; thất bại hoàn bằng bút toán ngược trong cùng transaction chuyển `FAILED`. Không dùng `HELD` vì bucket này đối soát với khoản giữ góp vốn |
| D42 | Kết quả chưa rõ không bao giờ gửi lệnh chi mới: tra cứu provider theo `withdrawalId`; provider khẳng định không có lệnh thì mới gửi lại với đúng mã đó (provider phải idempotent theo mã lệnh) |
| D43 | Provider mock gửi callback HTTP thật tới Payment, ký HMAC-SHA256 trên body bằng khóa riêng; số tài khoản kết thúc `0000` mô phỏng tài khoản sai (thất bại) để kiểm nhánh hoàn tiền |
| D44 | Số tài khoản mã hóa AES-256-GCM (`PAYMENT_ACCOUNT_ENCRYPTION_KEY`); khóa dev mặc định bị cổng môi trường chặn ở staging/production |

## 3. Thiết kế

### Dữ liệu (Payment V18)

`payment_withdrawals` (một dòng một lệnh rút, `0..N` lệnh mỗi ví): `withdrawal_id` unique, `wallet_id` FK
`payment_wallets`, `owner_id/owner_type`, `amount`, `fee_amount`, `business_date` (ngày nghiệp vụ VN để cộng hạn mức),
`status`, `bank_code`, `account_number_encrypted`, `account_number_masked`, `account_holder_name`, `idempotency_key` +
`request_hash` (unique theo chủ), `provider`, `provider_reference`, `reserve_ledger_transaction_id`,
`refund_ledger_transaction_id`, đếm lượt/lease/đối soát/thao tác vận hành, `version`.

Index: lịch sử `(owner_id, created_at DESC)`; hạn mức ngày `(wallet_id, business_date)` lọc `status <> 'FAILED'`; hàng đợi
worker `(next_attempt_at, id)` lọc trạng thái chưa kết thúc.

### Trạng thái

`REQUESTED` → `PROCESSING` (lease) → `PROVIDER_PENDING` → `COMPLETED` | `FAILED`; không rõ kết quả → `RECONCILIATION_REQUIRED`.

### API

| Method | Path | Ghi chú |
|---|---|---|
| GET | `/api/v1/wallets/withdrawal-policy` | Tối thiểu, hạn mức ngày, đã dùng hôm nay, phí, danh sách ngân hàng |
| POST | `/api/v1/wallets/me/withdrawals` | PIN `WITHDRAW`, `Idempotency-Key`, giới hạn 10 lệnh/giờ |
| GET | `/api/v1/wallets/me/withdrawals`, `/{withdrawalId}` | Lịch sử, chi tiết (số tài khoản đã che) |
| POST | `/api/v1/wallets/callbacks/mock-payout` | Không cần JWT; header `X-Payout-Signature` HMAC |
| GET/POST | `/api/v1/admin/withdrawals`, `/{withdrawalId}/recheck` | ADMIN, hàng đợi sự cố |

## 4. Ca lỗi

| Tình huống | Kết quả |
|---|---|
| Hai lệnh rút đồng thời vượt hạn mức | Khóa dòng ví: lệnh sau thấy tổng đã gồm lệnh trước → `422 WITHDRAWAL_DAILY_LIMIT` |
| Callback giả/sai chữ ký | `401`, không đổi gì |
| Callback lặp | Idempotent, không hoàn/ghi hai lần |
| Mất callback | Hết hạn chờ → tra cứu provider |
| Payment chết khi đang gọi provider | Hết lease → đối soát, không gửi lệnh chi lại khi chưa tra cứu |

## 5. Acceptance

- Unit/IT: hạn mức, số dư, idempotency, callback HMAC, hoàn tiền, đối soát, mã hóa số tài khoản.
- E2E: rút thành công qua callback, rút thất bại được hoàn, dưới mức tối thiểu/vượt hạn mức bị chặn, gửi lặp không trừ hai lần.
