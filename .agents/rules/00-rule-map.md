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
| Port, DB, topic, Keycloak, Fabric, hạ tầng | thêm `05-registry.md` |
| Review, hoàn thành, chuẩn bị PR | thêm `06-quality-gates.md` |
| Thay đổi rule/skill | đọc toàn bộ file trong `.agents/rules/` và skill liên quan |

## Danh mục rule

- `01-project-context.md`: phạm vi, hiện trạng và đích kiến trúc.
- `02-ownership-workflow.md`: ownership, vùng chung, Git và phối hợp contract.
- `03-architecture-structure.md`: bounded context, package và integration pattern.
- `04-conventions-contracts.md`: naming, dữ liệu, tiền, thời gian, REST và Kafka.
- `05-registry.md`: port, database, consumer group và Fabric identifier.
- `06-quality-gates.md`: quy trình thực hiện, kiểm thử và điều cấm.

## Nguyên tắc nguồn chuẩn

- `.agents/rules/`: governance, ownership, kiến trúc, convention, registry và quality gate.
- `.agents/skills/finora-engineering/`: quy trình thực thi và kỹ thuật chuyên sâu.
- `AGENTS.md`, `CLAUDE.md`: entrypoint tự phát hiện, không chứa bản sao luật chi tiết.
- README/tài liệu khóa luận mô tả sản phẩm; không tự động trở thành luật code nếu chưa được đưa vào `.agents/rules/`.
