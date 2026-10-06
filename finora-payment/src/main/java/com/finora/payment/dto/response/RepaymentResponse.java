package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.domain.repayment.PaymentRepaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RepaymentResponse(UUID repaymentId, Long loanApplicationId, PaymentRepaymentType repaymentType,
        UUID quoteId, UUID partialPrepaymentQuoteId, BigDecimal amount, BigDecimal coreAmount, BigDecimal platformFee,
        String currency, LocalDate transactionDate, PaymentRepaymentStatus status,
        Long fineractTransactionId, BigDecimal principalAmount, BigDecimal interestAmount,
        BigDecimal feeAmount, BigDecimal penaltyAmount, BigDecimal outstandingPrincipal,
        BigDecimal outstandingInterest, BigDecimal outstandingFee, BigDecimal outstandingPenalty,
        BigDecimal totalOutstanding, BigDecimal overdueAmount,
        LocalDate nextDueDate, BigDecimal nextDueAmount, String errorCode, Instant completedAt) {
    public static RepaymentResponse from(PaymentRepayment value) {
        return new RepaymentResponse(value.getRepaymentId(), value.getLoanApplicationId(), value.getRepaymentType(),
                value.getQuoteId(), value.getPartialPrepaymentQuoteId(), value.getAmount(), value.getCoreAmount(), value.getPlatformFee(),
                value.getCurrency(), value.getTransactionDate(), value.getStatus(), value.getFineractTransactionId(),
                value.getPrincipalAmount(), value.getInterestAmount(), value.getFeeAmount(), value.getPenaltyAmount(),
                value.getOutstandingPrincipal(), value.getOutstandingInterest(), value.getOutstandingFee(),
                value.getOutstandingPenalty(), value.getTotalOutstanding(), value.getOverdueAmount(),
                value.getNextDueDate(), value.getNextDueAmount(),
                value.getErrorCode(), value.getCompletedAt());
    }
}
