# CLAUDE.md — Điểm vào cho Claude Code trong FINORA

File này phải nằm ở gốc repository để Claude Code tự động phát hiện. Luật chi tiết chỉ có một nguồn chuẩn trong `.agents/rules/`; không sao chép luật chi tiết trở lại file này.

## Thứ tự ưu tiên

1. Yêu cầu hiện tại và phạm vi được người dùng cho phép.
2. Ownership, an toàn và điều cấm trong `.agents/rules/`.
3. Các rule còn lại trong `.agents/rules/`.
4. Skill `.agents/skills/finora-engineering/SKILL.md` và reference được skill định tuyến.
5. Convention cục bộ đã tồn tại trong module.

Nếu có xung đột không thể đồng thời tuân thủ, dừng phần thay đổi bị xung đột và báo người dùng; không tự chọn luật thuận tiện hơn.

## Tài liệu bắt buộc

Trước mọi task, đọc toàn bộ [rule map](.agents/rules/00-rule-map.md) rồi đọc các file được map yêu cầu. Task có ghi/sửa/review code phải dùng skill `finora-engineering`.

Luật tối thiểu luôn áp dụng:

- Không sửa ngoài module/owner được giao hoặc ngoài phạm vi task.
- Không đọc/ghi database của service khác.
- Không commit secret, log PII, dùng float/double cho tiền hoặc sửa migration đã merge.
- Không tự `git commit`, `git push`, merge hoặc thay đổi hệ thống ngoài phạm vi nếu người dùng chưa yêu cầu rõ trong lượt hiện tại.
- Không báo hoàn thành khi chưa chạy kiểm tra phù hợp; nếu không chạy được phải nói rõ.

Khi thay đổi hệ thống rule, cập nhật nguồn chuẩn trong `.agents/rules/`, entrypoint liên quan và skill liên quan trong cùng change; chạy `.agents/scripts/validate-rules.ps1` nếu script này tồn tại.
