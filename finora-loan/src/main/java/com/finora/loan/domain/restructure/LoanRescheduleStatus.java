package com.finora.loan.domain.restructure;

public enum LoanRescheduleStatus {
    PENDING_REVIEW,
    PENDING_INVESTOR_CONSENT,
    INVESTOR_REJECTED,
    REJECTED,
    CREATE_PENDING,
    CREATING,
    APPROVAL_PENDING,
    APPROVING,
    COMPLETED,
    RECONCILIATION_REQUIRED,
    MANUAL_REVIEW
}

