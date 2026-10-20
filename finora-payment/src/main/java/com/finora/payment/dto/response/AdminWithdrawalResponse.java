package com.finora.payment.dto.response;

import com.finora.payment.domain.withdrawal.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.WithdrawalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lệnh rút cho hàng đợi sự cố của vận hành (R4). {@code nextAttemptAt = null} ở RECONCILIATION_REQUIRED là đã hết lượt
 * tra cứu tự động. {@code attemptCount} là số lần gửi provider; {@code reconciliationAttemptCount} là số lần tra cứu chưa
 * ra kết quả cuối. Số tài khoản chỉ ở dạng che.
 */
public record AdminWithdrawalResponse(
        UUID withdrawalId,
        String ownerType,
        String ownerId,
        BigDecimal amount,
        String currency,
        WithdrawalStatus status,
        String bankCode,
        String accountNumberMasked,
        String provider,
        String providerReference,
        int attemptCount,
        int reconciliationAttemptCount,
        Instant nextAttemptAt,
        String errorCode,
        String errorDetail,
        int manualRecheckCount,
        String lastManualRecheckBy,
        Instant lastManualRecheckAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminWithdrawalResponse from(PaymentWithdrawal value) {
        return new AdminWithdrawalResponse(value.getWithdrawalId(), value.getOwnerType().name(), value.getOwnerId(),
                value.getAmount(), value.getCurrency(), value.getStatus(), value.getBankCode(),
                value.getAccountNumberMasked(), value.getProvider(), value.getProviderReference(),
                value.getAttemptCount(), value.getReconciliationAttemptCount(), value.getNextAttemptAt(),
                value.getErrorCode(), value.getErrorDetail(), value.getManualRecheckCount(),
                value.getLastManualRecheckBy(), value.getLastManualRecheckAt(), value.getCreatedAt(),
                value.getUpdatedAt());
    }
}
