package com.finora.common.enums.user;

/**
 * Kết quả chi tiết của một lần gọi eKYC (quét thẻ, kiểm tra khuôn mặt, xác nhận).
 * <p>
 * Tách khỏi {@link EkycStatus}: {@code EkycStatus} là trạng thái bền vững của hồ sơ,
 * còn mã này mô tả <b>lần gọi vừa rồi</b> dừng ở bước nào để client hiển thị đúng
 * hướng dẫn. Tên mã là hợp đồng với mobile/web (EKYC-002 mục 3.6): chỉ được thêm mã
 * mới, không đổi tên hay đổi nghĩa mã cũ.
 */
public enum EkycResultCode {

    /**
     * Đã đọc xong hai mặt thẻ, thông tin nằm ở bản nháp chờ bước kiểm tra khuôn mặt
     * và người dùng xác nhận — hồ sơ CHƯA được ghi.
     */
    DRAFT_READY,

    /** Bản nháp đã hết hạn hoặc không tồn tại — phải quét lại thẻ từ đầu. */
    DRAFT_EXPIRED,

    /** Hồ sơ đã chuyển sang {@link EkycStatus#VERIFIED} (hoặc đã xác minh từ trước). */
    VERIFIED,

    /** Không đọc được ảnh thẻ (ảnh hỏng hoặc AI từ chối ảnh) — yêu cầu chụp lại. */
    OCR_FAILED,

    /** Số định danh đọc được khác số đã có trong hồ sơ. */
    ID_MISMATCH,

    /** Số định danh trên thẻ đã thuộc tài khoản khác — mỗi số chỉ gắn một tài khoản. */
    ID_TAKEN,

    /** Gọi lại quá nhanh — chặn spam vì mỗi lần phân tích tốn tài nguyên AI. */
    RATE_LIMITED,

    /** Không gọi được AI service; trạng thái hồ sơ giữ nguyên để người dùng thử lại. */
    AI_UNAVAILABLE,

    /** Cần chụp lại thẻ hoặc quay lại video; lý do cụ thể nằm ở {@code retake}/{@code liveness.reasons}. */
    RETAKE,

    /** Thẻ đã hết hạn theo ngày Việt Nam. */
    CARD_EXPIRED,

    /** Chứng minh nhân dân hoặc loại giấy tờ không nhận diện được. */
    CARD_UNSUPPORTED,

    /** Người dùng chưa đủ 18 tuổi. */
    UNDERAGE,

    /** Chưa có sự đồng ý còn hiệu lực với phiên bản điều khoản eKYC hiện hành. */
    CONSENT_REQUIRED,

    /** Kho lưu bằng chứng đang tắt hoặc lỗi — không được xác minh khi không lưu được bằng chứng. */
    STORAGE_UNAVAILABLE,

    /** Hồ sơ đã gửi và đang chờ nhân viên duyệt tay. */
    REVIEW_PENDING,

    /** Xác nhận khi chưa qua bước kiểm tra khuôn mặt. */
    LIVENESS_REQUIRED,

    /** Phiên quay video (thử thách) không tồn tại, sai hoặc đã hết hạn. */
    CHALLENGE_EXPIRED,

    /** Kiểm tra khuôn mặt đạt. */
    LIVENESS_PASSED,

    /** Kiểm tra khuôn mặt ở vùng xám — được đi tiếp nhưng hồ sơ sẽ được duyệt thêm. */
    LIVENESS_REVIEW,

    /** Không làm đúng động tác hoặc video không nhất quán (tính là một lần trượt). */
    LIVENESS_FAILED,

    /** Khuôn mặt trong video không khớp ảnh chân dung trên thẻ (tính là một lần trượt). */
    FACE_MISMATCH
}
