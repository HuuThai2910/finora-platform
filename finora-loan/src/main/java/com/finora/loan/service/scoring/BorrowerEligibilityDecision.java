package com.finora.loan.service.scoring;

import com.finora.loan.domain.scoring.enums.EligibilityResult;

public record BorrowerEligibilityDecision(EligibilityResult result, String reasonCode) {
}
