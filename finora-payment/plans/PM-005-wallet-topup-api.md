---
document_type: TASK_PLAN
task_id: PM-005
owner: Thai
reviewers: [Hai]
status: REVIEW
updated_at: 2026-09-28
---

# Wallet và nạp tiền sandbox

## Kết quả

- Mobile đọc số dư/lịch sử từ Payment, không còn cộng tiền ở client.
- `POST /api/v1/wallets/me/top-ups` tạo lệnh idempotent; provider chạy ngoài transaction DB.
- Provider `mock` có endpoint hoàn tất chỉ khi chính lệnh mang provider MOCK.
- Provider `zalopay` tạo order sandbox bằng Key1; callback chỉ ghi có sau khi HMAC Key2 và số tiền khớp.
- Mỗi lần ghi có tạo ledger cân bằng `PROVIDER_CLEARING -> WALLET AVAILABLE` đúng một lần.

## Còn phải nghiệm thu

- Gọi thật ZaloPay sandbox bằng credential được cấp và callback HTTPS (ngrok hoặc deployment).
- Test callback trùng, callback sai MAC/sai amount, timeout mất response và reconciliation.
- Production legal/operations review theo `LEGAL-PAYMENT-01`; mock và sandbox không phải tiền thật.
