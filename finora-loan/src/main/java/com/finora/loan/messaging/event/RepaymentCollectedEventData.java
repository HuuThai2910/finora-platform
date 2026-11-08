package com.finora.loan.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RepaymentCollectedEventData(UUID repaymentId, Long loanApplicationId, String borrowerId,
        String repaymentType, String amount, String currency, LocalDate dueDate, Instant collectedAt) {}
