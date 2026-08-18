package com.finora.user.service;

import com.finora.user.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Dịch vụ giới hạn tốc độ và lưu trữ OTP — sử dụng Redis với TTL.
 * <p>
 * Bảo vệ chống brute-force đăng nhập và dò OTP 6 chữ số.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimitService {

    /** Số lần đăng nhập sai tối đa trước khi khóa tạm */
    private static final int MAX_LOGIN_FAILURES = 5;

    /** Thời gian khóa tài khoản sau khi vượt ngưỡng đăng nhập sai (phút) */
    private static final long LOGIN_BLOCK_TTL_MINUTES = 15;

    /** Thời gian sống của bộ đếm đăng nhập sai (phút) */
    private static final long LOGIN_FAIL_TTL_MINUTES = 5;

    /** Thời gian sống của OTP (phút) */
    private static final long OTP_TTL_MINUTES = 5;

    /** Số lần thử OTP tối đa — chống brute-force mã 6 chữ số */
    private static final int MAX_OTP_ATTEMPTS = 5;

    /** Số yêu cầu OTP tối đa mỗi email mỗi giờ */
    private static final int MAX_OTP_REQUESTS_PER_HOUR = 3;

    private static final String KEY_LOGIN_BLOCK = "login_block:";
    private static final String KEY_LOGIN_FAIL = "login_fail:";
    private static final String KEY_RESET_OTP = "reset_otp:";
    private static final String KEY_OTP_ATTEMPT = "otp_attempt:";
    private static final String KEY_OTP_RATE = "otp_rate:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Kiểm tra tài khoản hoặc IP có đang bị khóa tạm không.
     *
     * @param key email hoặc IP cần kiểm tra (đã bao gồm prefix bên ngoài hoặc truyền trực tiếp)
     * @return {@code true} nếu đang bị khóa
     */
    public boolean isLoginBlocked(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOGIN_BLOCK + key));
    }

    /**
     * Ghi nhận một lần đăng nhập thất bại — tăng bộ đếm và khóa tài khoản nếu vượt ngưỡng.
     *
     * @return số lần thất bại hiện tại (sau khi tăng)
     */
    public int recordFailedLogin(String email, String ipAddress) {
        String failKey = KEY_LOGIN_FAIL + email;

        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            // Lần đầu thất bại — đặt TTL cho bộ đếm
            redisTemplate.expire(failKey, Duration.ofMinutes(LOGIN_FAIL_TTL_MINUTES));
        }

        int currentCount = count != null ? count.intValue() : 1;

        if (currentCount >= MAX_LOGIN_FAILURES) {
            // Vượt ngưỡng — khóa tạm tài khoản theo email
            redisTemplate.opsForValue().set(
                    KEY_LOGIN_BLOCK + email, "blocked",
                    Duration.ofMinutes(LOGIN_BLOCK_TTL_MINUTES));
            log.warn("Tài khoản bị khóa tạm do đăng nhập sai {} lần: email={}, ip={}",
                    currentCount, PiiMasker.maskEmail(email), ipAddress);
        }

        return currentCount;
    }

    /**
     * Xóa bộ đếm đăng nhập thất bại sau khi đăng nhập thành công.
     */
    public void resetFailedLogin(String email) {
        redisTemplate.delete(KEY_LOGIN_FAIL + email);
    }

    /**
     * Lưu OTP vào Redis với TTL 5 phút.
     */
    public void storeOtp(Long userId, String otp) {
        redisTemplate.opsForValue().set(
                KEY_RESET_OTP + userId, otp,
                Duration.ofMinutes(OTP_TTL_MINUTES));
    }

    /**
     * Xác minh OTP — so khớp giá trị và theo dõi số lần thử.
     * <p>
     * Nếu đúng: xóa OTP và bộ đếm thử → trả {@code true}.
     * Nếu sai hoặc vượt ngưỡng thử: xóa OTP (chống dò) → trả {@code false}.
     *
     * @return {@code true} nếu OTP hợp lệ
     */
    public boolean verifyOtp(Long userId, String otp) {
        String otpKey = KEY_RESET_OTP + userId;
        String attemptKey = KEY_OTP_ATTEMPT + userId;

        // Tăng bộ đếm thử OTP
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, Duration.ofMinutes(OTP_TTL_MINUTES));
        }

        // Vượt ngưỡng thử — xóa OTP để chặn brute-force mã 6 chữ số
        if (attempts != null && attempts > MAX_OTP_ATTEMPTS) {
            redisTemplate.delete(otpKey);
            redisTemplate.delete(attemptKey);
            log.warn("Vượt ngưỡng thử OTP ({} lần) cho userId={} — đã xóa OTP",
                    attempts, userId);
            return false;
        }

        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            return false;
        }

        if (storedOtp.equals(otp)) {
            // OTP đúng — xóa cả OTP và bộ đếm thử
            redisTemplate.delete(otpKey);
            redisTemplate.delete(attemptKey);
            return true;
        }

        return false;
    }

    /**
     * Kiểm tra rate limit yêu cầu OTP — tối đa {@value MAX_OTP_REQUESTS_PER_HOUR} lần/email/giờ.
     *
     * @return {@code true} nếu được phép gửi OTP, {@code false} nếu đã vượt giới hạn
     */
    public boolean recordOtpRequest(String email) {
        String rateKey = KEY_OTP_RATE + email;

        Long count = redisTemplate.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redisTemplate.expire(rateKey, Duration.ofHours(1));
        }

        if (count != null && count > MAX_OTP_REQUESTS_PER_HOUR) {
            log.warn("Vượt giới hạn yêu cầu OTP ({} lần/giờ) cho email={}", count, PiiMasker.maskEmail(email));
            return false;
        }

        return true;
    }
}
