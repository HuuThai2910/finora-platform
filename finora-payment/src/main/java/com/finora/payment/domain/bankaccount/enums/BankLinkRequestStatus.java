package com.finora.payment.domain.bankaccount.enums;

/** Trạng thái một lần liên kết tài khoản ngân hàng. */
public enum BankLinkRequestStatus {
    /** Ngân hàng đã gửi OTP, chờ người dùng nhập. */
    PENDING,
    /** Nhập đúng OTP, tài khoản đã được liên kết. */
    CONFIRMED,
    /** Nhập sai OTP quá số lần cho phép. */
    LOCKED,
    /** OTP hết hạn trước khi nhập đúng. */
    EXPIRED
}
