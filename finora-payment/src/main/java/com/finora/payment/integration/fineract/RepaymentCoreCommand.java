package com.finora.payment.integration.fineract;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RepaymentCoreCommand(Long fineractLoanId, String repaymentReference,
        LocalDate transactionDate, BigDecimal amount) {}
