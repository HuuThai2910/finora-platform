package com.finora.loan.service.pricing;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.pricing.RiskBasedPricingResult;

public interface RiskBasedPricingService {

    /** Tính lãi cuối từ base rate và hạng AI, sau đó chặn trong min/max của Product snapshot. */
    RiskBasedPricingResult calculate(LoanApplication application, String creditGrade);
}
