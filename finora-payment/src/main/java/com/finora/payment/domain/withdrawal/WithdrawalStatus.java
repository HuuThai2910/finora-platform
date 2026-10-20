package com.finora.payment.domain.withdrawal;

/**
 * Vòng đời lệnh rút tiền (R4, W3B). Tiền đã rời ví từ {@link #REQUESTED}; {@link #FAILED} luôn đi kèm bút toán hoàn.
 */
public enum WithdrawalStatus {
    /** Đã trừ ví, chờ worker gửi provider. */
    REQUESTED,
    /** Worker đang gọi provider; {@code nextAttemptAt} là hạn lease. Còn ở đây sau hạn là worker đã chết giữa chừng. */
    PROCESSING,
    /** Provider đã nhận lệnh, chờ callback; {@code nextAttemptAt} là lúc tra cứu nếu callback không tới. */
    PROVIDER_PENDING,
    /** Không rõ provider đã chi hay chưa: chỉ tra cứu, không gửi lệnh chi mới. {@code nextAttemptAt = null} là chờ vận hành. */
    RECONCILIATION_REQUIRED,
    COMPLETED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
