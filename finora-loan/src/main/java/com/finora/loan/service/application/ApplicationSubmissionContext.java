package com.finora.loan.service.application;

import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;

public record ApplicationSubmissionContext(
        Long productId,
        Long productConfigurationVersion,
        Long mappingId,
        Long fineractProductId,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod
) {
}
