package com.finora.loan.messaging.event;

import com.finora.loan.domain.restructure.LoanRescheduleType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Contract liên service không mang reasonComment vì đó có thể là dữ liệu nhạy cảm tự do. */
public record LoanRescheduledEventData(
        Long loanApplicationId,
        String loanNumber,
        String borrowerId,
        Long fineractLoanId,
        UUID requestId,
        LoanRescheduleType requestType,
        LocalDate previousMaturityDate,
        LocalDate newMaturityDate,
        LocalDate rescheduleFromDate,
        LocalDate adjustedDueDate,
        Integer extraTerms,
        Instant occurredAt
) {}
