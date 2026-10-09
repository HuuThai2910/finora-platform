package com.finora.common.security.pin;

/**
 * Loại thao tác nhạy cảm cần xác nhận bằng mã PIN.
 * <p>
 * Pin-token chỉ hợp lệ cho đúng một loại: token lấy để đặt lệnh không dùng
 * được để trả nợ, kể cả khi còn hạn.
 */
public enum PinScope {
    /** Trả kỳ, trả trước một phần, tất toán sớm. */
    REPAYMENT,
    /** Rót vốn vào khoản gọi vốn. */
    INVEST,
    /** Đặt lệnh mua/bán trên sổ lệnh Notes. */
    ORDER,
    /** Bật hoặc sửa cấu hình Auto-Invest. */
    AUTO_INVEST,
    /** Ký hợp đồng vay hoặc hợp đồng đầu tư. */
    SIGN_CONTRACT,
    /** Rút tiền về tài khoản ngân hàng. */
    WITHDRAW
}
