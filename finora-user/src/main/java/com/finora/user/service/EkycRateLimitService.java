package com.finora.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Chặn gọi xác minh eKYC dồn dập.
 * <p>
 * Mỗi lần xác minh chạy OCR phía {@code finora-ai} — tốn tài nguyên và có thể
 * mất vài chục giây — nên mỗi người dùng chỉ được một lần gọi trong mỗi khoảng
 * {@link #VERIFY_MIN_INTERVAL}. Suất gọi giữ trên Redis và tự hết hạn; mất Redis
 * chỉ khiến rate limit tạm không hiệu lực chứ không hỏng nghiệp vụ.
 */
@Service
@RequiredArgsConstructor
public class EkycRateLimitService {

    /** Khoảng cách tối thiểu giữa hai lần gọi xác minh của cùng một người dùng. */
    public static final Duration VERIFY_MIN_INTERVAL = Duration.ofSeconds(10);

    private static final String KEY_VERIFY_RATE = "ekyc_verify_rate:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Chiếm suất gọi xác minh. Trả {@code false} nếu lần gọi trước còn trong
     * khoảng chờ.
     */
    public boolean tryAcquireVerifySlot(UUID keycloakUserId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                KEY_VERIFY_RATE + keycloakUserId, "1", VERIFY_MIN_INTERVAL);
        return Boolean.TRUE.equals(acquired);
    }
}
