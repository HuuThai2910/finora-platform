package com.finora.investment.dto.response;

import com.finora.investment.domain.AutoInvestConfig;

import java.math.BigDecimal;
import java.time.Instant;

public record AutoInvestResponse(
        Long id,
        Long investorId,
        String gradeFilter,
        BigDecimal minRate,
        BigDecimal maxRate,
        Integer minTerm,
        Integer maxTerm,
        BigDecimal amountPerNote,
        BigDecimal totalBudget,
        BigDecimal remainingBudget,
        boolean isActive,
        Instant createdAt
) {
    public static AutoInvestResponse from(AutoInvestConfig c) {
        return new AutoInvestResponse(
                c.getId(), c.getInvestorId(), c.getGradeFilter(),
                c.getMinRate(), c.getMaxRate(), c.getMinTerm(), c.getMaxTerm(),
                c.getAmountPerNote(), c.getTotalBudget(), c.getRemainingBudget(),
                c.getIsActive(), c.getCreatedAt());
    }
}
