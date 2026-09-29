package com.finora.payment.domain.topup;

public enum TopUpStatus {
    PROVIDER_PENDING,
    AWAITING_PAYMENT,
    COMPLETED,
    FAILED,
    EXPIRED,
    RECONCILIATION_REQUIRED
}
