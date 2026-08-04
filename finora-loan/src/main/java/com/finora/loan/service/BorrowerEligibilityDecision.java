package com.finora.loan.service;

import com.finora.loan.domain.EligibilityResult;

public record BorrowerEligibilityDecision(EligibilityResult result, String reasonCode) {
}
