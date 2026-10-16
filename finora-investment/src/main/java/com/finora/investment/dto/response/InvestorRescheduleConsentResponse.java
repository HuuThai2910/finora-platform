package com.finora.investment.dto.response;

import com.finora.investment.domain.restructure.InvestorRescheduleConsentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvestorRescheduleConsentResponse(
        UUID requestId, Long loanApplicationId, String loanNumber, String requestType,
        LocalDate rescheduleFromDate, LocalDate adjustedDueDate, Integer extraTerms,
        LocalDate originalMaturityDate, BigDecimal outstandingPrincipal,
        InvestorRescheduleConsentStatus status, String decisionComment,
        Instant decidedAt, Instant createdAt
) {}
