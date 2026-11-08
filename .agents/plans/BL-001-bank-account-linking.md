# BL-001 — Liên kết tài khoản ngân hàng (ngân hàng giả lập, luồng thật)

- **Trạng thái:** IMPLEMENTED (06/11/2026) — làm theo yêu cầu trực tiếp của Hải, chưa qua bước duyệt plan của Thái.
  Thái cần review phần `finora-payment` (module của Thái) và vùng dùng chung (`finora-common`, `docker/`, `.agents/`).
- **Module:** `finora-payment` (chính), `finora-notification`, `finora-common` (`PinScope.BANK_ACCOUNT`),
  `finora-mobile`.

## 1. Mục tiêu

Người dùng rút tiền về **tài khoản ngân hàng đã liên kết** thay vì gõ tay số tài khoản + tên chủ tài khoản. Chỉ phía
ngân hàng là giả lập; mọi bước kiểm tra trong hệ thống chạy thật:

1. Chỉ người đã eKYC `VERIFIED` mới liên kết được (Payment đọc `GET /api/v1/users/me` của finora-user bằng **token
   của chính người dùng**, như Loan đọc eKYC người vay — không thêm service account).
2. Ngân hàng xác nhận tài khoản tồn tại, nhận được tiền và **đứng tên đúng họ tên eKYC** (so sánh không dấu, IN HOA).
3. Xác nhận PIN `BANK_ACCOUNT` (gắn `Idempotency-Key`, D38) rồi ngân hàng gửi **OTP 6 số**, sống 5 phút, sai 5 lần thì
   khoá, gửi lại tối đa 3 lần, cách nhau ít nhất 60 giây.
4. Tối đa 5 tài khoản, đúng một tài khoản mặc định; huỷ liên kết cần PIN.

## 2. Danh bạ ngân hàng

`finora-payment/src/main/resources/banks/vn-banks.json` là bản chụp danh sách công khai của VietQR
(`https://api.vietqr.io/v2/banks`, NAPAS): 45 ngân hàng hỗ trợ chuyển khoản (đã bỏ ví MoMo/ZaloPay/MVAS), có mã, BIN,
tên ngắn, tên đầy đủ, logo CDN. Thay enum 10 ngân hàng cũ; mã cũ `CTG`/`AGR` của lệnh rút đã lưu được đọc thành
`ICB`/`VBA`. Không gọi VietQR lúc chạy; cập nhật bằng cách tải lại file.

## 3. Ngân hàng giả lập (`finora.payment.bank-link.provider=mock`)

Kết quả tất định theo 4 số cuối số tài khoản (cùng quy ước `0000` của payout giả lập):

| Đuôi | Kết quả |
|---|---|
| `0000` | `BANK_ACCOUNT_NOT_EXIST` |
| `9999` | `BANK_ACCOUNT_LOCKED` |
| `5555` | `503 BANK_UNAVAILABLE` |
| `1111` | tài khoản người khác → tên sinh từ số tài khoản → `BANK_ACCOUNT_NAME_MISMATCH` |
| còn lại | tài khoản của chính người dùng (tên eKYC dạng ngân hàng in) |

OTP: Payment ghi outbox `BankLinkOtpRequested.v1` (topic `finora.payment.bank-link-otp-requested`) → Notification ghi
hộp thư mẫu `BANK_LINK_OTP` (nhóm `WALLET`, giao cả giờ nghỉ). Mã chỉ nằm trong câu hộp thư, **không lên push** màn
hình khóa. Provider thật không phát event này (ngân hàng tự gửi SMS). Payment chỉ lưu HMAC của OTP.

Rủi ro chấp nhận cho demo: mã OTP gốc nằm trong payload outbox của Payment và trong hộp thư Notification (sống 5 phút).

## 4. API (Payment, qua Gateway `/api/v1/wallets/**`)

| Method | Path | Ghi chú |
|---|---|---|
| GET | `/wallets/banks` | danh bạ |
| GET | `/wallets/me/bank-accounts` | tài khoản đang liên kết, mặc định trước |
| POST | `/wallets/me/bank-accounts/inquiry` | tra cứu chủ tài khoản; 20 lần/giờ |
| POST | `/wallets/me/bank-accounts` | PIN `BANK_ACCOUNT` + `Idempotency-Key`; 201 challenge |
| POST | `/wallets/me/bank-accounts/link-requests/{id}/resend-otp` | |
| POST | `/wallets/me/bank-accounts/link-requests/{id}/confirm` | `{otp}` → 201 tài khoản |
| PUT | `/wallets/me/bank-accounts/{id}/default` | |
| DELETE | `/wallets/me/bank-accounts/{id}` | PIN `BANK_ACCOUNT` |

`POST /wallets/me/withdrawals` nhận thêm `bankAccountId` (app dùng). Ba trường gõ tay cũ vẫn nhận cho client cũ/test.

## 5. Dữ liệu

`V19__create_bank_accounts.sql`: `payment_bank_link_requests` (1 yêu cầu → 0..1 tài khoản) và `payment_bank_accounts`
(N tài khoản / người dùng). Số tài khoản AES-256-GCM như lệnh rút (D44), so trùng bằng HMAC; index một-tài-khoản-
đang-liên-kết và một-mặc-định mỗi người.

## 6. Kiểm thử

- `finora-payment`: `BankAccountServiceImplTest`, `BankDirectoryTest`, `MockBankAccountProviderTest`; cả bộ unit test
  145/145. `WithdrawalFlowIT` (Testcontainers) chưa chạy lại vì máy không bật Docker.
- `finora-notification`: `BankLinkOtpHandlerTest`; cả bộ 153/153.
- `finora-mobile`: `npx tsc --noEmit`; chưa chạy thử trên thiết bị.

## 7. Triển khai

Build lại `finora-common` → `finora-user` (nhận scope PIN mới), `finora-payment` (Flyway V19), `finora-notification`.
Payment cần `USER_SERVICE_URL` (đã thêm vào `docker-compose.app.yml`; `docker-compose.yml` lấy từ anchor chung).
