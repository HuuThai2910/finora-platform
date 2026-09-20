package com.finora.investment.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kết quả yêu cầu giữ tiền từ finora-payment.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentHoldResult {
    private boolean success;
    private String holdReference;
    private String errorCode;
    private String errorMessage;
    private boolean retryable;

    public static PaymentHoldResult ok(String holdReference) {
        return PaymentHoldResult.builder()
                .success(true)
                .holdReference(holdReference)
                .build();
    }

    public static PaymentHoldResult rejected(String errorCode, String errorMessage) {
        return PaymentHoldResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    public static PaymentHoldResult unavailable(String errorMessage) {
        return PaymentHoldResult.builder()
                .success(false)
                .errorCode("PAYMENT_UNAVAILABLE")
                .errorMessage(errorMessage)
                .retryable(true)
                .build();
    }
}
