package com.finora.user.domain;

/**
 * Vòng đời một lượt xác minh eKYC ({@code ekyc_attempts.status}).
 * <pre>
 * IN_PROGRESS ──► VERIFIED        (giấy tờ AUTO_OK + liveness PASS, người dùng xác nhận)
 *      │      ──► MANUAL_REVIEW ──► APPROVED | REJECTED   (nhân viên quyết định)
 *      └──────► ABANDONED         (quét lại, bỏ dở quá 24 giờ, upload lỗi, rút đồng ý)
 * </pre>
 * Rút lại đồng ý cũng đưa lượt {@code MANUAL_REVIEW} chưa duyệt về {@code ABANDONED}, vì bằng chứng
 * và bản nháp của nó đã bị xoá nên không còn gì để duyệt.
 */
public enum EkycAttemptStatus {
    IN_PROGRESS,
    VERIFIED,
    MANUAL_REVIEW,
    APPROVED,
    REJECTED,
    ABANDONED
}
