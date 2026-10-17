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
    /** Trạng thái khoản giữ Payment trả (R3); {@code null} khi Payment không trả kết quả. */
    private String holdStatus;

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

    /**
     * Payment trả lại khoản giữ cũ của chính mã lệnh nhưng đã được nhả (R3): lệnh không còn tiền giữ, không được cam kết.
     */
    public static PaymentHoldResult alreadyReleased(String holdReference) {
        return PaymentHoldResult.builder()
                .success(false)
                .holdReference(holdReference)
                .holdStatus("RELEASED")
                .errorCode("PAYMENT_HOLD_ALREADY_RELEASED")
                .errorMessage("Khoản giữ tiền của lệnh đã được nhả trước đó")
                .build();
    }

    /** Khoản giữ của mã lệnh đã vào giải ngân hoặc ở trạng thái lạ (R3): không cam kết, cần vận hành xem. */
    public static PaymentHoldResult notHeld(String holdReference, String holdStatus) {
        return PaymentHoldResult.builder()
                .success(false)
                .holdReference(holdReference)
                .holdStatus(holdStatus)
                .errorCode("PAYMENT_HOLD_NOT_HELD")
                .errorMessage("Khoản giữ tiền của lệnh đang ở trạng thái " + holdStatus)
                .build();
    }

    public boolean isAlreadyReleased() {
        return !success && "RELEASED".equals(holdStatus);
    }

    public boolean isHoldAnomaly() {
        return !success && holdReference != null && holdStatus != null && !"RELEASED".equals(holdStatus);
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
