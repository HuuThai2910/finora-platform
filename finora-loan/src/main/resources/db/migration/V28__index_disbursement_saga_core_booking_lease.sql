-- Worker giải ngân (DisbursementSagaRepository.findDueIds) quét saga chờ ghi core, chờ retry và saga đang ghi core
-- đã hết lease (worker trước chết giữa chừng; next_retry_at là hạn lease). Index cũ chỉ phủ hai trạng thái đầu nên
-- truy vấn có CORE_BOOKING không dùng được index. Saga REPAIR_REQUIRED/COMPLETED/PAYMENT_FAILED có next_retry_at NULL
-- và nằm ngoài predicate, nên không chiếm chỗ trong lô 20 bản ghi mỗi lượt quét.
-- Không dùng CONCURRENTLY: Flyway chạy migration trong transaction và bảng saga nhỏ (một dòng mỗi khoản giải ngân).
CREATE INDEX idx_loan_disbursement_saga_worker_due
    ON loan_disbursement_sagas (next_retry_at, id)
    WHERE status IN ('CORE_BOOKING_PENDING', 'RETRY_PENDING', 'CORE_BOOKING');

DROP INDEX IF EXISTS idx_loan_disbursement_saga_due;
