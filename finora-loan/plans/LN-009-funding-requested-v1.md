---
document_type: TASK_PLAN
task_id: LN-009
owner: Thai
reviewers: [Hai]
status: READY_FOR_REVIEW
updated_at: 2026-09-27
---

# LN-009 — Phát yêu cầu gọi vốn sau exact terms

Loan không tạo hợp đồng ngay khi hồ sơ được duyệt. Khi exact terms đã
`AUTO_AUTHORIZED` hoặc borrower `ACCEPTED`, Loan ghi `FUNDING_REQUESTED` và outbox
`LoanFundingRequested.v1` trong cùng transaction. Investment tạo đúng một listing theo
`applicationNumber + fundingRound`; mobile/web vẫn GET listing từ Investment.

```text
Application APPROVED + terms authorized
→ Loan state + transactional outbox
→ finora.loan.funding-requested
→ Investment MarketListing
```

- Principal/final rate/term/method/terms hash lấy từ snapshot Loan và không được Investment sửa.
- `15.0000` nghĩa là `15%/năm`; tiền là chuỗi decimal VND.
- Không phát CCCD, thu nhập, KYC, raw AI hoặc PDF qua Kafka.
- `eventId` + business key chống trùng; outbox retry không đổi logical event.
- V14 lưu funding state và consumer inbox; luồng Investment là bắt buộc, không cò nhánh Contract demo.

Contract, payload, topic và failure path: [LOAN-INVESTMENT-EVENTS](../../docs/integrations/LOAN-INVESTMENT-EVENTS.md).

- [x] Producer/outbox bắt buộc phía Loan.
- [x] Consumer idempotent tạo listing phía Investment.
- [x] Rate/amount contract và PII allowlist.
- [ ] Hải review phần Investment và tài liệu chung trước merge.
