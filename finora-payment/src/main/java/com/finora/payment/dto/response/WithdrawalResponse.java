package com.finora.payment.dto.response;

import com.finora.payment.domain.withdrawal.entity.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.enums.WithdrawalStatus;

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
    /** {@code bankName} lấy từ danh bạ ngân hàng của Payment theo {@code bankCode} đã lưu. */
    public static WithdrawalResponse from(PaymentWithdrawal value, String bankName) {
        boolean failed = value.getStatus() == WithdrawalStatus.FAILED;
        return new WithdrawalResponse(value.getWithdrawalId(), value.getAmount(), value.getFeeAmount(),
                value.getCurrency(), value.getStatus(), value.getBankCode(),
                bankName,
                value.getAccountNumberMasked(), value.getAccountHolderName(),
                failed ? value.getErrorCode() : null, failed ? value.getErrorDetail() : null,
                value.getCreatedAt(), value.getCompletedAt());
    }
}
