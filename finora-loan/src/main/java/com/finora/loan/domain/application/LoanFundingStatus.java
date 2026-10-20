package com.finora.loan.domain.application;

/** Trạng thái phối hợp gọi vốn; không thay thế trạng thái Application nguồn chuẩn. */
public enum LoanFundingStatus {
    REQUESTED,
    FULLY_FUNDED,
    /** Investment báo đợt gọi vốn hết hạn mà chưa đủ vốn; không lập Contract cho đợt này. */
    EXPIRED,
    /**
     * Đã đủ vốn nhưng khoản vay sẽ không được giải ngân (hợp đồng bị từ chối/hết hạn ký hoặc Payment
     * báo giải ngân thất bại khi tiền chưa chuyển). Loan phát {@code LoanFundingCancelled.v1} để
     * Investment hủy phần vốn và trả tiền đang giữ cho nhà đầu tư.
     */
    CANCELLED
}
