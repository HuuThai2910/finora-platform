package com.finora.loan.config;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình định giá theo hạng; đơn vị điều chỉnh là điểm phần trăm, không phải tỷ lệ thập phân. */
@ConfigurationProperties(prefix = "finora.loan.risk-pricing")
public record RiskBasedPricingProperties(
        String policyVersion,
        BigDecimal legalMaximumAnnualRate,
        Map<String, BigDecimal> gradeAdjustments
) {
    public RiskBasedPricingProperties {
        policyVersion = policyVersion == null || policyVersion.isBlank() ? "RISK_PRICING_V1" : policyVersion.trim();
        legalMaximumAnnualRate = legalMaximumAnnualRate == null
                ? new BigDecimal("20.0000")
                : legalMaximumAnnualRate;
        if (legalMaximumAnnualRate.signum() <= 0) {
            throw new IllegalArgumentException("Trần lãi suất tuân thủ phải lớn hơn 0");
        }
        gradeAdjustments = gradeAdjustments == null || gradeAdjustments.isEmpty()
                ? Map.of(
                        "A", new BigDecimal("-0.5000"),
                        "B", BigDecimal.ZERO,
                        "C", new BigDecimal("0.5000"),
                        "D", new BigDecimal("1.0000"),
                        "E", new BigDecimal("1.0000")
                )
                : Map.copyOf(gradeAdjustments);
    }
}
