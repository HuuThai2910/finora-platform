package com.finora.user.domain;

/**
 * Kết quả chi tiết của một lần gọi xác minh eKYC.
 * <p>
 * Tách khỏi {@link EkycStatus}: {@code EkycStatus} là trạng thái bền vững của hồ sơ,
 * còn mã này mô tả **lần gọi vừa rồi** dừng ở bước nào để client hiển thị đúng
 * hướng dẫn và cho người dùng chụp lại.
 */
public enum EkycResultCode {

    /** Đạt toàn bộ các bước — hồ sơ chuyển sang {@link EkycStatus#VERIFIED}. */
    VERIFIED,

    /** Hồ sơ chưa có số CCCD; phải nộp qua {@code cccd-manual} trước. */
    PROFILE_NO_CCCD,

    /** Phiên challenge hết hạn, sai hoặc đã dùng — lấy challenge mới rồi quay lại. */
    CHALLENGE_EXPIRED,

    /** OCR không đọc được số CCCD trên ảnh — yêu cầu chụp lại rõ hơn. */
    OCR_FAILED,

    /** Số CCCD đọc được khác số CCCD đã khai trong hồ sơ. */
    ID_MISMATCH,

    /** Không thực hiện đúng chuỗi hành động, hoặc trượt kiểm tra texture. */
    LIVENESS_FAILED,

    /** Khuôn mặt không khớp ảnh trên CCCD. */
    FACE_MISMATCH,

    /** Gọi lại quá nhanh — chặn spam vì mỗi lần xác minh tốn OCR và nhận dạng khuôn mặt. */
    RATE_LIMITED,

    /** Không gọi được AI service; trạng thái hồ sơ giữ nguyên để người dùng thử lại. */
    AI_UNAVAILABLE
}
