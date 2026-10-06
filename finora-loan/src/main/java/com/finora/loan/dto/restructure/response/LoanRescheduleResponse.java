package com.finora.loan.dto.restructure.response;

import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.domain.restructure.LoanRescheduleType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanRescheduleResponse(
        UUID requestId,
        String loanNumber,
        LoanRescheduleType requestType,
        LocalDate rescheduleFromDate,
        LocalDate adjustedDueDate,
        Integer extraTerms,
        String reasonComment,
        String termsVersion,
        LoanRescheduleStatus status,
        LocalDate originalMaturityDate,
        LocalDate newMaturityDate,
        String decisionComment,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt
) {}

