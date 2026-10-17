-- R3 (Wave 2D): đối soát lệnh góp vốn với khoản giữ tiền của Payment. Worker làm tiếp lệnh chờ giữ tiền và nhả khoản giữ
-- của lệnh đã kết thúc theo lịch lùi dần (trước đây quét lại mãi 50 dòng đầu theo id, dòng lỗi chặn dòng mới), và dừng ở
-- trạng thái cần vận hành khi Payment trả lỗi chắc chắn (khoản giữ đã vào giải ngân, không tìm thấy, lệch mã lệnh).
-- Chỉ thêm cột có mặc định: bản Investment cũ vẫn đọc/ghi được bảng.
ALTER TABLE investment_orders
    ADD COLUMN pending_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pending_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN release_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN release_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN release_error_code VARCHAR(80),
    ADD COLUMN release_blocked_at TIMESTAMPTZ;

ALTER TABLE investment_orders
    ADD CONSTRAINT ck_investment_orders_hold_reconciliation CHECK (
        pending_attempt_count >= 0 AND release_attempt_count >= 0
        AND (release_blocked_at IS NULL OR release_error_code IS NOT NULL)
    );

-- Hàng đợi nhả khoản giữ theo lượt thử kế tiếp, thay index cũ chỉ theo id (V12).
DROP INDEX ix_order_release_pending;
CREATE INDEX ix_order_release_due
    ON investment_orders (release_next_attempt_at, id)
    WHERE status IN ('CANCELLED', 'REJECTED')
      AND payment_hold_reference IS NOT NULL AND payment_released_at IS NULL AND release_blocked_at IS NULL;

-- Hàng đợi làm tiếp lệnh chờ giữ tiền.
CREATE INDEX ix_order_pending_due
    ON investment_orders (pending_next_attempt_at, updated_at, id)
    WHERE status = 'PENDING_FUNDS';
