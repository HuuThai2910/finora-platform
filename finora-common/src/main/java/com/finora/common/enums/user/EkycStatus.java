package com.finora.common.enums.user;

/**
 * Trạng thái eKYC bền vững của hồ sơ người dùng; giá trị {@code VERIFIED} được phát thành claim
 * {@code ekyc_status} để các service khác cho phép vay/đầu tư.
 * <ul>
 *   <li>{@link #PENDING}: chưa xác minh, hoặc đã rút lại sự đồng ý eKYC.</li>
 *   <li>{@link #VERIFIED}: giấy tờ và khuôn mặt đã được hệ thống hoặc nhân viên xác nhận.</li>
 *   <li>{@link #FAILED}: nhân viên từ chối hồ sơ; người dùng được làm lại.</li>
 *   <li>{@link #MANUAL_REVIEW}: hồ sơ đã gửi, chờ nhân viên duyệt tay.</li>
 * </ul>
 */
public enum EkycStatus {
    PENDING,
    VERIFIED,
    FAILED,
    MANUAL_REVIEW
}
