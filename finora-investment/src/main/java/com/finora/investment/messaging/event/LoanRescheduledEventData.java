package com.finora.investment.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanRescheduledEventData(Long loanApplicationId, String loanNumber, String borrowerId,
        Long fineractLoanId, UUID requestId, String requestType, LocalDate previousMaturityDate,
        LocalDate newMaturityDate, LocalDate rescheduleFromDate, LocalDate adjustedDueDate,
        Integer extraTerms, Instant occurredAt) {}
