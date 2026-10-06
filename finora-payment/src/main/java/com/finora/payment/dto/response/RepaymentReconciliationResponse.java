package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.domain.repayment.PaymentRepaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RepaymentReconciliationResponse(
        UUID repaymentId,
        Long loanApplicationId,
        String borrowerId,
        Long fineractLoanId,
        PaymentRepaymentType repaymentType,
        PaymentRepaymentStatus status,
        BigDecimal amount,
        String currency,
        LocalDate transactionDate,
        UUID walletLedgerTransactionId,
        Long fineractTransactionId,
        int attemptCount,
        String errorCode,
        String errorDetail,
        Instant updatedAt
) {
    public static RepaymentReconciliationResponse from(PaymentRepayment value) {
        return new RepaymentReconciliationResponse(value.getRepaymentId(), value.getLoanApplicationId(),
                value.getBorrowerId(), value.getFineractLoanId(), value.getRepaymentType(), value.getStatus(),
                value.getAmount(), value.getCurrency(), value.getTransactionDate(),
                value.getWalletLedgerTransactionId(), value.getFineractTransactionId(), value.getAttemptCount(),
                value.getErrorCode(), value.getErrorDetail(), value.getUpdatedAt());
    }
}
