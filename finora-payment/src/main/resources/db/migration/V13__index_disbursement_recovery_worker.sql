-- Worker giải ngân (PaymentDisbursementRepository.findDueIds) quét lệnh mới, chờ retry, lệnh PROCESSING đã hết lease
-- và lệnh RECONCILIATION_REQUIRED đến lượt tra cứu provider; next_attempt_at là hạn lease hoặc lịch tra cứu. Index V2
-- chỉ phủ REQUESTED/RETRY_PENDING nên truy vấn mới không dùng được. Lệnh COMPLETED/FAILED và lệnh đối soát đã hết lượt
-- (next_attempt_at NULL, chờ vận hành) nằm ngoài index, không chiếm chỗ trong lô 20 bản ghi mỗi lượt quét.
-- Không dùng CONCURRENTLY: Flyway chạy migration trong transaction và bảng nhỏ (một dòng mỗi khoản giải ngân).
CREATE INDEX idx_payment_disbursement_worker_due
    ON payment_disbursements (next_attempt_at, id)
    WHERE status IN ('REQUESTED', 'RETRY_PENDING', 'PROCESSING', 'RECONCILIATION_REQUIRED');

DROP INDEX IF EXISTS idx_payment_disbursement_due;
