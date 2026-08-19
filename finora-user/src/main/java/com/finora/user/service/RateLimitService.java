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

    boolean recordOtpRequest(String email);
}
