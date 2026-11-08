package com.finora.loan.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RepaymentCancelledEventData(UUID repaymentId, Long loanApplicationId, String borrowerId,
        LocalDate dueDate, Instant cancelledAt) {}
