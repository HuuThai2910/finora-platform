-- =====================================================================
-- V8 (web quản trị đợt 2): lý do khóa tài khoản và tìm khách hàng.
-- Keycloak vẫn là nguồn sự thật của việc bật/tắt tài khoản; các cột dưới chỉ lưu
-- lý do, thời điểm và người khóa để màn quản trị hiện lại. Mở khóa thì xoá về NULL.
-- Cột mới đều nullable nên bản service cũ vẫn chạy được trên schema mới.
-- =====================================================================

ALTER TABLE user_profiles
    ADD COLUMN lock_reason VARCHAR(500),
    ADD COLUMN locked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN locked_by_keycloak_id UUID;

-- Tìm theo số điện thoại / CCCD tra đúng giá trị HMAC, đã có index qua ràng buộc unique ở V1;
-- email và họ tên tìm theo chuỗi con (bảng nhỏ, quét tuần tự chấp nhận được).
