package com.finora.loan.dto.application.response;

import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;

public record LoanProductSnapshotResponse(
        String code,
        String name,
        Long configurationVersion,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTermMonths,
        Integer maxTermMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        Long fineractProductId,
        Long coreMappingId,
        String coreConfigVersion
) {
}
