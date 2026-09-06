package com.finora.loan.domain.pricing;

import java.math.BigDecimal;

/** Kết quả định giá được lưu cùng hồ sơ để tái hiện đúng điều khoản đã trình cho người vay. */
public record RiskBasedPricingResult(
        String creditGrade,
        BigDecimal configuredAdjustmentPercentagePoints,
        BigDecimal appliedAdjustmentPercentagePoints,
        BigDecimal finalAnnualInterestRate,
        String pricingPolicyVersion
) {
}
