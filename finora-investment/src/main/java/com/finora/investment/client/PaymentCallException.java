package com.finora.investment.client;

import lombok.Getter;

/**
 * Lời gọi Payment không có kết quả mong muốn (R3).
 *
 * <p>{@code retryable = true}: mạng, 5xx, token hoặc giới hạn tần suất; lần sau thử lại được. {@code false}: Payment trả lỗi
 * nghiệp vụ chắc chắn ({@code PAYMENT_HOLD_NOT_RELEASABLE}, {@code PAYMENT_HOLD_NOT_FOUND}, lệch mã lệnh...): thử lại không
 * đổi được kết quả, cần vận hành xem.</p>
 */
@Getter
public class PaymentCallException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public PaymentCallException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }
}
