package com.finora.loan.domain.application;

public enum LoanApplicationStatus {
    SUBMITTED,
    ELIGIBILITY_PENDING,
    SCORING,
    SCORING_RETRY_PENDING,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
