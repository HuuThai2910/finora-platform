package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.PartialPrepaymentQuote;
import com.finora.payment.domain.repayment.PartialPrepaymentQuoteStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PartialPrepaymentQuoteResponse(UUID quoteId, Long loanApplicationId, String currency,
        LocalDate transactionDate, BigDecimal scheduledDue, BigDecimal prepaidPrincipal,
        BigDecimal coreAmount, BigDecimal platformFee, BigDecimal totalAmount,
        BigDecimal outstandingPrincipalBefore, LocalDate nextDueDateBefore,
        BigDecimal nextDueAmountBefore, BigDecimal feeRate, String policyVersion,
        String allocationStrategy, PartialPrepaymentQuoteStatus status, Instant expiresAt) {
    public static PartialPrepaymentQuoteResponse from(PartialPrepaymentQuote value, Instant now) {
        PartialPrepaymentQuoteStatus visible = value.isExpired(now)
                ? PartialPrepaymentQuoteStatus.EXPIRED : value.getStatus();
        return new PartialPrepaymentQuoteResponse(value.getQuoteId(), value.getLoanApplicationId(),
                value.getCurrency(), value.getTransactionDate(), value.getScheduledDue(),
                value.getPrepaidPrincipal(), value.getCoreAmount(), value.getPlatformFee(),
                value.getTotalAmount(), value.getOutstandingPrincipalBefore(),
                value.getNextDueDateBefore(), value.getNextDueAmountBefore(), value.getFeeRate(),
                value.getPolicyVersion(), value.getAllocationStrategy(), visible, value.getExpiresAt());
    }
}
