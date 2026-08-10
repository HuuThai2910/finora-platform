# Ownership và quy trình cộng tác

## Ownership

| Owner | Module |
|---|---|
| Dev A — Thái | `finora-loan`, `finora-payment`, `finora-blockchain` |
| Dev B — Hải | `finora-ai`, `finora-investment`, `finora-user`, `finora-notification` |
| Dùng chung | `finora-common`, `.agents/`, `docs/`, contract dùng chung, cấu hình toàn cục |
| Dùng chung có giới hạn | `finora-gateway`: mỗi owner chỉ sửa route của service mình |

## Luật chống chồng chéo

- MUST xác định module thật và owner trước khi ghi file.
- MUST NOT sửa module của owner khác. Nếu task cần thay đổi bên kia, dừng phần đó và ghi rõ contract/TODO để owner thực hiện.
- Vùng dùng chung MUST được cả hai dev review trước khi merge.
- MUST NOT đọc/ghi database service khác; dùng REST, Kafka event hoặc read model được sở hữu rõ ràng.
- MUST tìm file/implementation tương tự trước khi tạo mới; MUST NOT tạo utility hoặc abstraction trùng chức năng.
- MUST NOT refactor ngoài phạm vi task. Nợ kỹ thuật ngoài phạm vi ghi thành đề xuất riêng.

## Git và thay đổi bên ngoài

- Branch: `feat/<task-id>-<slug>` hoặc `fix/<task-id>-<slug>`.
- Commit: Conventional Commits với scope module, ví dụ `feat(loan): tạo hồ sơ vay`.
- SHOULD giữ một task/một PR nhỏ, mục tiêu dưới khoảng 500 dòng diff khi thực tế cho phép.
- MUST NOT push thẳng `main`.
- Agent MUST NOT tự commit, push, merge, gửi message hoặc thay đổi hệ thống ngoài repository nếu người dùng chưa yêu cầu rõ trong lượt hiện tại.
- MUST bảo toàn thay đổi chưa liên quan của người dùng trong dirty worktree.

## Thay đổi contract hoặc vùng chung

- MUST ghi rõ producer/consumer, version, khả năng tương thích và rollout plan.
- Nếu chưa có `contracts/`, chỉ tạo khi task cho phép và cập nhật cấu trúc/ownership trong cùng change.
- Thay đổi port, topic, database, Keycloak client hoặc Fabric identifier MUST cập nhật `05-registry.md` cùng change.
