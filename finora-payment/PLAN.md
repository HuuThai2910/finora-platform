# Kế hoạch FINORA Payment Service

- **Owner:** Thái
- **Roadmap:** [finora-team-roadmap.md](../.agents/plans/finora-team-roadmap.md)
- **Boundary:** [Payment](../.agents/rules/07-service-boundaries.md#payment-service)
- **Pháp lý:** [`LEGAL-PAYMENT-01`](../docs/LEGAL-COMPLIANCE.md)

| Task | Nội dung | Phụ thuộc | Trạng thái | Đặc tả |
|---|---|---|---|---|
| PM-001 | Wallet + immutable balanced ledger foundation | Không cần Investment API contract | `READY_FOR_REVIEW` | [PM-001](plans/PM-001-wallet-ledger-foundation.md) |
| PM-002 | Mock/ZaloPay provider adapter | Provider/webhook contract | `IN_PROGRESS` | [PM-002–004](plans/PM-002-004-disbursement-provider-outbox.md) |
| PM-003 | Hold/release/capture + disbursement | Investment contract | `IN_PROGRESS` | [PM-002–004](plans/PM-002-004-disbursement-provider-outbox.md) |
| PM-004 | Financial outbox | Event contract | `IN_PROGRESS` | [PM-002–004](plans/PM-002-004-disbursement-provider-outbox.md) |

PM-001 không biến FINORA thành đơn vị giữ tiền. Đây là sổ kỹ thuật để đối chiếu số dư với provider được phép khi adapter thật được bổ sung.
