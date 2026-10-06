package com.finora.loan.messaging.event;

import java.time.Instant;
import java.time.LocalDate;

public record LoanDelinquencyChangedEventData(Long loanApplicationId, String loanNumber,
        String borrowerId, Long fineractLoanId, int previousDaysPastDue, int daysPastDue,
        int previousDebtGroup, int debtGroup, String overdueAmount, LocalDate overdueSince,
        String totalOutstanding, Instant dataAsOf) {}
