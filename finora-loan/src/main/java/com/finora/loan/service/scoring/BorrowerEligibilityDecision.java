package com.finora.loan.service.scoring;

import com.finora.loan.domain.scoring.EligibilityResult;

public record BorrowerEligibilityDecision(EligibilityResult result, String reasonCode) {
}
