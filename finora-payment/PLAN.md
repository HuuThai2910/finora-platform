# Kế hoạch FINORA Payment Service

- **Owner:** Thái
- **Roadmap:** [finora-team-roadmap.md](../.agents/plans/finora-team-roadmap.md)
- **Boundary:** [Payment](../.agents/rules/07-service-boundaries.md#payment-service)
- **Pháp lý:** [`LEGAL-PAYMENT-01`](../docs/LEGAL-COMPLIANCE.md)

| Task | Nội dung | Phụ thuộc | Trạng thái | Đặc tả |
|---|---|---|---|---|
| PM-001 | Wallet + immutable balanced ledger foundation | Không cần Investment API contract | `READY_FOR_REVIEW` | [PM-001](plans/PM-001-wallet-ledger-foundation.md) |
| PM-002 | Deposit sandbox/provider adapter | Provider/webhook contract | `BACKLOG` | Chưa tạo |
| PM-003 | Hold/release/capture | Investment contract | `BACKLOG` | Chưa tạo |
| PM-004 | Financial outbox | Event contract | `BACKLOG` | Chưa tạo |

PM-001 không biến FINORA thành đơn vị giữ tiền. Đây là sổ kỹ thuật để đối chiếu số dư với provider được phép khi adapter thật được bổ sung.
