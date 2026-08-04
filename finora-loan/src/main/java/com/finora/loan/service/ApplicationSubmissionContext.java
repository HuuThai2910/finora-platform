package com.finora.loan.service;

import com.finora.loan.domain.RepaymentMethod;

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
