package com.finora.payment.dto.response;

import com.finora.payment.domain.withdrawal.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.WithdrawalStatus;
import com.finora.payment.service.withdrawal.WithdrawalBank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lệnh rút cho chính chủ ví; số tài khoản luôn ở dạng che. {@code failureReason} chỉ có khi thất bại. */
public record WithdrawalResponse(
        UUID withdrawalId,
        BigDecimal amount,
        BigDecimal feeAmount,
        String currency,
        WithdrawalStatus status,
        String bankCode,
        String bankName,
        String accountNumberMasked,
        String accountHolderName,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant completedAt
) {
    public static WithdrawalResponse from(PaymentWithdrawal value) {
        boolean failed = value.getStatus() == WithdrawalStatus.FAILED;
        return new WithdrawalResponse(value.getWithdrawalId(), value.getAmount(), value.getFeeAmount(),
                value.getCurrency(), value.getStatus(), value.getBankCode(),
                WithdrawalBank.find(value.getBankCode()).map(WithdrawalBank::displayName).orElse(value.getBankCode()),
                value.getAccountNumberMasked(), value.getAccountHolderName(),
                failed ? value.getErrorCode() : null, failed ? value.getErrorDetail() : null,
                value.getCreatedAt(), value.getCompletedAt());
    }
}
