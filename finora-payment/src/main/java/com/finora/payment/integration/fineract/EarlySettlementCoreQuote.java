package com.finora.payment.integration.fineract;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EarlySettlementCoreQuote(LocalDate transactionDate, BigDecimal amount,
        BigDecimal principal, BigDecimal interest, BigDecimal fee, BigDecimal penalty,
        int originalTermMonths, LocalDate disbursedDate, LocalDate maturityDate) {
}
