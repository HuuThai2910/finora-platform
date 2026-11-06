package com.finora.loan.domain.scoring.enums;

public enum EligibilityResult {
    ELIGIBLE,
    RETRY_PENDING,
    INELIGIBLE,
    DEPENDENCY_UNAVAILABLE,
    INVALID_PROFILE
}
