package com.finora.notification.domain;

/**
 * Nhóm thông báo mà người dùng bật/tắt push (D63). Thông báo trong app luôn được ghi, chỉ kênh push theo lựa chọn.
 * <p>
 * Thêm nhóm mới phải cập nhật màn cài đặt của mobile; app hiển thị nhóm lạ bằng nhãn chung thay vì vỡ giao diện.
 */
public enum NotificationCategory {
    /** Lên sàn, đủ vốn, hết hạn hoặc bị hủy đợt gọi vốn. */
    FUNDING,
    /** Đến lượt ký, hợp đồng có hiệu lực. */
    CONTRACT,
    /** Giải ngân, phát hành Note. */
    DISBURSEMENT,
    /** Nhắc kỳ đến hạn, đã ghi nhận trả nợ, tiền về cho nhà đầu tư, tất toán. */
    REPAYMENT,
    /** Quá hạn, chuyển nhóm nợ, nợ xấu, khắc phục. */
    DELINQUENCY,
    /** Cơ cấu lại thời hạn trả nợ. */
    RESTRUCTURING,
    /** Kết quả rút tiền. */
    WALLET,
    /** Cập nhật chưa phân loại (loại thay đổi mới từ service khác). */
    GENERAL;

    /**
     * Kết quả rút tiền do chính người dùng vừa yêu cầu nên gửi ngay cả trong giờ nghỉ; các nhóm khác chờ hết giờ nghỉ
     * để event chạy lúc nửa đêm (job cuối ngày của Fineract) không đánh thức người dùng.
     */
    public boolean deliversDuringQuietHours() {
        return this == WALLET;
    }
}
