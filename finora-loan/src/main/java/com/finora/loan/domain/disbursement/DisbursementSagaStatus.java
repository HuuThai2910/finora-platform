package com.finora.loan.domain.disbursement;

public enum DisbursementSagaStatus {
    WAITING_PAYMENT,
    CORE_BOOKING_PENDING,
    CORE_BOOKING,
    RETRY_PENDING,
    REPAIR_REQUIRED,
    COMPLETED,
    PAYMENT_FAILED
}

