# Kế hoạch FINORA Blockchain Service

- **Owner:** Thái
- **Bản AS-IS xuyên Loan–Payment–Fineract–Fabric:** [`FINORA-CORE-BUSINESS-AND-DATA-FLOW.md`](../docs/architecture/FINORA-CORE-BUSINESS-AND-DATA-FLOW.md)
- **Roadmap:** [finora-team-roadmap.md](../.agents/plans/finora-team-roadmap.md)
- **Cross-service flow:** [F08](../.agents/rules/08-cross-service-flows.md#f08--audit-blockchain-và-đối-chiếu)
- **Pháp lý/dữ liệu:** [`LEGAL-CONTRACT-01`, `LEGAL-DATA-01`](../docs/LEGAL-COMPLIANCE.md)

| Task | Nội dung | Phụ thuộc | Trạng thái | Đặc tả |
|---|---|---|---|---|
| BC-001 | Durable proof submission foundation | Không cần event contract; local dùng mock | `READY_FOR_REVIEW` | [BC-001](plans/BC-001-proof-submission-foundation.md) |
| BC-002 | Kafka consumer bốn mốc proof (hợp đồng, giải ngân, trả nợ, Note) + DLT | Event v1 đã có của Loan/Payment/Investment | `READY_FOR_REVIEW` (W3D) | [W3D](../.agents/plans/W3D-fabric-proofs.md) |
| BC-003 | Hyperledger Fabric submit/query thật, mạng local 2 tổ chức | D14, Q10 | `READY_FOR_REVIEW` (W3D) | [W3D](../.agents/plans/W3D-fabric-proofs.md) |
| BC-004 | Explorer/verify API + đối chiếu DB ↔ chain | BC-003 | `READY_FOR_REVIEW` (W3D); trang web explorer chưa có | [W3D](../.agents/plans/W3D-fabric-proofs.md) |
| BC-005 | Proof cho quyết định servicing (tất toán, cơ cấu, nợ xấu) | Owner chọn mốc thứ năm | `BACKLOG` | Chưa tạo |

`READY_FOR_REVIEW` chỉ xác nhận code local và test hoàn tất. Fabric hiện là mạng local/demo (cryptogen, một máy);
production cần Fabric CA/HSM và tổ chức kiểm toán vận hành peer riêng.
