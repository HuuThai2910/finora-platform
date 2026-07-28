package com.finora.common.enums;

/**
 * Trạng thái của khoản vay — Dùng chung cho cả loan-service và payment-service.
 */
public enum LoanStatus {
    DRAFT,          // Bản nháp
    PENDING_REVIEW, // Chờ duyệt
    APPROVED,       // Đã duyệt, chờ đưa lên sàn
    ON_MARKET,      // Đang gọi vốn trên sàn
    FUNDED,         // Đã gọi đủ vốn
    DISBURSING,     // Đang giải ngân
    ACTIVE,         // Đang hoạt động (Trả nợ hàng tháng)
    OVERDUE,        // Quá hạn
    NPL,            // Nợ xấu (Non-performing loan)
    CLOSED,         // Đã tất toán
    REJECTED        // Bị từ chối
}
