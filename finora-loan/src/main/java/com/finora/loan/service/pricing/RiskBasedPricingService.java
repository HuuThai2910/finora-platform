package com.finora.loan.service.pricing;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.pricing.model.RiskBasedPricingResult;

/**
 * Định giá lãi suất theo rủi ro: lãi cuối bằng lãi cơ sở của Product cộng mức điều chỉnh theo hạng tín dụng AI.
 */
public interface RiskBasedPricingService {

    /** Tính lãi cuối từ base rate và hạng AI, sau đó chặn trong min/max của Product snapshot. */
    RiskBasedPricingResult calculate(LoanApplication application, String creditGrade);
}
