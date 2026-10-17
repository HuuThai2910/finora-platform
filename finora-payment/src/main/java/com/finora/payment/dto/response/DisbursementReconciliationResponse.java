package com.finora.payment.dto.response;

import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.disbursement.PaymentDisbursementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lệnh giải ngân cho màn sự cố của admin (R2). {@code nextAttemptAt = null} ở RECONCILIATION_REQUIRED nghĩa là đã hết lượt
 * tra cứu tự động và đang chờ vận hành. {@code attemptCount} là số lần gọi provider chuyển tiền,
 * {@code reconciliationAttemptCount} là số lần tra cứu chưa ra kết quả (tra cứu thấy kết quả ngay thì vẫn 0);
 * {@code errorCode} giữ lý do vào đối soát kể cả sau khi đã xong, ví dụ {@code PAYMENT_PROCESSING_INTERRUPTED}.
 */
public record DisbursementReconciliationResponse(
        UUID sagaId,
        Long loanApplicationId,
        String contractNumber,
        BigDecimal amount,
        String currency,
        String provider,
        String providerReference,
        PaymentDisbursementStatus status,
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
    public static DisbursementReconciliationResponse from(PaymentDisbursement value) {
        return new DisbursementReconciliationResponse(value.getSagaId(), value.getLoanApplicationId(),
                value.getContractNumber(), value.getAmount(), value.getCurrency(), value.getProvider(),
                value.getProviderReference(), value.getStatus(), value.getAttemptCount(),
                value.getReconciliationAttemptCount(), value.getNextAttemptAt(), value.getErrorCode(),
                value.getErrorDetail(), value.getManualRecheckCount(), value.getLastManualRecheckBy(),
                value.getLastManualRecheckAt(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
