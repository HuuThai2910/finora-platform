package com.finora.investment.dto.response;

import com.finora.investment.domain.InvestmentOrder;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long id,
        Long investorId,
        BigDecimal amount,
        BigDecimal remainingAmount,
        BigDecimal minRate,
        BigDecimal maxRate,
        String gradeFilter,
        String status,
        Instant createdAt
) {
    public static OrderResponse from(InvestmentOrder o) {
        return new OrderResponse(
                o.getId(), o.getInvestorId(),
                o.getAmount(), o.getRemainingAmount(),
                o.getMinRate(), o.getMaxRate(), o.getGradeFilter(),
                o.getStatus().name(), o.getCreatedAt());
    }
}
