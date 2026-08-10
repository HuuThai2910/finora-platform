package com.finora.loan.dto.response;

import com.finora.loan.domain.RepaymentMethod;

import java.math.BigDecimal;

public record LoanProductCatalogResponse(
        Long id,
        String code,
        String name,
        String description,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTermMonths,
        Integer maxTermMonths,
        BigDecimal annualInterestRate,
        String interestRateUnit,
        RepaymentMethod repaymentMethod,
        String rateNotice
) {
}
