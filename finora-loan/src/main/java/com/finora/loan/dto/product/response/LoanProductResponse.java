package com.finora.loan.dto.product.response;

import com.finora.loan.domain.product.CoreSyncStatus;
import com.finora.loan.domain.product.LoanProductStatus;
import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;

public record LoanProductResponse(
        Long id,
        String code,
        String name,
        String description,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTermMonths,
        Integer maxTermMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        LoanProductStatus status,
        CoreSyncStatus coreSyncStatus,
        Long currentCoreMappingId,
        Long configurationVersion,
        Long version,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
