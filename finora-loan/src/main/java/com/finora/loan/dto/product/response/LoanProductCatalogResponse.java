package com.finora.loan.dto.product.response;

import com.finora.loan.domain.product.RepaymentMethod;
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
