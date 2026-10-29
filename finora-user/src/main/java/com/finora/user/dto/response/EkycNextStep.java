package com.finora.user.dto.response;

/** Bước client làm tiếp sau một lần gọi eKYC. */
public enum EkycNextStep {
    /** Xin thử thách và quay video kiểm tra khuôn mặt. */
    LIVENESS,
    /** Soát bản nháp rồi gọi {@code ekyc-confirm}. */
    CONFIRM,
    /** Không có bước tiếp trong phiên này (đã xong, chờ duyệt, hoặc phải làm lại từ đầu). */
    NONE
}
