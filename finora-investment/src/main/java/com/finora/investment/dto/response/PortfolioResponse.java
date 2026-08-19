package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.util.Map;

public record PortfolioResponse(
        Long investorId,
        BigDecimal totalInvested,
        int noteCount,
        Map<String, BigDecimal> allocationByGrade
) {}
