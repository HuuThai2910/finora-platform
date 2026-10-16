package com.finora.user.domain;

/**
 * Kết quả kiểm tra khuôn mặt được lưu vào lượt xác minh ({@code ekyc_attempts.liveness_result}).
 * <p>
 * {@code RETAKE} của AI (video không phân tích được) không được lưu: nó không phải kết luận về người
 * dùng nên không ghi đè kết quả trước đó.
 */
public enum EkycLivenessResult {
    PASS,
    REVIEW,
    FAIL;

    /** Liveness đủ điều kiện để người dùng bấm xác nhận. */
    public boolean allowsConfirmation() {
        return this == PASS || this == REVIEW;
    }
}
