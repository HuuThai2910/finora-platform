# Kế hoạch FINORA Blockchain Service

- **Owner:** Thái
- **Roadmap:** [finora-team-roadmap.md](../.agents/plans/finora-team-roadmap.md)
- **Cross-service flow:** [F08](../.agents/rules/08-cross-service-flows.md#f08--audit-blockchain-và-đối-chiếu)
- **Pháp lý/dữ liệu:** [`LEGAL-CONTRACT-01`, `LEGAL-DATA-01`](../docs/LEGAL-COMPLIANCE.md)

| Task | Nội dung | Phụ thuộc | Trạng thái | Đặc tả |
|---|---|---|---|---|
| BC-001 | Durable proof submission foundation | Không cần event contract; local dùng mock | `READY_FOR_REVIEW` | [BC-001](plans/BC-001-proof-submission-foundation.md) |
| BC-002 | Kafka consumer nhận proof request | Event/schema do hai owner duyệt | `BACKLOG` | Chưa tạo |
| BC-003 | Hyperledger Fabric submit/query thật | P3 phase gate, network/chaincode/profile | `BACKLOG` | Chưa tạo |
| BC-004 | Reconciliation/explorer | BC-003 và source query contract | `BACKLOG` | Chưa tạo |

`READY_FOR_REVIEW` chỉ xác nhận code local và test hoàn tất. P4-A04/P4-A05 vẫn chưa hoàn thành cho tới khi Fabric và contract liên service được tích hợp thật.
