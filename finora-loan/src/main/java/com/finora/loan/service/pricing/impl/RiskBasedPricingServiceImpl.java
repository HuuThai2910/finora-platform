package com.finora.loan.service.pricing.impl;

import com.finora.loan.config.RiskBasedPricingProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.pricing.RiskBasedPricingResult;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.service.pricing.RiskBasedPricingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RiskBasedPricingServiceImpl implements RiskBasedPricingService {

    private final RiskBasedPricingProperties properties;

    /**
     * Grade chỉ chọn mức cộng/trừ đã cấu hình; Loan mới là nơi sở hữu công thức và giới hạn lãi suất.
     * Việc clamp hai lớp bảo đảm không vượt biên Product và chốt an toàn tuân thủ toàn hệ thống.
     */
    @Override
    public RiskBasedPricingResult calculate(LoanApplication application, String creditGrade) {
        BigDecimal configuredAdjustment = properties.gradeAdjustments().get(creditGrade);
        if (configuredAdjustment == null) {
            throw LoanBusinessException.conflict(
                    "PRICING_GRADE_NOT_CONFIGURED",
                    "Hạng tín dụng AI chưa có cấu hình điều chỉnh lãi suất trong Loan Service"
            );
        }
        BigDecimal baseRate = application.getAnnualInterestRateSnapshot();
        if (baseRate.compareTo(properties.legalMaximumAnnualRate()) > 0) {
            throw LoanBusinessException.conflict(
                    "LOAN_PRODUCT_RATE_EXCEEDS_CURRENT_LEGAL_LIMIT",
                    "Lãi suất cơ sở của sản phẩm vượt trần tuân thủ hiện tại; phải rà soát lại Product trước khi định giá"
            );
        }
        BigDecimal upperBound = application.getMaxAnnualInterestRateSnapshot()
                .min(properties.legalMaximumAnnualRate());
        BigDecimal rawRate = baseRate.add(configuredAdjustment);
        BigDecimal finalRate = rawRate.max(application.getMinAnnualInterestRateSnapshot())
                .min(upperBound)
                .setScale(4, RoundingMode.HALF_UP);
        return new RiskBasedPricingResult(
                creditGrade,
                configuredAdjustment.setScale(4, RoundingMode.HALF_UP),
                finalRate.subtract(baseRate).setScale(4, RoundingMode.HALF_UP),
                finalRate,
                properties.policyVersion()
        );
    }
}
