package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.domain.repayment.PaymentRepaymentType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Khoản trả nợ cần vận hành. {@code corePostingDate} là ngày sẽ gửi Fineract nếu ghi lại (ngày đến hạn với khoản được giữ,
 * D16); {@code coreRepostAvailableAt} là lúc sớm nhất được ghi lại (B32, D28), {@code null} khi không ở trạng thái đối soát.
 */
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
        Instant updatedAt,
        LocalDate corePostingDate,
        int coreRepostCount,
        String lastCoreRepostBy,
        Instant lastCoreRepostAt,
        Instant coreRepostAvailableAt
) {
    public static RepaymentReconciliationResponse from(PaymentRepayment value, Duration repostSafeAfter) {
        return new RepaymentReconciliationResponse(value.getRepaymentId(), value.getLoanApplicationId(),
                value.getBorrowerId(), value.getFineractLoanId(), value.getRepaymentType(), value.getStatus(),
                value.getAmount(), value.getCurrency(), value.getTransactionDate(),
                value.getWalletLedgerTransactionId(), value.getFineractTransactionId(), value.getAttemptCount(),
                value.getErrorCode(), value.getErrorDetail(), value.getUpdatedAt(), value.corePostingDate(),
                value.getCoreRepostCount(), value.getLastCoreRepostBy(), value.getLastCoreRepostAt(),
                value.coreRepostAvailableAt(repostSafeAfter));
    }
}
