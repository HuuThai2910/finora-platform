-- B32 (Wave 2D, D28): admin ghi lại vào Fineract một khoản trả nợ RECONCILIATION_REQUIRED khi tra cứu theo externalId xác nhận
-- Fineract chưa có giao dịch. Ghi lại dùng đúng externalId, số tiền và ngày ghi nhận ban đầu, nên không thể thành hai giao
-- dịch. Lưu số lần, ai yêu cầu và lúc nào. Chỉ thêm cột có mặc định: bản Payment cũ vẫn đọc/ghi được bảng.
ALTER TABLE payment_repayments
    ADD COLUMN core_repost_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_core_repost_by VARCHAR(100),
    ADD COLUMN last_core_repost_at TIMESTAMPTZ;

ALTER TABLE payment_repayments
    ADD CONSTRAINT ck_payment_repayments_core_repost CHECK (
        core_repost_count >= 0 AND ((last_core_repost_at IS NULL) = (last_core_repost_by IS NULL))
    );
