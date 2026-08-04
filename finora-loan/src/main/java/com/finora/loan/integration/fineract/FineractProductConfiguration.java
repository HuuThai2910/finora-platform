package com.finora.loan.integration.fineract;

import com.finora.loan.domain.RepaymentMethod;

import java.math.BigDecimal;

public record FineractProductConfiguration(
        Long loanProductId,
        Long finoraProductVersion,
        String code,
        String name,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTermMonths,
        Integer maxTermMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        String externalId,
        String configVersion
) {
}
