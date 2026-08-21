package com.finora.user.service.impl;

import com.finora.user.service.RateLimitService;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Triển khai dịch vụ giới hạn tốc độ và lưu trữ OTP — sử dụng Redis với TTL.
 * <p>
 * Bảo vệ chống brute-force đăng nhập và dò OTP 6 chữ số.
 * <p>
 * OTP đặt lại mật khẩu khoá theo {@code userId} (tài khoản đã tồn tại), còn OTP đăng ký
 * khoá theo email vì lúc đó tài khoản chưa được tạo. Hai không gian khoá tách biệt để
 * mã của luồng này không dùng được cho luồng kia.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimitServiceImpl implements RateLimitService {

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
    private static final String KEY_REGISTRATION_OTP = "reg_otp:";
    private static final String KEY_REGISTRATION_OTP_ATTEMPT = "reg_otp_attempt:";
    private static final String KEY_OTP_RATE = "otp_rate:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isLoginBlocked(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOGIN_BLOCK + key));
    }

    @Override
    public int recordFailedLogin(String email, String ipAddress) {
        String failKey = KEY_LOGIN_FAIL + email;

        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            redisTemplate.expire(failKey, Duration.ofMinutes(LOGIN_FAIL_TTL_MINUTES));
        }

        int currentCount = count != null ? count.intValue() : 1;

        if (currentCount >= MAX_LOGIN_FAILURES) {
            redisTemplate.opsForValue().set(
                    KEY_LOGIN_BLOCK + email, "blocked",
                    Duration.ofMinutes(LOGIN_BLOCK_TTL_MINUTES));
            log.warn("Tài khoản bị khóa tạm do đăng nhập sai {} lần: email={}, ip={}",
                    currentCount, PiiMasker.maskEmail(email), ipAddress);
        }

        return currentCount;
    }

    @Override
    public void resetFailedLogin(String email) {
        redisTemplate.delete(KEY_LOGIN_FAIL + email);
    }

    @Override
    public void storeOtp(Long userId, String otp) {
        storeOtp(KEY_RESET_OTP + userId, otp);
    }

    @Override
    public boolean verifyOtp(Long userId, String otp) {
        return matchOtp(KEY_RESET_OTP + userId, KEY_OTP_ATTEMPT + userId, otp,
                String.valueOf(userId), true);
    }

    @Override
    public boolean checkOtp(Long userId, String otp) {
        return matchOtp(KEY_RESET_OTP + userId, KEY_OTP_ATTEMPT + userId, otp,
                String.valueOf(userId), false);
    }

    @Override
    public void storeRegistrationOtp(String email, String otp) {
        storeOtp(KEY_REGISTRATION_OTP + email, otp);
    }

    @Override
    public boolean verifyRegistrationOtp(String email, String otp) {
        return matchOtp(
                KEY_REGISTRATION_OTP + email,
                KEY_REGISTRATION_OTP_ATTEMPT + email,
                otp,
                PiiMasker.maskEmail(email),
                true);
    }

    @Override
    public void clearRegistrationOtp(String email) {
        redisTemplate.delete(KEY_REGISTRATION_OTP + email);
        redisTemplate.delete(KEY_REGISTRATION_OTP_ATTEMPT + email);
    }

    @Override
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

    @Override
    public long otpTtlSeconds() {
        return Duration.ofMinutes(OTP_TTL_MINUTES).toSeconds();
    }

    // ── Phần dùng chung cho mọi loại OTP ────────────────────────────

    private void storeOtp(String otpKey, String otp) {
        redisTemplate.opsForValue().set(otpKey, otp, Duration.ofMinutes(OTP_TTL_MINUTES));
    }

    /**
     * Đếm số lần thử trước khi so mã, nên mã đúng ở lần thử thứ 6 trở đi vẫn bị từ chối.
     * Vượt ngưỡng thì xoá luôn OTP để kẻ tấn công không thể tiếp tục dò sau khi TTL đếm ngược.
     *
     * @param subjectForLog định danh đã che PII, chỉ dùng để ghi log
     * @param consumeOnMatch {@code true} thì xoá OTP khi khớp (dùng cho bước chốt);
     *                       {@code false} chỉ kiểm tra, giữ mã lại cho bước sau nhưng
     *                       vẫn tính một lần thử để không mở đường dò mã
     */
    private boolean matchOtp(
            String otpKey, String attemptKey, String otp, String subjectForLog, boolean consumeOnMatch) {
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, Duration.ofMinutes(OTP_TTL_MINUTES));
        }

        if (attempts != null && attempts > MAX_OTP_ATTEMPTS) {
            redisTemplate.delete(otpKey);
            redisTemplate.delete(attemptKey);
            log.warn("Vượt ngưỡng thử OTP ({} lần) cho {} — đã xóa OTP", attempts, subjectForLog);
            return false;
        }

        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            return false;
        }

        if (storedOtp.equals(otp)) {
            if (consumeOnMatch) {
                redisTemplate.delete(otpKey);
                redisTemplate.delete(attemptKey);
            }
            return true;
        }

        return false;
    }
}
