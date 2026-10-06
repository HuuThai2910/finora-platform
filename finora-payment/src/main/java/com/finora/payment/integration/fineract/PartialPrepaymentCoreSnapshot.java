package com.finora.payment.integration.fineract;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PartialPrepaymentCoreSnapshot(LocalDate transactionDate, BigDecimal scheduledDue,
        BigDecimal payoffAmount, BigDecimal outstandingPrincipal, LocalDate nextDueDate,
        BigDecimal nextDueAmount, int originalTermMonths, LocalDate disbursedDate,
        LocalDate maturityDate) {}
