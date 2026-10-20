package com.finora.investment.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanRescheduleConsentRequestedEventData(
        UUID requestId, Long loanApplicationId, String loanNumber, String requestType,
        LocalDate rescheduleFromDate, LocalDate adjustedDueDate, Integer extraTerms,
        LocalDate originalMaturityDate, Instant requestedAt
) {}
