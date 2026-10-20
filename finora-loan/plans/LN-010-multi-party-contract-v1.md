---
document_type: TASK_PLAN
task_id: LN-010
owner: Thai
reviewers: [Hai]
status: READY_FOR_REVIEW
updated_at: 2026-09-27
---

# LN-010 — Allocation và một hợp đồng nhiều bên

Investment phát `LoanFullyFunded.v2` (mang `paymentHoldReference` theo từng lender) khi tổng commitment bằng đúng target và allocation đã khóa.
Loan kiểm amount/round/version/hash, lưu snapshot rồi tạo **một** Contract/PDF gồm borrower và toàn
bộ lender allocations.

```text
Investment FULLY_FUNDED
→ Loan Contract PENDING_LENDER_SIGNATURES
→ đủ lender signatures
→ PENDING_BORROWER_SIGNATURE
→ borrower ký
→ EFFECTIVE + LoanContractActivated.v1
```

- Investment sở hữu listing/commitment/allocation; Loan chỉ giữ immutable snapshot cho Contract.
- Mọi bên ký cùng document/PDF hash; không tạo một hợp đồng cho từng investor.
- Event trùng cùng snapshot là no-op; snapshot khác bị fail closed.
- `LoanContractActivated` nghĩa là đủ chữ ký/có hiệu lực, chưa phải đã giải ngân.
- V15–V16 lưu contract parties và trạng thái SmartCA async theo từng lender;
  investor REST hỗ trợ list/detail/document/sign/refresh.
- Provider `MOCK` vẫn dành cho dev; khi cấu hình `VNPT_SMART_CA`, mỗi investor có
  transaction riêng và chỉ được ghi `SIGNED` sau khi VNPT trả kết quả hoàn tất.

- [x] Exact-target allocation và `LoanFullyFunded` outbox.
- [x] Consumer/hash/version/amount validation.
- [x] Một PDF, N lender parties, một borrower party và hai lượt ký.
- [x] Investment Contract projection cho UI.
- [x] SmartCA async cho từng lender, polling giới hạn và retry idempotent.
- [ ] Hải review Investment và chạy E2E Kafka local trước merge.

Cập nhật 2026-10-17 (R5, [W3C](../../.agents/plans/W3C-pades-contract-signing.md)): hợp đồng nhiều bên mới ký
PAdES-B-B ẩn nhúng vào chính PDF. Lender ký nối tiếp theo thứ tự hoàn tất (mỗi lúc một phiên ký chờ trên một hợp
đồng), borrower ký cuối; mỗi chữ ký là một incremental update và không làm hỏng chữ ký trước. `/document` vẫn trả
SIGNABLE trong lúc ký; sau khi đủ chữ ký trả bản ký đủ (`SIGNED_PDF`) và trang biên nhận nằm ở `/receipt`. Hợp đồng
lập trước V32 giữ luồng bằng chứng tách rời ở trên.
