package com.finora.payment.dto.response;

import com.finora.payment.domain.topup.PaymentTopUp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TopUpResponse(
        UUID topUpId,
        BigDecimal amount,
        String currency,
        String provider,
        String providerOrderId,
        String providerReference,
        String checkoutUrl,
        String qrPayload,
        String status,
        String errorCode,
        String errorDetail,
        Instant expiresAt,
        Instant completedAt,
        boolean mockCompletionAvailable
) {
    public static TopUpResponse from(PaymentTopUp topUp) {
        return new TopUpResponse(
                topUp.getTopUpId(), topUp.getAmount(), topUp.getCurrency(), topUp.getProvider(),
                topUp.getProviderOrderId(), topUp.getProviderReference(), topUp.getCheckoutUrl(),
                topUp.getQrPayload(), topUp.getStatus().name(), topUp.getErrorCode(), topUp.getErrorDetail(),
                topUp.getExpiresAt(), topUp.getCompletedAt(),
                "MOCK".equals(topUp.getProvider())
                        && topUp.getStatus() == com.finora.payment.domain.topup.TopUpStatus.AWAITING_PAYMENT);
    }
}
