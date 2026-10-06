package com.finora.payment.integration.fineract;

import java.math.BigDecimal;

public record ScheduledRepaymentCoreQuote(BigDecimal amount, BigDecimal overdueAmount) {}
