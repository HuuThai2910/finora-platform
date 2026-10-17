package com.finora.payment.domain.repayment;

public enum PaymentRepaymentStatus {
    COLLECTED,
    CORE_POSTING,
    CORE_POSTED,
    COMPLETED,
    RECONCILIATION_REQUIRED,
    FAILED,
    /** Người vay hủy khoản trả kỳ trước hạn đang giữ (D16) trước ngày ghi nhận; tiền đã hoàn về ví. */
    CANCELLED
}
