package com.finora.user.domain;

/**
 * Kết quả chi tiết của một lần gọi eKYC.
 * <p>
 * Tách khỏi {@link EkycStatus}: {@code EkycStatus} là trạng thái bền vững của hồ sơ,
 * còn mã này mô tả **lần gọi vừa rồi** dừng ở bước nào để client hiển thị đúng
 * hướng dẫn và cho người dùng chụp lại.
 */
public enum EkycResultCode {

    /**
     * OCR xong, thông tin nằm ở bản nháp chờ người dùng soát — hồ sơ CHƯA lưu.
     * Người dùng xác nhận qua {@code ekyc-confirm} thì mới ghi vào hồ sơ.
     */
    DRAFT_READY,

    /** Bản nháp đã hết hạn hoặc không tồn tại — phải quét lại từ đầu. */
    DRAFT_EXPIRED,

    /** Người dùng đã xác nhận, hồ sơ chuyển sang {@link EkycStatus#VERIFIED}. */
    VERIFIED,

    /** OCR không đọc được số CCCD trên ảnh mặt trước — yêu cầu chụp lại rõ hơn. */
    OCR_FAILED,

    /** Số CCCD đọc được khác số CCCD đã có trong hồ sơ. */
    ID_MISMATCH,

    /** Số CCCD trên ảnh đã được tài khoản khác đăng ký — mỗi CCCD một tài khoản. */
    ID_TAKEN,

    /** Gọi lại quá nhanh — chặn spam vì mỗi lần xác minh tốn OCR. */
    RATE_LIMITED,

    /** Không gọi được AI service; trạng thái hồ sơ giữ nguyên để người dùng thử lại. */
    AI_UNAVAILABLE
}
