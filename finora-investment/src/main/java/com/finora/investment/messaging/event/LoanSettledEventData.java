package com.finora.investment.messaging.event;

import java.time.Instant;

public record LoanSettledEventData(Long loanApplicationId, String loanNumber, String contractNumber,
        String borrowerId, Long fineractLoanId, Instant settledAt) {}
