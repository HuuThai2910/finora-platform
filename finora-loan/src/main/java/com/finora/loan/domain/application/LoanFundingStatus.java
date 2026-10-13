package com.finora.loan.domain.application;

/** Trạng thái phối hợp gọi vốn; không thay thế trạng thái Application nguồn chuẩn. */
public enum LoanFundingStatus {
    REQUESTED,
    FULLY_FUNDED,
    /** Investment báo đợt gọi vốn hết hạn mà chưa đủ vốn; không lập Contract cho đợt này. */
    EXPIRED
}
