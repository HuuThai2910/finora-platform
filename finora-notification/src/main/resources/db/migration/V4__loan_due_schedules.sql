-- R7 (W3F, D64): kỳ đến hạn tiếp theo của từng khoản vay để nhắc người vay trước ngày đến hạn.
-- Nguồn: RepaymentDistributed.v1 (nextDueDate/nextDueAmount, đã có), LoanDisbursed/LoanRescheduled (field optional,
-- chờ producer). held_* là khoản trả kỳ trước hạn Payment đang giữ (D16): có thì không nhắc kỳ đó.
CREATE TABLE notification_due_schedules (
    loan_application_id BIGINT PRIMARY KEY,
    borrower_id VARCHAR(100) NOT NULL,
    next_due_date DATE,
    next_due_amount DECIMAL(18, 2),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    source_event_id UUID NOT NULL,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    held_due_date DATE,
    held_repayment_id UUID,
    held_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_notification_due_schedules_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_notification_due_schedules_amount CHECK (next_due_amount IS NULL OR next_due_amount >= 0)
);

-- Worker nhắc lịch: WHERE status = 'ACTIVE' AND next_due_date = :date ORDER BY loan_application_id, theo lô.
CREATE INDEX idx_notification_due_schedules_status_due
    ON notification_due_schedules (status, next_due_date, loan_application_id);
