-- D16 (Wave 2C): khoản trả kỳ trước ngày đến hạn của khoản vay core V4 (lãi theo dư nợ và số ngày thực tế) được giữ ở
-- CLEARING:REPAYMENT và ghi Fineract đúng ngày đến hạn, nên lãi của kỳ không đổi. post_on là ngày ghi core; NULL nghĩa là
-- ghi ngay như trước. Người vay hủy được khoản đang giữ trước ngày ghi nhận (tiền hoàn về ví, trạng thái CANCELLED).
-- Chỉ thêm cột và nới ràng buộc: bản Payment cũ vẫn đọc/ghi được bảng này.

ALTER TABLE payment_repayments
    ADD COLUMN post_on DATE;

ALTER TABLE payment_repayments
    DROP CONSTRAINT ck_payment_repayments_status;

ALTER TABLE payment_repayments
    ADD CONSTRAINT ck_payment_repayments_status CHECK (status IN (
        'COLLECTED','CORE_POSTING','CORE_POSTED','COMPLETED','RECONCILIATION_REQUIRED','FAILED','CANCELLED'
    ));

ALTER TABLE payment_repayments
    ADD CONSTRAINT ck_payment_repayments_post_on CHECK (
        post_on IS NULL OR (repayment_type = 'SCHEDULED' AND post_on > transaction_date)
    );

-- Tra cứu khoản đã thu nhưng chưa ghi xong core của một khoản vay (chặn trả hai lần, chặn báo giá khi còn khoản chờ).
CREATE INDEX idx_payment_repayments_loan_in_flight
    ON payment_repayments (loan_application_id, created_at, id)
    WHERE status IN ('COLLECTED','CORE_POSTING','RECONCILIATION_REQUIRED');
