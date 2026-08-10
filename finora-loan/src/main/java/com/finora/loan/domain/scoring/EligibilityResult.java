package com.finora.loan.domain.scoring;

public enum EligibilityResult {
    ELIGIBLE,
    RETRY_PENDING,
    INELIGIBLE,
    DEPENDENCY_UNAVAILABLE,
    INVALID_PROFILE
}
