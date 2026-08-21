package com.finora.user.service;

/**
 * Interface dịch vụ giới hạn tốc độ và lưu trữ OTP.
 * Bảo vệ chống brute-force đăng nhập và dò OTP.
 */
public interface RateLimitService {

    boolean isLoginBlocked(String key);

    int recordFailedLogin(String email, String ipAddress);

    void resetFailedLogin(String email);

    void storeOtp(Long userId, String otp);

    boolean verifyOtp(Long userId, String otp);

    /**
     * Kiểm tra OTP đặt lại mật khẩu mà không tiêu huỷ mã khi khớp — dùng cho bước
     * xác nhận giữa chừng trên UI; mã chỉ bị tiêu huỷ khi đổi mật khẩu thành công.
     * Mỗi lần kiểm tra vẫn tính một lần thử để không mở đường dò mã.
     */
    boolean checkOtp(Long userId, String otp);

    /**
     * Lưu OTP đăng ký theo email — dùng khi tài khoản chưa tồn tại nên chưa có userId.
     */
    void storeRegistrationOtp(String email, String otp);

    /**
     * Xác minh OTP đăng ký. Trả về false khi mã sai, hết hạn hoặc vượt số lần thử.
     */
    boolean verifyRegistrationOtp(String email, String otp);

    /** Xoá OTP đăng ký và bộ đếm số lần thử của email này. */
    void clearRegistrationOtp(String email);

    boolean recordOtpRequest(String email);

    /** Thời gian sống của một mã OTP, tính bằng giây — dùng để hiển thị đếm ngược. */
    long otpTtlSeconds();
}
