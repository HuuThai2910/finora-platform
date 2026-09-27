---
document_type: TASK_PLAN
task_id: PM-001
owner: Thai
reviewers:
  - Hai
status: READY_FOR_REVIEW
approved_by: Thai
approved_at: 2026-09-21
updated_at: 2026-09-21
---

# PM-001 — Wallet và sổ bút toán bất biến

## Bản đọc nhanh

PM-001 tạo nền tài chính local trước khi có contract hold/capture của Investment. Mỗi biến động số dư phải đi cùng một giao dịch và ít nhất hai bút toán cân bằng tổng debit/credit. Retry cùng idempotency key không ghi tiền lần hai; hai lệnh đồng thời không được làm số dư âm.

Đây là operational ledger của FINORA để điều phối/đối soát, không phải tài khoản thanh toán hoặc ví điện tử được cấp phép. Nạp, rút, giải ngân và trả nợ production vẫn phải qua provider phù hợp theo `LEGAL-PAYMENT-01`.

## Phạm vi

- `payment_wallets`: available/held balance, currency, owner logical reference, optimistic version.
- `payment_ledger_transactions`: idempotency key, request hash, type/reference, trạng thái POSTED.
- `payment_ledger_entries`: debit/credit append-only theo wallet bucket hoặc clearing account.
- Internal application services mở wallet và post một transaction cân bằng.
- Row lock theo thứ tự ổn định, check không âm, idempotent insert và concurrency tests.

Ngoài phạm vi: public wallet API, deposit webhook, hold/release/capture, Kafka/outbox, provider reconciliation và quyền JWT thật.

## Quan hệ

```text
Owner logical reference (1) ── (N theo currency) PaymentWallet
LedgerTransaction (1) ── (2..N) LedgerEntry
PaymentWallet (1) ── (0..N) LedgerEntry
Clearing account logical (1) ── (0..N) LedgerEntry (wallet_id = null)
```

Không FK sang User/Investment/Loan vì mỗi service sở hữu database riêng. Entry clearing không gắn wallet; entry AVAILABLE/HELD bắt buộc gắn đúng wallet. Transaction/entry bị trigger PostgreSQL chặn UPDATE/DELETE.

## Transaction và concurrency

1. Validate command và tính SHA-256 canonical request.
2. `INSERT ... ON CONFLICT DO NOTHING` transaction theo idempotency key.
3. Nếu đã tồn tại: cùng hash trả kết quả cũ; khác hash trả conflict.
4. Lock toàn bộ wallet liên quan theo `id` tăng dần trong một query.
5. Kiểm tra tổng debit = credit và áp delta; domain chặn available/held âm.
6. Lưu entries và chuyển transaction `POSTED` trong cùng local transaction.

Rollback làm mất cả transaction intent, entry và balance delta. External/provider call không được đặt trong transaction này ở các task sau.

## Index/query

- Unique `(owner_type, owner_id, currency)` cho mở wallet idempotent.
- Unique `wallet_id` public và `idempotency_key` transaction.
- `(reference_type, reference_id, created_at, id)` cho reconcile theo business reference.
- `(wallet_id, created_at, id)` cho statement phân trang keyset sau này.
- Unique `(transaction_id, entry_sequence)` chống entry lặp trong cùng giao dịch.

## Kiểm thử bắt buộc

- Migration sạch/Hibernate validate trên PostgreSQL 17.
- Balanced post cập nhật balance và entries cùng transaction.
- Imbalanced command không ghi gì.
- Duplicate idempotency key không nhân đôi balance; payload khác bị conflict.
- Hai debit cạnh tranh không làm số dư âm.
- SQL UPDATE/DELETE ledger bị trigger từ chối.

Kết quả 2026-09-21: `mvn -pl finora-payment -am verify` pass; Common 6 unit, Payment 4 unit và 5 integration test trên PostgreSQL 17.5.

## Pháp lý

Áp dụng [`LEGAL-PAYMENT-01`](../../docs/LEGAL-COMPLIANCE.md). Dữ liệu test chỉ dùng account/reference giả. PM-001 không được mô tả trên UI là tiền đã nạp/chuyển thật khi chưa có provider transaction reference và reconciliation.
