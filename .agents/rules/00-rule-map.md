# FINORA Rule Map

Đây là chỉ mục bắt buộc. Các từ khóa có nghĩa:

- **MUST/MUST NOT**: bắt buộc/cấm; chỉ thay đổi khi người dùng cho phép sửa luật.
- **SHOULD**: mặc định phải theo; được khác nếu ghi rõ lý do và rủi ro.
- **CURRENT STATE**: hiện trạng để không suy diễn sai; không phải pattern để sao chép.
- **TARGET STATE**: đích kiến trúc; chỉ triển khai khi nằm trong phạm vi task.

## Đọc theo task

| Loại task | Rule bắt buộc |
|---|---|
| Mọi task | `01-project-context.md`, `02-ownership-workflow.md` |
| Tạo/sửa thư mục, module, package | thêm `03-architecture-structure.md` |
| Java/Python/TypeScript, DB, REST, Kafka | thêm `04-conventions-contracts.md` và skill `finora-engineering` |
| Thêm entity/state/API/event hoặc quyết định service sở hữu chức năng | thêm `07-service-boundaries.md` |
| Luồng đi qua từ hai service trở lên | thêm `07-service-boundaries.md`, `08-cross-service-flows.md` và skill `finora-engineering` |
| Port, DB, topic, Keycloak, Fabric, hạ tầng | thêm `05-registry.md` |
| Review, hoàn thành, chuẩn bị PR | thêm `06-quality-gates.md` |
| Lập kế hoạch, chọn task/giai đoạn hoặc phối hợp Thái–Hải | thêm `../plans/finora-team-roadmap.md` |
| Thay đổi rule/skill | đọc toàn bộ file trong `.agents/rules/` và skill liên quan |

## Danh mục rule

- `01-project-context.md`: phạm vi, hiện trạng và đích kiến trúc.
- `02-ownership-workflow.md`: ownership, vùng chung, Git và phối hợp contract.
- `03-architecture-structure.md`: bounded context, package và integration pattern.
- `04-conventions-contracts.md`: naming, dữ liệu, tiền, thời gian, REST và Kafka.
- `05-registry.md`: port, database, consumer group và Fabric identifier.
- `06-quality-gates.md`: quy trình thực hiện, kiểm thử và điều cấm.
- `07-service-boundaries.md`: System of Record, state authority và điều từng service được/không được sở hữu.
- `08-cross-service-flows.md`: orchestration, contract, idempotency, failure và compensation của luồng liên service.

## Nguyên tắc nguồn chuẩn

- `.agents/rules/`: governance, ownership, kiến trúc, convention, registry và quality gate.
- `.agents/skills/finora-engineering/`: quy trình thực thi và kỹ thuật chuyên sâu.
- `AGENTS.md`, `CLAUDE.md`: entrypoint tự phát hiện, không chứa bản sao luật chi tiết.
- README/tài liệu khóa luận mô tả sản phẩm; không tự động trở thành luật code nếu chưa được đưa vào `.agents/rules/`.
- `.agents/plans/finora-team-roadmap.md`: kế hoạch thực thi sống dành cho Thái, Hải và AI; cập nhật trạng thái theo tiến độ, không ghi đè rule kiến trúc/an toàn.
