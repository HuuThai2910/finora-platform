package com.finora.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Chặn gọi eKYC dồn dập.
 * <p>
 * Đọc thẻ và chấm liveness đều chạy phân tích nặng ở {@code finora-ai} (AI chỉ xử lý một phân tích tại
 * một thời điểm), nên mỗi người dùng chỉ được một lần gọi trong mỗi khoảng chờ. Suất gọi là key Redis tự
 * hết hạn; khi AI lỗi, suất được trả lại ngay vì lỗi không phải do người dùng. Mất Redis chỉ làm request
 * lỗi hệ thống, không làm sai trạng thái nghiệp vụ.
 */
@Service
@RequiredArgsConstructor
public class EkycRateLimitService {

    /** Khoảng cách tối thiểu giữa hai lần quét thẻ của cùng một người dùng. */
    public static final Duration VERIFY_MIN_INTERVAL = Duration.ofSeconds(10);

    /** Khoảng cách tối thiểu giữa hai lần gửi video liveness. */
    public static final Duration LIVENESS_MIN_INTERVAL = Duration.ofSeconds(10);

    /**
     * Khoảng cách giữa hai lần xin thử thách: ngắn vì app xin lại ngay khi quay video lỗi và chuyển sang
     * chế độ ảnh tĩnh; đủ để chặn vòng lặp xin liên tục.
     */
    public static final Duration CHALLENGE_MIN_INTERVAL = Duration.ofSeconds(3);

    private static final String KEY_VERIFY_RATE = "ekyc_verify_rate:";
    private static final String KEY_LIVENESS_RATE = "ekyc_liveness_rate:";
    private static final String KEY_CHALLENGE_RATE = "ekyc_challenge_rate:";

    private final StringRedisTemplate redisTemplate;

    /** Chiếm suất quét thẻ; {@code false} nếu lần gọi trước còn trong khoảng chờ. */
    public boolean tryAcquireVerifySlot(UUID keycloakUserId) {
        return acquire(KEY_VERIFY_RATE + keycloakUserId, VERIFY_MIN_INTERVAL);
    }

    /** Trả suất quét thẻ khi AI lỗi để người dùng thử lại ngay. */
    public void releaseVerifySlot(UUID keycloakUserId) {
        redisTemplate.delete(KEY_VERIFY_RATE + keycloakUserId);
    }

    public boolean tryAcquireLivenessSlot(UUID keycloakUserId) {
        return acquire(KEY_LIVENESS_RATE + keycloakUserId, LIVENESS_MIN_INTERVAL);
    }

    public void releaseLivenessSlot(UUID keycloakUserId) {
        redisTemplate.delete(KEY_LIVENESS_RATE + keycloakUserId);
    }

    public boolean tryAcquireChallengeSlot(UUID keycloakUserId) {
        return acquire(KEY_CHALLENGE_RATE + keycloakUserId, CHALLENGE_MIN_INTERVAL);
    }

    private boolean acquire(String key, Duration interval) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", interval);
        return Boolean.TRUE.equals(acquired);
    }
}
