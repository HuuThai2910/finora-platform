package com.finora.loan.domain;

public enum LoanApplicationStatus {
    SUBMITTED,
    ELIGIBILITY_PENDING,
    SCORING,
    SCORING_RETRY_PENDING,
    PENDING_REVIEW,
    REJECTED,
    WITHDRAWN
}
