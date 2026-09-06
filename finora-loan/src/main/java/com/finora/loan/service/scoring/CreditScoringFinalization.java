package com.finora.loan.service.scoring;

import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.domain.pricing.RiskBasedPricingResult;

/** Dữ liệu scalar chuẩn bị trong DB rồi mang ra ngoài transaction để gọi Fineract. */
public record CreditScoringFinalization(
        RiskBasedPricingResult pricing,
        String scheduleRequestId,
        ScheduleCalculationRequest scheduleRequest,
        boolean scheduleRequired
) {
}
